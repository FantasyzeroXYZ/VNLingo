package com.akira.tyranoemu.remote;


import com.core.nativeplugin.NativeLibraryLoader;

public final class Kirikiroid139 extends KirikiroidLauncherBaseActivity {
    private static final String TAG = "Kirikiroid139";

    @Override
    public void onLoadNativeLibraries() {
        android.util.Log.i(TAG, "step1: loading plugin libs");
        String gameLibrary = NativeLibraryLoader.loadKirikiroid139(this);
        android.util.Log.i(TAG, "step2: plugin libs loaded so=" + gameLibrary);
        if (gameLibrary == null) {
            // 与 KR2Activity 契约一致：静默 return 会让 Cocos 宿主带着缺失的 libgame.so
            // 继续启动，最终在 GL 线程以难以诊断的 UnsatisfiedLinkError 崩溃。
            // 提前抛出让失败原因直接出现在 logcat 顶部（插件缺失应在引擎页预检拦截）。
            throw new UnsatisfiedLinkError("Kirikiroid2 plugin missing or invalid for libgame.so");
        }
        setResolvedGameLibrary(gameLibrary);
        android.util.Log.i(TAG, "step3: loading krkr_bridge_v2");
        System.loadLibrary("krkr_bridge_v2");
        android.util.Log.i(TAG, "step4: krkr_bridge_v2 loaded");
        super.onLoadNativeLibraries();
        android.util.Log.i(TAG, "step5: super.onLoadNativeLibraries done");
    }

    @Override
    public String soName() {
        return "libgame.so";
    }
}
