package com.core.engine

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 游戏画面沉浸式全屏（状态栏 + 底部导航栏都自动隐藏，用户上滑/下滑可临时唤出）。
 *
 * 之前的实现只用 `LAYOUT_STABLE | LAYOUT_FULLSCREEN`（只是把内容铺到状态栏下面，
 * 状态栏本身仍然显示），所以真机上一直能看到手机状态栏。这里统一改成沉浸式：
 * - API 30+：`WindowInsetsControllerCompat.hide(systemBars())` +
 *   `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`（滑动临时显示，不驻留）；
 * - 低版本：`SYSTEM_UI_FLAG_IMMERSIVE_STICKY | HIDE_NAVIGATION | FULLSCREEN | LAYOUT_*`。
 *
 * 注意与 `FLAG_FORCE_NOT_FULLSCREEN`（128）互斥：有些宿主误加该 flag 会把状态栏钉住，
 * 这里会主动清除。
 */
object ImmersiveMode {

    /** 应用沉浸式全屏；可反复调用（获得焦点、对话框关闭后重新隐藏系统栏）。 */
    @JvmStatic
    fun apply(activity: Activity?) {
        val act = activity ?: return
        val window: Window = act.window ?: return
        // 误加的 FLAG_FORCE_NOT_FULLSCREEN 会强制显示状态栏，先清掉
        window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
        val decor: View = window.decorView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = ViewCompat.getWindowInsetsController(decor)
            if (controller != null) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    /**
     * 只用 legacy `systemUiVisibility` 压制系统栏（不调 `setDecorFitsSystemWindows`）。
     *
     * 专给 **NativeActivity 型宿主**（Artemis clean 内核走 `ArtemisLauncherBaseActivity`）：
     * 调 `WindowCompat.setDecorFitsSystemWindows(false)` 会让窗口按全屏坐标重新布局，
     * native surface 尺寸随之变化，实测会引起启动异常退出；`systemUiVisibility` 这条路
     * 与引擎里既有的 5894（HIDE_NAVIGATION|FULLSCREEN|LAYOUT_*|IMMERSIVE_STICKY）一致，安全。
     */
    @JvmStatic
    fun applyLegacy(activity: Activity?) {
        val act = activity ?: return
        val window: Window = act.window ?: return
        window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    /**
     * 「顽固」沉浸式：给 **NativeActivity / MIUI** 这类会把系统栏放回来的宿主用。
     *
     * 与 [applyLegacy] 的区别：
     * 1. 同时设置刘海屏适配（`LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`）——MIUI 上不声明
     *    刘海适配时，隐藏状态栏常常被系统撤销（画面还会被刘海区挤下去）；
     * 2. 注册 `OnSystemUiVisibilityChangeListener`，一旦系统把栏放回来就**立刻再压**；
     * 3. 创建后按 [intervalMs] 定时重试 [attempts] 次，覆盖启动动画/转屏/焦点切换阶段。
     *
     * 全程只用 legacy `systemUiVisibility`，不调 `setDecorFitsSystemWindows`——
     * 后者会让窗口按全屏坐标重排，native surface 尺寸变化会引起启动异常退出（实测）。
     */
    @JvmStatic
    @JvmOverloads
    fun applySticky(
        activity: Activity?,
        attempts: Int = 8,
        intervalMs: Long = 500L,
        applyCutoutMode: Boolean = false,
    ) {
        val act = activity ?: return
        val window: Window = act.window ?: return
        // 刘海/挖孔适配：MIUI 上这是沉浸式能生效的前提之一；但改 window attributes 会让
        // NativeActivity 的 surface 重排（实测 Artemis 会直接退出），默认关闭、按宿主需要开。
        if (applyCutoutMode) {
            runCatching {
                val lp = window.attributes
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes = lp
            }
        }
        val decor: View = window.decorView
        // 系统把栏放回来 → 立即再压（STICKY 之外的兜底）
        runCatching {
            decor.setOnSystemUiVisibilityChangeListener { vis ->
                val hidden = (vis and View.SYSTEM_UI_FLAG_FULLSCREEN) != 0 &&
                    (vis and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0
                if (!hidden) applyLegacy(act)
            }
        }
        applyLegacy(act)
        applyInsetsOnly(act)
        for (i in 1 until attempts) {
            decor.postDelayed({
                applyLegacy(act)
                applyInsetsOnly(act)
            }, intervalMs * i)
        }
    }

    /**
     * 只用 API30+ 的 WindowInsetsController 请求隐藏系统栏，**不改 decor-fits、不改窗口属性**
     * （因此不会触发 NativeActivity 的 surface 重排）。
     *
     * 用途：MIUI 上 legacy `systemUiVisibility` 会被忽略，但 insets controller 的
     * `hide(systemBars())` 有时仍被尊重——作为 legacy 之外的补充手段。
     */
    @JvmStatic
    fun applyInsetsOnly(activity: Activity?) {
        val act = activity ?: return
        val decor = act.window?.decorView ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val controller = ViewCompat.getWindowInsetsController(decor) ?: return
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /**
     * [applyLegacy] 的延时重试版（立即 + 300ms + 1000ms）。
     */
    @JvmStatic
    fun applyLegacyAndReapply(activity: Activity?) {
        val act = activity ?: return
        applyLegacy(act)
        val decor = act.window?.decorView ?: return
        decor.postDelayed({ applyLegacy(act) }, 300L)
        decor.postDelayed({ applyLegacy(act) }, 1000L)
    }

    /**
     * 让窗口退回「安全区」布局：不铺到状态栏/导航栏下面（清掉 LAYOUT_FULLSCREEN 类 flag 与
     * FLAG_FULLSCREEN），窗口内容完整落在系统栏之间。
     *
     * 用途：NativeActivity 型宿主（Artemis clean 内核）不接受沉浸式压制时，游戏原本按
     * `LAYOUT_FULLSCREEN` 铺满整屏，顶部 ~80px 被状态栏盖住（用户反馈「画面超出手机屏幕范围」）。
     * 退回安全区后引擎把 1280x720 画布按剩余区域等比缩放居中，整幅画面都在屏幕内。
     */
    @JvmStatic
    fun fitToSafeArea(activity: Activity?) {
        val act = activity ?: return
        val window: Window = act.window ?: return
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN
                or WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, true)
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    /** [fitToSafeArea] 的延时重试版（立即 + 300ms + 1000ms）。 */
    @JvmStatic
    fun fitToSafeAreaAndReapply(activity: Activity?) {
        val act = activity ?: return
        fitToSafeArea(act)
        val decor = act.window?.decorView ?: return
        decor.postDelayed({ fitToSafeArea(act) }, 300L)
        decor.postDelayed({ fitToSafeArea(act) }, 1000L)
    }

    /**
     * 对**某个 View 所属的窗口**隐藏系统栏（`systemUiVisibility` / insets controller 都是
     * 按窗口生效的）。
     *
     * 用途：Artemis 宿主用独立 WindowManager 窗口做按键捕获（虚拟鼠标模式下的光标窗、
     * 面板展开时的面板窗）。这些窗口一旦去掉 `FLAG_NOT_FOCUSABLE` 取得焦点，**系统栏就会
     * 重新出现**——因为沉浸式 flag 没设在那份额外的窗口上（用户反馈：切到虚拟鼠标模式后
     * 状态栏/底部栏藏不住了）。所以每次让它们可聚焦时都要补一次。
     */
    @JvmStatic
    fun applyToView(view: android.view.View?) {
        val v = view ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = ViewCompat.getWindowInsetsController(v)
            if (controller != null) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            v.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    /**
     * 延时再应用一次：部分 ROM 在窗口焦点变化/启动动画后会重新显示状态栏，
     * 创建时调用本方法（立即 + 300ms + 1000ms 三次）能稳定压住。
     */
    @JvmStatic
    fun applyAndReapply(activity: Activity?) {
        val act = activity ?: return
        apply(act)
        val decor = act.window?.decorView ?: return
        decor.postDelayed({ apply(act) }, 300L)
        decor.postDelayed({ apply(act) }, 1000L)
    }
}
