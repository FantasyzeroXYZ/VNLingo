package com.core.anki

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * 制卡配置多方案（TrackReader schemes 对齐）：把「制卡设置」的整套配置
 * （deck/model/field_map/capture_on_card/voice_source）保存为命名方案快照，
 * 可随时加载切换。活动配置始终在 [CONFIG_PREFS]（AnkiCardConfig 读写处），
 * 方案快照存 [SCHEMES_PREFS] 的 `scheme_<名>` 键（整包 JSON）。
 *
 * 未保存过方案 = 只有默认配置（向后兼容）；加载方案 = 整包覆盖活动配置。
 */
object AnkiCardSchemes {

    private const val SCHEMES_PREFS = "anki_card_schemes"
    private const val PREFIX_SCHEME = "scheme_"
    private const val KEY_ACTIVE = "active_scheme"

    fun store(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(SCHEMES_PREFS, Context.MODE_MULTI_PROCESS)

    private fun configStore(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(
            AnkiCardConfig.PREFS, Context.MODE_MULTI_PROCESS)

    /** 全部方案名（无序）。 */
    @JvmStatic
    fun list(context: Context): List<String> {
        val out = ArrayList<String>()
        for ((key, value) in store(context).all) {
            if (key.startsWith(PREFIX_SCHEME) && value is String && value.isNotEmpty()) {
                out.add(key.removePrefix(PREFIX_SCHEME))
            }
        }
        return out
    }

    /** 当前生效的方案名（空 = 默认配置未关联方案）。 */
    @JvmStatic
    fun activeName(context: Context): String =
        store(context).getString(KEY_ACTIVE, "") ?: ""

    @JvmStatic
    fun setActiveName(context: Context, name: String) {
        store(context).edit().putString(KEY_ACTIVE, name).apply()
    }

    /** 把当前活动制卡配置保存为命名方案（同名覆盖）。 */
    @JvmStatic
    fun saveCurrent(context: Context, name: String) {
        val config = JSONObject()
        for ((key, value) in configStore(context).all) {
            when (value) {
                null -> config.put(key, JSONObject.NULL)
                is Boolean -> config.put(key, value)
                is Int -> config.put(key, value)
                is Long -> config.put(key, value)
                is Float -> config.put(key, value.toDouble())
                is String -> config.put(key, value)
                is Set<*> -> config.put(key, org.json.JSONArray(value.filterNotNull()))
                else -> {}
            }
        }
        store(context).edit()
            .putString(PREFIX_SCHEME + name, config.toString())
            .apply()
        setActiveName(context, name)
    }

    /** 加载命名方案到活动配置（整包覆盖：先清活动配置再写入方案全部键）。 */
    @JvmStatic
    fun apply(context: Context, name: String) {
        val raw = store(context).getString(PREFIX_SCHEME + name, null) ?: return
        val obj = JSONObject(raw)
        val editor = configStore(context).edit()
        editor.clear()
        for (key in obj.keys()) {
            when (val value = obj.opt(key)) {
                JSONObject.NULL -> {}
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is String -> editor.putString(key, value)
                is org.json.JSONArray -> {
                    val set = HashSet<String>()
                    for (i in 0 until value.length()) set.add(value.optString(i))
                    editor.putStringSet(key, set)
                }
                else -> {}
            }
        }
        editor.apply()
        setActiveName(context, name)
    }

    @JvmStatic
    fun delete(context: Context, name: String) {
        store(context).edit().remove(PREFIX_SCHEME + name).apply()
        if (activeName(context) == name) setActiveName(context, "")
    }
}
