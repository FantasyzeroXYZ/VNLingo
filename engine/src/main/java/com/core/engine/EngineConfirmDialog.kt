package com.core.engine

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 引擎内通用「悬浮确认框」：圆角卡片 + 药丸按钮，视觉与 Launcher 的对话框一致
 * （engine 模块不依赖 app 的 LauncherDialogFactory，这里复刻同一套样式，
 * 与 TyranoActivity/SDLActivity/KRKRCall 里既有的三份实现同源）。
 *
 * 主要用途：手机返回/退出键 → 「是否退出游戏」（用户要求系统返回键也要弹悬浮框）。
 * 显示时会临时把系统栏放出（对话框需要可读），关闭后恢复沉浸式。
 */
object EngineConfirmDialog {

    /**
     * 弹确认框。[onConfirm] 在用户点确定后回调（对话框已 dismiss）。
     * 已 finish/destroy 的 Activity 直接忽略（防 WindowManager.BadTokenException）。
     */
    @JvmStatic
    fun show(
        activity: Activity?,
        title: CharSequence,
        message: CharSequence,
        confirmText: CharSequence,
        cancelText: CharSequence? = null,
        onConfirm: (() -> Unit)? = null,
    ) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) return
        val colors = EngineThemeColors.fromIntent(activity.intent)
        val density = activity.resources.displayMetrics.density
        fun dp(v: Float): Int = (v * density + 0.5f).toInt()

        val dialog = Dialog(activity).apply {
            setCancelable(true)
            setCanceledOnTouchOutside(true)
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22f), dp(22f), dp(22f), dp(22f))
            background = GradientDrawable().apply {
                setColor(colors.card)
                cornerRadius = dp(20f).toFloat()
            }
        }
        root.addView(TextView(activity).apply {
            text = title
            setTextColor(colors.text)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        })
        root.addView(TextView(activity).apply {
            text = message
            setTextColor(colors.textMuted)
            textSize = 13f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) }
        })

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18f) }
        }
        val cancel = cancelText ?: activity.getString(R.string.engine_cancel)
        val cancelBtn = Button(activity).apply {
            text = cancel
            isAllCaps = false
            setTextColor(colors.primary)
            background = GradientDrawable().apply {
                setColor(colors.card)
                cornerRadius = dp(18f).toFloat()
                setStroke(dp(1f), colors.primary)
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(36f)).apply { weight = 1f }
        }
        val confirmBtn = Button(activity).apply {
            text = confirmText
            isAllCaps = false
            setTextColor(colors.onPrimary)
            background = GradientDrawable().apply {
                setColor(colors.primary)
                cornerRadius = dp(18f).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(36f)).apply {
                weight = 1f
                leftMargin = dp(10f)
            }
        }
        buttonRow.addView(cancelBtn)
        buttonRow.addView(confirmBtn)
        root.addView(buttonRow)

        cancelBtn.setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            dialog.dismiss()
            runCatching { onConfirm?.invoke() }
        }
        dialog.setContentView(root)
        runCatching { dialog.show() }.onFailure {
            runCatching { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show() }
            return
        }
        // 固定宽度 252dp（与 Launcher 一致），窗口透明圆角
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            val width = dp(252f).coerceAtMost(
                activity.resources.displayMetrics.widthPixels - dp(32f),
            )
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.4f)
        }
        dialog.setOnDismissListener { ImmersiveMode.apply(activity) }
    }

    /** 便捷入口：引擎默认文案的「是否退出游戏」。 */
    @JvmStatic
    fun showExitGame(activity: Activity?, onConfirm: Runnable) {
        val ctx = activity ?: return
        show(
            activity = ctx,
            title = ctx.getString(R.string.engine_exit_game),
            message = ctx.getString(R.string.engine_exit_game_message),
            confirmText = ctx.getString(R.string.engine_confirm),
            onConfirm = { onConfirm.run() },
        )
    }
}
