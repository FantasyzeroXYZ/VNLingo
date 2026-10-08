package com.core.engine

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent

/**
 * 剧情文本框面板的动作绑定表（对齐参考实现 webgametxt `dict-gamepad` 的语义动作集）。
 *
 * 一个动作 = 一个语义（呼出/选词/查词/制卡/翻译/滚动/退出…），可绑定任意按键
 * （手柄按钮或键盘键，统一 keyCode、不区分来源），键盘与手柄由此**共用同一套语义**。
 *
 * 与 [VirtualMouseBindings] 同款存储与快照策略：
 * - prefs `panel_action_bindings`，`action_<id>` → StringSet(keyCode)，空/缺省 = 用默认绑定；
 * - `MODE_MULTI_PROCESS`：设置页（app 进程）写，引擎进程的面板读；
 * - 快照式：[refresh] 在宿主 onCreate/onResume 与设置改动后调用，匹配走纯内存。
 */
object PanelActionBindings {

    // ── 动作 id（持久化与 UI 共用；数值一经发布不要改） ──────────────────
    /** 呼出/收起面板（仅面板关闭态生效）。 */
    const val PANEL_TOGGLE = 0
    const val PANEL_TOKEN_PREV = 1
    const val PANEL_TOKEN_NEXT = 2
    const val PANEL_NAV_UP = 3
    const val PANEL_NAV_DOWN = 4
    const val PANEL_CONFIRM = 5
    const val PANEL_TRANSLATE = 6
    const val PANEL_CARD = 7
    const val PANEL_SENTENCE_CARD = 8
    const val PANEL_SCROLL_UP = 9
    const val PANEL_SCROLL_DOWN = 10
    const val PANEL_EXIT = 11
    const val PANEL_COPY = 12
    const val PANEL_PLAY = 13
    const val PANEL_HISTORY = 14
    /** 快速开关虚拟鼠标（点击模式：虚拟鼠标 ↔ 触摸）。面板收起/展开都生效。 */
    const val PANEL_MOUSE_TOGGLE = 15

    /** 全部动作（顺序 = 设置页展示顺序）。 */
    val ALL = intArrayOf(
        PANEL_TOGGLE,
        PANEL_MOUSE_TOGGLE,
        PANEL_TOKEN_PREV, PANEL_TOKEN_NEXT, PANEL_NAV_UP, PANEL_NAV_DOWN,
        PANEL_CONFIRM, PANEL_TRANSLATE, PANEL_CARD, PANEL_SENTENCE_CARD,
        PANEL_SCROLL_UP, PANEL_SCROLL_DOWN, PANEL_EXIT, PANEL_COPY, PANEL_PLAY,
        PANEL_HISTORY,
    )

    // ── 默认绑定（对齐参考 dict-gamepad 的键位；未自定义时生效） ──────────
    val DEFAULT_TOGGLE = intArrayOf(KeyEvent.KEYCODE_BUTTON_R1)
    val DEFAULT_TOKEN_PREV = intArrayOf(KeyEvent.KEYCODE_DPAD_LEFT)
    val DEFAULT_TOKEN_NEXT = intArrayOf(KeyEvent.KEYCODE_DPAD_RIGHT)
    val DEFAULT_NAV_UP = intArrayOf(KeyEvent.KEYCODE_DPAD_UP)
    val DEFAULT_NAV_DOWN = intArrayOf(KeyEvent.KEYCODE_DPAD_DOWN)
    val DEFAULT_CONFIRM = intArrayOf(
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER)
    val DEFAULT_TRANSLATE = intArrayOf(KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_T)
    val DEFAULT_CARD = intArrayOf(KeyEvent.KEYCODE_BUTTON_Y)
    val DEFAULT_SENTENCE_CARD = intArrayOf(KeyEvent.KEYCODE_BUTTON_START)
    val DEFAULT_SCROLL_UP = intArrayOf(KeyEvent.KEYCODE_BUTTON_L1)
    val DEFAULT_SCROLL_DOWN = intArrayOf(KeyEvent.KEYCODE_BUTTON_R1)
    val DEFAULT_EXIT = intArrayOf(
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE)
    val DEFAULT_COPY = intArrayOf(KeyEvent.KEYCODE_C)
    val DEFAULT_PLAY = intArrayOf(KeyEvent.KEYCODE_SPACE)
    /** 虚拟鼠标开关默认绑 R2（多数手柄空闲；可改绑）。 */
    val DEFAULT_MOUSE_TOGGLE = intArrayOf(KeyEvent.KEYCODE_BUTTON_R2)

    /** 面板动作是否用户自定义过（无 = 当前生效的是默认绑定）。 */
    private const val PREFS = "panel_action_bindings"
    private const val PREFIX_ACTION = "action_"

    /** 动作 id → 默认绑定（UI 展示与回退共用）。 */
    @JvmStatic
    fun defaultCodes(action: Int): IntArray = when (action) {
        PANEL_TOGGLE -> DEFAULT_TOGGLE
        PANEL_TOKEN_PREV -> DEFAULT_TOKEN_PREV
        PANEL_TOKEN_NEXT -> DEFAULT_TOKEN_NEXT
        PANEL_NAV_UP -> DEFAULT_NAV_UP
        PANEL_NAV_DOWN -> DEFAULT_NAV_DOWN
        PANEL_CONFIRM -> DEFAULT_CONFIRM
        PANEL_TRANSLATE -> DEFAULT_TRANSLATE
        PANEL_CARD -> DEFAULT_CARD
        PANEL_SENTENCE_CARD -> DEFAULT_SENTENCE_CARD
        PANEL_SCROLL_UP -> DEFAULT_SCROLL_UP
        PANEL_SCROLL_DOWN -> DEFAULT_SCROLL_DOWN
        PANEL_EXIT -> DEFAULT_EXIT
        PANEL_COPY -> DEFAULT_COPY
        PANEL_PLAY -> DEFAULT_PLAY
        PANEL_MOUSE_TOGGLE -> DEFAULT_MOUSE_TOGGLE
        // 历史悬浮窗默认不绑键（参考实现里也没有对应手柄键）
        else -> IntArray(0)
    }

    /**
     * 动作名（设置页行标题；引擎侧资源字符串由 app 侧映射，这里给稳定的英文/符号短名，
     * 便于日志与默认展示）。真正的中文标题在 app 设置页用资源提供。
     */
    fun actionKey(action: Int): String = when (action) {
        PANEL_TOGGLE -> "toggle"
        PANEL_TOKEN_PREV -> "token_prev"
        PANEL_TOKEN_NEXT -> "token_next"
        PANEL_NAV_UP -> "nav_up"
        PANEL_NAV_DOWN -> "nav_down"
        PANEL_CONFIRM -> "confirm"
        PANEL_TRANSLATE -> "translate"
        PANEL_CARD -> "card"
        PANEL_SENTENCE_CARD -> "sentence_card"
        PANEL_SCROLL_UP -> "scroll_up"
        PANEL_SCROLL_DOWN -> "scroll_down"
        PANEL_EXIT -> "exit"
        PANEL_COPY -> "copy"
        PANEL_PLAY -> "play"
        PANEL_MOUSE_TOGGLE -> "mouse_toggle"
        else -> "history"
    }

    /** 快照：动作 → 绑定 keyCode（空数组 = 显式解绑，不响应）。 */
    @Volatile
    private var snapshot: Map<Int, IntArray> = emptyMap()

    fun store(context: Context): SharedPreferences =
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS)

    /** 宿主 onCreate/onResume 调用：重载快照。 */
    @JvmStatic
    fun refresh(context: Context) {
        val out = mutableMapOf<Int, IntArray>()
        val prefs = store(context)
        for (action in ALL) {
            val bound = prefs.getStringSet("$PREFIX_ACTION$action", emptySet())
                ?.mapNotNull { it.toIntOrNull() }?.toIntArray()
            // 空集 = 未自定义（回退默认），不进快照
            if (bound != null && bound.isNotEmpty()) out[action] = bound
        }
        snapshot = out
    }

    /** 动作当前生效的 keyCode 列表（自定义优先，未自定义回退默认）。 */
    @JvmStatic
    fun codes(action: Int): IntArray {
        val bound = snapshot[action]
        if (bound != null && bound.isNotEmpty()) return bound
        return defaultCodes(action)
    }

    /** keyCode 命中动作？ */
    @JvmStatic
    fun matches(action: Int, keyCode: Int): Boolean = codes(action).contains(keyCode)

    /** 该动作是否有用户自定义绑定。 */
    @JvmStatic
    fun isCustomized(action: Int): Boolean = snapshot[action]?.isNotEmpty() == true

    /** 绑定展示（UI 用）：动作 → (键列表, 是否自定义)。 */
    fun bindings(context: Context): Map<Int, BindingDisplay> {
        refresh(context)
        val out = LinkedHashMap<Int, BindingDisplay>()
        for (action in ALL) {
            out[action] = BindingDisplay(codes(action).toList(), isCustomized(action))
        }
        return out
    }

    class BindingDisplay(val codes: List<Int>, val customized: Boolean)

    /** 追加绑定（捕获式设置页：按下的键即绑定到该动作）。 */
    fun addBinding(context: Context, action: Int, keyCode: Int) {
        val prefs = store(context)
        val existing = prefs.getStringSet("$PREFIX_ACTION$action", emptySet()) ?: emptySet()
        prefs.edit().putStringSet("$PREFIX_ACTION$action", existing + keyCode.toString()).apply()
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

    /** 按键展示名（A/↑/Start…，非手柄键取 keycode 去前缀）。 */
    fun keyName(keyCode: Int): String = GamepadRemap.targetName(keyCode, isGamepadButton(keyCode))

    private fun isGamepadButton(keyCode: Int): Boolean = GamepadRemap.mappableButtons
        .any { it.first == keyCode }
}
