// krkr_extract_hook.cpp
// Kirikiroid2（libgame.so / libgame134.so / libgame126.so）剧情文本提取钩子。
//
// 背景：提取面板的 KRKR 数据源（KrkrExtractFacade → OnsExtractBridge）期望内核上行
// 对话事件；设计上由 krkr2-main 源码钩子内核（libkrkr2.so 导出 nativeSetExtractSink）
// 承载，但随包内核均为原版构建、无提取导出，导致 KRKR 游戏内面板无文本。
//
// 方案：Kirikiroid2 的 cocos2d-x 移植把控制台文本落在 cocos2d::Label 上
// （三个版本均导出 cocos2d::Label::setString，llvm-nm 核实），对其挂钩；剧情对白
// 经 TJS 发射器（EngineLauncher.krExtractEmitterScriptV8，设置 kr_extract_tjs 开启
// 时随 patch.tjs 注入，包装 KAGParser 把对白以 [TNEXT] 标记发往 Debug.message 控制台）
// 汇入同一通道。上行 NativeBridge.onKrkrText → OnsExtractBridge dialogue 事件
// （页快照 + 前缀差分语义天然兼容打字机分段；Java 侧按 [TNEXT] 过滤控制台噪音）。
//
// 实现：shadowhook（jniLibs 真库 1.1.1，C API 经 dlsym 取用）entry inline hook；
// 本进程对插件目录 dlopen 的内核 inline hook 不可用（shadowhook errno=12
// "Init linker mod failed"），故 setString 走 Label 虚表补丁（与 krkr_bridge 的
// TVPMainScene::update 钩子同款 mprotect 技术）。
//
// 注意：libgame 系内核按旧 GNU libstdc++ COW string ABI 构建（见 krkr_bridge.cpp
// LegacyCowString 注释），setString 参数首字即 char* 数据指针。

#include <dlfcn.h>
#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <chrono>
#include <thread>
#include <cerrno>
#include <cstring>
#include <string>
#include <csetjmp>
#include <csignal>
#include <pthread.h>
#include <sys/mman.h>
#include <unistd.h>
#include <unordered_map>

#define TAG "KrkrExtract"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

// cocos2d::Label::setString(const std::string&) —— 三个版本符号一致（llvm-nm 核实）
constexpr const char* kLabelSetString = "_ZN7cocos2d5Label9setStringERKSs";
constexpr const char* kLabelVtable = "_ZTVN7cocos2d5LabelE";
// vtable 扫描上限（槽位数，cocos2d::Label 虚函数约 60+，留足余量）
constexpr size_t kLabelVtableScanSlots = 512;
// Itanium ABI：_ZTV 符号首槽是 offset-to-top，随后 typeinfo，之后才是虚函数表
constexpr size_t kVtablePrologueSlots = 2;

constexpr const char* kShadowhookLib = "libshadowhook.so";
constexpr const char* kShadowhookInit = "shadowhook_init";
constexpr const char* kShadowhookHookFuncAddr = "shadowhook_hook_func_addr";
constexpr const char* kShadowhookGetErrno = "shadowhook_get_errno";
constexpr const char* kShadowhookToErrmsg = "shadowhook_to_errmsg";

#pragma pack(push, 8)
struct ShadowhookInitInfo {
    int shadowhook_version;
    int abi_version;
    bool debuggable;
    void (*logging_callback)(int, const char*, const char*, ...);
};
#pragma pack(pop)

using ShadowhookInitFn = void* (*)(const ShadowhookInitInfo*);
using HookFuncAddrFn = void* (*)(void* func_addr, void* new_addr, void** orig_addr);
// 旧 GNU COW ABI 的 std::string 参数
using SetStringFn = void (*)(void* self, const void* legacyString);

SetStringFn gOrigSetString = nullptr;
JavaVM* gVm = nullptr;
jclass gBridgeClass = nullptr;
jmethodID gOnTextMethod = nullptr;
std::atomic<bool> gHookInstalled{false};
// setString 同文本重复到达（同帧多次排版/relayout）：只在首次上行
std::string gLastText;

// ---- B1 诊断埋点开关（默认关；由 Java 侧按调试日志开关下发） ----
// 复测协议见 docs/dev/KRKR提取失效与钩子开关方案.md §3.2：钩子武装成功但零字符流时，
// 需要区分「钩子没被调用」「有字符流但页推导不产出」「上行被 Java 过滤」。埋点日志
// 一律限频（ARM 转译下高频日志会拖死渲染线程并触发 ANR），且开关关闭时零开销。
std::atomic<bool> gDiagEnabled{false};
std::atomic<uint64_t> gFtCharCalls{0};
std::atomic<uint64_t> gLabelCalls{0};

extern "C" void krkr_set_extract_diag(bool enabled) {
    gDiagEnabled.store(enabled, std::memory_order_relaxed);
}

static bool diagEnabled() { return gDiagEnabled.load(std::memory_order_relaxed); }

// 首个 UTF-8 码点（诊断日志用：只打首码点与长度，避免中文全文刷屏/编码噪音）
unsigned long ftUtf8FirstCode(const std::string& s);

/** 旧 GNU COW std::string 读取：对象首字即 char* 数据指针（空串为静态共享实例，安全）。 */
const char* cowData(const void* legacyString) {
    if (legacyString == nullptr) return nullptr;
    return *reinterpret_cast<char* const*>(legacyString);
}

void emitText(const char* text) {
    JavaVM* vm = gVm;
    if (vm == nullptr || gBridgeClass == nullptr || gOnTextMethod == nullptr) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) return;
        attached = true;
    }
    // 非法 UTF-8 会令 NewStringUTF 直接 abort：走 String(byte[], "UTF-8") 容错解码
    jstring result = nullptr;
    {
        const jsize len = static_cast<jsize>(std::strlen(text));
        jbyteArray arr = env->NewByteArray(len);
        if (arr != nullptr) {
            env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte*>(text));
            jclass stringClass = env->FindClass("java/lang/String");
            if (stringClass != nullptr) {
                jmethodID ctor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
                if (ctor != nullptr) {
                    jstring charset = env->NewStringUTF("UTF-8");
                    jobject obj = env->NewObject(stringClass, ctor, arr, charset);
                    if (env->ExceptionCheck()) env->ExceptionClear();
                    else result = static_cast<jstring>(obj);
                    if (charset != nullptr) env->DeleteLocalRef(charset);
                }
                env->DeleteLocalRef(stringClass);
            }
            env->DeleteLocalRef(arr);
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    if (result != nullptr) {
        env->CallStaticVoidMethod(gBridgeClass, gOnTextMethod, result);
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(result);
    }
    if (attached) vm->DetachCurrentThread();
}

void hookedLabelSetString(void* self, const void* legacyString) {
    const SetStringFn original = gOrigSetString;
    if (original != nullptr) original(self, legacyString);
    if (!gHookInstalled.load(std::memory_order_relaxed)) return;

    const char* text = cowData(legacyString);
    if (text == nullptr || text[0] == '\0') return;
    const uint64_t calls = gLabelCalls.fetch_add(1, std::memory_order_relaxed) + 1;
    if (diagEnabled() && (calls <= 20 || calls % 200 == 0)) {
        // 只打长度与首码点：全文会刷屏且可能带控制台噪音
        const std::string probe(text);
        LOGI("label set: calls=%llu len=%zu head=U+%04lX",
             static_cast<unsigned long long>(calls), probe.size(), ftUtf8FirstCode(probe));
    }
    // 同一文本重复 setString（同帧多次排版）只在首次上行；
    // 不同 Label 交替（人名/正文）文本不同，天然放行
    if (gLastText == text) return;
    gLastText.assign(text);
    emitText(text);
}

void* shadowhookLib() {
    return dlopen(kShadowhookLib, RTLD_NOW | RTLD_GLOBAL);
}

bool installShadowhook() {
    void* shadowhook = shadowhookLib();
    if (shadowhook == nullptr) {
        LOGW("extract hook: shadowhook dlopen failed: %s", dlerror());
        return false;
    }
    auto init = reinterpret_cast<ShadowhookInitFn>(dlsym(shadowhook, kShadowhookInit));
    if (init == nullptr) {
        LOGW("extract hook: shadowhook_init missing");
        return false;
    }
    ShadowhookInitInfo info{0x01010100, 1, false, nullptr};
    return init(&info) != nullptr;
}

// ---- shadowhook 防崩护栏 ----
// 实测部分 ROM（MIUI 12.5 / Android 11，alioth）上 shadowhook_init 不走 errno
// 返回而是直接 abort（SIGABRT）杀死整个游戏进程，绕过「失败静默降级虚表补丁」
// 约定。用信号护栏把护栏窗口内的 SIGABRT/SIGSEGV/SIGBUS 转回调用方，降级为
// 无 inline hook（虚表补丁兜底）。sigsetjmp(1) 保存信号掩码，siglongjmp 出
// 信号处理器合法；护栏窗口仅覆盖 shadowhook 自身调用，且校验触发线程一致。
static sigjmp_buf gHookProbeEnv;
static volatile sig_atomic_t gHookProbeActive = 0;
static pthread_t gHookProbeThread;
static volatile sig_atomic_t gHookProbeSignal = 0;

void hookProbeSignalHandler(int sig) {
    if (gHookProbeActive == 0 || !pthread_equal(pthread_self(), gHookProbeThread)) {
        // 护栏窗口外的原生崩溃：恢复默认处置重发，保持原生崩溃行为
        signal(sig, SIG_DFL);
        raise(sig);
        return;
    }
    gHookProbeSignal = sig;
    gHookProbeActive = 0;
    siglongjmp(gHookProbeEnv, 1);
}

/**
 * 护栏窗口内完成 shadowhook 初始化 + setString inline hook。
 * 返回 true = inline hook 已就绪（gOrigSetString 已写）；false = 不可用或
 * 中途崩溃（调用方降级虚表补丁）。
 */
bool probeInlineHook(void* target) {
    struct sigaction act {};
    struct sigaction oldAbrt {};
    struct sigaction oldSegv {};
    struct sigaction oldBus {};
    act.sa_handler = hookProbeSignalHandler;
    sigemptyset(&act.sa_mask);
    act.sa_flags = 0;
    sigaction(SIGABRT, &act, &oldAbrt);
    sigaction(SIGSEGV, &act, &oldSegv);
    sigaction(SIGBUS, &act, &oldBus);

    volatile bool ok = false;
    if (sigsetjmp(gHookProbeEnv, 1) == 0) {
        gHookProbeThread = pthread_self();
        gHookProbeActive = 1;
        if (installShadowhook()) {
            void* shadowhook = shadowhookLib();
            auto hookAddr = reinterpret_cast<HookFuncAddrFn>(
                    dlsym(shadowhook, kShadowhookHookFuncAddr));
            void* orig = nullptr;
            if (hookAddr != nullptr &&
                hookAddr(target, reinterpret_cast<void*>(&hookedLabelSetString), &orig) != nullptr) {
                gOrigSetString = reinterpret_cast<SetStringFn>(orig);
                ok = true;
            } else {
                auto getErrno = reinterpret_cast<int (*)()>(dlsym(shadowhook, kShadowhookGetErrno));
                auto toErrmsg = reinterpret_cast<const char* (*)(int)>(dlsym(shadowhook, kShadowhookToErrmsg));
                int err = getErrno ? getErrno() : -1;
                LOGW("extract hook: inline hook failed errno=%d msg=%s", err,
                     toErrmsg ? toErrmsg(err) : "?");
            }
        }
        gHookProbeActive = 0;
    } else {
        LOGW("extract hook: shadowhook crashed with signal %d in init; degrade to vtable patch",
             gHookProbeSignal);
        ok = false;
    }

    sigaction(SIGABRT, &oldAbrt, nullptr);
    sigaction(SIGSEGV, &oldSegv, nullptr);
    sigaction(SIGBUS, &oldBus, nullptr);
    return ok;
}

// ---- FT_Get_Char_Index 钩子：字符流 → 行重建（纯运行时 hook 的对白提取，不改内核）----
// 内核导出 FreeType 全套且 JUMP_SLOT 经自身 GOT（libgame*.so 实测 158 个 FT_ 槽），
// GOT 补丁即可拦截层文本渲染的每个字符码点（Unicode）。按停顿 300ms 切行累积。
using FTLoadCharFn = unsigned long (*)(void* face, unsigned long code, int flags);
FTLoadCharFn gOrigFTLoadChar = nullptr;
// 实测（tsukikage）：对白渲染只经 FT_Load_Glyph(face, glyph_index)，码点→字形索引
// 的映射在字体装载期一次性完成（FT_Get_Char_Index 只承载控制台文本）。故再补
// FT_Load_Glyph 钩子 + 惰性反向映射（glyph_index → 码点）还原字符流。
using FTLoadGlyphFn = int (*)(void* face, unsigned int glyphIndex, int loadFlags);
FTLoadGlyphFn gOrigFTLoadGlyph = nullptr;
using FTGetFirstCharFn = unsigned long (*)(void* face, unsigned int* agindex);
FTGetFirstCharFn gOrigFTGetFirstChar = nullptr;
using FTGetNextCharFn = unsigned long (*)(void* face, unsigned long charCode, unsigned int* agindex);
FTGetNextCharFn gOrigFTGetNextChar = nullptr;
std::atomic<uint64_t> gFtGlyphCalls{0};
std::atomic<uint64_t> gFtNextCharCalls{0};
// glyph_index → 码点（构建一次后只读；指针原子发布，读侧无锁）
std::atomic<const std::unordered_map<unsigned int, unsigned long>*> gGlyphToCode{nullptr};

std::mutex gLineMutex;
std::chrono::steady_clock::time_point gLastCharAt;
std::chrono::steady_clock::time_point gLastEmitAt = std::chrono::steady_clock::time_point::min();

void ftOnCode(unsigned long code);

// 对白渲染的实际路径（krkr2-main FreeType.cpp:651/670 证实）：
// FT_Get_Char_Index(face, unicode) → FT_Load_Glyph(face, index)——FT_Load_Char 从不被调用。
// 字符码点全部经 FT_Get_Char_Index；测量+渲染会对同一字符相邻重复查询，相邻去重。
using FTGetCharIndexFn = unsigned long (*)(void* face, unsigned long code);
FTGetCharIndexFn gOrigFTGetCharIndex = nullptr;
unsigned long gPrevCode = 0;

// 打字机重绘状态机：KAG 每加一字整行重绘，码点流 = 前缀链『ず『ずっ『ずっと…
// known=已确认句；i=本轮重绘已匹配位置。码字匹配 known[i] 则 i++（重绘确认）；
// i 到尾则是新字（追加）；与 known[0] 相同且 i>0 则新一轮重绘开始。
std::string gKnown;     // UTF-8 已确认句
std::string gRawBuf;    // 原始码点流（未还原，调试用）
size_t gPassIdx = 0;    // 当前重绘轮匹配到的 UTF-8 字节位置（按码点推进）

static size_t ftUtf8ByteLenOfFirst(const std::string& s) {
    if (s.empty()) return 0;
    const unsigned char b = static_cast<unsigned char>(s[0]);
    return b < 0x80 ? 1 : b < 0xE0 ? 2 : b < 0xF0 ? 3 : 4;
}

unsigned long ftUtf8FirstCode(const std::string& s) {
    if (s.empty()) return 0;
    const unsigned char* b = reinterpret_cast<const unsigned char*>(s.data());
    switch (ftUtf8ByteLenOfFirst(s)) {
        case 1: return b[0];
        case 2: return ((b[0] & 0x1Fu) << 6) | (b[1] & 0x3Fu);
        case 3: return ((b[0] & 0x0Fu) << 12) | ((b[1] & 0x3Fu) << 6) | (b[2] & 0x3Fu);
        default: return ((b[0] & 0x07u) << 18) | ((b[1] & 0x3Fu) << 12) | ((b[2] & 0x3Fu) << 6) | (b[3] & 0x3Fu);
    }
}

void ftAppendUtf8Locked(unsigned long code) {
    std::string ch;
    if (code < 0x80) ch.push_back(static_cast<char>(code));
    else if (code < 0x800) {
        ch.push_back(static_cast<char>(0xC0 | (code >> 6)));
        ch.push_back(static_cast<char>(0x80 | (code & 0x3F)));
    } else if (code < 0x10000) {
        ch.push_back(static_cast<char>(0xE0 | (code >> 12)));
        ch.push_back(static_cast<char>(0x80 | ((code >> 6) & 0x3F)));
        ch.push_back(static_cast<char>(0x80 | (code & 0x3F)));
    } else {
        ch.push_back(static_cast<char>(0xF0 | (code >> 18)));
        ch.push_back(static_cast<char>(0x80 | ((code >> 12) & 0x3F)));
        ch.push_back(static_cast<char>(0x80 | ((code >> 6) & 0x3F)));
        ch.push_back(static_cast<char>(0x80 | (code & 0x3F)));
    }
    gKnown += ch;
    gPassIdx += ch.size();
}

static bool ftEndsWithTerminal(const std::string& s);
void deriveAndMaybeEmitLocked();

void ftFeedCharLocked(unsigned long code) {
    if (gPassIdx >= gKnown.size()) {
        // 到句尾：首字符重复出现 = 新一轮整行重绘开始（打字机逐字重绘的特征）；
        // 否则是新字符，追加
        if (!gKnown.empty() && code == ftUtf8FirstCode(gKnown)) {
            gPassIdx = ftUtf8ByteLenOfFirst(gKnown);
            return;
        }
        ftAppendUtf8Locked(code);
        return;
    }
    // 在已确认句内比对
    size_t pos = gPassIdx;
    unsigned long first = 0;
    bool matched = false;
    {
        // 解出 known[pos] 处的一个码点并与 code 比较
        const unsigned char* b = reinterpret_cast<const unsigned char*>(gKnown.data()) + pos;
        size_t len = gKnown.size() - pos;
        size_t chLen = *b < 0x80 ? 1 : *b < 0xE0 ? 2 : *b < 0xF0 ? 3 : 4;
        if (chLen <= len) {
            unsigned long c = 0;
            if (chLen == 1) c = b[0];
            else if (chLen == 2) c = ((b[0] & 0x1F) << 6) | (b[1] & 0x3F);
            else if (chLen == 3) c = ((b[0] & 0x0F) << 12) | ((b[1] & 0x3F) << 6) | (b[2] & 0x3F);
            else c = ((b[0] & 0x07) << 18) | ((b[1] & 0x3F) << 12) | ((b[2] & 0x3F) << 6) | (b[3] & 0x3F);
            matched = (c == code);
            first = chLen;  // 记录 known[0] 码点字节数备用
            pos += chLen;
        }
    }
    if (matched) {
        gPassIdx = pos;
        return;
    }
    if (gPassIdx > 0) {
        // 与 known[0] 相同 → 新一轮重绘；否则视为换行/改写，从当前字符重开
        //（known[0] 首码点重复出现即重绘标记）
        const unsigned char* b0 = reinterpret_cast<const unsigned char*>(gKnown.data());
        size_t firstLen = b0[0] < 0x80 ? 1 : b0[0] < 0xE0 ? 2 : b0[0] < 0xF0 ? 3 : 4;
        unsigned long c0 = 0;
        if (firstLen == 1) c0 = b0[0];
        else if (firstLen == 2) c0 = ((b0[0] & 0x1F) << 6) | (b0[1] & 0x3F);
        else if (firstLen == 3) c0 = ((b0[0] & 0x0F) << 12) | ((b0[1] & 0x3F) << 6) | (b0[2] & 0x3F);
        else c0 = ((b0[0] & 0x07) << 18) | ((b0[1] & 0x3F) << 12) | ((b0[2] & 0x3F) << 6) | (b0[3] & 0x3F);
        if (code == c0) {
            gPassIdx = firstLen;
            return;
        }
        gKnown.clear();
        gPassIdx = 0;
    }
    ftAppendUtf8Locked(code);
}

bool ftEndsWithTerminal(const std::string& s) {
    // 』 」 ！ ？ 。 …（UTF-8 尾字节判定）
    static const char* kTerms[] = {"ã", "ã", "ã",
                                   "ï¼", "ï¼", "ã",
                                   "â¦"};
    for (const char* t : kTerms) {
        const size_t n = std::strlen(t);
        if (s.size() >= n && s.compare(s.size() - n, n, t) == 0) return true;
    }
    return false;
}

std::atomic<bool> gFlushThreadRunning{false};

std::string gPage;        // 累计整页（跨 flush 周期持久）
std::string gLastEmitted;

// ---- B2（方案：KRKR文本碎片与选项误提取 §4.2）字形通道过滤与降级 ----
// 该通道看到的是「字形缓存未命中事件」，与「哪段是正文」无关：
//   · 选项按钮整块重绘 → 整行被捕获（用户报了「像是选项文本」）
//   · 对白打字机在复用 Label/图集命中时几乎不触发 → 长时间不更新
//   · 字体图集/装饰重绘 → HVIFNWYqO幅モトダ… 这类噪声串
// 在解析器通道可用之前，至少让面板不被碎片与堆叠污染：长度下限、同页去重、冻结停发。
constexpr size_t kMinPageCodepoints = 4;   // 少于 4 个码点的派生结果不上行
constexpr size_t kRecentEmittedCap = 8;    // 同页最近上行集合（去重窗口）
constexpr int kFrozenStreakLimit = 3;      // 连续 N 次派生结果相同 = 冻结，停发
std::string gRecentEmitted[kRecentEmittedCap];
size_t gRecentEmittedCount = 0;
std::string gLastDerived;
size_t gLastRawSize = 0;
int gFrozenStreak = 0;

/** UTF-8 码点计数（只数首字节，非法序列按 1 计；用于长度下限，不追求精确）。 */
size_t ftUtf8Codepoints(const std::string& s) {
    size_t n = 0;
    for (unsigned char c : s) {
        if ((c & 0xC0) != 0x80) ++n;
    }
    return n;
}

/**
 * 堆叠串判定（用户报告的「大量重复」形态）：同一段 1..3 码点的前缀在串首连续
 * 重复 ≥3 次，即 kazurauta/tsukikage 实测的 `きさらぎさらぎさらぎさらぎ…`、
 * `か　ず　はか　ず　は…` 这类「重绘链被当成页」的产物。只查串首，避免误伤正文
 * 中偶发的叠字（如「ああ、そうか」只有 2 次重复）。
 */
bool ftLooksStacked(const std::string& s) {
    const size_t total = ftUtf8Codepoints(s);
    if (total < 6) return false;
    for (size_t unitCp = 1; unitCp <= 3; ++unitCp) {
        // 计算 unitCp 个码点的字节长度
        size_t bytes = 0;
        size_t cps = 0;
        for (size_t i = 0; i < s.size() && cps < unitCp; ++i) {
            if ((static_cast<unsigned char>(s[i]) & 0xC0) != 0x80) ++cps;
            bytes = i + 1;
        }
        if (cps < unitCp || bytes == 0) break;
        const std::string unit = s.substr(0, bytes);
        int repeats = 0;
        size_t pos = 0;
        while (pos + bytes <= s.size() && s.compare(pos, bytes, unit) == 0) {
            ++repeats;
            pos += bytes;
        }
        if (repeats >= 3) return true;
    }
    return false;
}

/** 同页去重：命中返回 true（已上行过，跳过）。 */
bool ftRecentlyEmitted(const std::string& s) {
    for (size_t i = 0; i < gRecentEmittedCount; ++i) {
        if (gRecentEmitted[i] == s) return true;
    }
    return false;
}

/** 记录一次上行（环形，超过容量整体前移，代价 O(1)×容量，锁内可接受）。 */
void ftRememberEmitted(const std::string& s) {
    if (gRecentEmittedCount < kRecentEmittedCap) {
        gRecentEmitted[gRecentEmittedCount++] = s;
        return;
    }
    for (size_t i = 1; i < kRecentEmittedCap; ++i) gRecentEmitted[i - 1] = gRecentEmitted[i];
    gRecentEmitted[kRecentEmittedCap - 1] = s;
}

/** 页切换/清空时重置去重窗口与冻结计数。 */
void ftResetEmitMemory() {
    for (size_t i = 0; i < kRecentEmittedCap; ++i) gRecentEmitted[i].clear();
    gRecentEmittedCount = 0;
    gLastDerived.clear();
    gLastRawSize = 0;
    gFrozenStreak = 0;
}

/**
 * 页缓冲增量模型（核心）：在原始链里 rfind 已知页——
 * 找到 → 其后即是新打字符的增量，拼接（打字机换行后重绘只查新段，
 * 这是唯一能拿全上/下行的方式）；
 * 页在链中但无增量 → 无事；
 * 页不在链中 → 翻页（旧页已被滚出窗口/点击推进），重置页与原始链。
 */
std::string ftDerivePage(const std::string& raw);

void deriveAndMaybeEmitLocked(bool pageEnd, const std::string& snap) {
    std::string derived = ftDerivePage(snap);
    if (derived.empty()) {
        // 无重复快照 = 打字未达 2 次重绘（或 UI 字体图集一次性绘制）：
        // 前者下一轮重绘会补齐，后者是启动期垃圾——一律跳过，不上行
        return;
    }
    if (!gPage.empty()) {
        // 段拼接：本段与页尾最大重叠 → 只追加增量；无重叠 = 翻页
        size_t o = std::min(gPage.size(), derived.size());
        while (o > 0) {
            if (gPage.compare(gPage.size() - o, o, derived, 0, o) == 0) break;
            --o;
        }
        if (o > 0) {
            gPage += derived.substr(o);
        } else {
            gPage = derived;      // 翻页
            gRawBuf = derived;    // raw 重置到新页
            ftResetEmitMemory();
        }
    } else {
        gPage = derived;
    }
    if (gRawBuf.size() > 3000) {
        // raw 封顶：保留最后一次页出现之后的尾部（rfind 代价受控）
        const size_t p = gRawBuf.rfind(gPage);
        gRawBuf.erase(0, p == std::string::npos ? gRawBuf.size() / 3 : p);
    }
    // 冻结统计（仅诊断用，不再参与上行门控）：派生与原始链双双不动 = 链推不出新页。
    // 注意不能只看「派生结果相同」——打字机停顿时两者都不变是常态。
    const size_t rawNow = gRawBuf.size();
    if (derived == gLastDerived && rawNow == gLastRawSize) {
        if (gFrozenStreak < kFrozenStreakLimit) ++gFrozenStreak;
    } else {
        gLastDerived = derived;
        gLastRawSize = rawNow;
        gFrozenStreak = 0;
    }
    if (!gPage.empty() && gPage != gLastEmitted) {
        // B2 过滤 1：太短（碎片/单字）不上行
        if (ftUtf8Codepoints(gPage) < kMinPageCodepoints) {
            if (diagEnabled()) {
                static int shortSkips = 0;
                if (++shortSkips % 20 == 1) LOGI("ft skip: short cps=%zu", ftUtf8Codepoints(gPage));
            }
            return;
        }
        // B2 过滤 2：同页已上行过的内容不重复上行（原实现只有一位 gLastEmitted）
        if (ftRecentlyEmitted(gPage)) {
            if (diagEnabled()) {
                static int dupSkips = 0;
                if (++dupSkips % 20 == 1) LOGI("ft skip: dup cps=%zu", ftUtf8Codepoints(gPage));
            }
            return;
        }
        // B2 过滤 3：堆叠串（同一前缀连续 ≥3 次）不上行——用户报告的「大量重复」
        const auto sinceEmit = std::chrono::steady_clock::now() - gLastEmitAt;
        if (ftLooksStacked(gPage)) {
            if (diagEnabled()) {
                static int stackedSkips = 0;
                if (++stackedSkips % 20 == 1) LOGI("ft skip: stacked cps=%zu", ftUtf8Codepoints(gPage));
            }
            return;
        }
        if (!pageEnd && sinceEmit < std::chrono::milliseconds(300)) return;  // 打字中节流
        gLastEmitted = gPage;
        ftRememberEmitted(gPage);
        gLastEmitAt = std::chrono::steady_clock::now();
        std::string out = gPage;
        out.insert(0, "[FTLN]");
        LOGI("ft line: %s", out.c_str() + 6);
        emitText(out.c_str());
        // 原始链候选同步上行（面板候选切换「原始流」数据源）
        if (!gRawBuf.empty()) {
            std::string raw = gRawBuf;
            raw.insert(0, "[FTRAW]");
            emitText(raw.c_str());
        }
    }
}

void flushTimerLoop() {
    // 400ms 轮询 + 锁内只拷 512 字节尾部快照、推导在锁外：ARM 转译下
    // O(n²) 推导全速跑会饿死持同一锁的游戏渲染线程（实测 KRKR 主线程 ANR）
    while (gFlushThreadRunning.load(std::memory_order_relaxed)) {
        std::this_thread::sleep_for(std::chrono::milliseconds(400));
        std::string snap;
        std::string rawCopy;
        size_t rawSize = 0;
        size_t knownSize = 0;
        {
            std::lock_guard<std::mutex> lock(gLineMutex);
            rawSize = gRawBuf.size();
            knownSize = gKnown.size();
            snap = gRawBuf.size() > 512 ? gRawBuf.substr(gRawBuf.size() - 512) : gRawBuf;
            if (diagEnabled() && rawSize > 0) {
                // 尾部 120 字节：用于辨认到底哪些文本流经这条通道
                rawCopy = gRawBuf.size() > 120 ? gRawBuf.substr(gRawBuf.size() - 120) : gRawBuf;
            }
        }
        if (diagEnabled()) {
            // 2s 一条（400ms 轮询 ×5）：区分「完全没有字符流（raw=0）」与
            // 「有字符流但页推导不产出（raw 增长、无 ft line）」。
            // 必须在 raw 空判定之前打印：清空后仍需观察计数器是否仍在增长。
            static uint64_t tick = 0;
            static uint64_t lastIdxCalls = 0;
            static uint64_t lastGlyphCalls = 0;
            static int frozenTicks = 0;
            if (++tick % 5 == 0) {
                const uint64_t idxCalls = gFtCharCalls.load(std::memory_order_relaxed);
                const uint64_t glyphCalls = gFtGlyphCalls.load(std::memory_order_relaxed);
                LOGI("ft state: raw=%zu known=%zu idx_calls=%llu glyph_calls=%llu nextchar=%llu label=%llu",
                     rawSize, knownSize,
                     static_cast<unsigned long long>(idxCalls),
                     static_cast<unsigned long long>(glyphCalls),
                     static_cast<unsigned long long>(gFtNextCharCalls.load(std::memory_order_relaxed)),
                     static_cast<unsigned long long>(gLabelCalls.load(std::memory_order_relaxed)));
                if (!rawCopy.empty()) LOGI("ft raw: %s", rawCopy.c_str());
                // B2 冻结降级标记：字形请求 ≥10s 无增长（5 个 2s tick）→ 该通道此刻
                // 看不到对白推进（字形图集命中），面板应显示降级状态而不是空等
                if (idxCalls == lastIdxCalls && glyphCalls == lastGlyphCalls) {
                    if (frozenTicks < 1000) ++frozenTicks;
                } else {
                    frozenTicks = 0;
                }
                lastIdxCalls = idxCalls;
                lastGlyphCalls = glyphCalls;
                if (frozenTicks == 5) LOGI("glyph-frozen: no FT request for 10s (font atlas hit)");
            }
        }
        if (rawSize == 0) continue;
        const bool pageEnd = std::chrono::steady_clock::now() - gLastCharAt
                > std::chrono::milliseconds(3000);
        {
            std::lock_guard<std::mutex> lock(gLineMutex);
            deriveAndMaybeEmitLocked(pageEnd, snap);
            if (pageEnd) {
                gPage.clear();
                gLastEmitted.clear();
                gRawBuf.clear();
                ftResetEmitMemory();
            } else if (gRawBuf.size() > 4096) {
                gRawBuf.erase(0, gRawBuf.size() - 512);
            }
        }
    }
}

/**
 * 从原始重绘链推导整页文本：最长重复后缀 = 倒数第二次整窗重绘快照，
 * 末尾补上其后再出现的增量（最后一次重绘新增的字符）。两行窗口时
 * 该快照含上/下行全部字符——修复"只显示最后一行"。
 */
std::string ftDerivePage(const std::string& raw) {
    const size_t n = raw.size();
    if (n == 0) return {};
    if (n > 2048) {
        // 过长截尾：派生基于尾部（最近重绘）即可，防止巨型页拖垮主线程
        const std::string trimmed = raw.substr(n - 2048);
        return ftDerivePage(trimmed);
    }
    for (size_t k = n - 1; k >= 2; --k) {
        const std::string t = raw.substr(n - k);
        const size_t first = raw.find(t);
        if (first != std::string::npos && first < n - k) {
            // 尾部补差：最后一份快照比倒数第二份多出的字符（最多一两个码点）
            std::string page = t;
            const size_t second = raw.rfind(t);
            if (second != std::string::npos && second + k < n) {
                page += raw.substr(second + k);
            }
            return page;
        }
    }
    return {};  // 链未完成（打字中）：无重复快照，等下一轮
}

// 原始链候选随页变更上行一次（面板候选切换用）：页变更时由
// deriveAndMaybeEmitLocked 内附带；此处不再单独 flush。

/**
 * 码点入流唯一入口（FT_Get_Char_Index 与 FT_Load_Char 两条 GOT 通道共用）：
 * gPrevCode 相邻去重保证同一字符被两条路径重复喂入时只算一次。
 */
void ftOnCode(unsigned long code) {
    if (!gHookInstalled.load(std::memory_order_relaxed)) return;
    if (code < 0x20 || code >= 0x110000 || code == gPrevCode) return;
    const auto now = std::chrono::steady_clock::now();
    std::lock_guard<std::mutex> lock(gLineMutex);
    ftFeedCharLocked(code);
    {
        // raw 码点流累积（调试）
        std::string ch;
        if (code < 0x80) ch.push_back(static_cast<char>(code));
        else if (code < 0x800) {
            ch.push_back(static_cast<char>(0xC0 | (code >> 6)));
            ch.push_back(static_cast<char>(0x80 | (code & 0x3F)));
        } else if (code < 0x10000) {
            ch.push_back(static_cast<char>(0xE0 | (code >> 12)));
            ch.push_back(static_cast<char>(0x80 | ((code >> 6) & 0x3F)));
            ch.push_back(static_cast<char>(0x80 | (code & 0x3F)));
        } else {
            ch.push_back(static_cast<char>(0xF0 | (code >> 18)));
            ch.push_back(static_cast<char>(0x80 | ((code >> 12) & 0x3F)));
            ch.push_back(static_cast<char>(0x80 | ((code >> 6) & 0x3F)));
            ch.push_back(static_cast<char>(0x80 | (code & 0x3F)));
        }
        gRawBuf += ch;
        if (gRawBuf.size() > 2048) gRawBuf.clear();
    }
    gLastCharAt = now;
}

unsigned long hookedFTGetCharIndex(void* face, unsigned long code) {
    const unsigned long idx = gOrigFTGetCharIndex != nullptr ? gOrigFTGetCharIndex(face, code) : 0;
    const uint64_t calls = gFtCharCalls.fetch_add(1, std::memory_order_relaxed) + 1;
    if (diagEnabled() && (calls <= 50 || calls % 500 == 0)) {
        LOGI("ft char: calls=%llu code=U+%04lX idx=%lu",
             static_cast<unsigned long long>(calls), code & 0x1FFFFFul, idx);
    }
    ftOnCode(code);
    gPrevCode = code;
    return idx;
}

/** FT_Load_Char 兜底通道（部分内核/字体路径经 code 直接加载字形，不经 FT_Get_Char_Index）。 */
unsigned long hookedFTLoadChar(void* face, unsigned long code, int flags) {
    const unsigned long err = gOrigFTLoadChar != nullptr ? gOrigFTLoadChar(face, code, flags) : 0;
    ftOnCode(code);
    gPrevCode = code;
    return err;
}

// ---- 反向映射：glyph_index → 码点（FT_Load_Glyph 通道的字形还原） ----
// 映射在字体装载期由引擎自己建立（码点→字形），运行期只剩字形索引；
// 这里用「让内核自己回答」的方式重建：对 BMP 区间逐码点问 gOrigFTGetCharIndex，
// 取该字形索引的首次命中码点。65504 次 cmap 二分查找，一次性、毫秒级。
std::mutex gGlyphMapMutex;
unsigned gGlyphMapBuildAttempts = 0;

void maybeBuildGlyphMap(void* face) {
    if (gGlyphToCode.load(std::memory_order_acquire) != nullptr) return;
    if (gOrigFTGetCharIndex == nullptr || face == nullptr) return;
    std::lock_guard<std::mutex> lock(gGlyphMapMutex);
    if (gGlyphToCode.load(std::memory_order_acquire) != nullptr) return;
    if (gGlyphMapBuildAttempts >= 5) return;  // 字型表尚未就绪时不反复扫
    ++gGlyphMapBuildAttempts;
    auto* map = new std::unordered_map<unsigned int, unsigned long>();
    map->reserve(8192);
    for (unsigned long code = 0x20; code <= 0xFFFF; ++code) {
        if (code >= 0xD800 && code <= 0xDFFF) continue;  // 代理区无效码点
        const unsigned long index = gOrigFTGetCharIndex(face, code);
        if (index == 0) continue;
        map->emplace(static_cast<unsigned int>(index), code);
    }
    LOGI("ft glyph map built: face=%p entries=%zu attempts=%u", face, map->size(), gGlyphMapBuildAttempts);
    if (!map->empty()) {
        gGlyphToCode.store(map, std::memory_order_release);
    }
}

int hookedFTLoadGlyph(void* face, unsigned int glyphIndex, int loadFlags) {
    const int err = gOrigFTLoadGlyph != nullptr ? gOrigFTLoadGlyph(face, glyphIndex, loadFlags) : 0;
    const uint64_t calls = gFtGlyphCalls.fetch_add(1, std::memory_order_relaxed) + 1;
    if (diagEnabled() && (calls <= 20 || calls % 500 == 0)) {
        LOGI("ft glyph: calls=%llu index=%u err=%d", static_cast<unsigned long long>(calls),
             glyphIndex, err);
    }
    if (!gHookInstalled.load(std::memory_order_relaxed)) return err;
    if (gGlyphToCode.load(std::memory_order_acquire) == nullptr) maybeBuildGlyphMap(face);
    const auto* map = gGlyphToCode.load(std::memory_order_acquire);
    if (map == nullptr) return err;
    const auto it = map->find(glyphIndex);
    if (it != map->end()) ftOnCode(it->second);
    gPrevCode = it != map->end() ? it->second : gPrevCode;
    return err;
}

/** FT_Get_First_Char / FT_Get_Next_Char：仅计数（判定「码点→字形映射在装载期完成」）。 */
unsigned long hookedFTGetNextChar(void* face, unsigned long charCode, unsigned int* agindex) {
    gFtNextCharCalls.fetch_add(1, std::memory_order_relaxed);
    return gOrigFTGetNextChar != nullptr ? gOrigFTGetNextChar(face, charCode, agindex) : 0;
}

}  // namespace

extern "C" bool krkr_bridge_got_hook(const char* library, const char* symbol,
                                     void* replacement, void** original);

/**
 * 安装文本提取钩子并缓存上行 JNI 引用。在 NativeBridge.initialize
 * （内核 dlopen 之后、引擎启动之前）调用；env 为该 JNI 调用的环境，
 * FindClass 走调用方（:kirikiri2 进程应用类加载器），bridge/NativeBridge 必可解析。
 * @param gameHandle krkr_bridge resolveGameLocked 得到的内核 dlopen 句柄
 * @return 是否成功挂钩
 */
extern "C" bool krkr_install_extract_hook(void* gameHandle, JNIEnv* env) {
    if (gameHandle == nullptr || env == nullptr) return false;
    if (gHookInstalled.load(std::memory_order_relaxed)) return true;

    // 缓存 JNI 方法引用（onKrkrText 为 Kotlin object 的 @JvmStatic 方法，静态解析）
    if (gVm == nullptr) env->GetJavaVM(&gVm);
    if (gBridgeClass == nullptr) {
        jclass bridge = env->FindClass("bridge/NativeBridge");
        if (bridge == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            LOGW("NativeBridge class unavailable; text extraction disabled");
            return false;
        }
        gBridgeClass = static_cast<jclass>(env->NewGlobalRef(bridge));
        env->DeleteLocalRef(bridge);
    }
    if (gOnTextMethod == nullptr) {
        gOnTextMethod = env->GetStaticMethodID(gBridgeClass, "onKrkrText", "(Ljava/lang/String;)V");
        if (gOnTextMethod == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            LOGW("onKrkrText method unresolved; text extraction disabled");
            return false;
        }
    }

    void* target = dlsym(gameHandle, kLabelSetString);
    if (target == nullptr) {
        LOGW("extract hook: Label::setString not found in game lib");
        return false;
    }

    // 首选 shadowhook inline hook（信号护栏内探测：部分 ROM 上 shadowhook_init
    // 直接 abort）；护栏返回 false（含崩溃）一律退虚表补丁兜底
    if (probeInlineHook(target)) {
        gHookInstalled.store(true, std::memory_order_relaxed);
        LOGI("extract hook: Label::setString inline hooked (text extraction armed)");
        return true;
    }
    LOGW("extract hook: inline hook unavailable; falling back to vtable patch");

    // 兜底：Label 虚表补丁。setString 为虚函数，跨编译单元调用经 vtable；
    // 槽内指针与 dlsym 地址相等即定位，mprotect 改槽后恢复只读（RELRO）。
    void* vtableSym = dlsym(gameHandle, kLabelVtable);
    if (vtableSym == nullptr) {
        LOGW("extract hook: Label vtable not found; text extraction unavailable");
        return false;
    }
    auto** slots = reinterpret_cast<void**>(vtableSym);
    for (size_t i = 0; i < kLabelVtableScanSlots; ++i) {
        void** slot = slots + kVtablePrologueSlots + i;
        if (*slot != target) continue;
        const long pageSize = sysconf(_SC_PAGESIZE);
        if (pageSize <= 0) return false;
        const uintptr_t page = reinterpret_cast<uintptr_t>(slot)
                & ~(static_cast<uintptr_t>(pageSize) - 1);
        if (mprotect(reinterpret_cast<void*>(page), static_cast<size_t>(pageSize),
                     PROT_READ | PROT_WRITE) != 0) {
            LOGW("extract hook: vtable mprotect failed errno=%d", errno);
            return false;
        }
        __atomic_store_n(slot, reinterpret_cast<void*>(&hookedLabelSetString), __ATOMIC_RELEASE);
        mprotect(reinterpret_cast<void*>(page), static_cast<size_t>(pageSize), PROT_READ);
        // 虚表槽原值即内核实现地址
        gOrigSetString = reinterpret_cast<SetStringFn>(target);
        gHookInstalled.store(true, std::memory_order_relaxed);
        LOGI("extract hook: Label vtable patched slotIndex=%zu (text extraction armed)", i);
        return true;
    }
    LOGW("extract hook: setString slot not found in Label vtable");
    return false;
}

/** FT 字符流钩子：GOT 补丁 FT_Get_Char_Index（对白渲染的 Unicode 必经点）。 */
static bool gFtProbeInstalled = false;

// ============================================================================
// 方案 A：通用钩子 —— TVPConsoleWindow::addLine(const tTJSString&, cocos2d::Color3B)
// 离线依据见文件头注释：导出地址 0xaabbdc；序言含 stp x29,x30（trampoline 必须保 x30）。
// 设计：handler 只做内存解码；**仅含假名/汉字**的文本才上行（天然避开启动期 ASCII 日志风暴）。
// ============================================================================
extern "C" void gAddLineTrampolinePtr();   // 占位，实际用全局变量保存 trampoline

extern "C" __attribute__((visibility("hidden"))) void* gAddLineTrampoline = nullptr;
std::string gLastAddLineEmitted;

/** 含假名/汉字（UTF-8）→ 视为对白候选。 */
bool hasKanaOrKanji(const char* s) {
    if (s == nullptr) return false;
    size_t i = 0;
    const size_t n = std::strlen(s);
    while (i < n) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        unsigned int cp = 0;
        size_t len = 1;
        if (c < 0x80) { cp = c; }
        else if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; len = 2; }
        else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; len = 3; }
        else if ((c & 0xF8) == 0xF0) { cp = c & 0x07; len = 4; }
        else { ++i; continue; }
        if (i + len > n) break;
        for (size_t k = 1; k < len; ++k) {
            const unsigned char cc = static_cast<unsigned char>(s[i + k]);
            if ((cc & 0xC0) != 0x80) { cp = 0; break; }
            cp = (cp << 6) | (cc & 0x3F);
        }
        i += len;
        if ((cp >= 0x3040 && cp <= 0x30FF) || (cp >= 0x4E00 && cp <= 0x9FFF) ||
            (cp >= 0xFF00 && cp <= 0xFFEF)) {
            return true;
        }
    }
    return false;
}

/** C 侧处理：x0 = const tTJSString*（第一参数）。只解码 + 过滤 + 上行。 */
extern "C" void onConsoleAddLine(const void* tjsString) {
    if (tjsString == nullptr) return;
    // tTJSString：对象 offset0 = _Rep*（COW basic_string<char16_t>）
    uintptr_t rep = 0;
    std::memcpy(&rep, tjsString, sizeof(rep));
    if (rep < 0x1000) return;
    uint32_t len = 0;
    std::memcpy(&len, reinterpret_cast<const void*>(rep), sizeof(len));
    if (len == 0 || len > 8192) return;
    const char16_t* chars = reinterpret_cast<const char16_t*>(rep + 0x18);
    // UTF-16 → UTF-8（带越界保护：先读以确认可读，异常由进程自身处理；此处仅做长度上限）
    std::string utf8;
    utf8.reserve(len * 3);
    for (uint32_t i = 0; i < len; ++i) {
        const unsigned int cp = chars[i];
        if (cp == 0) break;
        if (cp < 0x80) {
            utf8.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            utf8.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            utf8.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            utf8.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            utf8.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            utf8.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }
    if (utf8.empty()) return;
    // 只上行"像对白"的文本：含假名/汉字。启动期的 ASCII 日志（路径/标签）在此被挡掉，
    // 从而**不会**在启动关键路径上触发 JNI（上次卡启动的根因）。
    if (!hasKanaOrKanji(utf8.c_str())) return;
    if (utf8 == gLastAddLineEmitted) return;   // 去重
    gLastAddLineEmitted = utf8;
    std::string out = "[FTLN]";
    out += utf8;
    LOGI("addline dialogue: %.60s", utf8.c_str());
    emitText(out.c_str());
}

/** naked 替换函数：保存 x0-x9/q0-q7/**x30** → 调 handler → 恢复 → 跳 trampoline。 */
extern "C" __attribute__((naked)) void addLineHook() {
    __asm__ volatile(
            "sub sp, sp, #304\n"
            "stp x0, x1, [sp, #0]\n"
            "stp x2, x3, [sp, #16]\n"
            "stp x4, x5, [sp, #32]\n"
            "stp x6, x7, [sp, #48]\n"
            "stp x8, x9, [sp, #64]\n"
            "stp q0, q1, [sp, #80]\n"
            "stp q2, q3, [sp, #112]\n"
            "stp q4, q5, [sp, #144]\n"
            "stp q6, q7, [sp, #176]\n"
            "str x30, [sp, #288]\n"          // ★ 保 LR：addLine 序言会 stp x29,x30
            "bl onConsoleAddLine\n"
            "ldr x30, [sp, #288]\n"          // ★ 还 LR（被搬移指令会用）
            "ldp x0, x1, [sp, #0]\n"
            "ldp x2, x3, [sp, #16]\n"
            "ldp x4, x5, [sp, #32]\n"
            "ldp x6, x7, [sp, #48]\n"
            "ldp x8, x9, [sp, #64]\n"
            "ldp q0, q1, [sp, #80]\n"
            "ldp q2, q3, [sp, #112]\n"
            "ldp q4, q5, [sp, #144]\n"
            "ldp q6, q7, [sp, #176]\n"
            "add sp, sp, #304\n"
            "adrp x16, gAddLineTrampoline\n"
            "add x16, x16, :lo12:gAddLineTrampoline\n"
            "ldr x16, [x16]\n"
            "br x16\n");
}

bool isPcRelativeInsn(uint32_t insn) {
    if ((insn & 0x1F000000u) == 0x10000000u) return true;   // ADR/ADRP
    if ((insn & 0x7C000000u) == 0x14000000u) return true;   // B/BL
    if ((insn & 0x3B000000u) == 0x18000000u) return true;   // LDR literal
    return false;
}

/** 安装 addLine 的 inline hook（失败仅记日志，不影响游戏）。 */
bool installAddLineHook(void* gameHandle) {
    void* target = dlsym(gameHandle,
                         "_ZN16TVPConsoleWindow7addLineERKN3TJS10tTJSStringEN7cocos2d7Color3BE");
    if (target == nullptr) {
        LOGW("addline hook: symbol not found");
        return false;
    }
    auto* insns = reinterpret_cast<uint32_t*>(target);
    for (int i = 0; i < 4; ++i) {
        if (isPcRelativeInsn(insns[i])) {
            LOGW("addline hook: pc-relative insn[%d]=0x%08x → refuse", i, insns[i]);
            return false;
        }
    }
    const long pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize <= 0) return false;
    void* tramp = mmap(nullptr, static_cast<size_t>(pageSize),
                       PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (tramp == MAP_FAILED) return false;
    auto* tp = reinterpret_cast<uint32_t*>(tramp);
    for (int i = 0; i < 4; ++i) tp[i] = insns[i];
    tp[4] = 0x58000050u;   // ldr x16, #8
    tp[5] = 0xD61F0200u;   // br x16
    *reinterpret_cast<uint64_t*>(tp + 6) = reinterpret_cast<uint64_t>(target) + 16;
    __builtin___clear_cache(reinterpret_cast<char*>(tramp),
                            reinterpret_cast<char*>(tramp) + pageSize);
    gAddLineTrampoline = tramp;
    const uintptr_t page = reinterpret_cast<uintptr_t>(target)
            & ~(static_cast<uintptr_t>(pageSize) - 1);
    if (mprotect(reinterpret_cast<void*>(page), static_cast<size_t>(pageSize),
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOGW("addline hook: mprotect failed errno=%d", errno);
        return false;
    }
    insns[0] = 0x58000050u;   // ldr x16, #8
    insns[1] = 0xD61F0200u;   // br x16
    *reinterpret_cast<uint64_t*>(insns + 2) = reinterpret_cast<uint64_t>(&addLineHook);
    mprotect(reinterpret_cast<void*>(page), static_cast<size_t>(pageSize),
             PROT_READ | PROT_EXEC);
    __builtin___clear_cache(reinterpret_cast<char*>(target),
                            reinterpret_cast<char*>(target) + 16);
    LOGI("addline hook: armed target=%p trampoline=%p", target, tramp);
    return true;
}

extern "C" bool krkr_install_ft_probe(void* gameHandle, const char* library) {
    if (gameHandle == nullptr || library == nullptr) return false;
    if (gFtProbeInstalled) return true;
    void* orig = nullptr;
    const bool primary = krkr_bridge_got_hook(library, "FT_Get_Char_Index",
                                              reinterpret_cast<void*>(&hookedFTGetCharIndex), &orig);
    if (primary) {
        gOrigFTGetCharIndex = reinterpret_cast<FTGetCharIndexFn>(orig);
        if (diagEnabled()) {
            // A2 判据：GOT 槽原值应与 dlsym 得到的内核导出地址一致；
            // 不一致 = 该库另有一份静态 FreeType / 槽指向别的实现
            LOGI("ft probe: FT_Get_Char_Index dlsym=%p got_original=%p",
                 dlsym(gameHandle, "FT_Get_Char_Index"), orig);
        }
    } else {
        LOGW("ft probe GOT hook failed (FT_Get_Char_Index)");
    }
    // 兜底通道 1：FT_Load_Glyph —— 实测 tsukikage 的对白只走这一条（码点→字形
    // 映射在装载期完成），经反向映射还原码点。缺槽不致命（其他内核可能无此路径）。
    void* origLoadGlyph = nullptr;
    if (krkr_bridge_got_hook(library, "FT_Load_Glyph",
                             reinterpret_cast<void*>(&hookedFTLoadGlyph), &origLoadGlyph)) {
        gOrigFTLoadGlyph = reinterpret_cast<FTLoadGlyphFn>(origLoadGlyph);
        LOGI("ft probe GOT hooked (FT_Load_Glyph dialogue path)");
    }
    // 兜底通道 2：FT_Load_Char（按码点直接加载字形的路径）。
    void* origLoadChar = nullptr;
    if (krkr_bridge_got_hook(library, "FT_Load_Char",
                             reinterpret_cast<void*>(&hookedFTLoadChar), &origLoadChar)) {
        gOrigFTLoadChar = reinterpret_cast<FTLoadCharFn>(origLoadChar);
        LOGI("ft probe GOT hooked (FT_Load_Char fallback)");
    }
    // 诊断：字符集枚举计数（判定映射是否在装载期一次性完成）
    void* origNextChar = nullptr;
    if (krkr_bridge_got_hook(library, "FT_Get_Next_Char",
                             reinterpret_cast<void*>(&hookedFTGetNextChar), &origNextChar)) {
        gOrigFTGetNextChar = reinterpret_cast<FTGetNextCharFn>(origNextChar);
    }
    // 方案 A：通用控制台行钩子（所有 KAG3 游戏通用）
    installAddLineHook(gameHandle);
    if (gOrigFTGetCharIndex == nullptr && gOrigFTLoadGlyph == nullptr && gOrigFTLoadChar == nullptr) {
        return false;
    }
    gFtProbeInstalled = true;
    // 主动结算线程：进程生命周期常驻（detach），保证点击等待期面板也刷新
    if (!gFlushThreadRunning.exchange(true)) {
        std::thread(flushTimerLoop).detach();
    }
    LOGI("ft probe GOT hooked (Get_Char_Index=%d Load_Glyph=%d Load_Char=%d)",
         gOrigFTGetCharIndex != nullptr ? 1 : 0, gOrigFTLoadGlyph != nullptr ? 1 : 0,
         gOrigFTLoadChar != nullptr ? 1 : 0);
    return true;
}
