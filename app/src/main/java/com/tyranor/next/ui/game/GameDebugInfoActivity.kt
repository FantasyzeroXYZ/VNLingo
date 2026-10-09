package com.tyranor.next.ui.game

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.tyranor.next.R
import com.core.diag.DiagLog
import com.tyranor.next.core.game.launch.GameRunRecord
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 条目选项 →「调试信息」：展示**最近一次运行**的记录与相关日志，用于分析启动失败/白屏。
 *
 * 内容：
 *  1. 最近一次运行：引擎、启动目标、启动文件、补丁选择、发起时间、结果（失败码/详情）；
 *  2. 运行时日志：`DiagLog` 环形缓冲尾部（引擎/面板/加载链路的关键节点）；
 *  3. 崩溃日志：`filesDir/crash/` 最新一份（存在时）。
 *
 * 纯只读展示，提供「复制」「分享」，方便贴给开发者排查。
 */
class GameDebugInfoActivity : Activity() {

    private lateinit var body: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getStringExtra(EXTRA_URI) ?: ""
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val header = TextView(this).apply {
            text = getString(R.string.game_debug_info) + (if (title.isEmpty()) "" else " · $title")
            setTextColor(Color.WHITE)
            textSize = 18f
        }
        root.addView(header)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = getString(R.string.game_debug_copy)
            setOnClickListener { copyToClipboard() }
        })
        actions.addView(Button(this).apply {
            text = getString(R.string.game_debug_share)
            setOnClickListener { share() }
        })
        root.addView(actions)

        body = TextView(this).apply {
            setTextColor(0xFFDDDDDD.toInt())
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(
            ScrollView(this).apply { addView(body) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ),
        )
        setContentView(root)
        body.text = buildReport(uri)
    }

    private fun buildReport(uri: String): String {
        val sb = StringBuilder()
        val rec = GameRunRecord.read(this, uri)
        sb.append("=== ").append(getString(R.string.game_debug_last_run)).append(" ===\n")
        sb.append("title    : ").append(rec["title"]).append('\n')
        sb.append("engine   : ").append(rec["engine"]).append('\n')
        sb.append("target   : ").append(rec["target"]).append('\n')
        sb.append("file     : ").append(rec["file"]).append('\n')
        sb.append("patch    : ").append(rec["patch"]).append('\n')
        sb.append("at       : ").append(time(rec["at"])).append('\n')
        sb.append("result   : ").append(rec["result"]).append('\n')
        sb.append("detail   : ").append(rec["detail"]).append('\n')
        sb.append("failAt   : ").append(time(rec["failAt"])).append('\n')
        sb.append("uri      : ").append(uri).append('\n')

        sb.append("\n=== runtime log (DiagLog tail) ===\n")
        runCatching {
            DiagLog.readTail(this, 400).forEach { sb.append(it).append('\n') }
        }.onFailure { sb.append("(读取失败: ").append(it.message).append(")\n") }

        sb.append("\n=== crash log (latest) ===\n")
        val crash = latestCrashFile()
        if (crash == null) {
            sb.append("(无)\n")
        } else {
            sb.append(crash.name).append('\n')
            runCatching {
                crash.readLines().takeLast(300).forEach { sb.append(it).append('\n') }
            }.onFailure { sb.append("(读取失败: ").append(it.message).append(")\n") }
        }
        return sb.toString()
    }

    private fun latestCrashFile(): File? = runCatching {
        val dir = File(filesDir, "crash")
        dir.listFiles()?.filter { it.isFile }?.maxByOrNull { it.lastModified() }
    }.getOrNull()

    private fun time(ms: String?): String {
        val v = ms?.toLongOrNull() ?: return "-"
        if (v <= 0L) return "-"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(v))
    }

    private fun copyToClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("vnlingo-debug", body.text))
        Toast.makeText(this, R.string.game_debug_copied, Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, body.text.toString())
        }
        runCatching { startActivity(Intent.createChooser(i, getString(R.string.game_debug_share))) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_URI = "uri"
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context, uri: String, title: String): Intent =
            Intent(context, GameDebugInfoActivity::class.java)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_TITLE, title)
    }
}
