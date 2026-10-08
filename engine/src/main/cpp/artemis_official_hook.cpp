// artemis_official_hook.cpp — 官方 revision Artemis 内核的字符串层提取钩子。
//
// 背景：官方内核（libartemis.so / -compatible / -compatible-v2 / -v4 / -v5 / -v6）
// 导出全套引擎 API（artemis:: 类 / Lua / FreeType），其中：
// - artemis::CBackLog::Add(shared_ptr, deque&, vector&, bool, string const&)
//   第 6 参 = 每页完整文本（后日志追加时传入，显示时序）——理想主源；
// - artemis::CArtemisParser::Text / TextTail(string const&)
//   解析器文本段（候选源）。
// 三符号在六个官方内核均导出（llvm-nm 实测）；clean 内核（artc:: 命名空间）
// 不导出 → dlsym 探测自动区分，不装钩子。
//
// 内联钩子（arm64 手写跳板）：内核内部对上述函数为 intra-DSO 直调
// （无 JUMP_SLOT 重定位，GOT 补丁不可达）。序言已静态核验为无 PC 相对
// 指令（sub/stp/str/add/mrs/ldr-reg/mov），4 指令跳板安全；安装时仍逐条
// 校验，发现 adrp/adr/b/bl/cbz/cbnz/tbz/tbnz/ldr-literal 拒绝挂钩。
// 本机模拟器 shadowhook_init 失败（errno=12），故自带跳板；真机如需可换。
//
// 上行：[FTLN] = BackLog 页（候选 0/自动显示），[FTRAW] = Parser 段（候选）。
// 事件复用 KRKR 的 FTLN/FTRAW 通道（NativeBridge/面板/候选机制全复用）。

#include <dlfcn.h>
#include <android/log.h>
#include <jni.h>
#include <sys/mman.h>
#include <sys/uio.h>
#include <unistd.h>
#include <cerrno>
#include <fcntl.h>
#include <cstdint>
#include <cstring>
#include <string>
#include <atomic>
#include <mutex>
#include <thread>
#include <chrono>

#define TAG "ArtemisExtract"

// TPoint<int>（OnTouch 的坐标参数；POD 两 int，const 引用传入）
template <typename T>
struct TPoint {
    T x;
    T y;
};
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

constexpr const char* kBackLogAdd =
    "_ZN7artemis8CBackLog3AddEN5boost10shared_ptrINS_18CLinkableTextLayerEEERNSt6__ndk15deque"
    "INS_13CFontPropertyENS5_9allocatorIS7_EEEERNS5_6vectorINS_12CScriptBlockENS8_ISD_EEEEbRK"
    "NS5_12basic_stringIcNS5_11char_traitsIcEENS8_IcEEEE";
constexpr const char* kParserText =
    "_ZN7artemis14CArtemisParser4TextERNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9al"
    "locatorIcEEEE";
constexpr const char* kParserTextTail =
    "_ZN7artemis14CArtemisParser8TextTailERNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1"
    "_9allocatorIcEEEE";
// 官方内核脚本 [s] 文本的打印命令处理器（Girl's Blossom Project 实测三个解析器钩子
// 全静默 → 文本不流经 CArtemisParser::Text / CBackLog::Add，探测此通道）
constexpr const char* kCommandPrint =
    "_ZN7artemis8CArtemis12CommandPrintERNS_12CScriptBlockEb";
constexpr const char* kBlockToString =
    "_ZN7artemis12CScriptBlock8ToStringENS_11CStringUtil7CHARSETE";
// 语音命令处理器（与 CommandPrint 同签名，块参数 map 携带语音文件名）
constexpr const char* kCommandVoice =
    "_ZN7artemis8CArtemis12CommandVoiceERNS_12CScriptBlockEb";

JavaVM* gJvm = nullptr;
jclass gBridgeClass = nullptr;
jmethodID gOnTextMethod = nullptr;
std::atomic<bool> gInstalled{false};
std::atomic<bool> gFirstEmitLogged{false};
std::mutex gInstallMutex;

// ---- libc++（__ndk1）std::string 读取（布局与 clean 内核一致，已核验） ----
std::string readStdString(const void* p) {
    if (p == nullptr) return {};
    const unsigned char* b = static_cast<const unsigned char*>(p);
    if ((b[0] & 1u) != 0) {
        const size_t size = *reinterpret_cast<const size_t*>(b + 8);
        const char* data = *reinterpret_cast<const char* const*>(b + 16);
        if (data == nullptr || size == 0) return {};
        return std::string(data, size > 8192 ? 8192 : size);
    }
    return std::string(reinterpret_cast<const char*>(b + 1), b[0] >> 1);
}

// ---- 上行（ArtemisActivity.onArtemisExtract；引擎线程 attach） ----
// onArtemisExtract 签名 (text, voiceName, voiceCached) 三 String——任何一参
// 缺传时 CheckJNI 会把 va_list 栈上垃圾当 jobject 校验 → SIGABRT，必须传满。
jstring newJavaString(JNIEnv* env, const char* text) {
    if (text == nullptr) return env->NewStringUTF("");
    const jsize len = static_cast<jsize>(strlen(text));
    jbyteArray arr = env->NewByteArray(len);
    if (arr == nullptr) return env->NewStringUTF("");
    env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte*>(text));
    jstring out = nullptr;
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass != nullptr) {
        jmethodID ctor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
        if (ctor != nullptr) {
            jstring charset = env->NewStringUTF("UTF-8");
            jobject obj = env->NewObject(stringClass, ctor, arr, charset);
            if (env->ExceptionCheck()) env->ExceptionClear();
            else out = static_cast<jstring>(obj);
            if (charset != nullptr) env->DeleteLocalRef(charset);
        }
        env->DeleteLocalRef(stringClass);
    }
    env->DeleteLocalRef(arr);
    return out != nullptr ? out : env->NewStringUTF("");
}

void emitEvent(const char* text, const char* voiceName) {
    JavaVM* vm = gJvm;
    if (vm == nullptr || gBridgeClass == nullptr || gOnTextMethod == nullptr) {
        LOGW("emitText skip: vm=%p cls=%p m=%p", vm, (void*) gBridgeClass,
             (void*) gOnTextMethod);
        return;
    }
    JNIEnv* env = nullptr;
    bool attached = false;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) return;
        attached = true;
    }
    jstring result = newJavaString(env, text);
    jstring voice = newJavaString(env, voiceName);
    if (result != nullptr && voice != nullptr) {
        env->CallStaticVoidMethod(gBridgeClass, gOnTextMethod, result, voice, voice);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    if (result != nullptr) env->DeleteLocalRef(result);
    if (voice != nullptr) env->DeleteLocalRef(voice);
    if (attached) vm->DetachCurrentThread();
}

void emitText(const char* text) {
    emitEvent(text, "");
}

// ---- arm64 内联钩子（4 指令跳板） ----
constexpr uint32_t INSN_COUNT = 4;

// PC 相对指令检测（不可原样搬运）：adrp/adr/b/bl/cbz/cbnz/tbz/tbnz/ldr literal
bool insnIsPcRelative(uint32_t ins) {
    if ((ins & 0x1F000000u) == 0x10000000u) return true;             // adr/adrp
    if ((ins & 0x7C000000u) == 0x14000000u) return true;             // b/bl
    if ((ins & 0x7E000000u) == 0x34000000u) return true;             // cbz/cbnz
    if ((ins & 0x7E000000u) == 0x36000000u) return true;             // tbz/tbnz
    if ((ins & 0x3B000000u) == 0x18000000u) return true;             // ldr literal
    return false;
}

struct InlineHook {
    void* target;          // 函数入口
    void* trampoline;      // 原指令副本 + 跳回
    void* originalFn;      // = trampoline（直接调它即调原函数）
};

// 跳板页（RWX 需求拆成两段：先 RW 写，再改 RX 执行）
bool makeTrampoline(void* target, void** trampolineOut, void** originalOut) {
    const long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) return false;
    void* mem = mmap(nullptr, static_cast<size_t>(page), PROT_READ | PROT_WRITE,
                     MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (mem == MAP_FAILED) return false;
    auto* code = static_cast<uint32_t*>(mem);

    // 校验目标前 4 条指令可搬运
    auto* ins = static_cast<uint32_t*>(target);
    for (uint32_t i = 0; i < INSN_COUNT; ++i) {
        uint32_t v;
        memcpy(&v, ins + i, 4);
        if (insnIsPcRelative(v)) {
            munmap(mem, static_cast<size_t>(page));
            return false;
        }
        code[i] = v;  // 无 PC 相对 → 原样复制
    }
    // 跳回 target + 16：ldr x17, [pc, #8]（字面量在 +24..31）；br x17 前补 nop
    // 布局：+16 ldr x17,[pc,#8] → 字面量在 +16+8+4 = +28？ 精确：LDR literal 的
    // 目标地址 = PC + imm19*4。放 imm=2 → PC+8（+16+8=+24），字面量 8 字节放 +24..31。
    // +20 空隙放 NOP（d503201f）。
    // ldr x17, [pc, #12]：imm19=3 → 字面量在 +16+12 = +28（8 字节至 +35）
    code[4] = 0x58000071u;  // ldr x17, [pc, #12]
    code[5] = 0xD503201Fu;  // nop
    code[6] = 0xD61F0220u;  // br x17
    const uint64_t back = reinterpret_cast<uint64_t>(target) + INSN_COUNT * 4;
    memcpy(code + 7, &back, 8);

    if (mprotect(mem, static_cast<size_t>(page), PROT_READ | PROT_EXEC) != 0) {
        munmap(mem, static_cast<size_t>(page));
        return false;
    }
    __builtin___clear_cache(reinterpret_cast<char*>(mem),
                            reinterpret_cast<char*>(mem) + 64);
    *trampolineOut = mem;
    if (originalOut != nullptr) *originalOut = mem;
    LOGI("official hook: trampoline %p back=%p (target %p)",
         mem, (void*)(reinterpret_cast<uintptr_t>(target) + INSN_COUNT * 4), target);
    return true;
}

// W^X 加固内核（实测 alioth/MIUI12.5 Android 11）：文本页 mprotect 加 W 成功、
// 恢复 RX 被 EACCES 拒绝（exec-after-write 硬化）→ 条目页停在不可执行态，
// 游戏首次调用被钩函数即 SEGV_ACCERR。改走 /proc/self/mem 内核直写：
// 不动页保护（页保持 R+X），内核写路径不受 mprotect W^X 策略约束。
static int gProcMemFd = -1;

bool writeProcMem(void* addr, const void* data, size_t len) {
    if (gProcMemFd < 0) {
        gProcMemFd = open("/proc/self/mem", O_RDWR);
        if (gProcMemFd < 0) {
            LOGW("official hook: /proc/self/mem open failed errno=%d", errno);
            return false;
        }
    }
    ssize_t n = pwrite(gProcMemFd, data, len, static_cast<off_t>(
            reinterpret_cast<uintptr_t>(addr)));
    if (n != static_cast<ssize_t>(len)) {
        LOGW("official hook: proc-mem pwrite short %zd/%zu errno=%d addr=%p",
             n, len, errno, addr);
        return false;
    }
    return true;
}

bool patchEntry(void* target, void* hookFn) {
    uint32_t patch[4] = {0x58000050u,   // ldr x16, [pc, #8]（字面量在 +8）
                         0xD61F0200u,   // br x16
                         0, 0};
    const uint64_t hook = reinterpret_cast<uint64_t>(hookFn);
    memcpy(patch + 2, &hook, 8);
    if (!writeProcMem(target, patch, 16)) return false;
    // 回读校验（直写被内核策略拦截时立即暴露，不留「装好但页不可执行」暗雷）
    uint32_t verify[4] = {};
    if (pread(gProcMemFd, verify, 16, static_cast<off_t>(
            reinterpret_cast<uintptr_t>(target))) != 16 ||
        memcmp(verify, patch, 16) != 0) {
        LOGW("official hook: patch verify failed; target=%p", target);
        return false;
    }
    LOGI("official hook: patched entry %p via /proc/self/mem", target);
    __builtin___clear_cache(reinterpret_cast<char*>(target),
                            reinterpret_cast<char*>(target) + 16);
    return true;
}

// ---- 钩子体 ----
// CBackLog::Add 真实 ABI（Itanium；shared_ptr 非平凡类型按不可见引用占 1 寄存器）：
//   Add(this=x0, shared_ptr&=x1, deque&=x2, vector&=x3, bool=w4, string const&=x5)
// —— 6 槽位。此前声明成 7 参（shared_ptr 拆双槽）会错读 x6 垃圾指针为字符串
// 引用 → 引擎线程 SEGV_ACCERR 崩溃（真机 alioth 实测）。
using BackLogAddFn = void (*)(void*, void*, void*, void*, int, const void*);
using ParserTextFn = void (*)(void*, const void*);

BackLogAddFn gOrigBackLogAdd = nullptr;
ParserTextFn gOrigParserText = nullptr;
ParserTextFn gOrigParserTextTail = nullptr;

// 首调诊断：区分「钩子未被调用」与「被调用但 readStdString 读空」。
// process_vm_readv 自读可安全探测野指针（失败不崩），原始字节直接判断 ABI——
// libc++ 短串首字节 = size*2（偶数），const char* 则直接是 ASCII。
static void logFirstEntry(int slot, const char* name, void* self, const void* strRef) {
    static std::atomic<unsigned> logged{0};
    const unsigned bit = 1u << slot;
    if (logged.load(std::memory_order_relaxed) & bit) return;
    if (logged.fetch_or(bit, std::memory_order_relaxed) & bit) return;
    uint8_t head[16] = {};
    size_t got = 0;
    if (strRef != nullptr) {
        struct iovec local = { head, sizeof(head) };
        struct iovec remote = { const_cast<void*>(strRef), sizeof(head) };
        ssize_t r = process_vm_readv(getpid(), &local, 1, &remote, 1, 0);
        if (r > 0) got = static_cast<size_t>(r);
    }
    char hex[3 * sizeof(head) + 1] = {};
    for (size_t i = 0; i < got; ++i) {
        snprintf(hex + 3 * i, 4, "%02x ", head[i]);
    }
    LOGI("official hook: %s FIRST ENTRY self=%p strRef=%p read=%zu [%s]",
         name, self, strRef, got, hex);
}

void hookedBackLogAdd(void* self, void* sharedPtr, void* deque,
                      void* vec, int flag, const void* strRef) {
    logFirstEntry(0, "BackLog::Add", self, strRef);
    const BackLogAddFn orig = gOrigBackLogAdd;
    if (orig != nullptr) orig(self, sharedPtr, deque, vec, flag, strRef);
    if (strRef != nullptr) {
        std::string text = readStdString(strRef);
        if (!text.empty()) {
            if (!gFirstEmitLogged.exchange(true, std::memory_order_relaxed)) {
                LOGI("BackLog::Add first capture: len=%zu text=%.60s",
                     text.size(), text.c_str());
            }
            emitText(text.c_str());
        }
    }
}

void hookedParserText(void* self, const void* strRef) {
    logFirstEntry(1, "Parser::Text", self, strRef);
    const ParserTextFn orig = gOrigParserText;
    if (orig != nullptr) orig(self, strRef);
    if (strRef != nullptr) {
        std::string text = readStdString(strRef);
        if (!text.empty()) {
            emitText(text.c_str());
        }
    }
}

void hookedParserTextTail(void* self, const void* strRef) {
    logFirstEntry(2, "Parser::TextTail", self, strRef);
    const ParserTextFn orig = gOrigParserTextTail;
    if (orig != nullptr) orig(self, strRef);
}

using CommandPrintFn = void (*)(void*, void*, bool);
CommandPrintFn gOrigCommandPrint = nullptr;

// CScriptBlock::ToString(CHARSET) — Itanium ABI 返回 std::string 走隐藏 sret：
//   ToString(std::string* sret, this, charset)。CommandPrint 的文本经此取回。
using BlockToStringFn = void (*)(void* sret, void* self, int charset);
BlockToStringFn gOrigBlockToString = nullptr;

void hookedBlockToString(void* sret, void* self, int charset) {
    const BlockToStringFn orig = gOrigBlockToString;
    if (orig != nullptr) orig(sret, self, charset);
    if (sret != nullptr) {
        std::string text = readStdString(sret);
        if (!text.empty()) {
            static std::atomic<bool> firstLogged{false};
            if (!firstLogged.exchange(true, std::memory_order_relaxed)) {
                LOGI("Block::ToString first capture: len=%zu text=%.60s",
                     text.size(), text.c_str());
            }
            emitText(text.c_str());
        }
    }
}

// libc++ __tree_node<std::__ndk1::pair<string const, string>>: left+0 right+8
// parent+0x10 black+0x18 key+0x20 value+0x38（Girl's Blossom Project 实测布局）。
// 回调返回 true 停止遍历。
template <typename Fn>
static void walkArgTree(void* node, int depth, Fn&& fn) {
    if (node == nullptr || depth > 3) return;
    uint8_t* p = static_cast<uint8_t*>(node);
    uint64_t left = 0, right = 0;
    memcpy(&left, p, 8);
    memcpy(&right, p + 8, 8);
    walkArgTree(reinterpret_cast<void*>(left), depth + 1, fn);
    std::string key = readStdString(p + 0x20);
    std::string value = readStdString(p + 0x38);
    if (fn(key, value)) return;
    walkArgTree(reinterpret_cast<void*>(right), depth + 1, fn);
}

// 官方内核文本提取主通道（Girl's Blossom Project 实测）：脚本每个 print 命令
// 触发 CArtemis::CommandPrint，块参数 map 携带 data=<文本>。说话人行与正文行
// 成对到达（间隔 ~2ms）：speaker("Himari") → 正文。native 侧配对成
// 「speaker\n正文」一次上行——分条上行会让宿主历史记录多出说话人碎片条目；
// 400ms 兜底线程 flush 独立行（旁白无说话人也能及时显示）。
static std::mutex gPairMutex;
static std::string gPendingLine;
static int64_t gPendingAtMs = 0;

static int64_t steadyNowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

static void flushPendingIfStale() {
    std::string out;
    {
        std::lock_guard<std::mutex> lk(gPairMutex);
        if (!gPendingLine.empty() && steadyNowMs() - gPendingAtMs >= 400) {
            out = std::move(gPendingLine);
            gPendingLine.clear();
        }
    }
    if (!out.empty()) emitText(out.c_str());
}

static void feedPrintLine(const std::string& value) {
    std::string emitNow;
    bool spawnTimer = false;
    int64_t at = 0;
    {
        std::lock_guard<std::mutex> lk(gPairMutex);
        int64_t now = steadyNowMs();
        if (!gPendingLine.empty() && now - gPendingAtMs < 400
                && gPendingLine.size() <= 48
                && gPendingLine.find('\n') == std::string::npos) {
            // 短行 + 紧随 → 说话人；与其正文合并一次上行
            emitNow = gPendingLine + "\n" + value;
            gPendingLine.clear();
        } else {
            if (!gPendingLine.empty()) emitNow = std::move(gPendingLine);  // 孤儿补发
            gPendingLine = value;
            gPendingAtMs = now;
            at = now;
            spawnTimer = true;
        }
    }
    if (!emitNow.empty()) emitText(emitNow.c_str());
    if (spawnTimer) {
        std::thread([at] {
            std::this_thread::sleep_for(std::chrono::milliseconds(420));
            (void) at;
            flushPendingIfStale();
        }).detach();
    }
}

void hookedCommandPrint(void* self, void* blockRef, bool flag) {
    const CommandPrintFn orig = gOrigCommandPrint;
    if (blockRef != nullptr) {
        static std::atomic<bool> firstCaptureLogged{false};
        uint64_t node = 0;
        memcpy(&node, static_cast<uint8_t*>(blockRef) + 0x20, 8);
        if (node > 0x1000) {
            walkArgTree(reinterpret_cast<void*>(node), 0,
                    [](const std::string& key, const std::string& value) {
                        if (key != "data" || value.empty()) return false;
                        if (!firstCaptureLogged.exchange(true, std::memory_order_relaxed)) {
                            LOGI("CommandPrint first capture: %.60s", value.c_str());
                        }
                        feedPrintLine(value);
                        return false;
                    });
        }
    }
    if (orig != nullptr) orig(self, blockRef, flag);
}

// ---- 语音命令钩子：块参数携带语音文件名 → 空文本+语音名上行
// （facade 只认「text 空且 voiceFile 非空」进待配队列，随下一句台词消费）。
using CommandVoiceFn = void (*)(void*, void*, bool);
CommandVoiceFn gOrigCommandVoice = nullptr;

static bool looksLikeVoiceFile(const std::string& v) {
    if (v.empty() || v.size() > 200) return false;
    static const char* kExts[] = {".ogg", ".opus", ".wav", ".m4a", ".mp3", ".npz"};
    for (const char* e : kExts) {
        if (v.size() >= strlen(e)
                && strcasecmp(v.c_str() + v.size() - strlen(e), e) == 0) return true;
    }
    return v.find('/') != std::string::npos;  // 脚本内路径（voice/xxx）
}

void hookedCommandVoice(void* self, void* blockRef, bool flag) {
    const CommandVoiceFn orig = gOrigCommandVoice;
    if (blockRef != nullptr) {
        static std::atomic<int> logCount{0};
        bool first = logCount.load(std::memory_order_relaxed) < 8;
        uint64_t node = 0;
        memcpy(&node, static_cast<uint8_t*>(blockRef) + 0x20, 8);
        if (node > 0x1000) {
            std::string voice;
            walkArgTree(reinterpret_cast<void*>(node), 0,
                    [&voice, first](const std::string& key, const std::string& value) {
                        if (first) {
                            int n = logCount.fetch_add(1, std::memory_order_relaxed);
                            LOGI("CommandVoice arg[%d] key='%.24s' value='%.96s'",
                                 n, key.c_str(), value.c_str());
                        }
                        if (voice.empty() && looksLikeVoiceFile(value)) voice = value;
                        return false;
                    });
            if (!voice.empty()) {
                static std::atomic<bool> voiceEmitLogged{false};
                if (!voiceEmitLogged.exchange(true, std::memory_order_relaxed)) {
                    LOGI("CommandVoice first emit: %.80s", voice.c_str());
                }
                emitEvent("", voice.c_str());
            }
        }
    }
    if (orig != nullptr) orig(self, blockRef, flag);
}

// ---- CArtemisTouch 触摸状态机钩子：官方内核虚拟鼠标点击注入的入口 ----
// 官方内核经 NativeActivity 输入队列驱动 CArtemisTouch（OnBegin/OnTouch/OnEnd）。
// 钩住三点：捕获触摸对象指针（this）与引擎坐标点（TPoint<int>），供虚拟鼠标
// 点击时在该对象上合成 OnBegin/OnTouch/OnEnd 序列。
constexpr const char* kTouchOnBegin = "_ZN7artemis13CArtemisTouch7OnBeginEv";
constexpr const char* kTouchOnTouch = "_ZN7artemis13CArtemisTouch7OnTouchEiRKNS_6TPointIiEE";
constexpr const char* kTouchOnEnd = "_ZN7artemis13CArtemisTouch5OnEndEv";

using TouchOnBeginFn = void (*)(void*);
using TouchOnTouchFn = void (*)(void*, int, const void*);
using TouchOnEndFn = void (*)(void*);

void* gTouchObj = nullptr;
std::atomic<bool> gTouchObjCaptured{false};
std::atomic<int> gTouchPtX{0};
std::atomic<int> gTouchPtY{0};

TouchOnBeginFn gOrigTouchBegin = nullptr;
TouchOnTouchFn gOrigTouchOnTouch = nullptr;
TouchOnEndFn gOrigTouchEnd = nullptr;

void hookedTouchBegin(void* self) {
    const TouchOnBeginFn orig = gOrigTouchBegin;
    if (orig != nullptr) orig(self);
    gTouchObj = self;
    gTouchObjCaptured.store(true, std::memory_order_relaxed);
}

void hookedTouchOnTouch(void* self, int id, const void* point) {
    const TouchOnTouchFn orig = gOrigTouchOnTouch;
    if (orig != nullptr) orig(self, id, point);
    gTouchObj = self;
    gTouchObjCaptured.store(true, std::memory_order_relaxed);
    if (point != nullptr) {
        gTouchPtX.store(*static_cast<const int*>(point), std::memory_order_relaxed);
        gTouchPtY.store(*(reinterpret_cast<const int*>(point) + 1), std::memory_order_relaxed);
    }
}

void hookedTouchEnd(void* self) {
    const TouchOnEndFn orig = gOrigTouchEnd;
    if (orig != nullptr) orig(self);
    gTouchObj = self;
    gTouchObjCaptured.store(true, std::memory_order_relaxed);
}

}  // namespace

/**
 * 官方内核提取钩子安装：kernelHandle = artemis_loader dlopen 的内核句柄。
 * dlsym 探测 CBackLog::Add —— 官方内核命中并装内联钩子；clean 内核无此符号
 * 自动跳过（其提取走内置 extract_bridge）。jvm 用于上行 JNI 绑定。
 */
extern "C" bool artemis_official_install(void* kernelHandle, JavaVM* jvm, jobject activityObj) {
    if (kernelHandle == nullptr || jvm == nullptr || activityObj == nullptr) {
        LOGW("official hook: skip (handle=%p jvm=%p activity=%p)",
             kernelHandle, (void*) jvm, (void*) activityObj);
        return false;
    }
    std::lock_guard<std::mutex> lock(gInstallMutex);
    if (gInstalled.load(std::memory_order_relaxed)) return true;

    void* backlogAdd = dlsym(kernelHandle, kBackLogAdd);
    if (backlogAdd == nullptr) {
        LOGW("official hook: CBackLog::Add not exported (clean kernel?); skip");
        return false;  // clean 内核：静默跳过
    }

    if (gJvm == nullptr) gJvm = jvm;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (gJvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        if (gJvm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) {
            LOGW("official hook: JNIEnv unavailable");
            return false;
        }
        attached = true;
    }
    // 类必须经 activity 对象取（GetObjectClass → 应用类加载器）。直接 FindClass
    // 会落在 NativeActivity 的引导类加载器上——应用类（com.ies_net.**）全部找不到
    //（真机 alioth/MIUI12.5 实测 "ArtemisActivity class not found" 根因）。
    jclass bridge = env->GetObjectClass(activityObj);
    if (bridge == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (attached) gJvm->DetachCurrentThread();
        LOGW("official hook: ArtemisActivity class not found via activity object");
        return false;
    }
    gBridgeClass = static_cast<jclass>(env->NewGlobalRef(bridge));
    env->DeleteLocalRef(bridge);
    gOnTextMethod = env->GetStaticMethodID(gBridgeClass, "onArtemisExtract",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    if (attached) gJvm->DetachCurrentThread();
    if (gOnTextMethod == nullptr) {
        LOGW("official hook: onArtemisExtract method unresolved");
        return false;
    }

    InlineHook hooks[10];
    int count = 0;
    void* orig = nullptr;
    if (makeTrampoline(backlogAdd, &hooks[count].trampoline, &orig)
            && patchEntry(backlogAdd, reinterpret_cast<void*>(&hookedBackLogAdd))) {
        gOrigBackLogAdd = reinterpret_cast<BackLogAddFn>(orig);
        ++count;
    } else {
        LOGW("official hook: BackLog::Add inline hook failed");
    }
    void* parserText = dlsym(kernelHandle, kParserText);
    if (parserText != nullptr
            && makeTrampoline(parserText, &hooks[count].trampoline, &orig)
            && patchEntry(parserText, reinterpret_cast<void*>(&hookedParserText))) {
        gOrigParserText = reinterpret_cast<ParserTextFn>(orig);
        ++count;
    }
    void* textTail = dlsym(kernelHandle, kParserTextTail);
    if (textTail != nullptr
            && makeTrampoline(textTail, &hooks[count].trampoline, &orig)
            && patchEntry(textTail, reinterpret_cast<void*>(&hookedParserTextTail))) {
        gOrigParserTextTail = reinterpret_cast<ParserTextFn>(orig);
        ++count;
    }
    // 触摸状态机钩子（虚拟鼠标点击注入入口；序言校验失败仅缺失点击能力）
    void* touchBegin = dlsym(kernelHandle, kTouchOnBegin);
    void* touchOnTouch = dlsym(kernelHandle, kTouchOnTouch);
    void* touchEnd = dlsym(kernelHandle, kTouchOnEnd);
    if (touchBegin != nullptr && count < 8
            && makeTrampoline(touchBegin, &hooks[count].trampoline, &orig)
            && patchEntry(touchBegin, reinterpret_cast<void*>(&hookedTouchBegin))) {
        gOrigTouchBegin = reinterpret_cast<TouchOnBeginFn>(orig);
        ++count;
    }
    if (touchOnTouch != nullptr && count < 8
            && makeTrampoline(touchOnTouch, &hooks[count].trampoline, &orig)
            && patchEntry(touchOnTouch, reinterpret_cast<void*>(&hookedTouchOnTouch))) {
        gOrigTouchOnTouch = reinterpret_cast<TouchOnTouchFn>(orig);
        ++count;
    }
    if (touchEnd != nullptr && count < 8
            && makeTrampoline(touchEnd, &hooks[count].trampoline, &orig)
            && patchEntry(touchEnd, reinterpret_cast<void*>(&hookedTouchEnd))) {
        gOrigTouchEnd = reinterpret_cast<TouchOnEndFn>(orig);
        ++count;
    }
    // 文本通道探测：CommandPrint 首调日志定位真实显示路径
    void* commandPrint = dlsym(kernelHandle, kCommandPrint);
    if (commandPrint != nullptr && count < 8
            && makeTrampoline(commandPrint, &hooks[count].trampoline, &orig)
            && patchEntry(commandPrint, reinterpret_cast<void*>(&hookedCommandPrint))) {
        gOrigCommandPrint = reinterpret_cast<CommandPrintFn>(orig);
        ++count;
    } else if (commandPrint == nullptr) {
        LOGW("official hook: CArtemis::CommandPrint not exported");
    }
    // 文本提取主通道：CScriptBlock::ToString（CommandPrint 内部经此取文本）
    void* blockToString = dlsym(kernelHandle, kBlockToString);
    if (blockToString != nullptr && count < 9
            && makeTrampoline(blockToString, &hooks[count].trampoline, &orig)
            && patchEntry(blockToString, reinterpret_cast<void*>(&hookedBlockToString))) {
        gOrigBlockToString = reinterpret_cast<BlockToStringFn>(orig);
        ++count;
    } else if (blockToString == nullptr) {
        LOGW("official hook: CScriptBlock::ToString not exported");
    }
    // 语音通道：CommandVoice 块参数取语音文件名 → 空文本+语音名上行
    void* commandVoice = dlsym(kernelHandle, kCommandVoice);
    if (commandVoice != nullptr && count < 9
            && makeTrampoline(commandVoice, &hooks[count].trampoline, &orig)
            && patchEntry(commandVoice, reinterpret_cast<void*>(&hookedCommandVoice))) {
        gOrigCommandVoice = reinterpret_cast<CommandVoiceFn>(orig);
        ++count;
    } else if (commandVoice == nullptr) {
        LOGW("official hook: CArtemis::CommandVoice not exported");
    }
    (void) hooks;
    gInstalled.store(true, std::memory_order_relaxed);
    LOGI("official hook: installed %d hooks (BackLog::Add / Parser::Text / TextTail / CArtemisTouch x3)", count);
    return count > 0;
}

// ---- 虚拟鼠标点击注入（audio_bridge JNI 层经此访问） ----

/** 触摸状态：out[0]=对象已捕获 out[1]=对象指针 out[2]=最近引擎点 x out[3]=y。 */
extern "C" void artemis_official_touch_state(double* out) {
    out[0] = gTouchObjCaptured.load(std::memory_order_relaxed) ? 1.0 : 0.0;
    out[1] = static_cast<double>(reinterpret_cast<uintptr_t>(gTouchObj));
    out[2] = gTouchPtX.load(std::memory_order_relaxed);
    out[3] = gTouchPtY.load(std::memory_order_relaxed);
}

/** 在触摸对象上合成一次点击（OnBegin → OnTouch(id=0, pt) → OnEnd）。 */
extern "C" bool artemis_official_touch_click(long objPtr, int x, int y) {
    if (!gTouchObjCaptured.load(std::memory_order_relaxed)) return false;
    void* obj = reinterpret_cast<void*>(static_cast<uintptr_t>(objPtr));
    TPoint<int> pt{x, y};
    if (gOrigTouchBegin != nullptr) gOrigTouchBegin(obj);
    if (gOrigTouchOnTouch != nullptr) gOrigTouchOnTouch(obj, 0, &pt);
    if (gOrigTouchEnd != nullptr) gOrigTouchEnd(obj);
    return true;
}

// JNI：Kotlin ArtemisActivity.artemisTouchState()/artemisTouchClick()（运行时按名解析）
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_ies_1net_artemis_ArtemisActivity_artemisTouchState(JNIEnv* env, jclass) {
    double out[4] = {0, 0, 0, 0};
    artemis_official_touch_state(out);
    jdoubleArray arr = env->NewDoubleArray(4);
    if (arr != nullptr) env->SetDoubleArrayRegion(arr, 0, 4, out);
    return arr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ies_1net_artemis_ArtemisActivity_artemisTouchClick(JNIEnv*, jclass, jlong objPtr, jint x, jint y) {
    return artemis_official_touch_click(objPtr, x, y) ? JNI_TRUE : JNI_FALSE;
}
