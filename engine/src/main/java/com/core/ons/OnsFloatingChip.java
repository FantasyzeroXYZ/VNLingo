package com.core.ons;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 悬浮球按钮（提取/存档等面板的收起态入口）：圆角半透明底 + 单字标签。
 * 支持拖动换位（位置持久化到 prefs）、无操作自动收缩为贴边半透明小点、
 * 点击恢复或触发宿主回调。纯平台控件覆盖层，不干扰 SDL 触摸。
 */
public class OnsFloatingChip {

    /** 点击回调（收缩态点击只恢复显示，不触发）。 */
    public interface ClickListener {
        void onClick();
    }

    private static final int BG = 0x66303030;
    private static final int STROKE = 0x87FFFFFF;
    private static final long AUTO_HIDE_DELAY_MS = 6000;

    private final Activity activity;
    private final SharedPreferences prefs;
    private final String keyRight;
    private final String keyTop;
    private final TextView view;
    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout.LayoutParams lp;
    /** 窗口模式（WindowManager 承载）下的布局参数；view 模式为 null。 */
    private android.view.WindowManager.LayoutParams windowLp;
    private ClickListener clickListener;
    private boolean hidden;
    private float lastX;
    private float lastY;
    private boolean moved;

    public OnsFloatingChip(Activity activity, SharedPreferences prefs, String keyRight,
                           String keyTop, int defaultRightDp, int defaultTopDp, String label) {
        this.activity = activity;
        this.prefs = prefs;
        this.keyRight = keyRight;
        this.keyTop = keyTop;
        view = new TextView(activity);
        view.setText(label);
        view.setTextColor(Color.WHITE);
        view.setTextSize(13);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setBackground(rounded());
        lp = new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP | Gravity.END);
        lp.topMargin = prefs.getInt(keyTop, dp(defaultTopDp));
        lp.rightMargin = prefs.getInt(keyRight, dp(defaultRightDp));
        view.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = ev.getRawX();
                    lastY = ev.getRawY();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - lastX;
                    float dy = ev.getRawY() - lastY;
                    if (!moved && Math.hypot(dx, dy) < dp(6)) return true;
                    moved = true;
                    moveBy(dx, dy);
                    lastX = ev.getRawX();
                    lastY = ev.getRawY();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (moved) persist();
                    else click();
                    return true;
                default:
                    return true;
            }
        });
    }

    /** 加入覆盖层。 */
    public void install(ViewGroup overlay) {
        overlay.addView(view, lp);
    }

    /**
     * 以独立悬浮窗加入（NativeActivity 等游戏独占窗口 surface、Java 视图
     * 不参与合成的宿主用）。位置键与 view 模式共用，拖动持久化一致。
     * 横屏时应用窗宽 ≠ 屏宽（系统导航栏占右侧），按应用窗宽 TOP|START 绝对锚定。
     */
    public void installAsWindow() {
        android.view.WindowManager wm = activity.getWindowManager();
        int viewSize = dp(32);
        int[] anchor = OnsSideButtons.anchorTopEnd(activity, viewSize, lp.rightMargin, lp.topMargin);
        // 尺寸显式取芯片大小：addView 会用这里的 lp 替换子视图自身的 84x84，
        // WRAP_CONTENT 会退化成按文字测量（S 存球被压成细条）的 bug
        windowLp = new android.view.WindowManager.LayoutParams(
                viewSize,
                viewSize,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        windowLp.gravity = Gravity.TOP | Gravity.START;
        windowLp.x = anchor[0];
        windowLp.y = anchor[1];
        wm.addView(view, windowLp);
    }

    /** 重挂悬浮窗（覆盖层窗口后加入会盖住本球，重加提升 z 序；位置不变）。 */
    public void readdWindow() {
        if (windowLp == null) return;
        android.view.WindowManager wm = activity.getWindowManager();
        try {
            wm.removeView(view);
        } catch (Throwable ignored) {
        }
        try {
            wm.addView(view, windowLp);
        } catch (Throwable ignored) {
        }
    }

    public void setClickListener(ClickListener listener) {
        this.clickListener = listener;
    }

    public TextView getView() {
        return view;
    }

    /** 无操作后收缩为贴边小点（供宿主在开面板后重置计时）。 */
    public void scheduleAutoHide() {
        main.removeCallbacks(autoHideRunnable);
        main.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS);
    }

    /** 供虚拟光标命中时模拟一次点击（语义同真实点击）。 */
    public void triggerClick() {
        click();
    }

    private final Runnable autoHideRunnable = new Runnable() {
        @Override
        public void run() {
            hidden = true;
            view.animate().alpha(0.25f).scaleX(0.5f).scaleY(0.5f)
                    .translationX(dp(14)).setDuration(250).start();
        }
    };

    private void click() {
        if (hidden) {
            hidden = false;
            view.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .translationX(0f).setDuration(200).start();
            scheduleAutoHide();
            return;
        }
        if (clickListener != null) clickListener.onClick();
    }

    private void moveBy(float dx, float dy) {
        lp.rightMargin = Math.max(0, lp.rightMargin - Math.round(dx));
        lp.topMargin = Math.max(0, lp.topMargin + Math.round(dy));
        if (windowLp != null) {
            // 窗口模式：TOP|START 绝对锚定（installAsWindow 时由右缘换算的 x 起步）
            windowLp.x = Math.max(0, windowLp.x - Math.round(dx));
            windowLp.y = Math.max(0, windowLp.y + Math.round(dy));
            try {
                activity.getWindowManager().updateViewLayout(view, windowLp);
            } catch (Throwable ignored) {
            }
            return;
        }
        ViewGroup overlay = (ViewGroup) view.getParent();
        if (overlay == null) return;
        lp.rightMargin = Math.min(overlay.getWidth() - view.getWidth(), lp.rightMargin);
        overlay.updateViewLayout(view, lp);
    }

    private void persist() {
        prefs.edit().putInt(keyRight, lp.rightMargin).putInt(keyTop, lp.topMargin).apply();
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(BG);
        d.setCornerRadius(dp(16));
        d.setStroke(dp(1), STROKE);
        return d;
    }
}
