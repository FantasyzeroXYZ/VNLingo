package com.core.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/**
 * 虚拟鼠标光标的**统一绘制**（各引擎共用一套样式）。
 *
 * 样式取自 Tyrano/RPGM 的 WebView 光标层（`VirtualMouseLayer.drawCursor`）——标准指针箭头：
 * 竖直左缘 + 内凹缺口 + 甩尾，白色填充 + 深色描边 + 轻微投影；滚轮/高亮态填充黄色。
 * KRKR / ONS / Artemis 的 `EngineVirtualMouse` 也改用它，避免各引擎光标粗细/大小/有无投影不一致。
 *
 * 用法：每个宿主持有一个实例（内含画笔与路径，勿跨线程共享），在 `onDraw` 里调 [draw]。
 */
class VirtualMouseCursorRenderer(private val density: Float) {

    private companion object {
        /** 与 Tyrano 一致的基准缩放（dp）。 */
        const val SCALE_DP = 1.2f

        // 箭头几何（以 1 为单位的相对坐标，乘以 scale 后即为像素）
        const val TIP_Y = 0f
        const val LEFT_Y = 13.6f          // 左缘（竖直）
        const val NOTCH_X = 3.3f
        const val NOTCH_Y = 10.6f          // 内凹
        const val TAIL_OUT_X = 5.6f
        const val TAIL_OUT_Y = 15.8f       // 尾部外缘
        const val TAIL_IN_X = 7.7f
        const val TAIL_IN_Y = 14.8f        // 尾部内缘
        const val TAIL_ROOT_X = 5.6f
        const val TAIL_ROOT_Y = 9.9f       // 尾根
        const val BARB_X = 10.3f
        const val BARB_Y = 9.6f            // 右侧倒钩
    }

    private val path = Path()
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 0.9f
        color = 0xE6141414.toInt()
        strokeJoin = Paint.Join.ROUND
    }

    /** 在 (x, y) 画光标；[highlight] = 滚轮/特殊态（黄色填充）。 */
    fun draw(canvas: Canvas, x: Float, y: Float, highlight: Boolean = false) {
        val s = SCALE_DP * density
        path.rewind()
        path.moveTo(x, y + TIP_Y * s)
        path.lineTo(x, y + LEFT_Y * s)
        path.lineTo(x + NOTCH_X * s, y + NOTCH_Y * s)
        path.lineTo(x + TAIL_OUT_X * s, y + TAIL_OUT_Y * s)
        path.lineTo(x + TAIL_IN_X * s, y + TAIL_IN_Y * s)
        path.lineTo(x + TAIL_ROOT_X * s, y + TAIL_ROOT_Y * s)
        path.lineTo(x + BARB_X * s, y + BARB_Y * s)
        path.close()

        canvas.save()
        canvas.translate(0.9f * density, 1.5f * density)
        shadowPaint.color = 0x59000000
        canvas.drawPath(path, shadowPaint)
        canvas.restore()

        fillPaint.color = if (highlight) 0xFFFFD34D.toInt() else Color.WHITE
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
    }
}
