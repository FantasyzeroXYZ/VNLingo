package com.core.engine;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.core.ons.OnsExtractPanel;

/**
 * **覆盖层的统一路由与装配入口**（各引擎宿主共用；见用户要求"统一抽象与调用层"）。
 *
 * 五个宿主（ONS / Tyrano / Kirikiroid / Artemis / RPGM）此前各自手写这几段顺序与
 * 判断，结果反复出现"某个引擎漏一步"的细碎问题（例如 Artemis/Tyrano/RPGM 的
 * 「鼠标点侧键/面板无效」、RPGM 漏沉浸式、RPGM 虚拟鼠标没有手柄输入）。
 *
 * 统一后的约定（顺序固定，不要再在宿主里另写）：
 * 1. `routeKey`：手柄重映射 → **面板优先**（展开时独占）→ 虚拟鼠标（鼠标模式时才接管）；
 * 2. `routeMotion`：面板展开时轴事件全吞 → 否则给虚拟鼠标；
 * 3. `routeCursorClick`：光标点击优先命中平台控件（右缘侧键 → 面板本体，面板上非控件处
 *    点一下即收起），未命中才由宿主注入游戏画面；
 * 4. `applyImmersive`：沉浸式全屏（状态栏/底部栏隐藏）。
 */
public final class EngineOverlayRouting {

    private EngineOverlayRouting() {
    }

    /**
     * 按键路由（宿主 dispatchKeyEvent 调用）。
     *
     * @param panel        提取面板（可为 null）
     * @param mouse        虚拟鼠标（可为 null）
     * @param mouseEnabled 宿主是否处于鼠标模式（触摸模式下不接管按键，保持既有语义）
     * @return true = 已消费，宿主应直接返回 true
     */
    public static boolean routeKey(OnsExtractPanel panel, VirtualMouseController mouse,
                                   boolean mouseEnabled, KeyEvent event) {
        if (event == null) return false;
        // 面板优先：展开时按键只进面板、不透传游戏，也不驱动虚拟鼠标
        if (panel != null && panel.handleKey(event)) return true;
        if (mouseEnabled && mouse != null && mouse.handleKey(event)) return true;
        return false;
    }

    /** 轴路由（宿主 dispatchGenericMotionEvent 调用）。 */
    public static boolean routeMotion(OnsExtractPanel panel, VirtualMouseController mouse,
                                      boolean mouseEnabled, MotionEvent event) {
        if (event == null) return false;
        // 面板展开：摇杆/轴事件全吞，既不驱动游戏也不驱动虚拟光标
        if (panel != null && panel.handleMotion(event)) return true;
        if (mouseEnabled && mouse != null && mouse.handleMotion(event)) return true;
        return false;
    }

    /**
     * 光标点击的"平台控件优先"路由：命中平台控件（右缘侧键/面板）即消费，
     * 未命中返回 false，由宿主把触摸注入游戏画面。
     */
    public static boolean routeCursorClick(OnsExtractPanel panel, float x, float y) {
        return panel != null && panel.dispatchCursorClick(x, y);
    }

    /**
     * 光标可见性与宿主的「鼠标模式」开关对齐（**启动时与每次 toggle 后都必须调用**）。
     *
     * 宿主把鼠标模式持久化在 prefs，启动时只把它读回自己的**布尔字段**；光标本身的
     * 可见性在 {@code EngineVirtualMouse.cursorVisible} 里，初始恒为 false。不同步就会出现
     * 「右缘『点击模式』键显示鼠标模式，但光标不出现、方向键也不驱动光标」——
     * 用户反馈的「刚进游戏时虚拟鼠标/触摸模式错乱、与右侧控制模式状态不一致」即此。
     * 统一收口在这里，避免以后又有宿主漏写（ONS 此前完全没有这一步）。
     */
    public static void syncCursor(VirtualMouseController mouse, boolean mouseEnabled) {
        if (mouse == null) return;
        if (mouseEnabled) mouse.showCursor();
        else mouse.hideCursor();
    }

    /** 沉浸式全屏（统一入口，杜绝"某个引擎漏做"）。 */
    public static void applyImmersive(Activity activity) {
        ImmersiveMode.applyAndReapply(activity);
    }
}
