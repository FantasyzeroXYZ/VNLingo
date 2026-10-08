package com.core.engine

import android.content.Context

/**
 * 剧情文本框面板的行为选项（与按键绑定分开存放；`MODE_MULTI_PROCESS`：
 * 设置在 app 进程写、面板在引擎进程读）。
 */
object PanelOptions {

    /** 手柄查词方式：移动到词元后**按确认键**才查词（默认，与旧行为一致）。 */
    const val LOOKUP_CONFIRM = "confirm"

    /** 手柄查词方式：移动到词元**立即查词**（查不到不弹提示，只看面板状态行）。 */
    const val LOOKUP_AUTO = "auto"

    private const val PREFS = "panel_options"
    private const val KEY_LOOKUP_MODE = "lookup_mode"

    fun store(context: Context) =
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS)

    /** 当前查词方式（缺省 = 按确认键）。 */
    @JvmStatic
    fun lookupMode(context: Context): String =
        store(context).getString(KEY_LOOKUP_MODE, LOOKUP_CONFIRM) ?: LOOKUP_CONFIRM

    @JvmStatic
    fun setLookupMode(context: Context, value: String) {
        store(context).edit().putString(KEY_LOOKUP_MODE, value).apply()
    }

    /** 是否「移动到即查词」。 */
    @JvmStatic
    fun isAutoLookup(context: Context): Boolean = LOOKUP_AUTO == lookupMode(context)
}
