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
#include <unistd.h>
#include <cerrno>
#include <fcntl.h>
#include <cstdint>
#include <cstring>
#include <string>
#include <atomic>
#include <mutex>

#define TAG "ArtemisExtract"
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

JavaVM* gJvm = nullptr;
jclass gBridgeClass = nullptr;
jmethodID gOnTextMethod = nullptr;
std::atomic<bool> gInstalled{false};
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
void emitText(const char* text) {
    JavaVM* vm = gJvm;
    if (vm == nullptr || gBridgeClass == nullptr || gOnTextMethod == nullptr) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) return;
        attached = true;
    }
    jstring result = nullptr;
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
    if (result != nullptr) {
        env->CallStaticVoidMethod(gBridgeClass, gOnTextMethod, result);
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(result);
    }
    if (attached) vm->DetachCurrentThread();
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

void hookedBackLogAdd(void* self, void* sharedPtr, void* deque,
                      void* vec, int flag, const void* strRef) {
    const BackLogAddFn orig = gOrigBackLogAdd;
    if (orig != nullptr) orig(self, sharedPtr, deque, vec, flag, strRef);
    if (strRef != nullptr) {
        std::string text = readStdString(strRef);
        if (!text.empty()) {
            text.insert(0, "[FTLN]");
            emitText(text.c_str());
        }
    }
}

void hookedParserText(void* self, const void* strRef) {
    const ParserTextFn orig = gOrigParserText;
    if (orig != nullptr) orig(self, strRef);
    if (strRef != nullptr) {
        std::string text = readStdString(strRef);
        if (!text.empty()) {
            text.insert(0, "[FTRAW]");
            emitText(text.c_str());
        }
    }
}

void hookedParserTextTail(void* self, const void* strRef) {
    const ParserTextFn orig = gOrigParserTextTail;
    if (orig != nullptr) orig(self, strRef);
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

    InlineHook hooks[3];
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
    (void) hooks;
    gInstalled.store(true, std::memory_order_relaxed);
    LOGI("official hook: installed %d hooks (BackLog::Add / Parser::Text / TextTail)", count);
    return count > 0;
}
