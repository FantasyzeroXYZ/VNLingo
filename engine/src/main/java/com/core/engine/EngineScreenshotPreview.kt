package com.core.engine

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 引擎内通用「截图预览悬浮框」：截图后先弹预览（圆角卡片 + 截图 + 两个药丸按钮），
 * 由用户决定「保存」还是「不保存直接关闭」——不再自动落盘
 * （用户要求：误截的图不该直接进截图库）。
 *
 * 视觉与 [EngineConfirmDialog] 同源（圆角卡片 + 药丸按钮，对齐 Launcher 对话框样式）；
 * 显示时临时放出系统栏（可读），关闭后恢复沉浸式。
 */
object EngineScreenshotPreview {

    /**
     * 弹预览框。[onSave] 在用户点「保存」后回调（对话框已 dismiss，位图交还调用方落盘）；
     * 点「不保存」或点框外取消 → 位图就地回收。位图为 null/已回收直接忽略。
     */
    @JvmStatic
    fun show(activity: Activity?, bmp: Bitmap?, onSave: Runnable) {
        if (bmp == null || bmp.isRecycled) return
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            bmp.recycle()
            return
        }
        val colors = EngineThemeColors.fromIntent(activity.intent)
        val density = activity.resources.displayMetrics.density
        fun dp(v: Float): Int = (v * density + 0.5f).toInt()

        val dialog = Dialog(activity).apply {
            setCancelable(true)
            setCanceledOnTouchOutside(true)
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            background = GradientDrawable().apply {
                setColor(colors.card)
                cornerRadius = dp(20f).toFloat()
            }
        }
        root.addView(TextView(activity).apply {
            text = activity.getString(R.string.engine_screenshot_preview_title)
            setTextColor(colors.text)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        })
        val image = ImageView(activity).apply {
            // 横屏游戏截图是宽图：按宽适配、限制最大高，避免竖屏上把按钮挤出屏幕
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            background = GradientDrawable().apply {
                // 图片底衬用固定黑：两种主题下截图边缘都清晰，也避免引入新色板字段
                setColor(0xFF000000.toInt())
                cornerRadius = dp(12f).toFloat()
            }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(12f)
            }
        }
        root.addView(image)
        // 尺寸兜底（maximumHeight 需 API 26，这里手动按比例算）：预览不超过
        // 对话框宽度 × 45% 屏高，按钮在任何方向都可见
        val dialogWidth = dp(420f).coerceAtMost(
            activity.resources.displayMetrics.widthPixels - dp(32f),
        ) - dp(32f) // 减卡片内边距
        val maxH = (activity.resources.displayMetrics.heightPixels * 0.45f).toInt()
        val scale = minOf(
            dialogWidth.toFloat() / bmp.width,
            maxH.toFloat() / bmp.height,
            1f,
        )
        image.layoutParams = LinearLayout.LayoutParams(
            (bmp.width * scale).toInt().coerceAtLeast(1),
            (bmp.height * scale).toInt().coerceAtLeast(1),
        ).apply { topMargin = dp(12f) }
        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16f) }
        }
        val discardBtn = Button(activity).apply {
            text = activity.getString(R.string.engine_screenshot_discard)
            isAllCaps = false
            setTextColor(colors.primary)
            background = GradientDrawable().apply {
                setColor(colors.card)
                cornerRadius = dp(18f).toFloat()
                setStroke(dp(1f), colors.primary)
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(36f)).apply { weight = 1f }
        }
        val saveBtn = Button(activity).apply {
            text = activity.getString(R.string.engine_screenshot_save)
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
        buttonRow.addView(discardBtn)
        buttonRow.addView(saveBtn)
        root.addView(buttonRow)

        discardBtn.setOnClickListener {
            dialog.dismiss()
            if (!bmp.isRecycled) bmp.recycle() // 不保存：位图就地回收
        }
        saveBtn.setOnClickListener {
            dialog.dismiss()
            runCatching { onSave.run() }
        }
        dialog.setContentView(root)
        val shown = runCatching { dialog.show() }.isSuccess
        if (!shown) {
            // 弹不出（极端环境）→ 退回旧行为直接保存，别让截图丢失
            runCatching { onSave.run() }
            return
        }
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            val width = dp(420f).coerceAtMost(
                activity.resources.displayMetrics.widthPixels - dp(32f),
            )
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.5f)
        }
        dialog.setOnDismissListener { ImmersiveMode.apply(activity) }
        // 兜底：框被系统回收（cancel/dismiss 任何路径）且未走保存 → 回收位图
        dialog.setOnCancelListener { if (!bmp.isRecycled) bmp.recycle() }
        Toast.makeText(
            activity,
            activity.getString(R.string.engine_screenshot_preview_hint),
            Toast.LENGTH_SHORT,
        ).show()
    }
}
