package com.core.engine

import android.graphics.Canvas
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
 * 虚拟鼠标的**视图宿主**（ONS / KRKR / Artemis 共用）。
 *
 * 输入语义（按键映射、长按重复、连点、HAT/摇杆、模拟器旋转、诊断）**全部**在
 * [VirtualMouseInput] 里，本类只做三件事：
 * - 画光标（[VirtualMouseCursorRenderer]，与 Tyrano/RPGM 光标层同一样式）；
 * - 维护光标位置/可见性（中心起步、边界 clamp）；
 * - 把"点击"落到宿主提供的注入方式上（[ClickInjector] / [OverlayClickHandler]）。
 *
 * 宿主只需提供：
 * - `surfaceProvider`：游戏画面视图（坐标换算基准；NativeActivity 宿主给 decorView）
 * - `clickInjector`：在画面视图坐标处合成一次点击（视图系走 dispatchTouchEvent，
 *   NativeActivity 宿主走内核触摸注入 JNI）
 * - `overlayClickHandler`：光标悬停在面板控件/侧键上时优先点平台控件
 *
 * 说明：2026-10 之前本类自己又实现了一份按键/重复逻辑，导致"共享核心修了、这里没修"
 * （虚拟鼠标点击键长按连按就漏过一次）。现在统一走 [VirtualMouseInput]，避免再分叉。
 */
class EngineVirtualMouse(
    overlay: ViewGroup,
    private val surfaceProvider: () -> View?,
    private val clickInjector: ClickInjector,
    private val overlayClickHandler: OverlayClickHandler? = null,
) : View(overlay.context), VirtualMouseInput.Target, VirtualMouseController {

    /** 光标统一绘制器（与 Tyrano/RPGM 光标层同一套样式）。 */
    private val cursorRenderer = VirtualMouseCursorRenderer(resources.displayMetrics.density)

    private val input = VirtualMouseInput(
        this,
        resources.displayMetrics.density,
        overlay.context,
        handleAxes = true,
    )

    var cursorX = 0f
        private set
    var cursorY = 0f
        private set

    private var cursorVisible = false
    /** 显示光标时窗口尚未 layout（宽高为 0）→ 待在 onSizeChanged 里补居中。 */
    private var needsRecenter = false

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        isClickable = false
        isFocusable = false
        VirtualMouseBindings.refresh(context)
        overlay.addView(this)
    }

    /** 返回 true 表示该按键已被虚拟鼠标消费（Activity.dispatchKeyEvent 应直接返回）。 */
    override fun handleKey(event: KeyEvent): Boolean = input.handleKey(event)

    /** 摇杆 / 十字键 HAT 移动（Activity.dispatchGenericMotionEvent 前置调用）。 */
    override fun handleMotion(event: MotionEvent): Boolean = input.handleMotion(event)

    /** 宿主退出/隐藏光标时调用：停掉所有重复任务。 */
    fun cancel() {
        input.cancel()
    }

    /** 隐藏光标（切回触摸模式时调用）：光标隐藏后方向/确认不再被消费，长按态一并复位。 */
    override fun hideCursor() {
        input.cancel()
        cursorVisible = false
        invalidate()
    }

    /** 立即显示光标（切进鼠标模式时调用）：悬停画面中心，无需先按方向键。 */
    override fun showCursor() {
        ensureCursor()
    }

    // ---- VirtualMouseInput.Target ----

    override fun isCursorVisible(): Boolean = cursorVisible

    override fun ensureCursor() {
        if (cursorVisible) return
        cursorVisible = true
        // 启动时（宿主按持久化模式补 showCursor）本窗可能还没 layout：宽高为 0 时居中
        // 会算成 (0,0)，光标停在左上角看起来像"又错乱了"。记下来，等 onSizeChanged 再居中。
        needsRecenter = width <= 0 || height <= 0
        if (!needsRecenter) recenterCursor()
        clampCursor()
        invalidate()
    }

    /** 光标居中到游戏画面；surface 未就绪时退回本窗中心。 */
    private fun recenterCursor() {
        val surface = surfaceProvider()
        cursorX = if (surface != null && surface.width > 0) surface.width / 2f else width / 2f
        cursorY = if (surface != null && surface.height > 0) surface.height / 2f else height / 2f
        // 光标状态以覆盖层（窗口）坐标系为准；surface 居中时两者中心一致
        if (surface != null) {
            val ol = IntArray(2)
            getLocationOnScreen(ol)
            val sl = IntArray(2)
            surface.getLocationOnScreen(sl)
            cursorX += (sl[0] - ol[0]).toFloat()
            cursorY += (sl[1] - ol[1]).toFloat()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!cursorVisible) return
        if (needsRecenter) {
            needsRecenter = false
            recenterCursor()
        }
        clampCursor()
        invalidate()
    }

    override fun moveBy(dx: Float, dy: Float) {
        cursorX += dx
        cursorY += dy
        clampCursor()
        invalidate()   // 触摸拖动移动光标后必须重绘，否则光标看着不动
    }

    // ── 触摸当触控板用（用户要求） ──────────────────────────────────────────
    // 虚拟鼠标模式下打开光标窗的触摸接收后：
    //   * 单指拖动 → 相对移动光标（像笔记本触控板，不需要按到光标上）
    //   * 单指点按 → 在**光标当前所在位置**触发一次点击（不是点哪里点哪里）
    // 因此这两个手势都会被本层消费，不再透传给游戏；想直接触摸游戏就切回触摸模式
    // （右缘「点击模式」键）。
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchLastX = 0f
    private var touchLastY = 0f
    private var touchMoved = false
    private val touchSlop by lazy {
        android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!cursorVisible) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                touchLastX = event.x
                touchLastY = event.y
                touchMoved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - touchLastX
                val dy = event.y - touchLastY
                touchLastX = event.x
                touchLastY = event.y
                if (Math.abs(event.x - touchDownX) > touchSlop ||
                    Math.abs(event.y - touchDownY) > touchSlop) {
                    touchMoved = true
                }
                if (dx != 0f || dy != 0f) moveBy(dx, dy)
                return true
            }
            MotionEvent.ACTION_UP -> {
                // 没怎么移动 = 点按 → 在光标处点击
                if (!touchMoved) click()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }

    override fun click() {
        clickAtCursor()
    }

    override fun requestRender() {
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
        // 覆盖层优先：光标悬停在悬浮球/面板控件/侧键上时点平台控件，不穿进游戏画面
        if (overlayClickHandler != null) {
            // **必须换算成屏幕坐标**：面板/侧键窗口是独立窗口（Artemis 的面板是
            // gravity=BOTTOM 的独立窗，展开后还会把系统栏带出来 → 光标窗原点相对屏幕
            // 发生偏移）。cursorX/Y 是光标窗的**局部**坐标，而面板侧命中判定
            // （OnsExtractPanel.dispatchCursorClick → hitView）用的是 getLocationOnScreen
            // 的**屏幕**坐标；不换算就会整体偏移 → 鼠标点面板/侧键落空，
            // 表现即"剧情文本框点不掉"（WebView 版 VirtualMouseLayer 早已这样换算）。
            val overlayLocation = IntArray(2)
            getLocationOnScreen(overlayLocation)
            val screenX = overlayLocation[0] + cursorX
            val screenY = overlayLocation[1] + cursorY
            // 诊断：光标窗原点与换算后的屏幕坐标（面板/侧键命中失败时一眼看出偏移量）
            android.util.Log.i("YukiVmouse", "click cursor=(" + Math.round(cursorX) + ","
                    + Math.round(cursorY) + ") origin=(" + overlayLocation[0] + ","
                    + overlayLocation[1] + ") screen=(" + Math.round(screenX) + ","
                    + Math.round(screenY) + ")")
            if (overlayClickHandler.onClickAt(screenX, screenY)) return
        }
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
