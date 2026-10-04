package com.core.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 游玩时长统计（引擎进程内计时，宿主无关）：
 * - 宿主 onResume 调 [onForeground]、onPause/onDestroy 调 [onBackground]
 * - 前台期间每 60s 周期落盘一次（进程被杀最多丢 1 分钟）
 * - 数据落 filesDir/play_time.json：{"<gameKey>":{"t":总ms,"d":{"yyyy-MM-dd":ms}}}
 *   gameKey 约定为游戏根目录绝对路径（app 层据此关联游戏与聚合周/月）
 * - 跨进程：同一时间只有一个引擎进程在玩；app 层只读该文件
 */
object PlayTimeTracker {

    private const val FILE_NAME = "play_time.json"
    private const val FLUSH_INTERVAL_MS = 60_000L
    private const val DAILY_KEEP_DAYS = 120
    private val DAY_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private val lock = Any()
    private var appContext: Context? = null
    private var activeKey: String? = null
    private var activeStartElapsed = 0L
    private var handler: Handler? = null

    /** 前台开始计时（重复调用同 key 无副作用）。 */
    @JvmStatic
    fun onForeground(context: Context, gameKey: String) {
        if (gameKey.isEmpty()) return
        synchronized(lock) {
            val app = context.applicationContext
            appContext = app
            if (activeKey == gameKey) return
            activeKey?.let { commitLocked(it) }
            activeKey = gameKey
            activeStartElapsed = SystemClock.elapsedRealtime()
            val h = handler ?: Handler(Looper.getMainLooper()).also { handler = it }
            h.removeCallbacksAndMessages(null)
            scheduleFlushLocked()
        }
    }

    /** 后台/退出：累计本次前台时长并落盘。 */
    @JvmStatic
    fun onBackground(gameKey: String) {
        synchronized(lock) {
            if (activeKey != gameKey) return
            commitLocked(gameKey)
            activeKey = null
            handler?.removeCallbacksAndMessages(null)
        }
    }

    private fun scheduleFlushLocked() {
        handler?.postDelayed({
            synchronized(lock) {
                activeKey?.let {
                    commitLocked(it)
                    scheduleFlushLocked()
                }
            }
        }, FLUSH_INTERVAL_MS)
    }

    private fun commitLocked(key: String) {
        val context = appContext ?: return
        val delta = SystemClock.elapsedRealtime() - activeStartElapsed
        activeStartElapsed = SystemClock.elapsedRealtime()
        if (delta <= 0) return
        try {
            val data = readData(context)
            val entry = data.optJSONObject(key) ?: JSONObject().also {
                data.put(key, it)
            }
            val today = DAY_FORMAT.format(Date())
            val daily = entry.optJSONObject("d") ?: JSONObject().also { entry.put("d", it) }
            entry.put("t", entry.optLong("t") + delta)
            daily.put(today, daily.optLong(today) + delta)
            pruneOldDays(daily)
            writeData(context, data)
        } catch (t: Throwable) {
            android.util.Log.w("PlayTime", "commit failed", t)
        }
    }

    /** 只保留最近 DAILY_KEEP_DAYS 天的日粒度明细。 */
    private fun pruneOldDays(daily: JSONObject) {
        val cutoff = System.currentTimeMillis() - DAILY_KEEP_DAYS * 86_400_000L
        val cutoffDay = DAY_FORMAT.format(Date(cutoff))
        val stale = daily.keys().asSequence()
            .filter { it < cutoffDay }
            .toList()
        for (k in stale) daily.remove(k)
    }

    private fun dataFile(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun readData(context: Context): JSONObject = try {
        JSONObject(dataFile(context).readText())
    } catch (t: Throwable) {
        JSONObject()
    }

    private fun writeData(context: Context, data: JSONObject) {
        val file = dataFile(context)
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(data.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(data.toString())
            tmp.delete()
        }
    }
}
