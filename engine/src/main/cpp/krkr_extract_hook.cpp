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

// ---- FT_Load_Char 探针（内核经 PLT 调用 FreeType；层文本渲染来源观测）----
using FTLoadCharFn = unsigned long (*)(void* face, unsigned long code, int flags);
FTLoadCharFn gOrigFTLoadChar = nullptr;

unsigned long hookedFTLoadChar(void* face, unsigned long code, int flags) {
    const unsigned long err = gOrigFTLoadChar != nullptr ? gOrigFTLoadChar(face, code, flags) : 0;
    if (code >= 0x20 && code < 0x110000) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "ft char: %lu", code);
    }
    return err;
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

/** FT_Load_Char 探针（诊断用；内核层文本渲染的 FreeType 来源观测）。 */
extern "C" bool krkr_install_ft_probe(void* gameHandle, const char* library) {
    if (gameHandle == nullptr || library == nullptr || gOrigFTLoadChar != nullptr) return false;
    void* target = dlsym(gameHandle, "FT_Load_Char");
    if (target == nullptr) return false;
    void* orig = nullptr;
    if (!krkr_bridge_got_hook(library, "FT_Load_Char",
                              reinterpret_cast<void*>(&hookedFTLoadChar), &orig)) {
        LOGW("ft probe GOT hook failed");
        return false;
    }
    gOrigFTLoadChar = reinterpret_cast<FTLoadCharFn>(orig);
    LOGI("ft probe GOT hooked");
    return true;
}
