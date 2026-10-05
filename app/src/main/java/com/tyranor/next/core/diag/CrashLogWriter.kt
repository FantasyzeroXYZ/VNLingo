package com.tyranor.next.core.diag

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志落盘：未捕获异常先同步写一份堆栈到 filesDir/crash/ 再交还系统原
 * handler（保持崩溃对话框/杀进程行为不变）。
 *
 * 用途：现场设备（非 adb 环境）上的崩溃可让用户直接把日志文件发回来定位；
 * 引擎子进程（:kirikiri2 等）同样经 Application.onCreate 安装，引擎崩溃也有迹可查。
 * 只保留最近 [KEEP] 份；写日志本身全程 try 包裹，绝不二次抛出。
 */
object CrashLogWriter {

    private const val KEEP = 3
    private const val DIR = "crash"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write(appContext, thread, throwable)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
                ?: Runtime.getRuntime().exit(2)
        }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(dir, "crash_$stamp.txt")
        val process = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            "unknown"
        }
        out.writeText(
            buildString {
                append("time: ").append(stamp).append('\n')
                append("process: ").append(process).append(" (pid=").append(android.os.Process.myPid()).append(")\n")
                append("thread: ").append(thread.name).append('\n')
                append("version: ").append(runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrDefault("unknown")).append('\n')
                append("android: ").append(Build.VERSION.RELEASE).append(" (sdk ").append(Build.VERSION.SDK_INT).append(")\n")
                append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                append('\n')
                append(android.util.Log.getStackTraceString(throwable))
            }
        )
        // 只留最近 KEEP 份（文件名含时间戳，字典序即时间序）
        val logs = dir.listFiles { f -> f.name.startsWith("crash_") } ?: return
        if (logs.size > KEEP) {
            logs.sortByDescending { it.name }
            for (i in KEEP until logs.size) logs[i].delete()
        }
    }
}
