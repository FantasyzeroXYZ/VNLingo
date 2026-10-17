package com.core.engine;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * 扳机轴（L2/R2）→ 键事件合成器。
 *
 * **为什么需要它**：不少手柄（实测 Xbox One S Model 1708 蓝牙，见
 * {@code /system/usr/keylayout/Vendor_045e_Product_02fd.kl}）的键位布局把扳机
 * **只映射为模拟轴**（{@code axis 0x09 RTRIGGER} / {@code axis 0x0a LTRIGGER}），
 * 内核层永远不产生 {@link KeyEvent#KEYCODE_BUTTON_R2} / {@link KeyEvent#KEYCODE_BUTTON_L2}
 * —— 应用只收到 {@code MotionEvent} 里的 AXIS_GAS / AXIS_BRAKE 轴量。
 * 而面板动作表 / 虚拟鼠标绑定表都按 keyCode 匹配（统一语义），结果是
 * 「扳机键没有发挥作用」（用户反馈：默认绑在 R2 的切换虚拟鼠标键失灵）。
 *
 * **做法**：在宿主 {@code dispatchGenericMotionEvent} 最前端调用 {@link #apply}，
 * 对扳机轴做 0.5 阈值的**按下/松开边沿检测**（按 deviceId + 轴记状态），
 * 边沿处合成一条标准 KeyEvent（DOWN / UP），宿主把它交给自己的
 * {@code dispatchKeyEvent} —— 手柄重映射、面板动作表、虚拟鼠标与游戏链路
 * 因此对"真按键"与"合成扳机键"完全无感，一处收口，全宿主受益。
 *
 * 只在边沿合成：按住不重复（面板 toggle 自身还有去抖兜底）；
 * 非扳机轴 / 非手柄源返回 null，不改变既有摇杆行为。
 */
public final class TriggerAxisKeys {

    private TriggerAxisKeys() {
    }

    /** 合成阈值（与 Android 对轴量转按键的通用判定一致）。 */
    private static final float PRESS_THRESHOLD = 0.5f;

    /** deviceId → (轴 → 上次是否按下)。进程级状态，宿主无需管理生命周期。 */
    private static final Map<Integer, Map<Integer, Boolean>> STATES = new HashMap<>();

    /**
     * 扳机轴边沿检测：按下边沿返回 {@code ACTION_DOWN} 的合成键、松开边沿返回
     * {@code ACTION_UP}，其余（含非扳机轴、非边沿帧）返回 {@code null}。
     * 覆盖 GAS/BRAKE（手柄前扳机）与 LTRIGGER/RTRIGGER（摇杆别名）两套轴。
     */
    public static KeyEvent apply(MotionEvent event) {
        if (event == null) return null;
        int sources = event.getSource();
        boolean isGamepadLike = (sources & InputDevice.SOURCE_GAMEPAD) != 0
                || (sources & InputDevice.SOURCE_JOYSTICK) != 0;
        if (!isGamepadLike) return null;
        Map<Integer, Boolean> state = STATES.computeIfAbsent(event.getDeviceId(), k -> new HashMap<>());
        KeyEvent synthesized = null;
        synthesized = edge(state, event, MotionEvent.AXIS_GAS,
                KeyEvent.KEYCODE_BUTTON_R2, synthesized);
        synthesized = edge(state, event, MotionEvent.AXIS_RTRIGGER,
                KeyEvent.KEYCODE_BUTTON_R2, synthesized);
        synthesized = edge(state, event, MotionEvent.AXIS_BRAKE,
                KeyEvent.KEYCODE_BUTTON_L2, synthesized);
        synthesized = edge(state, event, MotionEvent.AXIS_LTRIGGER,
                KeyEvent.KEYCODE_BUTTON_L2, synthesized);
        return synthesized;
    }

    /** 单轴边沿检测：有边沿就合成键事件（同帧双轴边沿时取后者，实际不会发生）。 */
    private static KeyEvent edge(Map<Integer, Boolean> state, MotionEvent event,
                                 int axis, int keyCode, KeyEvent current) {
        // 摇杆/手柄源的轴事件每帧携带全部轴（缺席轴恒 0），
        // 直接读值即可；从未上报的轴首帧记 false，不产生边沿。
        float value = event.getAxisValue(axis);
        boolean pressed = value > PRESS_THRESHOLD;
        Boolean prev = state.get(axis);
        state.put(axis, pressed);
        if (prev == null || prev == pressed) return current; // 无边沿
        long now = SystemClock.uptimeMillis();
        return new KeyEvent(now, now,
                pressed ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                keyCode, 0, 0, event.getDeviceId(), 0,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_SOFT_KEYBOARD
                        | KeyEvent.FLAG_KEEP_TOUCH_MODE,
                event.getSource());
    }
}
