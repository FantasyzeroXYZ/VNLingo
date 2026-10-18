package com.akira.tyranoemu.remote;

import com.core.nativeplugin.NativeLibraryLoader;

/**
 * krkr2-main 带钩内核（libkrkr2.so）宿主：三个原版 Kirikiroid2 内核
 * （libgame.so/134/126）保持不变，本宿主按设置独立加载追加内核。
 * 对白/语音提取经 KR2Activity.setExtractListener → KrkrExtractBridge 上行；
 * isHookedKernel 探针在基类 onLoadNativeLibraries 里关闭运行时 FT/Label 钩子。
 */
public final class KirikiroidKrkr2 extends KirikiroidLauncherBaseActivity {
    private static final String TAG = "KirikiroidKrkr2";

    @Override
    public void onLoadNativeLibraries() {
        android.util.Log.i(TAG, "step1: loading hooked kernel libs");
        String gameLibrary = NativeLibraryLoader.loadKrkr2Hooked(this);
        android.util.Log.i(TAG, "step2: hooked kernel libs loaded so=" + gameLibrary);
        if (gameLibrary == null) {
            // 与 Kirikiroid139 契约一致：提前抛出让失败原因直接出现在 logcat 顶部。
            throw new UnsatisfiedLinkError("Kirikiroid2 plugin missing or invalid for libkrkr2.so");
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
        return "libkrkr2.so";
    }
}
