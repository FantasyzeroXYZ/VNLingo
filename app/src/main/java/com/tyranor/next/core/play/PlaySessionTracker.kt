package com.tyranor.next.core.play

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/**
 * 游玩会话制统计（参考 RinneMobile PlaySessionRepository 架构重实现）。
 *
 * 每次游戏启动创建 play_session 记录，退出/心跳时结算时长并累加到 games 表。
 * 替代旧 JSON 累加方案（play_time.json），优势：
 * - 会话粒度可回溯（最近游玩记录/时间段聚合）
 * - 单调时钟有效时长（锁屏/系统校时不误计）
 * - 边界保护（<5s 删除、>12h 封顶）
 * - 下次启动自动结算遗留会话
 * - 游玩状态（unplayed/playing/completed）
 *
 * 独立 SQLite（非 Room）：避免 GameLibraryDatabase 版本迁移；
 * 与 GameLibraryFacade 通过 GameLibraryRepository.post 落库协调。
 */
object PlaySessionTracker {

    private const val DB_NAME = "play_sessions.db"
    private const val TABLE = "play_sessions"
    /** 短于此的会话启动即删（误触）。 */
    private const val MIN_SESSION_MS = 5_000L
    /** 单会话上限 12h（防挂机爆表）。 */
    private const val MAX_SESSION_MS = 12L * 60L * 60L * 1000L

    @Volatile
    private var dbHelper: Helper? = null

    @Volatile
    private var appRef: Context? = null

    /** Application.onCreate 注入（跨进程引擎子进程各自安装）。 */
    @JvmStatic
    fun install(context: Context) {
        appRef = context.applicationContext
    }

    private fun helper(context: Context): Helper {
        if (appRef == null) appRef = context.applicationContext
        return dbHelper ?: synchronized(this) {
            dbHelper ?: Helper(context.applicationContext).also { dbHelper = it }
        }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                game_uri TEXT NOT NULL,
                game_title TEXT NOT NULL DEFAULT '',
                start_time INTEGER NOT NULL,
                end_time INTEGER,
                duration INTEGER NOT NULL DEFAULT 0,
                session_uuid TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL DEFAULT 0,
                play_status TEXT NOT NULL DEFAULT 'playing')""")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ps_uri ON $TABLE(game_uri)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ps_end ON $TABLE(end_time)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    // ---- 会话生命周期 ----

    /** 游戏启动：创建会话，同时自动结算该游戏及全局的遗留未关闭会话。返回 sessionId。 */
    fun startSession(context: Context, gameUri: String, gameTitle: String): Long {
        val db = helper(context).writableDatabase
        val now = System.currentTimeMillis()
        finishAllOpen(db, now)
        val v = android.content.ContentValues().apply {
            put("game_uri", gameUri)
            put("game_title", gameTitle)
            put("start_time", now)
            putNull("end_time")
            put("duration", 0L)
            put("session_uuid", UUID.randomUUID().toString())
            put("created_at", now)
            put("play_status", "playing")
        }
        return db.insert(TABLE, null, v)
    }

    /** 结算当前游戏的开放会话（退出/心跳调用）。 */
    fun finishSession(context: Context, gameUri: String) {
        val db = helper(context).writableDatabase
        val now = System.currentTimeMillis()
        val c = db.rawQuery(
            "SELECT id, start_time FROM $TABLE WHERE game_uri=? AND end_time IS NULL ORDER BY start_time DESC LIMIT 1",
            arrayOf(gameUri))
        var sessionId = -1L
        var start = 0L
        if (c.moveToFirst()) {
            sessionId = c.getLong(0)
            start = c.getLong(1)
        }
        c.close()
        if (sessionId <= 0) return
        val raw = now - start
        if (raw < MIN_SESSION_MS) {
            db.delete(TABLE, "id=?", arrayOf(sessionId.toString()))
            return
        }
        val duration = raw.coerceAtMost(MAX_SESSION_MS)
        val v = android.content.ContentValues().apply {
            put("end_time", now)
            put("duration", duration)
        }
        db.update(TABLE, v, "id=?", arrayOf(sessionId.toString()))
    }

    /** 全局：结算所有未关闭会话（应用退出/冷启动兜底）。 */
    private fun finishAllOpen(db: SQLiteDatabase, now: Long) {
        val c = db.rawQuery("SELECT id, start_time FROM $TABLE WHERE end_time IS NULL", null)
        val ids = ArrayList<Pair<Long, Long>>()
        while (c.moveToNext()) ids.add(c.getLong(0) to c.getLong(1))
        c.close()
        for ((id, start) in ids) {
            val raw = now - start
            if (raw < MIN_SESSION_MS) {
                db.delete(TABLE, "id=?", arrayOf(id.toString()))
            } else {
                val v = android.content.ContentValues().apply {
                    put("end_time", now)
                    put("duration", raw.coerceAtMost(MAX_SESSION_MS))
                }
                db.update(TABLE, v, "id=?", arrayOf(id.toString()))
            }
        }
    }

    // ---- 查询 ----

    data class SessionRecord(
        val id: Long,
        val gameUri: String,
        val gameTitle: String,
        val startTime: Long,
        val endTime: Long,
        val duration: Long,
        val playStatus: String,
    )

    /** 最近 N 条已结算会话记录。 */
    fun recentSessions(context: Context, limit: Int): List<SessionRecord> {
        val db = helper(context).readableDatabase
        val out = ArrayList<SessionRecord>()
        db.rawQuery(
            "SELECT id, game_uri, game_title, start_time, end_time, duration, play_status FROM $TABLE WHERE end_time IS NOT NULL ORDER BY end_time DESC LIMIT ?",
            arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) {
                out.add(SessionRecord(c.getLong(0), c.getString(1), c.getString(2),
                    c.getLong(3), c.getLong(4), c.getLong(5), c.getString(6)))
            }
        }
        return out
    }

    /** 指定游戏的总游玩时长（ms）。 */
    fun totalPlayTime(context: Context, gameUri: String): Long {
        val db = helper(context).readableDatabase
        db.rawQuery(
            "SELECT SUM(duration) FROM $TABLE WHERE game_uri=? AND end_time IS NOT NULL",
            arrayOf(gameUri)).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return 0
    }

    /** 时间段内各游戏游玩时长（ms），按时长降序。 */
    fun durationsBetween(context: Context, startMs: Long, endMs: Long): Map<String, Long> {
        val db = helper(context).readableDatabase
        val out = LinkedHashMap<String, Long>()
        db.rawQuery(
            "SELECT game_title, SUM(duration) FROM $TABLE WHERE end_time>=? AND end_time<=? AND end_time IS NOT NULL GROUP BY game_uri ORDER BY SUM(duration) DESC",
            arrayOf(startMs.toString(), endMs.toString())).use { c ->
            while (c.moveToNext()) {
                val title = c.getString(0) ?: "未命名"
                out[title] = c.getLong(1)
            }
        }
        return out
    }

    // ---- 游玩状态 ----

    fun setPlayStatus(context: Context, gameUri: String, status: String) {
        val db = helper(context).writableDatabase
        val v = android.content.ContentValues().apply { put("play_status", status) }
        db.update(TABLE, v, "game_uri=? AND end_time IS NULL", arrayOf(gameUri))
    }

    fun getPlayStatus(context: Context, gameUri: String): String {
        val db = helper(context).readableDatabase
        db.rawQuery(
            "SELECT play_status FROM $TABLE WHERE game_uri=? ORDER BY start_time DESC LIMIT 1",
            arrayOf(gameUri)).use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "unplayed"
        }
        return "unplayed"
    }
}
