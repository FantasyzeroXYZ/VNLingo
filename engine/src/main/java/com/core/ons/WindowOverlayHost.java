package com.core.ons;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * WindowManager 承载的全屏覆盖层（NativeActivity 等游戏独占窗口 surface、
 * Java 视图不参与合成的宿主用）：面板视图加入本覆盖层即可绘制在游戏之上。
 *
 * 触摸放行规则：覆盖层内没有可见内容时置 FLAG_NOT_TOUCHABLE，触摸全部穿透
 * 给游戏；任一面板可见时恢复可触摸（此时面板区域外触摸不透传，呈模态）。
 * 面板类在开合时回调 {@link #syncTouchability()}（或经由宿主包装的点击回调）。
 */
public class WindowOverlayHost {

    private final Activity activity;
    private final FrameLayout overlay;
    private WindowManager.LayoutParams lp;
    private boolean attached;
    private boolean touchable;

    public WindowOverlayHost(Activity activity) {
        this.activity = activity;
        this.overlay = new FrameLayout(activity);
    }

    /** 面板等内容的挂载容器（加入后记得调 syncTouchability）。 */
    public FrameLayout overlay() {
        return overlay;
    }

    /** 首次挂载全屏覆盖层窗口（延迟到内容真正需要展示时调用也可）。 */
    public synchronized void ensureAttached() {
        if (attached) return;
        // 尺寸按系统栏 insets 取应用可用区（横屏时导航栏占右侧），面板 ✕ 不被遮挡
        int displayW = activity.getResources().getDisplayMetrics().widthPixels;
        int displayH = activity.getResources().getDisplayMetrics().heightPixels;
        int leftInset = 0, topInset = 0, rightInset = 0, bottomInset = 0;
        android.view.WindowInsets ins = activity.getWindow().getDecorView().getRootWindowInsets();
        if (ins != null) {
            android.graphics.Insets sb = ins.getInsets(android.view.WindowInsets.Type.systemBars());
            leftInset = sb.left;
            topInset = sb.top;
            rightInset = sb.right;
            bottomInset = sb.bottom;
        }
        lp = new WindowManager.LayoutParams(
                Math.max(1, displayW - leftInset - rightInset),
                Math.max(1, displayH - topInset - bottomInset),
                WindowManager.LayoutParams.TYPE_APPLICATION,
                // NOT_FOCUSABLE：不抢游戏窗口焦点（NativeActivity 失焦即暂停渲染黑屏）
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        lp.x = leftInset;
        lp.y = topInset;
        activity.getWindowManager().addView(overlay, lp);
        attached = true;
        touchable = false;
    }

    /** 按指定面板视图的可见性放行/拦截触摸；全部隐藏时触摸穿透给游戏。 */
    public synchronized void syncTouchability(View... watched) {
        boolean anyVisible = false;
        for (View v : watched) {
            if (v != null && v.getVisibility() == View.VISIBLE) {
                anyVisible = true;
                break;
            }
        }
        ensureAttached();
        if (anyVisible == touchable) return;
        if (anyVisible) {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        try {
            activity.getWindowManager().updateViewLayout(overlay, lp);
            touchable = anyVisible;
        } catch (Throwable ignored) {
        }
    }

    public synchronized void release() {
        if (attached) {
            try {
                activity.getWindowManager().removeView(overlay);
            } catch (Throwable ignored) {
            }
            attached = false;
        }
    }
}
