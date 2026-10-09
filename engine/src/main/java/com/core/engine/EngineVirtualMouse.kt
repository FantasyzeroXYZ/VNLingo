package com.core.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
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
 * 统一虚拟鼠标抽象（手柄/键盘通用鼠标模拟，ONS/KRKR/Artemis 三宿主共用）。
 *
 * 各宿主只提供三件事，引擎实现零改动：
 * - surfaceProvider：游戏画面视图（坐标换算基准；NativeActivity 宿主给 decorView）
 * - clickInjector：在画面视图坐标处合成一次点击——视图系宿主走
 *   dispatchTouchEvent（ONS=SDL Surface、KRKR=Cocos GLSurfaceView），
 *   NativeActivity 宿主走内核触摸注入 JNI（Artemis=InjectHostTouch）
 * - overlayClickHandler：光标悬停在面板控件上时优先点平台控件
 *
 * 按键绑定走 [VirtualMouseBindings]（设置页可改，手柄/键盘通用）：
 * - 方向键（默认键盘方向键/手柄十字键）移动光标，四向等步进
 * - 确认（默认手柄 A / 键盘回车 / DPAD_CENTER）：先问 overlayClickHandler
 *   （命中面板控件即消费），未命中才在光标处合成触摸点击注入游戏画面
 * - 长按连续移动：内部重复引擎驱动（按下 350ms 后起、60ms/步、逐步加速），
 *   不依赖系统按键重复——adb 注入等无重复事件序列同样可长按连移
 * - 覆盖层纯绘制、不可点击：未按方向/确认前零干扰
 *
 * 按键拦截在 Activity.dispatchKeyEvent 最前端（handleKey 返回 true 即消费），
 * 其余按键全部透传给游戏（原生手柄/键盘支持不受影响）。
 */
class EngineVirtualMouse(
    overlay: ViewGroup,
    private val surfaceProvider: () -> View?,
    private val clickInjector: ClickInjector,
    private val overlayClickHandler: OverlayClickHandler? = null,
) : View(overlay.context) {

    private companion object {
        const val REPEAT_DELAY_MS = 350L
        const val REPEAT_INTERVAL_MS = 60L
        const val CLICK_COOLDOWN_MS = 250L
        const val STEP_BASE = 14f
        const val STEP_MAX = 48f

        /** 单步距离：随按住时长（内部重复步数）线性加速，四向一致。 */
        fun stepDistance(stepsTaken: Int, density: Float): Float =
            (STEP_BASE * (1f + stepsTaken / 8f)).coerceAtMost(STEP_MAX) * density
    }

    /** 光标统一绘制器（与 Tyrano/RPGM 光标层同一套样式）。 */
    private val cursorRenderer = VirtualMouseCursorRenderer(resources.displayMetrics.density)
    private val repeatHandler = Handler(Looper.getMainLooper())

    var cursorX = 0f
        private set
    var cursorY = 0f
        private set

    /** 点击冷却：部分手柄/ROM 会重复下发确认键（双设备/按键抖动），窗口内只点一次。 */
    private var lastClickAtMs = 0L
    private var cursorVisible = false

    /** 当前按住的方向动作（-1 = 无）；内部重复引擎按它连续步进。 */
    private var heldAction = -1
    private var heldSteps = 0

    private val repeatStepper = object : Runnable {
        override fun run() {
            if (heldAction < 0) return
            if (!isAttachedToWindow) {
                stopHold()
                return
            }
            stepCursor(heldAction, stepDistance(++heldSteps, resources.displayMetrics.density))
            invalidate()
            repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        isClickable = false
        isFocusable = false
        VirtualMouseBindings.refresh(context)
        overlay.addView(this)
    }

    /** 返回 true 表示该按键已被虚拟鼠标消费（Activity.dispatchKeyEvent 应直接返回）。 */
    fun handleKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val action = resolveAction(code) ?: return false
            ensureCursor()
            if (action == VirtualMouseBindings.ACTION_CONFIRM) {
                clickAtCursor()
            } else {
                startHold(action)
                invalidate()
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) {
            val wasHeld = heldAction >= 0 && VirtualMouseBindings.matches(heldAction, code)
            if (wasHeld) stopHold()
            // 抬起相位一律消费：防止方向/确认键的 UP 泄漏给游戏（半按下状态）
            return wasHeld || cursorVisible
        }
        // 系统长按重复事件：连续移动由内部重复引擎驱动，吞掉即可
        return heldAction >= 0 || cursorVisible
    }

    private fun resolveAction(keyCode: Int): Int? {
        for (action in intArrayOf(
            VirtualMouseBindings.ACTION_UP,
            VirtualMouseBindings.ACTION_DOWN,
            VirtualMouseBindings.ACTION_LEFT,
            VirtualMouseBindings.ACTION_RIGHT,
            VirtualMouseBindings.ACTION_CONFIRM,
        )) {
            if (VirtualMouseBindings.matches(action, keyCode)) return action
        }
        return null
    }

    /**
     * 摇杆 / 十字键移动光标（Activity.dispatchGenericMotionEvent 前置调用）。
     *
     * 处理三类轴：
     * - **十字键 HAT 轴 `AXIS_HAT_X/Y`**（实测部分手柄 D-pad 不产生按键事件，只报 HAT）：
     *   按下立即走一步，**按住期间由内部重复引擎持续移动**（修复：原先只在轴变化时走一步，
     *   长按不动），轴回中即停；看门狗兜底防释放事件丢失；
     * - 左摇杆 `AXIS_X/Y` → 连续移动（带死区）；
     * - 右摇杆 `AXIS_RX/RY` → 同左摇杆（部分手柄/映射把它当鼠标用）。
     */
    fun handleMotion(event: MotionEvent): Boolean {
        // HAT 轴可能来自 SOURCE_JOYSTICK 或 SOURCE_DPAD，这里不按 source 过滤
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (hatX == 0f && hatY == 0f) {
            if (hatActive) { // 轴回中：停掉连续移动，并消费这次释放
                stopHatMove()
                return true
            }
        } else {
            lastHatEventMs = android.os.SystemClock.uptimeMillis()
            val (sx, sy) = toScreenVector(hatX to hatY)
            if (!hatActive || sx != hatDx || sy != hatDy) {
                ensureCursor()
                val step = HAT_STEP_DP * resources.displayMetrics.density
                cursorX = (cursorX + sx * step).coerceIn(0f, width.toFloat())
                cursorY = (cursorY + sy * step).coerceIn(0f, height.toFloat())
                invalidate()
                startHatMove(sx, sy)
            }
            return true
        }
        if ((event.source and InputDevice.SOURCE_CLASS_JOYSTICK) == 0) {
            logUnhandledAxes(event)
            return false
        }
        var x = event.getAxisValue(MotionEvent.AXIS_X)
        var y = event.getAxisValue(MotionEvent.AXIS_Y)
        // 左摇杆无输入时回退右摇杆（同一根轴映射不同手柄有差异）
        if (x == 0f && y == 0f) {
            x = event.getAxisValue(MotionEvent.AXIS_RX)
            y = event.getAxisValue(MotionEvent.AXIS_RY)
        }
        val deadzone = 0.15f
        if (-deadzone < x && x < deadzone && -deadzone < y && y < deadzone) return cursorVisible
        ensureCursor()
        val (rx, ry) = toScreenVector(x to y)
        val speed = 14f * resources.displayMetrics.density
        cursorX = (cursorX + rx * speed).coerceIn(0f, width.toFloat())
        cursorY = (cursorY + ry * speed).coerceIn(0f, height.toFloat())
        invalidate()
        return true
    }

    /** HAT 轴长按连续移动的状态与重复引擎（修复「长按方向键不连续移动」）。 */
    private var hatActive = false
    private var hatDx = 0f
    private var hatDy = 0f
    private var lastHatEventMs = 0L
    private val HAT_STEP_DP = 12f
    private val HAT_WATCHDOG_MS = 1200L

    private fun startHatMove(dx: Float, dy: Float) {
        hatActive = true
        hatDx = dx
        hatDy = dy
        repeatHandler.removeCallbacks(hatStepper)
        repeatHandler.postDelayed(hatStepper, REPEAT_DELAY_MS)
    }

    private fun stopHatMove() {
        hatActive = false
        hatDx = 0f
        hatDy = 0f
        repeatHandler.removeCallbacks(hatStepper)
    }

    /** HAT 按住期间的连续移动；轴事件断流超过看门狗时长即自停。 */
    private val hatStepper = object : Runnable {
        override fun run() {
            if (!hatActive || !isAttachedToWindow) {
                stopHatMove()
                return
            }
            if (android.os.SystemClock.uptimeMillis() - lastHatEventMs > HAT_WATCHDOG_MS) {
                stopHatMove()
                return
            }
            val step = HAT_STEP_DP * resources.displayMetrics.density
            cursorX = (cursorX + hatDx * step).coerceIn(0f, width.toFloat())
            cursorY = (cursorY + hatDy * step).coerceIn(0f, height.toFloat())
            invalidate()
            repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    /** HAT 轴移动的最小间隔（防同一次方向被重复上报导致连跳）。 */
    private var lastHatMoveMs = 0L
    private val HAT_MOVE_MIN_INTERVAL_MS = 120L

    /** 诊断：收到的非摇杆轴事件记一条（限频 1s），用于定位「手柄方向键没反应」。 */
    private var lastAxisLogMs = 0L
    private fun logUnhandledAxes(event: MotionEvent) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastAxisLogMs < 1000) return
        lastAxisLogMs = now
        com.core.diag.DiagLog.debug(
            "vmouse",
            "motion source=0x" + Integer.toHexString(event.source) +
                " X=" + event.getAxisValue(MotionEvent.AXIS_X) +
                " Y=" + event.getAxisValue(MotionEvent.AXIS_Y) +
                " RX=" + event.getAxisValue(MotionEvent.AXIS_RX) +
                " RY=" + event.getAxisValue(MotionEvent.AXIS_RY) +
                " HAT_X=" + event.getAxisValue(MotionEvent.AXIS_HAT_X) +
                " HAT_Y=" + event.getAxisValue(MotionEvent.AXIS_HAT_Y) +
                " Z=" + event.getAxisValue(MotionEvent.AXIS_Z),
        )
    }

    private fun startHold(action: Int) {
        if (heldAction == action) return
        stopHold()
        heldAction = action
        heldSteps = 0
        stepCursor(action, stepDistance(0, resources.displayMetrics.density))
        repeatHandler.postDelayed(repeatStepper, REPEAT_DELAY_MS)
    }

    private fun stopHold() {
        heldAction = -1
        heldSteps = 0
        repeatHandler.removeCallbacks(repeatStepper)
    }

    private fun stepCursor(action: Int, distance: Float) {
        val (dx, dy) = toScreenVector(actionVector(action))
        cursorX += dx * distance
        cursorY += dy * distance
        clampCursor()
    }

    /** 动作的设备框方向向量：D-pad/键盘方向键 keycode 相对设备机身为绝对方向。 */
    private fun actionVector(action: Int): Pair<Float, Float> = when (action) {
        VirtualMouseBindings.ACTION_UP -> 0f to -1f
        VirtualMouseBindings.ACTION_DOWN -> 0f to 1f
        VirtualMouseBindings.ACTION_LEFT -> -1f to 0f
        VirtualMouseBindings.ACTION_RIGHT -> 1f to 0f
        else -> 0f to 0f
    }

    /**
     * 设备框 → 屏幕框：横竖屏不一致时（横屏游戏跑在竖屏机身上），方向键
     * keycode 的设备框朝向与屏幕朝向差 90°——不旋转会「按上往右」。按
     * display.rotation 把位移向量旋到屏幕坐标系，保证按下的方向 =
     * 光标在屏幕上的移动方向。
     *
     * 仅模拟器启用：模拟器把 PC 方向键按设备框转发（需补偿）；真机手柄
     * （Xbox 等）的 D-pad 天然是「所按即屏幕方向」，真机补偿反而把它转回
     * 竖屏朝向（真机 alioth + Xbox 手柄实测）。
     */
    private fun toScreenVector(device: Pair<Float, Float>): Pair<Float, Float> {
        if (!runningOnEmulator()) return device
        val (dx, dy) = device
        return when (display?.rotation ?: android.view.Surface.ROTATION_0) {
            android.view.Surface.ROTATION_90 -> dy to -dx
            android.view.Surface.ROTATION_180 -> -dx to -dy
            android.view.Surface.ROTATION_270 -> -dy to dx
            else -> dx to dy
        }
    }

    /** 模拟器判定（指纹/机型含 generic/sdk_；真机恒 false）。 */
    private fun runningOnEmulator(): Boolean {
        val f = android.os.Build.FINGERPRINT ?: ""
        val m = android.os.Build.MODEL ?: ""
        return f.contains("generic", ignoreCase = true) ||
            f.contains("emulator", ignoreCase = true) ||
            m.startsWith("sdk_")
    }

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

    /** 隐藏光标（切回触摸模式时调用）：光标隐藏后方向/确认不再被消费，长按态一并复位。 */
    fun hideCursor() {
        stopHold()
        cursorVisible = false
        invalidate()
    }

    /** 立即显示光标（切进鼠标模式时调用）：悬停画面中心，无需先按方向键。 */
    fun showCursor() {
        ensureCursor()
    }

    private fun clampCursor() {
        val maxX = width.toFloat()
        val maxY = height.toFloat()
        if (maxX <= 0 || maxY <= 0) return
        cursorX = cursorX.coerceIn(0f, maxX)
        cursorY = cursorY.coerceIn(0f, maxY)
    }

    private fun clickAtCursor() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastClickAtMs < CLICK_COOLDOWN_MS) return
        lastClickAtMs = now
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
        // 光标样式与 Tyrano/RPGM 的光标层统一（同尺寸/同描边/同投影）
        cursorRenderer.draw(canvas, cursorX, cursorY)
    }
}
