package com.core.engine

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent

/**
 * 虚拟鼠标按键绑定：把「虚拟鼠标动作」绑定到任意按键（手柄按钮或键盘键，
 * 统一为 keyCode、不区分来源）。
 *
 * 动作五个：上移/下移/左移/右移/确认。默认绑定 = 键盘方向键（KEYCODE_DPAD_*
 * 同时覆盖手柄十字键）+ 确认 = 手柄 A / 键盘回车 / DPAD_CENTER。
 *
 * 持久化在 `virtual_mouse_bindings` prefs（`action_<id>` → StringSet(keyCode)，
 * 空/缺省 = 用默认绑定）；MODE_MULTI_PROCESS 与 GamepadRemap 同款——应用设置页
 * （GamepadSettingsActivity 虚拟鼠标区）写入，引擎进程的 EngineVirtualMouse
 * 快照读取。快照式：宿主 onCreate/onResume 调 [refresh]，匹配走纯内存。
 */
object VirtualMouseBindings {

    /** 动作 id（持久化与 UI 共用）。 */
    const val ACTION_UP = 0
    const val ACTION_DOWN = 1
    const val ACTION_LEFT = 2
    const val ACTION_RIGHT = 3
    const val ACTION_CONFIRM = 4

    private const val PREFS = "virtual_mouse_bindings"
    private const val PREFIX_ACTION = "action_"

    /** 默认绑定（未自定义时生效）。 */
    val DEFAULT_UP = intArrayOf(KeyEvent.KEYCODE_DPAD_UP)
    val DEFAULT_DOWN = intArrayOf(KeyEvent.KEYCODE_DPAD_DOWN)
    val DEFAULT_LEFT = intArrayOf(KeyEvent.KEYCODE_DPAD_LEFT)
    val DEFAULT_RIGHT = intArrayOf(KeyEvent.KEYCODE_DPAD_RIGHT)
    val DEFAULT_CONFIRM = intArrayOf(
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER)

    /** 动作短名（设置页行标题）。 */
    fun actionName(action: Int): String = when (action) {
        ACTION_UP -> "↑"
        ACTION_DOWN -> "↓"
        ACTION_LEFT -> "←"
        ACTION_RIGHT -> "→"
        else -> "OK"
    }

    /** 快照：动作 → 绑定 keyCode 列表（空数组 = 该动作未绑定任何键，不响应）。 */
    @Volatile
    private var snapshot: Map<Int, IntArray> = emptyMap()

    fun store(context: Context): SharedPreferences =
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS)

    /** 宿主 Activity onCreate/onResume / 虚拟鼠标构造时调用：重载快照。 */
    @JvmStatic
    fun refresh(context: Context) {
        val out = mutableMapOf<Int, IntArray>()
        val prefs = store(context)
        for (action in intArrayOf(ACTION_UP, ACTION_DOWN, ACTION_LEFT, ACTION_RIGHT, ACTION_CONFIRM)) {
            val bound = prefs.getStringSet("$PREFIX_ACTION$action", emptySet())
                ?.mapNotNull { it.toIntOrNull() }?.toIntArray()
            // 空集 = 未自定义（回退默认），不进快照
            if (bound != null && bound.isNotEmpty()) out[action] = bound
        }
        snapshot = out
    }

    /** 动作当前生效的 keyCode 列表（自定义优先，未自定义回退默认）。 */
    fun codes(action: Int): IntArray {
        val bound = snapshot[action]
        if (bound != null && bound.isNotEmpty()) return bound
        return when (action) {
            ACTION_UP -> DEFAULT_UP
            ACTION_DOWN -> DEFAULT_DOWN
            ACTION_LEFT -> DEFAULT_LEFT
            ACTION_RIGHT -> DEFAULT_RIGHT
            else -> DEFAULT_CONFIRM
        }
    }

    /** keyCode 命中动作？ */
    fun matches(action: Int, keyCode: Int): Boolean = codes(action).contains(keyCode)

    /** 动作的完整绑定表（UI 展示）：动作 → keyCode 列表（自定义或默认）。 */
    fun bindings(context: Context): Map<Int, List<Int>> {
        refresh(context)
        return mapOf(
            ACTION_UP to codes(ACTION_UP).toList(),
            ACTION_DOWN to codes(ACTION_DOWN).toList(),
            ACTION_LEFT to codes(ACTION_LEFT).toList(),
            ACTION_RIGHT to codes(ACTION_RIGHT).toList(),
            ACTION_CONFIRM to codes(ACTION_CONFIRM).toList(),
        )
    }

    /** 追加绑定（捕获式设置页：按下的键即绑定到该动作）。 */
    fun addBinding(context: Context, action: Int, keyCode: Int) {
        val prefs = store(context)
        val existing = prefs.getStringSet("$PREFIX_ACTION$action", emptySet()) ?: emptySet()
        val updated = existing + keyCode.toString()
        prefs.edit().putStringSet("$PREFIX_ACTION$action", updated).apply()
        refresh(context)
    }

    /** 移除单条绑定；清空后该动作回退默认绑定。 */
    fun removeBinding(context: Context, action: Int, keyCode: Int) {
        val prefs = store(context)
        val existing = prefs.getStringSet("$PREFIX_ACTION$action", emptySet()) ?: emptySet()
        val updated = existing - keyCode.toString()
        if (updated.isEmpty()) prefs.edit().remove("$PREFIX_ACTION$action").apply()
        else prefs.edit().putStringSet("$PREFIX_ACTION$action", updated).apply()
        refresh(context)
    }

    /** 恢复该动作默认（删除自定义绑定集）。 */
    fun resetAction(context: Context, action: Int) {
        store(context).edit().remove("$PREFIX_ACTION$action").apply()
        refresh(context)
    }

    /** 按键展示名：标准手柄按钮用按钮名（A/↑/Start…），其余取 keycode 去前缀。 */
    fun keyName(keyCode: Int): String = GamepadRemap.targetName(keyCode, isGamepadButton(keyCode))

    private fun isGamepadButton(keyCode: Int): Boolean = GamepadRemap.mappableButtons
        .any { it.first == keyCode }
}
