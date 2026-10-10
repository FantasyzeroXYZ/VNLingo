package com.core.engine

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * 虚拟鼠标的**输入核心**（与宿主解耦，见「统一抽象」）。
 *
 * 只负责「把按键/摇杆信号翻译成移动/点击意图」：
 * - 按键：`VirtualMouseBindings` 五动作（上/下/左/右/确认），**自带长按重复**（350ms 后每 60ms 一步，
 *   步长随按住时长递增到上限）——不依赖系统的按键重复事件；
 * - 十字键 **HAT 轴**（部分手柄 D-pad 不报按键只报 `AXIS_HAT_X/Y`）：
 *   按下即起步，**按住期间由内部重复引擎持续移动**（本次修复：原先只在轴变化时走一步，
 *   长按不动），轴回中即停；另有看门狗兜底（HAT 释放事件丢失时 1.2s 后自停）；
 * - 左/右摇杆 `AXIS_X/Y`、`AXIS_RX/RY`：按死区连续移动。
 *
 * 宿主只需实现 [Target]：怎么画光标、怎么发点击、光标当前在哪。
 */
class VirtualMouseInput(
    private val target: Target,
    private val density: Float,
    private val context: android.content.Context? = null,
    /** 宿主是否需要处理轴事件（WebView 层由 WebView 自己消费触摸，不接管轴时可返回 false）。 */
    private val handleAxes: Boolean = true,
) {

    /** 宿主适配：光标位置与移动/点击落地方式由宿主决定。 */
    interface Target {
        /** 光标是否已显示（隐藏时按键也应消费，避免泄漏给游戏）。 */
        fun isCursorVisible(): Boolean
        /** 显示光标（首次移动/点击时调用）。 */
        fun ensureCursor()
        /** 屏幕像素位移（已按屏幕坐标系，宿主自行 clamp）。 */
        fun moveBy(dx: Float, dy: Float)
        /** 在光标处点击。 */
        fun click()
        /** 请求重绘（View 型宿主用；WebView 型可为空实现）。 */
        fun requestRender() {}
    }

    private companion object {
        const val REPEAT_DELAY_MS = 350L
        const val REPEAT_INTERVAL_MS = 60L
        // 点击键（A/CONFIRM）长按连点：先按一次，按住 CLICK_REPEAT_DELAY_MS 后开始以
        // CLICK_REPEAT_INTERVAL_MS 连点。系统长按重复事件被 repeatCount==0 过滤掉了，
        // 所以必须自己做——否则「按住 A 只点一次」（用户反馈：手柄连按无效）。
        const val CLICK_REPEAT_DELAY_MS = 320L
        const val CLICK_REPEAT_INTERVAL_MS = 120L
        const val STEP_BASE = 14f
        const val STEP_MAX = 48f
        const val HAT_STEP_DP = 12f
        const val HAT_WATCHDOG_MS = 1200L
        const val AXIS_DEADZONE = 0.15f
    }

    private val handler = Handler(Looper.getMainLooper())

    // ---- 按键长按重复 ----
    private var heldAction = -1
    private var heldSteps = 0

    // ---- 点击键长按连点 ----
    private var clickHeldCode = -1
    private val clickStepper = object : Runnable {
        override fun run() {
            if (clickHeldCode < 0) return
            target.click()
            target.requestRender()
            android.util.Log.i("YukiVmouse", "click repeat tick code=$clickHeldCode")
            handler.postDelayed(this, CLICK_REPEAT_INTERVAL_MS)
        }
    }

    private fun startClickRepeat(code: Int) {
        clickHeldCode = code
        handler.removeCallbacks(clickStepper)
        handler.postDelayed(clickStepper, CLICK_REPEAT_DELAY_MS)
    }

    private fun stopClickRepeat() {
        clickHeldCode = -1
        handler.removeCallbacks(clickStepper)
    }

    // ---- HAT 轴长按连续移动 ----
    private var hatActive = false
    private var hatDx = 0f
    private var hatDy = 0f
    private var lastHatEventMs = 0L

    /** 返回 true = 该事件已被虚拟鼠标消费（宿主不要再透传游戏）。 */
    fun handleKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val action = resolveAction(code) ?: return false
            target.ensureCursor()
            if (action == VirtualMouseBindings.ACTION_CONFIRM) {
                target.click()
                // 按住不放 → 连点（长按连按；松开在 ACTION_UP 里停）
                startClickRepeat(code)
            } else {
                startHold(action)
                target.requestRender()
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) {
            if (clickHeldCode >= 0 && VirtualMouseBindings.matches(VirtualMouseBindings.ACTION_CONFIRM, code)) {
                stopClickRepeat()
                return true
            }
            val wasHeld = heldAction >= 0 && VirtualMouseBindings.matches(heldAction, code)
            if (wasHeld) stopHold()
            // 抬起相位一律消费：防止方向/确认键的 UP 泄漏给游戏（半按下状态）
            return wasHeld || target.isCursorVisible()
        }
        // 系统长按重复事件：连续移动由内部重复引擎驱动，吞掉即可
        return heldAction >= 0 || target.isCursorVisible()
    }

    /** 返回 true = 轴事件已被消费。 */
    fun handleMotion(event: MotionEvent): Boolean {
        if (!handleAxes) return false
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        // 1) 十字键 HAT 轴：回中即停（释放事件也消费掉，避免漏给游戏）
        if (hatX == 0f && hatY == 0f) {
            if (hatActive) {
                stopHatMove()
                return true
            }
        } else {
            lastHatEventMs = SystemClock.uptimeMillis()
            val (sx, sy) = toScreenVector(hatX to hatY)
            if (!hatActive || sx != hatDx || sy != hatDy) {
                // 新方向（或首次按下）：立即走一步，再交给重复引擎
                target.ensureCursor()
                val step = HAT_STEP_DP * density
                target.moveBy(sx * step, sy * step)
                target.requestRender()
                startHatMove(sx, sy)
            }
            return true
        }

        // 2) 摇杆：非摇杆源不处理（记一条诊断，便于定位「方向键没反应」）
        if ((event.source and InputDevice.SOURCE_CLASS_JOYSTICK) == 0) {
            logUnhandledAxes(event)
            return false
        }
        var x = event.getAxisValue(MotionEvent.AXIS_X)
        var y = event.getAxisValue(MotionEvent.AXIS_Y)
        if (x == 0f && y == 0f) { // 左摇杆无输入时回退右摇杆
            x = event.getAxisValue(MotionEvent.AXIS_RX)
            y = event.getAxisValue(MotionEvent.AXIS_RY)
        }
        if (-AXIS_DEADZONE < x && x < AXIS_DEADZONE && -AXIS_DEADZONE < y && y < AXIS_DEADZONE) {
            return target.isCursorVisible()
        }
        target.ensureCursor()
        val (rx, ry) = toScreenVector(x to y)
        val speed = STEP_BASE * density
        target.moveBy(rx * speed, ry * speed)
        target.requestRender()
        return true
    }

    /** 宿主退出/隐藏光标时调用：停掉所有重复任务。 */
    fun cancel() {
        stopHold()
        stopHatMove()
        stopClickRepeat()
    }

    private fun startHold(action: Int) {
        if (heldAction == action) return
        stopHold()
        heldAction = action
        heldSteps = 0
        moveByAction(action, stepDistance(0))
        handler.postDelayed(repeatStepper, REPEAT_DELAY_MS)
    }

    private fun stopHold() {
        heldAction = -1
        heldSteps = 0
        handler.removeCallbacks(repeatStepper)
    }

    private val repeatStepper = object : Runnable {
        override fun run() {
            if (heldAction < 0) return
            moveByAction(heldAction, stepDistance(++heldSteps))
            target.requestRender()
            handler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    private fun startHatMove(dx: Float, dy: Float) {
        hatActive = true
        hatDx = dx
        hatDy = dy
        handler.removeCallbacks(hatStepper)
        handler.postDelayed(hatStepper, REPEAT_DELAY_MS)
    }

    private fun stopHatMove() {
        hatActive = false
        hatDx = 0f
        hatDy = 0f
        handler.removeCallbacks(hatStepper)
    }

    /** HAT 按住期间的连续移动（看门狗：轴事件断流 1.2s 自动停，防释放事件丢失卡住）。 */
    private val hatStepper = object : Runnable {
        override fun run() {
            if (!hatActive) return
            if (SystemClock.uptimeMillis() - lastHatEventMs > HAT_WATCHDOG_MS) {
                stopHatMove()
                return
            }
            val step = HAT_STEP_DP * density
            target.moveBy(hatDx * step, hatDy * step)
            target.requestRender()
            handler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    private fun moveByAction(action: Int, distance: Float) {
        val (dx, dy) = toScreenVector(actionVector(action))
        target.moveBy(dx * distance, dy * distance)
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

    private fun actionVector(action: Int): Pair<Float, Float> = when (action) {
        VirtualMouseBindings.ACTION_UP -> 0f to -1f
        VirtualMouseBindings.ACTION_DOWN -> 0f to 1f
        VirtualMouseBindings.ACTION_LEFT -> -1f to 0f
        VirtualMouseBindings.ACTION_RIGHT -> 1f to 0f
        else -> 0f to 0f
    }

    /** 长按越久步长越大（14px → 48px 封顶，按 density 缩放）。 */
    private fun stepDistance(stepsTaken: Int): Float =
        (STEP_BASE * (1f + stepsTaken / 8f)).coerceAtMost(STEP_MAX) * density

    /**
     * 设备框 → 屏幕框：横竖屏不一致时（横屏游戏跑在竖屏机身上），方向键的设备框朝向
     * 与屏幕朝向差 90°——不旋转会「按上往右」。真机手柄天然是「所按即屏幕方向」，
     * 因此仅在模拟器按 display.rotation 旋转（与既有引擎实现同款判定）。
     */
    private fun toScreenVector(device: Pair<Float, Float>): Pair<Float, Float> {
        if (!isEmulator()) return device
        val rotation = try {
            val wm = context?.getSystemService(android.content.Context.WINDOW_SERVICE)
            (wm as? android.view.WindowManager)?.defaultDisplay?.rotation ?: 0
        } catch (t: Throwable) {
            0
        }
        return when (rotation) {
            1 -> -device.second to device.first
            3 -> device.second to -device.first
            2 -> -device.first to -device.second
            else -> device
        }
    }

    private fun isEmulator(): Boolean =
        android.os.Build.FINGERPRINT.contains("generic") ||
            android.os.Build.MODEL.contains("sdk", ignoreCase = true) ||
            android.os.Build.HARDWARE.contains("goldfish") ||
            android.os.Build.HARDWARE.contains("ranchu")

    private var lastAxisLogMs = 0L
    private fun logUnhandledAxes(event: MotionEvent) {
        val now = SystemClock.uptimeMillis()
        if (now - lastAxisLogMs < 1000) return
        lastAxisLogMs = now
        com.core.diag.DiagLog.debug(
            "vmouse",
            "motion source=0x" + Integer.toHexString(event.source) +
                " X=" + event.getAxisValue(MotionEvent.AXIS_X) +
                " Y=" + event.getAxisValue(MotionEvent.AXIS_Y) +
                " HAT_X=" + event.getAxisValue(MotionEvent.AXIS_HAT_X) +
                " HAT_Y=" + event.getAxisValue(MotionEvent.AXIS_HAT_Y),
        )
    }
}
