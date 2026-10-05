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
#include <sys/mman.h>
#include <unistd.h>

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

// ---- FT_Get_Char_Index 钩子：字符流 → 行重建（纯运行时 hook 的对白提取，不改内核）----
// 内核导出 FreeType 全套且 JUMP_SLOT 经自身 GOT（libgame*.so 实测 158 个 FT_ 槽），
// GOT 补丁即可拦截层文本渲染的每个字符码点（Unicode）。按停顿 300ms 切行累积。
using FTLoadCharFn = unsigned long (*)(void* face, unsigned long code, int flags);
FTLoadCharFn gOrigFTLoadChar = nullptr;
std::mutex gLineMutex;
std::chrono::steady_clock::time_point gLastCharAt;
std::chrono::steady_clock::time_point gLastEmitAt = std::chrono::steady_clock::time_point::min();

unsigned long hookedFTLoadChar(void* face, unsigned long code, int flags) {
    const unsigned long err = gOrigFTLoadChar != nullptr ? gOrigFTLoadChar(face, code, flags) : 0;
    return err;
}

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
        }
    } else {
        gPage = derived;
    }
    if (gRawBuf.size() > 3000) {
        // raw 封顶：保留最后一次页出现之后的尾部（rfind 代价受控）
        const size_t p = gRawBuf.rfind(gPage);
        gRawBuf.erase(0, p == std::string::npos ? gRawBuf.size() / 3 : p);
    }
    if (!gPage.empty() && gPage != gLastEmitted) {
        const auto sinceEmit = std::chrono::steady_clock::now() - gLastEmitAt;
        if (!pageEnd && sinceEmit < std::chrono::milliseconds(300)) return;  // 打字中节流
        gLastEmitted = gPage;
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
        {
            std::lock_guard<std::mutex> lock(gLineMutex);
            if (gRawBuf.empty()) continue;
            snap = gRawBuf.size() > 512 ? gRawBuf.substr(gRawBuf.size() - 512) : gRawBuf;
        }
        const bool pageEnd = std::chrono::steady_clock::now() - gLastCharAt
                > std::chrono::milliseconds(3000);
        {
            std::lock_guard<std::mutex> lock(gLineMutex);
            deriveAndMaybeEmitLocked(pageEnd, snap);
            if (pageEnd) {
                gPage.clear();
                gLastEmitted.clear();
                gRawBuf.clear();
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

unsigned long hookedFTGetCharIndex(void* face, unsigned long code) {
    const unsigned long idx = gOrigFTGetCharIndex != nullptr ? gOrigFTGetCharIndex(face, code) : 0;
    if (gHookInstalled.load(std::memory_order_relaxed)
            && code >= 0x20 && code < 0x110000 && code != gPrevCode) {
        const auto now = std::chrono::steady_clock::now();
        std::lock_guard<std::mutex> lock(gLineMutex);
        ftFeedCharLocked(code);
        {
            // raw 码点流累积（调试）
            const unsigned char* b = reinterpret_cast<const unsigned char*>(&code);
            (void) b;
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
    gPrevCode = code;
    return idx;
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

    // 首选 shadowhook inline hook；本进程对插件目录内核不可用（errno=12）时退虚表补丁
    if (installShadowhook()) {
        void* shadowhook = shadowhookLib();
        auto hookAddr = reinterpret_cast<HookFuncAddrFn>(
                dlsym(shadowhook, kShadowhookHookFuncAddr));
        void* orig = nullptr;
        if (hookAddr != nullptr &&
            hookAddr(target, reinterpret_cast<void*>(&hookedLabelSetString), &orig) != nullptr) {
            gOrigSetString = reinterpret_cast<SetStringFn>(orig);
            gHookInstalled.store(true, std::memory_order_relaxed);
            LOGI("extract hook: Label::setString inline hooked (text extraction armed)");
            return true;
        }
        auto getErrno = reinterpret_cast<int (*)()>(dlsym(shadowhook, kShadowhookGetErrno));
        auto toErrmsg = reinterpret_cast<const char* (*)(int)>(dlsym(shadowhook, kShadowhookToErrmsg));
        int err = getErrno ? getErrno() : -1;
        LOGW("extract hook: inline hook failed errno=%d msg=%s; falling back to vtable patch",
             err, toErrmsg ? toErrmsg(err) : "?");
    }

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
extern "C" bool krkr_install_ft_probe(void* gameHandle, const char* library) {
    if (gameHandle == nullptr || library == nullptr || gOrigFTGetCharIndex != nullptr) return false;
    void* orig = nullptr;
    if (!krkr_bridge_got_hook(library, "FT_Get_Char_Index",
                              reinterpret_cast<void*>(&hookedFTGetCharIndex), &orig)) {
        LOGW("ft probe GOT hook failed");
        return false;
    }
    gOrigFTGetCharIndex = reinterpret_cast<FTGetCharIndexFn>(orig);
    // 主动结算线程：进程生命周期常驻（detach），保证点击等待期面板也刷新
    if (!gFlushThreadRunning.exchange(true)) {
        std::thread(flushTimerLoop).detach();
    }
    LOGI("ft probe GOT hooked (FT_Get_Char_Index)");
    return true;
}
