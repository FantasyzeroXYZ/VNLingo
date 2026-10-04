// artemis_extract_hook.cpp
// Artemis 提取桥的原生侧补齐。编译进两库（CMakeLists 传 ARTEMIS_HOOK_LOADER 区分）：
// - libartemis_loader（ARTEMIS_HOOK_LOADER）：ANativeActivity_onCreate 阶段的早期安装入口
//   artemis_install_extract_hook；
// - libartemis_audio_bridge：JNI 入口 nativeInstallExtractHook——早期失败时由 Java 侧
//   （ArtemisExtractFacade 构造）延迟重试。
//
// 背景：提取面板的 Java 侧依赖内核 extract_bridge 发射器回调
// ArtemisActivity.onArtemisExtract，但随包 Artemis 内核（含 clean 内核
// libartemis-clean.so）均无该发射器（外部内核源码仓的带桥构建未随本仓分发）。
//
// 方案：GOT 补丁（与 krkr_bridge relocate 的 open/fopen 钩子同款技术）——
// clean 内核对自家导出的消息/音频函数经 PLT→GOT 调用（llvm-objdump 证实存在
// `bl ...@plt` 调用点与 JUMP_SLOT 重定位），扫描内核 JMPREL 把对应槽改写为本钩子：
// - artc::Compositor::SetMessageLayered(const std::string&, bool) —— 消息层文本
// - artc::AudioChannels::Play(const std::string&, const std::string&, ...) —— 首参音频名
//   （voplay/se/bgm 共用通道，bgm 由 Java 侧按名字过滤）
// 不做代码段 inline hook（shadowhook 在部分模拟器环境报 errno=12 "Init linker mod
// failed"，GOT 补丁只动 RELRO 数据页，无此依赖）。官方 revision 内核（artemis::
// 命名空间）符号布局不同，暂不挂钩，行为与现状一致。所有步骤失败均静默降级（记录
// 日志），不影响游戏运行。

#include <dlfcn.h>
#include <android/log.h>
#include <jni.h>
#include <link.h>
#include <sys/mman.h>
#include <unistd.h>

#include <atomic>
#include <cstring>
#include <mutex>
#include <string>

#define TAG "ArtemisExtract"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

// libartemis-clean.so（artc 命名空间）导出符号
constexpr const char* kKernelLibBasename = "libartemis-clean.so";
constexpr const char* kSetMessageLayered =
    "_ZN4artc10Compositor17SetMessageLayeredERKNSt6__ndk112basic_stringIcNS1_11char_"
    "traitsIcEENS1_9allocatorIcEEEEb";
constexpr const char* kAudioPlay =
    "_ZN4artc13AudioChannels4PlayERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEEN"
    "S1_9allocatorIcEEEES9_biidb";

using SetMessageLayeredFn = void (*)(void* self, const std::string& text, bool flag);
using AudioPlayFn = void (*)(void* self, const std::string& a, const std::string& b,
                             bool flag1, int int1, int int2, double dbl, bool flag2);

SetMessageLayeredFn gOrigSetMessageLayered = nullptr;
AudioPlayFn gOrigAudioPlay = nullptr;
JavaVM* gJvm = nullptr;
jclass gBridgeClass = nullptr;
jmethodID gOnTextMethod = nullptr;
std::atomic<bool> gHookInstalled{false};
std::mutex gInstallMutex;

// NewStringUTF 对非法 modified-UTF-8 字节会直接 JNI abort；游戏文本编码不可控，
// 统一走 new String(byte[], "UTF-8")（非法序列替换为 U+FFFD，绝不崩）。
jstring toJString(JNIEnv* env, const char* text) {
    if (!env) return nullptr;
    std::string s = text != nullptr ? text : "";
    const jsize len = static_cast<jsize>(s.size());
    if (len == 0) return env->NewStringUTF("");
    jbyteArray arr = env->NewByteArray(len);
    if (arr == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte*>(s.data()));
    jstring result = nullptr;
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
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(arr);
    return result;
}

/** 上行提取事件到 ArtemisActivity.onArtemisExtract（引擎线程，内部 attach JVM）。 */
void emitText(const char* text, const char* voice) {
    JavaVM* jvm = gJvm;
    if (jvm == nullptr || gBridgeClass == nullptr || gOnTextMethod == nullptr) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) return;
        attached = true;
    }
    jstring jText = toJString(env, text);
    jstring jVoice = toJString(env, voice);
    jstring jEmpty = toJString(env, "");
    if (jText != nullptr && jVoice != nullptr && jEmpty != nullptr) {
        env->CallStaticVoidMethod(gBridgeClass, gOnTextMethod, jText, jVoice, jEmpty);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (jText != nullptr) env->DeleteLocalRef(jText);
    if (jVoice != nullptr) env->DeleteLocalRef(jVoice);
    if (jEmpty != nullptr) env->DeleteLocalRef(jEmpty);
    if (attached) jvm->DetachCurrentThread();
}

void hookedSetMessageLayered(void* self, const std::string& text, bool flag) {
    const SetMessageLayeredFn original = gOrigSetMessageLayered;
    if (original != nullptr) original(self, text, flag);
    // 只放行疑似对白的文本（含 CJK 假名/汉字），过滤内部标签（znotify 等）
    bool hasCjk = false;
    for (unsigned char c : text) {
        if (c >= 0xE0) { hasCjk = true; break; }  // UTF-8 三字节以上即 CJK 区间
    }
    if (!hasCjk || text.size() < 6) return;
    emitText(text.c_str(), "");
}

void hookedAudioPlay(void* self, const std::string& a, const std::string& b,
                     bool flag1, int int1, int int2, double dbl, bool flag2) {
    const AudioPlayFn original = gOrigAudioPlay;
    if (original != nullptr) original(self, a, b, flag1, int1, int2, dbl, flag2);
    // 取更像音频路径的参数：含 '/' 或 '.' 优先，其次更长者（纯数字是频道号）
    auto pathLike = [](const std::string& s) {
        return !s.empty() && (s.find('/') != std::string::npos ||
                              s.find('.') != std::string::npos);
    };
    const std::string& name = pathLike(a) ? a : pathLike(b) ? b
                              : a.size() >= b.size() ? a : b;
    if (!name.empty()) emitText("", name.c_str());
}

uintptr_t loadedAddress(uintptr_t base, ElfW(Addr) value) {
    const uintptr_t address = static_cast<uintptr_t>(value);
    return base != 0 && address < base ? base + address : address;
}

bool patchGotSlot(void** slot, void* replacement, void** original) {
    if (slot == nullptr || replacement == nullptr) return false;
    const long pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize <= 0) return false;
    const uintptr_t page = reinterpret_cast<uintptr_t>(slot)
            & ~(static_cast<uintptr_t>(pageSize) - 1);
    if (mprotect(reinterpret_cast<void*>(page), static_cast<size_t>(pageSize),
                 PROT_READ | PROT_WRITE) != 0) {
        LOGW("extract hook: mprotect GOT failed");
        return false;
    }
    void* previous = __atomic_load_n(slot, __ATOMIC_ACQUIRE);
    __atomic_store_n(slot, replacement, __ATOMIC_RELEASE);
    mprotect(reinterpret_cast<void*>(page), static_cast<size_t>(pageSize), PROT_READ);
    if (original != nullptr && *original == nullptr) *original = previous;
    return previous != nullptr;
}

struct GotHookRequest {
    const char* libraryBasename;
    const char* symbol;
    void* replacement;
    void* original = nullptr;
    int patched = 0;
};

int patchLoadedLibrary(struct dl_phdr_info* info, size_t, void* data) {
    auto* request = static_cast<GotHookRequest*>(data);
    if (info == nullptr || request == nullptr || request->libraryBasename == nullptr) return 0;
    const char* loaded = info->dlpi_name == nullptr ? "" : info->dlpi_name;
    const char* loadedBase = std::strrchr(loaded, '/');
    loadedBase = loadedBase == nullptr ? loaded : loadedBase + 1;
    if (*loaded == '\0' || std::strcmp(loadedBase, request->libraryBasename) != 0) return 0;

    const uintptr_t base = static_cast<uintptr_t>(info->dlpi_addr);
    ElfW(Dyn)* dynamic = nullptr;
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) {
            dynamic = reinterpret_cast<ElfW(Dyn)*>(base + info->dlpi_phdr[i].p_vaddr);
            break;
        }
    }
    if (dynamic == nullptr) return 1;

    ElfW(Sym)* symbols = nullptr;
    const char* strings = nullptr;
    ElfW(Rela)* relocations = nullptr;
    size_t relocationSize = 0;
    for (ElfW(Dyn)* item = dynamic; item->d_tag != DT_NULL; ++item) {
        switch (item->d_tag) {
            case DT_SYMTAB:
                symbols = reinterpret_cast<ElfW(Sym)*>(loadedAddress(base, item->d_un.d_ptr));
                break;
            case DT_STRTAB:
                strings = reinterpret_cast<const char*>(loadedAddress(base, item->d_un.d_ptr));
                break;
            case DT_JMPREL:
                relocations = reinterpret_cast<ElfW(Rela)*>(loadedAddress(base, item->d_un.d_ptr));
                break;
            case DT_PLTRELSZ:
                relocationSize = static_cast<size_t>(item->d_un.d_val);
                break;
            default:
                break;
        }
    }
    if (symbols == nullptr || strings == nullptr || relocations == nullptr) return 1;

    const size_t count = relocationSize / sizeof(ElfW(Rela));
    for (size_t i = 0; i < count; ++i) {
        const size_t symbolIndex = static_cast<size_t>(ELF64_R_SYM(relocations[i].r_info));
        const char* name = strings + symbols[symbolIndex].st_name;
        if (name != nullptr && std::strcmp(name, request->symbol) == 0) {
            auto** slot = reinterpret_cast<void**>(base + relocations[i].r_offset);
            if (patchGotSlot(slot, request->replacement, &request->original)) request->patched++;
        }
    }
    return 1;
}

/** 补丁内核自身 GOT 的 symbol 槽；返回是否成功且拿到原函数。 */
bool hookKernelGot(const char* symbol, void* replacement, void** original) {
    GotHookRequest request{kKernelLibBasename, symbol, replacement};
    dl_iterate_phdr(patchLoadedLibrary, &request);
    if (original != nullptr) *original = request.original;
    LOGI("extract hook: GOT %s patched=%d original=%p", symbol, request.patched,
         request.original);
    return request.patched > 0 && request.original != nullptr;
}

void installHooksLocked() {
    if (hookKernelGot(kSetMessageLayered,
                      reinterpret_cast<void*>(&hookedSetMessageLayered),
                      reinterpret_cast<void**>(&gOrigSetMessageLayered))) {
        LOGI("extract hook: SetMessageLayered armed (text)");
    } else {
        LOGW("extract hook: SetMessageLayered GOT hook failed (non-clean kernel?)");
    }
    if (hookKernelGot(kAudioPlay,
                      reinterpret_cast<void*>(&hookedAudioPlay),
                      reinterpret_cast<void**>(&gOrigAudioPlay))) {
        LOGI("extract hook: AudioChannels::Play armed (voice)");
    } else {
        LOGW("extract hook: AudioChannels::Play GOT hook failed (non-clean kernel?)");
    }
    gHookInstalled.store(true, std::memory_order_relaxed);
}

/** 缓存上行 JNI 引用（仅 audio_bridge 编译目标需要；Java env 可用）。 */
bool cacheJniRefs(JNIEnv* env) {
    if (gJvm == nullptr) env->GetJavaVM(&gJvm);
    if (gBridgeClass == nullptr) {
        jclass bridge = env->FindClass("com/ies_net/artemis/ArtemisActivity");
        if (bridge == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            LOGW("extract hook: ArtemisActivity class unresolved");
            return false;
        }
        gBridgeClass = static_cast<jclass>(env->NewGlobalRef(bridge));
        env->DeleteLocalRef(bridge);
    }
    if (gOnTextMethod == nullptr) {
        gOnTextMethod = env->GetStaticMethodID(
                gBridgeClass, "onArtemisExtract",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        if (gOnTextMethod == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            LOGW("extract hook: onArtemisExtract unresolved");
            return false;
        }
    }
    return true;
}

}  // namespace

#ifdef ARTEMIS_HOOK_LOADER

/** 早期安装入口：ANativeActivity_onCreate 阶段（内核 dlopen 后、引擎启动前）调用。 */
extern "C" void artemis_install_extract_hook(void* kernelHandle, JavaVM* jvm) {
    (void) kernelHandle;
    if (jvm == nullptr) return;
    std::lock_guard<std::mutex> lock(gInstallMutex);
    if (gHookInstalled.load(std::memory_order_relaxed)) return;
    gJvm = jvm;
    LOGI("extract hook: installing (early, GOT patch)");
    installHooksLocked();
}

#else  // !ARTEMIS_HOOK_LOADER —— audio_bridge 编译目标：JNI 入口（app 类加载器可绑定）

extern "C" JNIEXPORT void JNICALL
Java_com_ies_1net_artemis_ArtemisActivity_nativeSetExtractCacheDir(JNIEnv*, jobject) {
    // 缓存目录注册：带桥内核才有的导出；本实现不发号施令，语音副本暂不落盘
    //（面板读游戏目录 voice/ 兜底，见 ArtemisExtractFacade.readVoiceBytesByName）。
    LOGI("nativeSetExtractCacheDir (hook path, no-op)");
}

/** 延迟安装：早期未装时由 Java 侧重试（含上行 JNI 引用缓存）。 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ies_1net_artemis_ArtemisActivity_nativeInstallExtractHook(JNIEnv* env, jobject) {
    if (gHookInstalled.load(std::memory_order_relaxed)) return JNI_TRUE;
    if (env == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(gInstallMutex);
    if (!cacheJniRefs(env)) return JNI_FALSE;
    LOGI("extract hook: installing (late, GOT patch)");
    installHooksLocked();
    return JNI_TRUE;
}

#endif  // ARTEMIS_HOOK_LOADER
