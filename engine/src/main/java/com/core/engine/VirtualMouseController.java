package com.core.engine;

import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * 虚拟鼠标的**宿主无关接口**：各引擎的实现（{@link EngineVirtualMouse}、
 * com.core.tyrano.VirtualMouseLayer 等）都实现它，宿主与覆盖层路由只依赖这一层，
 * 不再为某个引擎单独调某个具体类的方法。
 *
 * 目标（见用户要求）：任何"输入 / 覆盖层"相关的行为只在共享实现里改一次，
 * 不会再出现"某个引擎漏了某一步"。
 */
public interface VirtualMouseController {

    /** 按键路由：返回 true = 已消费（宿主不要再透传游戏）。 */
    boolean handleKey(KeyEvent event);

    /** 轴（摇杆/十字键 HAT）路由：返回 true = 已消费。 */
    boolean handleMotion(MotionEvent event);

    /** 光标是否可见/激活（宿主用来切换图标与焦点策略）。 */
    boolean isCursorVisible();

    /** 切进鼠标模式（显示光标）。 */
    void showCursor();

    /** 切回触摸模式（隐藏光标并停掉重复任务）。 */
    void hideCursor();
}
