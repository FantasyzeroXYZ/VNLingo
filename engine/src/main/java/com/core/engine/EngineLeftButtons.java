package com.core.engine;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * 游戏画面左缘竖排按键组（引擎适配按钮，样式对齐 OnsSideButtons 右缘按键）：
 * 顶部折叠键（折叠后仍可见）+ 宿主自定义按键列（40dp 圆形，深色半透明底 + 白字）。
 * 两种安装形态：
 * - {@link #install(ViewGroup, String, List)}：加入宿主视图树（同进程视图合成的宿主，如 KRKR）；
 * - {@link #installAsWindow(Activity, String, List)}：独立小悬浮窗锚定左上
 *   （NativeActivity 宿主，如 Artemis——窗口只占按键自身区域，触摸不遮挡游戏）。
 * 贴缘边距含系统栏左 inset（三键导航/挖孔避让）。
 */
public final class EngineLeftButtons {

    private static final String PREFS = "engine_left_buttons";
    /** 对齐 OnsSideButtons 非激活配色。 */
    private static final int BG = 0xA6101010;
    private static final int STROKE = 0x87FFFFFF;
    private static final int BUTTON_DP = 40;
    private static final int GAP_DP = 5;
    private static final int EDGE_MARGIN_DP = 2;
    private static final int TOP_MARGIN_DP = 12;

    /** 单个按键：短标签（1-4 字符）+ 点击动作。 */
    public static final class ButtonSpec {
        public final String label;
        public final Runnable action;

        public ButtonSpec(String label, Runnable action) {
            this.label = label;
            this.action = action;
        }
    }

    private EngineLeftButtons() {
    }

    /** 加入宿主视图树左缘（overlay 顶层）。返回按键组根视图。 */
    public static View install(ViewGroup overlay, String prefsKey, List<ButtonSpec> buttons) {
        Activity activity = (Activity) overlay.getContext();
        LinearLayout container = buildContainer(activity, prefsKey, buttons);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        lp.topMargin = dp(activity, TOP_MARGIN_DP);
        // 左缘三键导航/挖孔 inset 避让；onCreate 时 insets 未就绪，attach 后校准
        lp.leftMargin = edgeMargin(activity);
        overlay.addView(container, lp);
        recalibrateInsets(activity, container);
        return container;
    }

    /** 独立小悬浮窗锚定左上（NativeActivity 宿主用）。返回按键组根视图。 */
    public static View installAsWindow(Activity activity, String prefsKey, List<ButtonSpec> buttons) {
        LinearLayout container = buildContainer(activity, prefsKey, buttons);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;  // 必须显式：缺省 gravity 会让 x/y 不按左上锚定
        // 横屏左缘系统栏占位：按 insets 锚定 TOP|START
        int leftInset = 0, topInset = 0;
        android.view.WindowInsets ins = activity.getWindow().getDecorView().getRootWindowInsets();
        if (ins != null) {
            android.graphics.Insets sb = ins.getInsets(android.view.WindowInsets.Type.systemBars());
            leftInset = sb.left;
            topInset = sb.top;
        }
        lp.x = leftInset + dp(activity, EDGE_MARGIN_DP);
        lp.y = topInset + dp(activity, TOP_MARGIN_DP);
        activity.getWindowManager().addView(container, lp);
        return container;
    }

    private static LinearLayout buildContainer(Activity activity, String prefsKey, List<ButtonSpec> buttons) {
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        for (ButtonSpec spec : buttons) {
            column.addView(button(activity, spec.label, spec.action));
        }
        container.addView(column);

        boolean visible = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .getBoolean(prefsKey, true);
        column.setVisibility(visible ? View.VISIBLE : View.GONE);
        TextView toggle = button(activity, visible ? "«" : "»", null);
        toggle.setOnClickListener(v -> {
            boolean now = column.getVisibility() != View.VISIBLE;
            column.setVisibility(now ? View.VISIBLE : View.GONE);
            toggle.setText(now ? "«" : "»");
            activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                    .edit().putBoolean(prefsKey, now).apply();
        });
        container.addView(toggle);
        return container;
    }

    private static TextView button(Activity activity, String label, Runnable action) {
        TextView tv = new TextView(activity);
        tv.setText(label);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(13);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        int side = dp(activity, BUTTON_DP);
        int margin = dp(activity, GAP_DP);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
        lp.topMargin = margin;
        tv.setLayoutParams(lp);
        tv.setPadding(dp(activity, 2), 0, dp(activity, 2), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(BG);
        bg.setStroke(dp(activity, 1), STROKE);
        tv.setBackground(bg);
        if (action != null) {
            tv.setOnClickListener(v -> {
                try {
                    action.run();
                } catch (Throwable ignored) {
                }
            });
        }
        return tv;
    }

    /** 左缘边距 = EDGE_MARGIN_DP + 系统栏左 inset；attach 后校准（insets 未就绪兜底）。 */
    private static int edgeMargin(Activity activity) {
        try {
            android.view.WindowInsets ins = activity.getWindow().getDecorView().getRootWindowInsets();
            if (ins != null) {
                android.graphics.Insets sb = ins.getInsets(android.view.WindowInsets.Type.systemBars());
                return dp(activity, EDGE_MARGIN_DP) + sb.left;
            }
        } catch (Throwable ignored) {
        }
        return dp(activity, EDGE_MARGIN_DP);
    }

    private static void recalibrateInsets(Activity activity, View container) {
        container.post(() -> {
            ViewGroup.LayoutParams lp = container.getLayoutParams();
            if (lp instanceof FrameLayout.LayoutParams) {
                ((FrameLayout.LayoutParams) lp).leftMargin = edgeMargin(activity);
                container.setLayoutParams(lp);
            }
        });
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
