package com.core.engine;

import android.content.Context;

/**
 * 控制模式（触摸 / 虚拟鼠标）的统一抽象。
 *
 * **开发准则：共通部分做统一抽象，差异化部分做继承适配。**
 *
 * 控制模式的公共路径全项目只有一份，收口在本类：
 *  1. 模式状态与持久化（prefs，prefs 文件/键与缺省值由宿主给定，保持各引擎既有存储不变）；
 *  2. 切换语义：翻转 → 落盘 → 光标显隐同步（[EngineOverlayRouting#syncCursor]）；
 *  3. 面板 / 手柄快捷键 / 右缘按钮的统一查询与切换入口（[isMouseMode] / [toggle]）。
 *
 * 宿主差异通过 [Differ] 回调做继承适配，**不得**再各自手写「mode 字段 + toggle +
 * prefs 读写」三件套（此前三处实现已经漂移出过：ONS 漏启动同步、Artemis 漏按钮
 * 图标补刷、Tyrano 完全不持久化等"某个引擎漏一步"的问题）：
 *  - Artemis：按键捕获窗焦点旗标（applyMouseWindowFocusFlags）+ setKeepNativeFocused；
 *  - ONS / KRKR：无额外适配（光标同步 + 宿主补刷按钮图标即可）；
 *  - Tyrano / RPG Maker（Web 宿主）：VirtualMouseLayer 激活态（本类经
 *    [VirtualMouseController] 统一接口直接驱动，无需专用 Differ）。
 */
public final class EngineControlMode {

    /** 宿主差异适配：模式生效（启动恢复或切换）后由宿主做自己的窗口/层调整。 */
    public interface Differ {
        void onModeApplied(boolean mouseMode);
    }

    private final Context context;
    private final String prefsName;
    private final String key;
    private VirtualMouseController mouse;
    private Differ differ;
    private boolean mode;

    private EngineControlMode(Context context, String prefsName, String key, boolean mode) {
        this.context = context.getApplicationContext();
        this.prefsName = prefsName;
        this.key = key;
        this.mode = mode;
    }

    /**
     * 读取持久化模式（无记录用 [defaultMode]）。此时宿主尚未装配光标（mouse 为 null），
     * 不会触发任何副作用；装配完成后宿主调 [applyTo] 一次性应用。
     */
    public static EngineControlMode load(Context context, String prefsName, String key, boolean defaultMode) {
        boolean mode = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .getBoolean(key, defaultMode);
        return new EngineControlMode(context, prefsName, key, mode);
    }

    /** 当前是否处于虚拟鼠标模式（面板动作表 / 手柄快捷键查询用）。 */
    public boolean isMouseMode() {
        return mode;
    }

    /**
     * 绑定光标与宿主差异适配，并把当前持久化模式应用到它们（宿主启动装配时调用一次）。
     * 替代各宿主此前的「读回字段 + 手写 syncCursor + 补刷按钮」散装序列。
     */
    public void applyTo(VirtualMouseController mouse, Differ differ) {
        this.mouse = mouse;
        this.differ = differ;
        // 只做光标同步与差异适配，**不重新落盘**（启动恢复路径不应产生写 prefs 的副作用）
        EngineOverlayRouting.syncCursor(mouse, mode);
        if (differ != null) differ.onModeApplied(mode);
    }

    /** 切换（右缘「点击模式」按钮 / 手柄 PANEL_MOUSE_TOGGLE 快捷键共用）。返回切换后的模式。 */
    public boolean toggle() {
        set(!mode);
        return mode;
    }

    /** 直接设置（含持久化、光标同步与差异适配）。 */
    public void set(boolean mouseMode) {
        mode = mouseMode;
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit().putBoolean(key, mouseMode).apply();
        EngineOverlayRouting.syncCursor(mouse, mode);
        if (differ != null) differ.onModeApplied(mode);
    }
}
