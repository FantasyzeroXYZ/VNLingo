package com.core.diag

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 运行时诊断日志（调试日志页的「运行时日志」数据源）：
 * 内存环形缓冲（最近 [MEM_CAP] 行）+ filesDir/diag/diag.log 落盘
 * （单文件超 [FILE_CAP] 字节时砍掉旧的二分之一）。
 *
 * 关键路径埋点用 [debug]（翻译/查词/TTS/同步/引擎启动等）；写入全程 try 包裹
 * 绝不抛出。跨进程（:kirikiri2 等引擎子进程）并发追加为诊断用途，允许行级交错。
 */
object DiagLog {

    private const val MEM_CAP = 500
    private const val FILE_CAP = 256 * 1024
    private const val DIR = "diag"
    private const val FILE = "diag.log"

    private val lock = Any()
    private val mem = ArrayDeque<String>()

    @Volatile
    private var enabled = true

    @JvmStatic
    fun debug(tag: String, message: String) {
        if (!enabled) return
        try {
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val line = "[$stamp $tag] $message"
            synchronized(lock) {
                mem.addLast(line)
                while (mem.size > MEM_CAP) mem.removeFirst()
            }
            appendFile(line)
        } catch (ignored: Throwable) {
        }
    }

    /** 读取落盘日志的尾部（调试日志页查看用）；最多 [maxLines] 行。 */
    @JvmStatic
    fun readTail(context: Context, maxLines: Int): List<String> {
        return try {
            val f = file(context)
            if (!f.exists()) emptyList()
            else synchronized(lock) {
                val all = f.readText().split('\n')
                if (all.size <= maxLines) all else all.subList(all.size - maxLines, all.size)
            }
        } catch (ignored: Throwable) {
            emptyList()
        }
    }

    @JvmStatic
    fun file(context: Context): File = File(File(context.filesDir, DIR), FILE)

    @JvmStatic
    fun clear(context: Context) {
        synchronized(lock) { mem.clear() }
        runCatching { file(context).delete() }
    }

    @Throws(java.io.IOException::class)
    private fun appendFile(line: String) {
        // context 由调用方路径携带不可行（object 无 context）：落盘走调用方可选传入，
        // 这里用进程级缓存的应用上下文（首次 debug 时经 install 注入）。
        val ctx = appContext ?: return
        val f = file(ctx)
        val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
        if (f.exists() && f.length() > FILE_CAP) {
            // 超限砍掉旧的二分之一：整文件重写（单进程内 synchronized 保护）
            val all = runCatching { f.readText().split('\n') }.getOrDefault(emptyList())
            val keep = if (all.size > 2) all.subList(all.size / 2, all.size) else all
            synchronized(lock) { f.writeText(keep.joinToString("\n")) }
        }
        synchronized(lock) {
            java.io.FileOutputStream(f, true).use { it.write((line + "\n").toByteArray()) }
        }
    }

    @Volatile
    private var appContext: Context? = null

    /** Application.onCreate 注入应用上下文（主进程与引擎子进程各自安装）。 */
    @JvmStatic
    fun install(context: Context) {
        appContext = context.applicationContext
        enabled = context.getSharedPreferences("diag_settings", Context.MODE_PRIVATE)
            .getBoolean("diag_enabled", true)
    }

    @JvmStatic
    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences("diag_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("diag_enabled", value).apply()
    }

    @JvmStatic
    fun isEnabled(): Boolean = enabled

}
