package com.akira.tyranoemu.remote;

import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.content.Intent;
import android.view.KeyEvent;

import com.core.engine.DoubleBackExit;
import com.core.engine.EnginePrefs;
import com.core.engine.LaunchContract;

public abstract class ArtemisLauncherBaseActivity extends com.ies_net.artemis.ArtemisActivity {
    private static final long EARLY_EXIT_WINDOW_MS = 3_000L;
    private static final int FALLBACK_STAGE_V4_DIRECT = -1;
    private static final String KEY_ARTEMIS_ENGINE_PREFIX = "artemis_engine.";
    private static final String KEY_ARTEMIS_ENGINE_SUCCESS_PREFIX = "artemis_engine_success.";
    private long createdAtElapsed;
    private boolean userRequestedFinish;
    /** 存档/提取面板门面（游戏路径存在时安装，见 installPanels）。 */
    private com.core.ons.ArtemisExtractFacade facade;
    private boolean panelsInstalled;
    /** 提取面板窗口内容（面板 GONE 时窗口塌缩，无残留遮挡）。 */
    private android.view.View extractPanelView;
    /** 加载 revision-specific 的 Artemis native 库（如 libartemis.so），onCreate 一次性调用。 */
    public abstract void loadEngineLibrary();

    @Override
    public java.io.File getExternalFilesDir(String type) {
        String path = getIntent() == null ? null : getIntent().getStringExtra(LaunchContract.PATH);
        if (path == null || path.isEmpty()) {
            java.io.File fallback = super.getExternalFilesDir(type);
            Log.i("YukiArtemis", "getExternalFilesDir type=" + type + " fallback=" + (fallback == null ? "null" : fallback.getAbsolutePath()));
            return fallback;
        }
        if (path.startsWith("file://")) path = path.substring("file://".length());
        java.io.File out = new java.io.File(path);
        Log.i("YukiArtemis", "getExternalFilesDir type=" + type + " path=" + out.getAbsolutePath() + " scoped=" + getIntent().getBooleanExtra(LaunchContract.SCOPED_SAVE_DIR, false));
        return out;
    }

    @Override
    public final void onCreate(Bundle bundle) {
        Log.i("YukiArtemis", "onCreate enter pid=" + android.os.Process.myPid());
        // 提取语音缓存目录须在 super.onCreate 前提供（内核 ANativeActivity_onCreate
        // 阶段会反向经 ArtemisActivity.getExtractCacheDir 拉取）
        String extractPath = getIntent() == null ? null : getIntent().getStringExtra(LaunchContract.PATH);
        if (extractPath != null && !extractPath.trim().isEmpty()) {
            if (extractPath.startsWith("file://")) extractPath = extractPath.substring("file://".length());
            com.ies_net.artemis.ArtemisActivity.setExtractCacheDirOverride(
                    extractPath + "/artemis_extract/voice/");
        }
        super.onCreate(bundle);
        createdAtElapsed = SystemClock.elapsedRealtime();
        Log.i("YukiArtemis", "onCreate path=" + (getIntent() == null ? null : getIntent().getStringExtra(LaunchContract.PATH)) + " scoped=" + (getIntent() != null && getIntent().getBooleanExtra(LaunchContract.SCOPED_SAVE_DIR, false)) + " saveName=" + (getIntent() == null ? null : getIntent().getStringExtra(LaunchContract.SCOPED_SAVE_NAME)));
        loadEngineLibrary();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            maybeInstallPanels();
        }
    }

    /**
     * NativeActivity 的内容根在 onCreate 阶段不跑布局（后加视图 bounds 全 0）；
     * 且游戏 onResume 才切横屏，刚转屏时 decor 尺寸/insets 还是旧值（悬浮窗
     * 会锚错位置）——等 decor 宽与当前屏宽一致（旋转稳定）再安装，未稳定则重试。
     */
    private void maybeInstallPanels() {
        if (panelsInstalled) return;
        // 竖屏转横屏未稳定时 decor 高 ≥ 宽；稳定（横屏）后宽 > 高即安装。
        // 注意 dm.widthPixels 是应用可见区（2274），与 decor 全屏宽（2400）永不相等。
        int decorWidth = getWindow().getDecorView().getWidth();
        int decorHeight = getWindow().getDecorView().getHeight();
        Log.i("YukiArtemis", "maybeInstallPanels decor=" + decorWidth + "x" + decorHeight);
        if (decorWidth > decorHeight) {
            panelsInstalled = true;
            installPanels();
            return;
        }
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(this::maybeInstallPanels, 500);
    }

    /**
     * 覆盖层面板安装（NativeActivity 窗口 surface 被游戏独占，Java 视图不参与
     * 合成——addContentView 的视图 bounds 恒为 0），全部走 WindowManager 悬浮窗：
     * 「存」球小窗 + 全屏覆盖窗（存档面板 / 提取面板与右缘按键组）。
     * 覆盖窗在无可见面板时置 FLAG_NOT_TOUCHABLE，触摸穿透给游戏。
     * 提取数据源待内核文本钩子（见 ArtemisExtractFacade 注释），先接入框架。
     */
    private void installPanels() {
        try {
            String path = getIntent() == null ? null : getIntent().getStringExtra(LaunchContract.PATH);
            if (path == null || path.trim().isEmpty()) return;
            if (path.startsWith("file://")) path = path.substring("file://".length());
            facade = new com.core.ons.ArtemisExtractFacade(this, path);

            com.core.ons.OnsExtractPanel extractPanel = new com.core.ons.OnsExtractPanel(facade);
            // 右缘按键组走独立小窗；面板自身承载为底部独立窗口（可触摸）：
            // 旧方案（全屏覆盖窗随面板可见性切换触摸态）在面板开启时会吃掉全部
            // 游戏触摸——触摸推进失效的根因。面板窗口只占面板自身区域，
            // 其余触摸透传游戏；面板 GONE 时窗口塌缩为 0。
            extractPanel.setSideButtonsWindowMode(true);
            android.view.View panelView = extractPanel.installDetached(null, null);
            installEngineLeftButtons();

            android.view.WindowManager.LayoutParams plp = new android.view.WindowManager.LayoutParams(
                    extractPanel.preferredWindowWidthPx(),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    android.graphics.PixelFormat.TRANSLUCENT);
            plp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
            plp.y = navBarBottomInset();
            getWindowManager().addView(panelView, plp);
            extractPanelView = panelView;
            Log.i("YukiArtemis", "extract panel installed (artemis, detached window)");
        } catch (Throwable t) {
            Log.w("YukiArtemis", "installPanels failed", t);
        }
    }

    /** 导航条高度（横屏底部 inset），面板窗口避开。 */
    private int navBarBottomInset() {
        try {
            android.view.WindowInsets ins = getWindow().getDecorView().getRootWindowInsets();
            if (ins != null) return ins.getInsets(android.view.WindowInsets.Type.systemBars()).bottom;
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 引擎键注入：经内核 EmulateKeyEvent（keycode 映射同 ArtemisActivity.dispatchKeyEvent）。 */
    private void artKey(int code, int action) {
        try {
            EmulateKeyEvent(code, action);
        } catch (Throwable t) {
            Log.w("YukiArtemis", "artKey failed code=" + code, t);
        }
    }

    /**
     * 左缘引擎适配按键（Artemis）：NEXT=回车推进、SKIP=按住 Ctrl、
     * 方向键=选肢/回览。经独立小窗安装（NativeActivity 宿主视图不参与合成）。
     */
    private void installEngineLeftButtons() {
        try {
            java.util.List<com.core.engine.EngineLeftButtons.ButtonSpec> buttons =
                    new java.util.ArrayList<>();
            buttons.add(new com.core.engine.EngineLeftButtons.ButtonSpec("NEXT",
                    () -> artKey(13, 2)));
            buttons.add(new com.core.engine.EngineLeftButtons.ButtonSpec("SKIP",
                    () -> {
                        artKey(140, 1);
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(() -> artKey(140, 0), 900);
                    }));
            buttons.add(new com.core.engine.EngineLeftButtons.ButtonSpec("▲",
                    () -> artKey(38, 2)));
            buttons.add(new com.core.engine.EngineLeftButtons.ButtonSpec("▼",
                    () -> artKey(40, 2)));
            buttons.add(new com.core.engine.EngineLeftButtons.ButtonSpec("◀",
                    () -> artKey(37, 2)));
            buttons.add(new com.core.engine.EngineLeftButtons.ButtonSpec("▶",
                    () -> artKey(39, 2)));
            com.core.engine.EngineLeftButtons.installAsWindow(
                    this, "artemis_left", buttons);
            Log.i("YukiArtemis", "engine left buttons installed (artemis)");
        } catch (Throwable t) {
            Log.w("YukiArtemis", "install engine left buttons failed", t);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (facade != null) {
            facade.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onBackPressed() {
        DoubleBackExit.handleBack(this, this::exitFromBack);
    }

    private void exitFromBack() {
        userRequestedFinish = true;
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        DoubleBackExit.clear(this);
        // 兼容回退改为「先按动态候选链/旧 stage 拉起下一版本（独立进程），未回退时再记录成功版本」：
        // 兼容版本在各自独立进程（如 :artemis.compat / :artemis.compat.v2）启动，当前进程
        // （无论正常退出还是早退回退）都直接终结，避免引擎 native 进程级全局状态
        // （android_app/音频/GL/dlopen lib）被二次初始化污染——同进程二次 init 挂起黑屏
        // 是部分设备黑屏/闪退的根因，与 KRKR 退出即杀进程同策略。
        boolean retryStarted = maybeRetryWithCompatibleArtemis();
        if (!retryStarted) recordSuccessfulArtemisVersionIfNeeded();
        Log.i("YukiArtemis", "onDestroy before super retry=" + retryStarted + " pid=" + android.os.Process.myPid());
        super.onDestroy();
        Log.i("YukiArtemis", "onDestroy killing pid=" + android.os.Process.myPid());
        try {
            android.os.Process.killProcess(android.os.Process.myPid());
        } catch (Throwable ignored) {
            // 进程终结失败可安全忽略：系统会回收进程，下次启动仍为全新进程
        }
    }

    /**
     * Artemis titles target several mutually incompatible native revisions.  A bad
     * revision returns to the launcher almost immediately without a Java exception.
     * Retry only that short startup failure, and never override a user-selected
     * revision or a normal, longer-running game exit.
     *
     * @return true 表示已启动兼容回退 Activity（进程不得终结）；false 表示正常退出
     */
    private boolean maybeRetryWithCompatibleArtemis() {
        Intent source = getIntent();
        if (source == null || userRequestedFinish
                || !source.getBooleanExtra(LaunchContract.ARTEMIS_AUTO_FALLBACK, false)
                || SystemClock.elapsedRealtime() - createdAtElapsed > EARLY_EXIT_WINDOW_MS) return false;
        boolean dynamicRetried = maybeRetryWithDynamicArtemisPlan(source);
        if (dynamicRetried) return true;
        int stage = source.getIntExtra(LaunchContract.ARTEMIS_FALLBACK_STAGE, 0);
        String nextPackage = stage == FALLBACK_STAGE_V4_DIRECT ? "internal.artemis"
                : stage == 0 ? "internal.artemis.compat"
                : stage == 1 ? "internal.artemis.compat.v2"
                : stage == 2 ? "internal.artemis.v4"
                : stage == 3 ? "internal.artemis.v5"
                : stage == 4 ? "internal.artemis.v6"
                : null;
        String path = source.getStringExtra(LaunchContract.PATH);
        if (nextPackage == null || path == null || path.trim().isEmpty()) return false;

        Intent retry = new Intent(this,
                stage == FALLBACK_STAGE_V4_DIRECT ? com.akira.tyranoemu.remote.ArtemisActivityV1.class
                        : stage == 0 ? com.akira.tyranoemu.remote.ArtemisActivityV2.class
                        : stage == 1 ? com.akira.tyranoemu.remote.ArtemisActivityV3.class
                        : stage == 2 ? com.akira.tyranoemu.remote.ArtemisActivityV4.class
                        : stage == 3 ? com.akira.tyranoemu.remote.ArtemisActivityV5.class
                        : com.akira.tyranoemu.remote.ArtemisActivityV6.class);
        retry.putExtras(source);
        retry.putExtra(LaunchContract.ARTEMIS_FALLBACK_STAGE, stage == FALLBACK_STAGE_V4_DIRECT ? 0 : stage + 1);
        // retry 到下一 revision 时，bootstrap loader 需加载对应的插件库名。
        retry.putExtra(LaunchContract.ENGINE_LIB_NAME,
                stage == FALLBACK_STAGE_V4_DIRECT ? "artemis"
                        : stage == 0 ? "artemis-compatible"
                        : stage == 1 ? "artemis-compatible-v2"
                        : stage == 2 ? "artemis-v4"
                        : stage == 3 ? "artemis-v5"
                        : "artemis-v6");
        retry.putExtra(LaunchContract.ARTEMIS_CURRENT_VERSION,
                stage == FALLBACK_STAGE_V4_DIRECT ? "1"
                        : stage == 0 ? "2"
                        : stage == 1 ? "3"
                        : stage == 2 ? "4"
                        : stage == 3 ? "5"
                        : "6");
        retry.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Log.w("YukiArtemis", "Artemis exited during startup; retrying with " + nextPackage + " path=" + path);
        try {
            startActivity(retry);
        } catch (Throwable t) {
            Log.e("YukiArtemis", "Artemis compatibility retry failed", t);
            return false;
        }
        return true;
    }

    private boolean maybeRetryWithDynamicArtemisPlan(Intent source) {
        String chainText = source.getStringExtra(LaunchContract.ARTEMIS_FALLBACK_VERSIONS);
        int currentIndex = source.getIntExtra(LaunchContract.ARTEMIS_FALLBACK_INDEX, -1);
        String path = source.getStringExtra(LaunchContract.PATH);
        if (chainText == null || chainText.trim().isEmpty() || currentIndex < 0
                || path == null || path.trim().isEmpty()) return false;
        String[] rawVersions = chainText.split(",");
        java.util.ArrayList<String> versions = new java.util.ArrayList<>();
        for (String raw : rawVersions) {
            String version = normalizeArtemisVersion(raw);
            if (version != null && !versions.contains(version)) versions.add(version);
        }
        int nextIndex = currentIndex + 1;
        if (nextIndex >= versions.size()) return false;
        String nextVersion = versions.get(nextIndex);
        Class<?> activity = activityClassForVersion(nextVersion);
        String engineLibName = engineLibNameForVersion(nextVersion);
        if (activity == null || engineLibName == null) return false;

        Intent retry = new Intent(this, activity);
        retry.putExtras(source);
        retry.putExtra(LaunchContract.ARTEMIS_FALLBACK_INDEX, nextIndex);
        retry.putExtra(LaunchContract.ARTEMIS_CURRENT_VERSION, nextVersion);
        retry.putExtra(LaunchContract.ENGINE_LIB_NAME, engineLibName);
        retry.putExtra(LaunchContract.ARTEMIS_FALLBACK_STAGE, fallbackStageForVersion(nextVersion));
        retry.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Log.w("YukiArtemis", "Artemis exited during startup; retrying dynamic version=" + nextVersion
                + " lib=" + engineLibName + " index=" + nextIndex + "/" + versions.size()
                + " path=" + path);
        try {
            startActivity(retry);
        } catch (Throwable t) {
            Log.e("YukiArtemis", "Artemis dynamic compatibility retry failed", t);
            return false;
        }
        return true;
    }

    private void recordSuccessfulArtemisVersionIfNeeded() {
        Intent source = getIntent();
        if (source == null || !source.getBooleanExtra(LaunchContract.ARTEMIS_AUTO_FALLBACK, false)) return;
        long aliveMs = SystemClock.elapsedRealtime() - createdAtElapsed;
        if (!userRequestedFinish && aliveMs <= EARLY_EXIT_WINDOW_MS) return;
        String path = source.getStringExtra(LaunchContract.PATH);
        String version = normalizeArtemisVersion(source.getStringExtra(LaunchContract.ARTEMIS_CURRENT_VERSION));
        if (path == null || path.trim().isEmpty() || version == null) return;
        String keySuffix = Integer.toHexString(path.hashCode());
        getSharedPreferences(EnginePrefs.APP_PREFS, MODE_PRIVATE).edit()
                .putString(KEY_ARTEMIS_ENGINE_SUCCESS_PREFIX + keySuffix, version)
                .putString(KEY_ARTEMIS_ENGINE_PREFIX + keySuffix, version)
                .apply();
        Log.i("YukiArtemis", "Artemis recorded successful version=" + version + " aliveMs=" + aliveMs + " path=" + path);
    }

    private static String normalizeArtemisVersion(String value) {
        if (value == null) return null;
        String v = value.trim();
        if ("1".equals(v) || "internal.artemis".equals(v)) return "1";
        if ("2".equals(v) || "internal.artemis.compat".equals(v)) return "2";
        if ("3".equals(v) || "internal.artemis.compat.v2".equals(v)) return "3";
        if ("4".equals(v) || "internal.artemis.v4".equals(v)) return "4";
        if ("5".equals(v) || "internal.artemis.v5".equals(v)) return "5";
        if ("6".equals(v) || "internal.artemis.v6".equals(v)) return "6";
        return null;
    }

    private static Class<?> activityClassForVersion(String version) {
        if ("2".equals(version)) return com.akira.tyranoemu.remote.ArtemisActivityV2.class;
        if ("3".equals(version)) return com.akira.tyranoemu.remote.ArtemisActivityV3.class;
        if ("4".equals(version)) return com.akira.tyranoemu.remote.ArtemisActivityV4.class;
        if ("5".equals(version)) return com.akira.tyranoemu.remote.ArtemisActivityV5.class;
        if ("6".equals(version)) return com.akira.tyranoemu.remote.ArtemisActivityV6.class;
        if ("1".equals(version)) return com.akira.tyranoemu.remote.ArtemisActivityV1.class;
        return null;
    }

    private static String engineLibNameForVersion(String version) {
        if ("2".equals(version)) return "artemis-compatible";
        if ("3".equals(version)) return "artemis-compatible-v2";
        if ("4".equals(version)) return "artemis-v4";
        if ("5".equals(version)) return "artemis-v5";
        if ("6".equals(version)) return "artemis-v6";
        if ("1".equals(version)) return "artemis";
        return null;
    }

    private static int fallbackStageForVersion(String version) {
        if ("2".equals(version)) return 1;
        if ("3".equals(version)) return 2;
        if ("4".equals(version)) return 3;
        if ("5".equals(version)) return 4;
        if ("6".equals(version)) return 5;
        return 0;
    }
}
