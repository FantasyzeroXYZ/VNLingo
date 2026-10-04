package com.core.engine

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent

/**
 * 手柄按键重映射：把手柄按钮（gamepad/joystick 源）替换为键盘按键或其他手柄按键。
 *
 * 映射持久化在 `gamepad_remap` prefs（`src_<keyCode>` → `g_<target>` 或 `k_<target>`）；
 * 宿主 Activity 在 onCreate/onResume 调 [refresh] 载入快照，[apply] 在 dispatchKeyEvent
 * 最前端做纯内存替换（命中则返回同时间戳/动作的新 KeyEvent，未命中原样返回），
 * 后续面板拦截、虚拟鼠标与引擎原生按键链路拿到的都是替换后的事件。
 *
 * 设置页（app GamepadSettingsActivity）经 [setMapping]/[clear]/[mappings] 维护配置；
 * MODE_MULTI_PROCESS 保证应用主进程写入后引擎进程刷新时读到最新值。
 */
object GamepadRemap {

    private const val PREFS = "gamepad_remap"
    private const val PREFIX_SRC = "src_"
    private const val TAG_GAMEPAD = "g_"
    private const val TAG_KEYBOARD = "k_"

    /** 快照：源手柄按键码 → 目标按键码（键盘/手柄统一为 keyCode）。 */
    @Volatile
    private var snapshot: Map<Int, Int> = emptyMap()

    fun store(context: Context): SharedPreferences =
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS)

    /** 宿主 Activity onCreate/onResume 调用：从 prefs 重载快照。 */
    @JvmStatic
    fun refresh(context: Context) {
        snapshot = mappings(context).mapValues { it.value.first }
    }

    /** dispatchKeyEvent 最前端调用：命中映射则返回替换 keyCode 的新事件。 */
    @JvmStatic
    fun apply(event: KeyEvent): KeyEvent {
        val source = event.source
        val isGamepad = (source and android.view.InputDevice.SOURCE_GAMEPAD) != 0 ||
            (source and android.view.InputDevice.SOURCE_JOYSTICK) != 0
        if (!isGamepad) return event
        val target = snapshot[event.keyCode] ?: return event
        if (target == event.keyCode) return event
        return KeyEvent(
            event.downTime, event.eventTime, event.action, target, event.repeatCount,
            event.metaState, event.deviceId, event.scanCode, event.flags, event.source,
        )
    }

    /** 全部映射：src keyCode → (target keyCode, target 是否手柄键)。 */
    fun mappings(context: Context): Map<Int, Pair<Int, Boolean>> {
        val prefs = store(context)
        val out = mutableMapOf<Int, Pair<Int, Boolean>>()
        prefs.all.forEach { (key, value) ->
            if (!key.startsWith(PREFIX_SRC) || value !is String) return@forEach
            val src = key.removePrefix(PREFIX_SRC).toIntOrNull() ?: return@forEach
            val target = when {
                value.startsWith(TAG_GAMEPAD) -> value.removePrefix(TAG_GAMEPAD).toIntOrNull()
                value.startsWith(TAG_KEYBOARD) -> value.removePrefix(TAG_KEYBOARD).toIntOrNull()
                else -> null
            } ?: return@forEach
            out[src] = target to value.startsWith(TAG_GAMEPAD)
        }
        return out
    }

    fun setMapping(context: Context, srcKeyCode: Int, targetKeyCode: Int, targetIsGamepad: Boolean) {
        val tag = if (targetIsGamepad) TAG_GAMEPAD else TAG_KEYBOARD
        store(context).edit()
            .putString("$PREFIX_SRC$srcKeyCode", "$tag$targetKeyCode")
            .apply()
        refresh(context)
    }

    fun clear(context: Context, srcKeyCode: Int) {
        store(context).edit().remove("$PREFIX_SRC$srcKeyCode").apply()
        refresh(context)
    }

    /** 可映射的源按钮（标准手柄布局），值用于展示的短名。 */
    val mappableButtons: List<Pair<Int, String>> = listOf(
        KeyEvent.KEYCODE_BUTTON_A to "A",
        KeyEvent.KEYCODE_BUTTON_B to "B",
        KeyEvent.KEYCODE_BUTTON_X to "X",
        KeyEvent.KEYCODE_BUTTON_Y to "Y",
        KeyEvent.KEYCODE_BUTTON_L1 to "L1",
        KeyEvent.KEYCODE_BUTTON_R1 to "R1",
        KeyEvent.KEYCODE_BUTTON_L2 to "L2",
        KeyEvent.KEYCODE_BUTTON_R2 to "R2",
        KeyEvent.KEYCODE_BUTTON_THUMBL to "L3",
        KeyEvent.KEYCODE_BUTTON_THUMBR to "R3",
        KeyEvent.KEYCODE_BUTTON_START to "Start",
        KeyEvent.KEYCODE_BUTTON_SELECT to "Select",
        KeyEvent.KEYCODE_DPAD_UP to "↑",
        KeyEvent.KEYCODE_DPAD_DOWN to "↓",
        KeyEvent.KEYCODE_DPAD_LEFT to "←",
        KeyEvent.KEYCODE_DPAD_RIGHT to "→",
    )

    /** 目标按键短名：手柄键用上表，键盘键取 keycode 去前缀（如 KEYCODE_Z → Z）。 */
    fun targetName(keyCode: Int, isGamepad: Boolean): String {
        if (isGamepad) {
            mappableButtons.firstOrNull { it.first == keyCode }?.let { return it.second }
        }
        return KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
    }
}
