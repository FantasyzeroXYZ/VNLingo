package com.core.ons

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

/** A 键点击回调（Java SAM 友好）。 */
fun interface ClickInjector {
    fun inject(surface: View, x: Float, y: Float)
}

/**
 * 光标点击的覆盖层优先命中回调（Java SAM 友好）：
 * 返回 true 表示光标处命中面板/悬浮球等平台控件，已消费（不再注入游戏画面）。
 */
fun interface OverlayClickHandler {
    fun onClickAt(x: Float, y: Float): Boolean
}

/**
 * 手柄方向键虚拟鼠标（ONS/SDL 系）。
 *
 * - D-pad（BUTTON_DPAD_*，兼容 KEYCODE_DPAD_*）移动光标，长按 repeat 加速
 * - 左摇杆（AXIS_X/Y）连续移动光标（面板展开时也可用，与 D-pad 滚动分工）
 * - A 键（BUTTON_A）：先问 overlayClickHandler（命中面板控件即消费），
 *   未命中才在光标处合成触摸点击注入 SDL Surface
 * - 覆盖层纯绘制、不可点击：未按 D-pad/摇杆前零干扰
 *
 * 按键拦截在 Activity.dispatchKeyEvent 最前端（handleKey 返回 true 即消费），
 * 其余按键全部透传给游戏（SDL 原生手柄支持不受影响）。
 */
class OnsVirtualMouse(
    overlay: ViewGroup,
    private val surfaceProvider: () -> View?,
    private val clickInjector: ClickInjector,
    private val overlayClickHandler: OverlayClickHandler? = null,
) : View(overlay.context) {

    private companion object {
        // 手柄 D-pad 经系统按键布局映射为 KEYCODE_DPAD_*（无 BUTTON_DPAD_* 常量）
        val DPAD_LEFT_CODES = intArrayOf(KeyEvent.KEYCODE_DPAD_LEFT)
        val DPAD_RIGHT_CODES = intArrayOf(KeyEvent.KEYCODE_DPAD_RIGHT)
        val DPAD_UP_CODES = intArrayOf(KeyEvent.KEYCODE_DPAD_UP)
        val DPAD_DOWN_CODES = intArrayOf(KeyEvent.KEYCODE_DPAD_DOWN)
        const val CLICK_KEY = KeyEvent.KEYCODE_BUTTON_A
        const val STEP_BASE = 12f
        const val STEP_MAX = 72f
    }

    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.BLACK
    }
    private val arrow = Path()

    var cursorX = 0f
        private set
    var cursorY = 0f
        private set
    private var cursorVisible = false

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        isClickable = false
        isFocusable = false
        overlay.addView(this)
    }

    /** 返回 true 表示该按键已被虚拟鼠标消费（Activity.dispatchKeyEvent 应直接返回）。 */
    fun handleKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        val action = event.action
        if (code == CLICK_KEY) {
            if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                ensureCursor()
                clickAtCursor()
            }
            return cursorVisible
        }
        val step = when {
            isKey(code, DPAD_LEFT_CODES) -> -1
            isKey(code, DPAD_RIGHT_CODES) -> 1
            isKey(code, DPAD_UP_CODES) -> -2
            isKey(code, DPAD_DOWN_CODES) -> 2
            else -> return false
        }
        if (action != KeyEvent.ACTION_DOWN) return true
        ensureCursor()
        val distance = stepDistance(event.repeatCount)
        when {
            step == -1 -> cursorX -= distance
            step == 1 -> cursorX += distance
            step == -2 -> cursorY -= distance
            else -> cursorY += distance
        }
        clampCursor()
        invalidate()
        return true
    }

    private fun isKey(code: Int, codes: IntArray): Boolean = codes.contains(code)

    /** 左摇杆连续移动光标（Activity.dispatchGenericMotionEvent 前置调用）。 */
    fun handleMotion(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_CLASS_JOYSTICK) == 0) return false
        val x = event.getAxisValue(MotionEvent.AXIS_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y)
        val deadzone = 0.15f
        if (-deadzone < x && x < deadzone && -deadzone < y && y < deadzone) return cursorVisible
        ensureCursor()
        val speed = 14f * resources.displayMetrics.density
        cursorX = (cursorX + x * speed).coerceIn(0f, width.toFloat())
        cursorY = (cursorY + y * speed).coerceIn(0f, height.toFloat())
        invalidate()
        return true
    }

    private fun stepDistance(repeatCount: Int): Float =
        minOf(STEP_MAX, STEP_BASE * (1f + repeatCount / 4f)) * resources.displayMetrics.density

    private fun ensureCursor() {
        if (cursorVisible) return
        val surface = surfaceProvider()
        cursorX = if (surface != null) surface.width / 2f else width / 2f
        cursorY = if (surface != null) surface.height / 2f else height / 2f
        // 光标状态以覆盖层（窗口）坐标系为准；surface 居中时两者中心一致
        val ol = IntArray(2)
        getLocationOnScreen(ol)
        val sl = IntArray(2)
        surface?.getLocationOnScreen(sl)
        if (surface != null) {
            cursorX += (sl[0] - ol[0]).toFloat()
            cursorY += (sl[1] - ol[1]).toFloat()
        }
        cursorVisible = true
        clampCursor()
        invalidate()
    }

    /** 隐藏光标（切回触摸模式时调用）：光标隐藏后 D-pad/A 不再被消费。 */
    fun hideCursor() {
        cursorVisible = false
        invalidate()
    }

    private fun clampCursor() {
        val maxX = width.toFloat()
        val maxY = height.toFloat()
        if (maxX <= 0 || maxY <= 0) return
        cursorX = cursorX.coerceIn(0f, maxX)
        cursorY = cursorY.coerceIn(0f, maxY)
    }

    private fun clickAtCursor() {
        // 覆盖层优先：光标悬停在悬浮球/面板控件上时点平台控件，不穿进游戏画面
        if (overlayClickHandler != null && overlayClickHandler.onClickAt(cursorX, cursorY)) return
        val surface = surfaceProvider() ?: return
        val surfaceLocation = IntArray(2)
        surface.getLocationOnScreen(surfaceLocation)
        val overlayLocation = IntArray(2)
        getLocationOnScreen(overlayLocation)
        val surfaceX = cursorX + (overlayLocation[0] - surfaceLocation[0]).toFloat()
        val surfaceY = cursorY + (overlayLocation[1] - surfaceLocation[1]).toFloat()
        if (surfaceX < 0 || surfaceY < 0 ||
            surfaceX > surface.width || surfaceY > surface.height) return
        clickInjector.inject(surface, surfaceX, surfaceY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!cursorVisible) return
        val size = 22f * resources.displayMetrics.density
        arrow.reset()
        arrow.moveTo(cursorX, cursorY)
        arrow.lineTo(cursorX, cursorY + size)
        arrow.lineTo(cursorX + size * 0.28f, cursorY + size * 0.72f)
        arrow.lineTo(cursorX + size * 0.62f, cursorY + size * 1.12f)
        arrow.lineTo(cursorX + size * 0.88f, cursorY + size * 0.92f)
        arrow.lineTo(cursorX + size * 0.52f, cursorY + size * 0.56f)
        arrow.lineTo(cursorX + size * 0.86f, cursorY + size * 0.56f)
        arrow.close()
        cursorPaint.color = Color.WHITE
        canvas.drawPath(arrow, cursorPaint)
        canvas.drawPath(arrow, outlinePaint)
    }
}
