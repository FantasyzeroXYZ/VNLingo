// artemis_extract_hook.cpp
// Artemis 提取桥的原生侧补齐。
//
// 背景：提取功能线的 Java 侧（ArtemisExtractBridge / ArtemisExtractFacade / 面板）
// 依赖内核 extract_bridge 发射器回调 ArtemisActivity.onArtemisExtract，但随包的
// Artemis 内核（含自研 clean 内核 libartemis-clean.so）二进制里均未包含该发射器
// （外部内核源码仓的带桥构建未随本仓分发），导致游戏内剧情文本框始终无文本/语音。
//
// 方案：沿用本 loader 已验证的 dlsym + 运行时补丁思路，改用 shadowhook
// （jniLibs 内置 libshadowhook.so，C API 经 dlsym 取用）对 clean 内核导出的
// 消息层/音频层函数做 entry inline hook，在原函数执行后把文本/语音名上行到 Java：
// - artc::Compositor::SetMessageLayered(const std::string&, bool) —— 消息层文本
// - artc::AudioChannels::Play(const std::string&, const std::string&, bool, int, int, double, bool)
//   —— 首参为音频名（voplay 语音/se/bgm 共用通道，bgm 由 Java 侧按名字过滤）
//
// 官方 revision 内核（artemis:: 命名空间，luabind 类型）符号布局不同，暂不挂钩，
// 行为与现状一致（无提取）。所有步骤失败均静默降级（记录日志），不影响游戏运行。

#include <dlfcn.h>
#include <android/log.h>
#include <jni.h>

#include <cstdint>
#include <string>

#define TAG "ArtemisExtract"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

JavaVM* g_jvm = nullptr;

// libartemis-clean.so（artc 命名空间）导出符号
constexpr const char* kSetMessageLayered =
    "_ZN4artc10Compositor17SetMessageLayeredERKNSt6__ndk112basic_stringIcNS1_11char_"
    "traitsIcEENS1_9allocatorIcEEEEb";
constexpr const char* kAudioPlay =
    "_ZN4artc13AudioChannels4PlayERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEEN"
    "S1_9allocatorIcEEEES9_biidb";

// shadowhook C API（1.1.x；经 dlsym 取用，Java 包装是编译桩不可用）
constexpr const char* kShadowhookLib = "libshadowhook.so";
constexpr const char* kShadowhookInit = "shadowhook_init";
constexpr const char* kShadowhookHookFuncAddr = "shadowhook_hook_func_addr";

// shadowhook_init_info_t（1.1.x）：{int version; int abi_version; bool debuggable; logging_cb}
// 仅 abi_version 被严格校验（1.x 恒为 1）；version 仅登记，callback 允许 NULL。
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

using SetMessageLayeredFn = void (*)(void* self, const std::string& text, bool flag);
using AudioPlayFn = void (*)(void* self, const std::string& a, const std::string& b,
                             bool flag1, int int1, int int2, double dbl, bool flag2);

SetMessageLayeredFn g_origSetMessageLayered = nullptr;
AudioPlayFn g_origAudioPlay = nullptr;

// NewStringUTF 对非法 modified-UTF-8 字节会直接 JNI abort；游戏文本编码不可控，
// 统一走 new String(byte[], "UTF-8")（非法序列替换为 U+FFFD，绝不崩）。
jstring toJString(JNIEnv* env, const std::string& text) {
    if (!env) return nullptr;
    const jsize len = static_cast<jsize>(text.size());
    if (len == 0) return env->NewStringUTF("");
    jbyteArray arr = env->NewByteArray(len);
    if (arr == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte*>(text.data()));
    jclass stringClass = env->FindClass("java/lang/String");
    jstring result = nullptr;
    if (stringClass != nullptr) {
        jmethodID ctor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
        if (ctor != nullptr) {
            jstring charset = env->NewStringUTF("UTF-8");
            jobject obj = env->NewObject(stringClass, ctor, arr, charset);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            } else {
                result = static_cast<jstring>(obj);
            }
            if (charset != nullptr) env->DeleteLocalRef(charset);
        }
        env->DeleteLocalRef(stringClass);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(arr);
    return result;
}

/** 上行提取事件到 ArtemisActivity.onArtemisExtract（引擎线程，内部 attach JVM）。 */
void emitExtract(const char* text, const char* voice) {
    JavaVM* jvm = g_jvm;
    if (jvm == nullptr) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) return;
        attached = true;
    }
    jclass cls = env->FindClass("com/ies_net/artemis/ArtemisActivity");
    if (cls == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (attached) jvm->DetachCurrentThread();
        return;
    }
    jmethodID method = env->GetStaticMethodID(
            cls, "onArtemisExtract", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    if (method != nullptr) {
        jstring jText = toJString(env, text != nullptr ? text : "");
        jstring jVoice = toJString(env, voice != nullptr ? voice : "");
        jstring jEmpty = toJString(env, "");
        if (jText != nullptr && jVoice != nullptr && jEmpty != nullptr) {
            env->CallStaticVoidMethod(cls, method, jText, jVoice, jEmpty);
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (jText != nullptr) env->DeleteLocalRef(jText);
        if (jVoice != nullptr) env->DeleteLocalRef(jVoice);
        if (jEmpty != nullptr) env->DeleteLocalRef(jEmpty);
    } else if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    env->DeleteLocalRef(cls);
    if (attached) jvm->DetachCurrentThread();
}

void hookedSetMessageLayered(void* self, const std::string& text, bool flag) {
    const SetMessageLayeredFn original = g_origSetMessageLayered;
    if (original != nullptr) original(self, text, flag);
    if (!text.empty()) emitExtract(text.c_str(), "");
}

void hookedAudioPlay(void* self, const std::string& a, const std::string& b,
                     bool flag1, int int1, int int2, double dbl, bool flag2) {
    const AudioPlayFn original = g_origAudioPlay;
    if (original != nullptr) original(self, a, b, flag1, int1, int2, dbl, flag2);
    // 首参为音频名；为空时退用次参（不同 revision 参数序可能互换）
    const std::string& name = !a.empty() ? a : b;
    if (!name.empty()) emitExtract("", name.c_str());
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_ies_1net_artemis_ArtemisActivity_nativeSetExtractCacheDir(JNIEnv*, jobject) {
    // 缓存目录注册：带桥内核才有的导出；本实现不发号施令，语音副本暂不落盘
    //（面板读游戏目录 voice/ 兜底，见 ArtemisExtractFacade.readVoiceBytesByName）。
    LOGI("nativeSetExtractCacheDir (hook path, no-op)");
}

/**
 * 安装提取钩子。在 artemis_loader 的 dlopen 之后、ANativeActivity_onCreate 转发之前调用
 * （此刻内核尚未运行任何脚本，inline hook 无并发窗口）。
 * @param kernelHandle artemis_loader dlopen 内核得到的句柄
 * @param jvm 宿主 Activity 的 JavaVM
 */
extern "C" void artemis_install_extract_hook(void* kernelHandle, JavaVM* jvm) {
    if (kernelHandle == nullptr || jvm == nullptr) return;
    g_jvm = jvm;

    void* shadowhook = dlopen(kShadowhookLib, RTLD_NOW | RTLD_GLOBAL);
    if (shadowhook == nullptr) {
        LOGW("extract hook: %s dlopen failed: %s", kShadowhookLib, dlerror());
        return;
    }
    auto init = reinterpret_cast<ShadowhookInitFn>(dlsym(shadowhook, kShadowhookInit));
    auto hookAddr = reinterpret_cast<HookFuncAddrFn>(dlsym(shadowhook, kShadowhookHookFuncAddr));
    if (init == nullptr || hookAddr == nullptr) {
        LOGW("extract hook: shadowhook symbols missing (%s/%s)",
             kShadowhookInit, kShadowhookHookFuncAddr);
        return;
    }
    ShadowhookInitInfo info{0x01010100, 1, false, nullptr};
    void* handle = init(&info);
    if (handle == nullptr) {
        LOGW("extract hook: shadowhook_init failed (abi mismatch?)");
        return;
    }

    void* setMessageLayered = dlsym(kernelHandle, kSetMessageLayered);
    if (setMessageLayered != nullptr) {
        void* orig = nullptr;
        if (hookAddr(setMessageLayered,
                     reinterpret_cast<void*>(&hookedSetMessageLayered), &orig) != nullptr) {
            g_origSetMessageLayered = reinterpret_cast<SetMessageLayeredFn>(orig);
            LOGI("extract hook: SetMessageLayered hooked");
        } else {
            LOGW("extract hook: SetMessageLayered hook failed");
        }
    } else {
        LOGI("extract hook: SetMessageLayered not found (non-clean kernel?)");
    }

    void* audioPlay = dlsym(kernelHandle, kAudioPlay);
    if (audioPlay != nullptr) {
        void* orig = nullptr;
        if (hookAddr(audioPlay, reinterpret_cast<void*>(&hookedAudioPlay), &orig) != nullptr) {
            g_origAudioPlay = reinterpret_cast<AudioPlayFn>(orig);
            LOGI("extract hook: AudioChannels::Play hooked");
        } else {
            LOGW("extract hook: AudioChannels::Play hook failed");
        }
    } else {
        LOGI("extract hook: AudioChannels::Play not found (non-clean kernel?)");
    }
}
