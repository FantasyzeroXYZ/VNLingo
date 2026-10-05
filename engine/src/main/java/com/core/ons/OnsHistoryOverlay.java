package com.core.ons;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 历史记录独立悬浮窗：与剧情文本框分离，可拖动、实时更新（面板每次刷新时
 * 同步内容）。条目 = 「#序号 正文（♪语音名）」；✕ 关闭，再次点面板历史键重开。
 */
public final class OnsHistoryOverlay {

    private static View root;
    private static WindowManager wm;
    private static WindowManager.LayoutParams lp;
    private static LinearLayout list;
    private static Runnable onClear;
    /** 创建悬浮窗所用的 Activity：换实例（旋转重建/重开游戏）时旧 token 作废，
     *  必须整体重建，否则 addView 抛 BadTokenException / 内容挂在已死的旧窗口上。 */
    private static Activity owner;

    /** 注册「清空」回调（面板清空历史数据）。 */
    public static void setOnClear(Runnable r) {
        onClear = r;
    }

    private OnsHistoryOverlay() {
    }

    /** 打开（已开则更新内容）。items = 已格式化的历史行。 */
    public static void show(Activity activity, List<String> items) {
        if (root == null || owner != activity) {
            detach();
            create(activity);
            owner = activity;
        }
        list.removeAllViews();
        int n = 1;
        for (String item : items) {
            TextView tv = new TextView(activity);
            tv.setText(item);
            tv.setTextColor(0xFFE2E8F0);
            tv.setTextSize(13);
            tv.setLineSpacing(0f, 1.25f);
            tv.setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 6));
            list.addView(tv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            n++;
        }
        if (items.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText(com.core.engine.R.string.engine_ons_extract_history_empty);
            empty.setTextColor(0xFF94A3B8);
            empty.setPadding(dp(activity, 10), dp(activity, 10), dp(activity, 10), dp(activity, 10));
            list.addView(empty);
        }
        // token 可能随 Activity 销毁失效（旋转重建/游戏退出），wm 操作全部兜底，
        // 失败时丢弃旧窗口引用，下次 show 用新 Activity 重建
        try {
            if (root.getParent() == null) {
                wm.addView(root, lp);
            } else {
                wm.updateViewLayout(root, lp);
            }
        } catch (Throwable t) {
            android.util.Log.w("OnsHistoryOverlay", "show failed (stale token?)", t);
            detach();
        }
    }

    /** 完全丢弃当前悬浮窗（视图与窗口分离），下次 show 按 Activity 重建。 */
    private static void detach() {
        try {
            if (root != null && root.getParent() != null && wm != null) {
                wm.removeView(root);
            }
        } catch (Throwable ignored) {
        }
        root = null;
        list = null;
        owner = null;
    }

    /** 悬浮窗开着时增量追加一条（不重建，保持滚动位置）。 */
    public static void addEntry(Activity activity, String item) {
        if (root == null || list == null || root.getParent() == null) return;
        TextView tv = new TextView(activity);
        tv.setText(item);
        tv.setTextColor(0xFFE2E8F0);
        tv.setTextSize(13);
        tv.setLineSpacing(0f, 1.25f);
        tv.setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 6));
        list.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        list.post(() -> list.getChildAt(list.getChildCount() - 1).requestRectangleOnScreen(
                new android.graphics.Rect(), true));
    }

    public static boolean showing() {
        return root != null && root.getParent() != null;
    }

    public static void hide() {
        try {
            if (root != null && root.getParent() != null && wm != null) {
                wm.removeView(root);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void create(Activity activity) {
        wm = activity.getWindowManager();
        final int dp = (int) (activity.getResources().getDisplayMetrics().density + 0.5f);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6101010);
        bg.setCornerRadius(dp * 10);
        bg.setStroke(dp, 0x87FFFFFF);
        box.setBackground(bg);
        box.setPadding(dp * 4, dp * 4, dp * 4, dp * 6);

        // 标题条：标题 + ✕（同时作为拖动手柄）
        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(activity);
        title.setText(com.core.engine.R.string.engine_ons_extract_history);
        title.setTextColor(0xFF2DD4BF);
        title.setTextSize(13);
        head.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = new TextView(activity);
        clear.setText(com.core.engine.R.string.engine_ons_extract_history_clear);
        clear.setTextColor(0xFF94A3B8);
        clear.setTextSize(12);
        clear.setPadding(dp * 6, dp * 4, dp * 6, dp * 4);
        clear.setOnClickListener(v -> {
            list.removeAllViews();
            if (onClear != null) onClear.run();
        });
        head.addView(clear);
        TextView close = new TextView(activity);
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setTextSize(14);
        close.setPadding(dp * 8, dp * 4, dp * 8, dp * 4);
        close.setOnClickListener(v -> hide());
        head.addView(close);
        box.addView(head);

        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroller = new ScrollView(activity);
        scroller.addView(list);
        box.addView(scroller, new LinearLayout.LayoutParams(
                dp * 300, dp * 320));

        root = box;

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp * 40;
        lp.y = dp * 90;

        // 标题条拖动（delta：按下时记录起点，移动按差量平移窗口）
        final int[] down = new int[2];
        final int[] startLp = new int[2];
        head.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = (int) e.getRawX();
                    down[1] = (int) e.getRawY();
                    startLp[0] = lp.x;
                    startLp[1] = lp.y;
                    v.setPressed(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.x = startLp[0] + (int) e.getRawX() - down[0];
                    lp.y = startLp[1] + (int) e.getRawY() - down[1];
                    wm.updateViewLayout(root, lp);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    return true;
                default:
                    return false;
            }
        });
    }

    private static int dp(Activity activity, int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
