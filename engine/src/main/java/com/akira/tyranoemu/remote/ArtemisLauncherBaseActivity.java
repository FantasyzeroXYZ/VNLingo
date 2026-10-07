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
    /** 统一虚拟鼠标（手柄移动光标 + A 键经内核 InjectHostTouch 合成点击）。 */
    private com.core.engine.EngineVirtualMouse virtualMouse;
    private boolean virtualMouseMode;
    private static final String PREF_ARTEMIS_MOUSE_MODE = "artemis_virtual_mouse";
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
            installVirtualMouseWindow();
            android.view.View panelView = extractPanel.installDetached(
                    this::toggleVirtualMouseMode, this::isVirtualMouseMode);
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

    /**
     * 统一虚拟鼠标（Artemis）：光标承载为独立纯绘制小窗（NOT_TOUCHABLE，
     * 触摸全部穿透给游戏窗）；点击经内核 InjectHostTouch JNI（pluginVersion 32
     * 起导出）与物理触摸同管线入队——NativeActivity 的触摸 InputQueue 为
     * native 独占，这是应用层唯一有效点击路径（官方内核无该符号，调用处
     * 捕获 UnsatisfiedLinkError 降级为无点击）。光标悬停面板窗口时点面板控件。
     *
     * 按键：NativeActivity 的按键不经 Activity.dispatchKeyEvent（实测 D-pad/A
     * 到不了 Java），鼠标模式把光标窗口设为可聚焦接管按键焦点——D-pad/A 归
     * 光标，其余键转发游戏映射链；模式关时窗口 NOT_FOCUSABLE，按键归还游戏。
     */
    private void installVirtualMouseWindow() {
        try {
            virtualMouseMode = getSharedPreferences(PREF_ARTEMIS_MOUSE_MODE, MODE_PRIVATE)
                    .getBoolean(PREF_ARTEMIS_MOUSE_MODE, false);
            mouseKeyCatcher = new android.widget.FrameLayout(this) {
                @Override
                public boolean dispatchKeyEvent(android.view.KeyEvent event) {
                    return routeMouseKey(event);
                }
            };
            android.widget.FrameLayout overlay = mouseKeyCatcher;
            cursorWindowLp = new android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    android.graphics.PixelFormat.TRANSLUCENT);
            // TYPE_APPLICATION 必须显式 gravity（缺省 x/y 不按左上锚定，见 MEMORY）
            cursorWindowLp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            getWindowManager().addView(overlay, cursorWindowLp);
            applyMouseWindowFocusFlags();
            virtualMouse = new com.core.engine.EngineVirtualMouse(overlay,
                    this::virtualMouseSurface,
                    (v, x, y) -> artTouchTap(x, y),
                    this::virtualMouseOverlayClick);
            if (!virtualMouseMode) virtualMouse.hideCursor();
            Log.i("YukiArtemis", "virtual mouse installed (artemis)");
        } catch (Throwable t) {
            Log.w("YukiArtemis", "install virtual mouse failed", t);
        }
    }

    private android.widget.FrameLayout mouseKeyCatcher;
    private android.view.WindowManager.LayoutParams cursorWindowLp;

    /**
     * 光标窗口按键路由（窗口可聚焦时）：重映射 → 虚拟鼠标（D-pad/摇杆/A），
     * 未消费的键转发游戏映射链（ArtemisActivity.dispatchKeyEvent 的
     * EmulateKeyEvent 映射），保证鼠标模式下 ENTER/ESC/SKIP 等仍可用。
     */
    private boolean routeMouseKey(android.view.KeyEvent event) {
        KeyEvent mapped = com.core.engine.GamepadRemap.apply(event);
        if (virtualMouse != null && virtualMouse.handleKey(mapped)) return true;
        try {
            super.dispatchKeyEvent(mapped);
        } catch (Throwable t) {
            Log.w("YukiArtemis", "mouse key forward failed", t);
        }
        return true; // 鼠标模式下按键不外溢
    }

    /** 鼠标模式 = 光标窗口可聚焦（接管按键）；触摸模式 = NOT_FOCUSABLE 还给游戏。 */
    private void applyMouseWindowFocusFlags() {
        if (mouseKeyCatcher == null || cursorWindowLp == null) return;
        try {
            if (virtualMouseMode) {
                cursorWindowLp.flags &= ~android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                mouseKeyCatcher.setFocusable(true);
                mouseKeyCatcher.setFocusableInTouchMode(true);
                mouseKeyCatcher.requestFocus();
            } else {
                cursorWindowLp.flags |= android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            }
            getWindowManager().updateViewLayout(mouseKeyCatcher, cursorWindowLp);
        } catch (Throwable t) {
            Log.w("YukiArtemis", "apply mouse focus flags failed", t);
        }
    }

    /** 坐标换算基准：游戏窗口 decor（全屏，与内核窗口像素坐标同基准）。 */
    private android.view.View virtualMouseSurface() {
        return getWindow().getDecorView();
    }

    /** 光标处合成一次完整触摸：down 立即、up 延时，均经内核触摸注入。 */
    private void artTouchTap(float x, float y) {
        try {
            injectHostTouch(x, y, true);
        } catch (Throwable t) {
            Log.w("YukiArtemis", "injectHostTouch down failed (official kernel?)", t);
            return;
        }
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> {
                    try {
                        injectHostTouch(x, y, false);
                    } catch (Throwable t) {
                        Log.w("YukiArtemis", "injectHostTouch up failed", t);
                    }
                }, 120);
    }

    /** 光标悬停面板窗口时点面板控件（合成触摸派发给面板根视图），否则注入游戏。 */
    private boolean virtualMouseOverlayClick(float x, float y) {
        android.view.View panelView = extractPanelView;
        if (panelView == null || panelView.getWidth() <= 0) return false;
        int[] loc = new int[2];
        panelView.getLocationOnScreen(loc);
        if (x < loc[0] || x >= loc[0] + panelView.getWidth()
                || y < loc[1] || y >= loc[1] + panelView.getHeight()) return false;
        dispatchTapToView(panelView, x - loc[0], y - loc[1]);
        return true;
    }

    /** 在视图坐标处合成完整触摸（down 立即、up 延时 60ms）。 */
    private void dispatchTapToView(android.view.View target, float x, float y) {
        try {
            long now = android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent down = android.view.MotionEvent.obtain(
                    now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
            down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            target.dispatchTouchEvent(down);
            down.recycle();
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> {
                        try {
                            long t2 = android.os.SystemClock.uptimeMillis();
                            android.view.MotionEvent up = android.view.MotionEvent.obtain(
                                    t2, t2, android.view.MotionEvent.ACTION_UP, x, y, 0);
                            up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                            target.dispatchTouchEvent(up);
                            up.recycle();
                        } catch (Throwable t) {
                            Log.w("YukiArtemis", "panel tap up failed", t);
                        }
                    }, 60);
        } catch (Throwable t) {
            Log.w("YukiArtemis", "panel tap failed", t);
        }
    }

    private boolean isVirtualMouseMode() {
        return virtualMouseMode;
    }

    private void toggleVirtualMouseMode() {
        virtualMouseMode = !virtualMouseMode;
        getSharedPreferences(PREF_ARTEMIS_MOUSE_MODE, MODE_PRIVATE).edit()
                .putBoolean(PREF_ARTEMIS_MOUSE_MODE, virtualMouseMode).apply();
        if (virtualMouse != null) {
            // 切进鼠标立即显示光标（触屏设备没有方向键可按）；切回触摸隐藏
            if (virtualMouseMode) virtualMouse.showCursor();
            else virtualMouse.hideCursor();
        }
        applyMouseWindowFocusFlags();
    }

    /**
     * 引擎键注入：经内核 EmulateKeyEvent。clean 内核（>= pluginVersion 31）按
     * 「key = 官方 key id、status = Android action（0=down/1=up）」真实入队；
     * 旧内核该 JNI 是日志桩（按下无效即此根因）。
     */
    private void artKey(int keyId, int action) {
        try {
            EmulateKeyEvent(keyId, action);
        } catch (Throwable t) {
            Log.w("YukiArtemis", "artKey failed keyId=" + keyId, t);
        }
    }

    /** down → 延时 up：跨帧保证引擎 IsPush/DownEdge 可见。 */
    private void artTap(int keyId) {
        artKey(keyId, 0);
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> artKey(keyId, 1), 120);
    }

    /**
     * 左缘引擎适配按键（Artemis）：NEXT=ENTER(key id 13) 推进、SKIP=按住
     * ctrl(id 140)、方向键=选肢/回览。经独立小窗安装（NativeActivity 宿主
     * 视图不参与合成）。
     */
    private void installEngineLeftButtons() {
        try {
            java.util.List<com.core.engine.EngineLeftButtons.ButtonSpec> buttons =
                    new java.util.ArrayList<>();
            buttons.add(new com.core.engine.EngineLeftButtons.ButtonSpec("NEXT",
                    () -> artTap(13)));
            buttons.add(new com.core.engine.EngineLeftButtons.ButtonSpec("SKIP",
                    () -> {
                        artKey(140, 0);
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(() -> artKey(140, 1), 900);
                    }));
            buttons.add(new com.core.engine.EngineLeftButtons.ButtonSpec("▲",
                    () -> artTap(38)));
            buttons.add(new com.core.engine.EngineLeftButtons.ButtonSpec("▼",
                    () -> artTap(40)));
            buttons.add(new com.core.engine.EngineLeftButtons.ButtonSpec("◀",
                    () -> artTap(37)));
            buttons.add(new com.core.engine.EngineLeftButtons.ButtonSpec("▶",
                    () -> artTap(39)));
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
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 虚拟鼠标模式：D-pad 移动光标、A 键点击（消费后不再走 EmulateKeyEvent
        // 游戏键映射）；其余按键交父类既有映射链路（重映射也在其内再做一次）
        if (virtualMouseMode && virtualMouse != null) {
            KeyEvent mapped = com.core.engine.GamepadRemap.apply(event);
            if (mapped != null && virtualMouse.handleKey(mapped)) return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {
        // 左摇杆移动虚拟光标（若内核/框架把摇杆事件预派发到 Java 侧则生效；
        // 未预派发时摇杆事件不会到达此处，D-pad 仍可用）
        if (virtualMouseMode && virtualMouse != null && virtualMouse.handleMotion(event)) {
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
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
