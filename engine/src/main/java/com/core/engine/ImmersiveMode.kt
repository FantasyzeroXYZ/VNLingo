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
