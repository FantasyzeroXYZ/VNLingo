package com.vnlingo.app.core.game.launch

import android.content.Context
import com.vnlingo.app.core.game.model.ScanGame

/**
 * 每个游戏「最近一次运行」的记录（条目选项 → 调试信息）。
 *
 * 只记录启动链路的关键信息与失败原因，用于事后分析「为什么进不去/白屏」：
 * 引擎、启动目标、启动文件、补丁选择、发起时间、最终结果（成功 / 失败码 + 详情）。
 * 日志正文（运行时日志 / 崩溃日志）不在这里，调试页会另行读取 DiagLog 与崩溃目录。
 */
object GameRunRecord {

    private const val PREFS = "game_run_record"

    private fun key(game: ScanGame): String = game.uri
    private fun key(uri: String): String = uri

    /** 启动发起时写入（覆盖上一次）。 */
    fun begin(context: Context, game: ScanGame, patch: String? = null) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(key(game) + "#title", game.title)
                .putString(key(game) + "#engine", game.engine?.name ?: "")
                .putString(key(game) + "#target", game.launchTarget ?: "")
                .putString(key(game) + "#file", game.launchFile ?: "")
                .putString(key(game) + "#patch", patch ?: "")
                .putString(key(game) + "#at", System.currentTimeMillis().toString())
                .putString(key(game) + "#result", "launching")
                .remove(key(game) + "#detail")
                .apply()
        }
    }

    /** 启动失败时补充原因（成功不写，保持 result=launching → 由 [markSuccess] 收尾）。 */
    fun fail(context: Context, game: ScanGame, reason: String) = fail(context, key(game), reason)

    fun fail(context: Context, uri: String, reason: String) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(key(uri) + "#result", "failed")
                .putString(key(uri) + "#detail", reason)
                .putString(key(uri) + "#failAt", System.currentTimeMillis().toString())
                .apply()
        }
    }

    /** 成功发起启动。 */
    fun markSuccess(context: Context, uri: String) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(key(uri) + "#result", "ok")
                .apply()
        }
    }

    /** 读取某游戏最近一次运行记录（按字段名取值，缺失返回空串）。 */
    fun read(context: Context, uri: String): Map<String, String> {
        val p = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fields = listOf("title", "engine", "target", "file", "patch", "at", "result", "detail", "failAt")
        return fields.associateWith { p.getString(key(uri) + "#$it", "") ?: "" }
    }
}
