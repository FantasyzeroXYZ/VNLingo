package com.tyranor.next.core.play

import android.content.Context
import com.tyranor.next.core.playtime.PlayTimeReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 助手「游玩统计」工具的数据源（engine 模块引不到 app，经
 * [com.core.ons.OnsAgentDialog.setPlayStatsProvider] 注入，同 OnsSaveCloud
 * 凭据注入模式）。
 *
 * 数据 = 会话制 [PlaySessionTracker]（新）+ filesDir/play_time.json 旧 JSON
 * （历史记录兼容），与 PlayStatsActivity 同一双数据源口径。
 *
 * 说明：`play_sessions.game_title` 目前记的是 Activity 标题（宿主引擎 Activity
 * 上等于应用名），不能当游戏名用——展示名一律由 game_uri 路径推导
 * （`.../game/<引擎>/<游戏目录>/...` 取游戏目录段）。
 */
object AgentPlayStats {

    private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.US)

    /** 当前游戏（按显示名匹配）+ 全局统计的多行文本；无数据返回空串。 */
    @JvmStatic
    fun describe(context: Context, gameName: String?): String {
        // 会话制（按 uri 聚合）+ 旧 JSON（按根目录路径）→ 统一成 {key, 显示名, 时长}
        val entries = LinkedHashMap<String, Long>()
        runCatching { PlaySessionTracker.allTotals(context) }
            .getOrDefault(emptyMap())
            .forEach { (uri, ms) -> entries[uri] = (entries[uri] ?: 0L) + ms }
        val legacy = runCatching {
            PlayTimeReader.summarize(PlayTimeReader.readAll(context))
        }.getOrNull()
        legacy?.entries?.forEach { e -> entries[e.gameKey] = (entries[e.gameKey] ?: 0L) + e.totalMs }
        if (entries.isEmpty()) return ""

        val ranked = entries.entries.sortedByDescending { it.value }
        val sb = StringBuilder()

        val name = gameName?.trim().orEmpty()
        if (name.isNotEmpty()) {
            val hit = ranked.firstOrNull { displayTitle(it.key) == name }
                ?: ranked.firstOrNull { it.key.contains(name) }
            if (hit != null) {
                val status = runCatching {
                    PlaySessionTracker.getPlayStatus(context, hit.key)
                }.getOrNull()
                sb.append("当前游戏「").append(displayTitle(hit.key)).append("」累计 ")
                    .append(format(hit.value))
                if (!status.isNullOrEmpty()) sb.append("，状态：").append(statusText(status))
                sb.append('\n')
            }
        }

        val now = System.currentTimeMillis()
        val week = runCatching {
            PlaySessionTracker.durationsBetween(context, now - 7L * 24 * 3600 * 1000, now)
                .values.sum()
        }.getOrDefault(0L) + (legacy?.weekMs ?: 0L)

        sb.append("全部游戏累计 ").append(format(ranked.sumOf { it.value }))
        sb.append("（本周 ").append(format(week)).append("）\n")

        sb.append("时长排行：")
        ranked.take(5).forEachIndexed { index, entry ->
            if (index > 0) sb.append("；")
            sb.append(displayTitle(entry.key)).append(' ').append(format(entry.value))
        }

        val recent = runCatching { PlaySessionTracker.recentSessions(context, 3) }
            .getOrDefault(emptyList())
        if (recent.isNotEmpty()) {
            sb.append("\n最近游玩：")
            recent.forEachIndexed { index, s ->
                if (index > 0) sb.append("；")
                sb.append(displayTitle(s.gameUri)).append(' ')
                    .append(TIME_FORMAT.format(Date(if (s.endTime > 0) s.endTime else s.startTime)))
                    .append(' ').append(format(s.duration))
            }
        }
        return sb.toString()
    }

    /** 会话键（游戏绝对路径 / 类名）→ 展示名：取 `.../game/<引擎>/<游戏目录>/` 的游戏目录段。 */
    internal fun displayTitle(key: String): String {
        if (!key.contains('/')) return key
        val parts = key.trimEnd('/').split('/').filter { it.isNotEmpty() }
        val gameIndex = parts.indexOfFirst { it.equals("game", ignoreCase = true) }
        if (gameIndex >= 0 && gameIndex + 2 < parts.size) return parts[gameIndex + 2]
        val fallback = PlayTimeReader.displayName(key)
        return fallback.substringBeforeLast('.', fallback).ifEmpty { key }
    }

    private fun statusText(status: String): String = when (status) {
        "playing" -> "进行中"
        "completed" -> "已通关"
        "unplayed" -> "未开始"
        else -> status
    }

    private fun format(ms: Long): String = when {
        ms < 60_000L -> "不足 1 分钟"
        ms < 3_600_000L -> "${ms / 60_000L} 分钟"
        else -> String.format(Locale.US, "%.1f 小时", ms / 3_600_000.0)
    }
}
