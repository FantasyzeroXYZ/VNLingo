package com.tyranor.next.core.playtime

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 单游戏的游玩时长条目（总时长 + 日粒度明细）。 */
data class PlayTimeEntry(
    val gameKey: String,
    val totalMs: Long,
    val daily: Map<String, Long>,
)

/** 聚合后的时长快照。 */
data class PlayTimeSummary(
    val entries: List<PlayTimeEntry>,
    val grandTotalMs: Long,
    val weekMs: Long,
    val monthMs: Long,
)

/**
 * 游玩时长读取与聚合：数据由引擎宿主（PlayTimeTracker，引擎进程）落盘到
 * filesDir/play_time.json，本读取器在 app 进程读取同一文件并按周/月聚合。
 */
object PlayTimeReader {

    private val DAY_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun readAll(context: Context): List<PlayTimeEntry> = try {
        val file = File(context.filesDir, "play_time.json")
        val data = JSONObject(file.readText())
        data.keys().asSequence().map { key ->
            val entry = data.optJSONObject(key) ?: JSONObject()
            val daily = mutableMapOf<String, Long>()
            val d = entry.optJSONObject("d")
            d?.keys()?.asSequence()?.forEach { day -> daily[day] = d.optLong(day) }
            PlayTimeEntry(key, entry.optLong("t"), daily)
        }.filter { it.totalMs > 0 }.sortedByDescending { it.totalMs }.toList()
    } catch (t: Throwable) {
        emptyList()
    }

    /** 聚合总时长与本周/本月（周一起算）。 */
    fun summarize(entries: List<PlayTimeEntry>): PlayTimeSummary {
        val weekStart = dayStartOf(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        val monthStart = dayStartOf(Calendar.DAY_OF_MONTH, 1)
        var grand = 0L
        var week = 0L
        var month = 0L
        for (entry in entries) {
            grand += entry.totalMs
            for ((day, ms) in entry.daily) {
                val time = try {
                    DAY_FORMAT.parse(day)?.time ?: continue
                } catch (t: Throwable) {
                    continue
                }
                if (time >= weekStart) week += ms
                if (time >= monthStart) month += ms
            }
        }
        return PlayTimeSummary(entries, grand, week, month)
    }

    /** gameKey（游戏根目录绝对路径）→ 展示用的目录名。 */
    fun displayName(gameKey: String): String =
        gameKey.trimEnd('/', '\\').substringAfterLast('/', gameKey).ifEmpty { gameKey }

    private fun dayStartOf(field: Int, value: Int): Long =
        Calendar.getInstance().apply {
            set(field, value)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    fun nowDay(): String = DAY_FORMAT.format(Date())
}
