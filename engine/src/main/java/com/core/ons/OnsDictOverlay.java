package com.core.ons;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 词典释义独立悬浮窗（交互参考 jidoujisho：句中高亮对应词，释义显示在顶部
 * 独立悬浮框，不挤占剧情文本框）：顶部停靠、词卡一键制卡、✕ 关闭。
 * 与 OnsHistoryOverlay 同款窗口管理与失效重建策略（token 失效整体重建）。
 */
public final class OnsDictOverlay {

    private static View root;
    private static WindowManager wm;
    private static WindowManager.LayoutParams lp;
    private static TextView header;
    private static LinearLayout body;
    private static LinearLayout actions;
    private static Activity owner;

    private OnsDictOverlay() {
    }

    /**
     * 显示释义（已开则更新内容）。
     *
     * @param term       查得词条（扫描命中串）
     * @param groups     释义组（词条/读音/释义列表）
     * @param onWordCard 词卡动作回调（面板 makeWordCard；defGroups 已就绪）
     */
    public static void show(Activity activity, String term, List<OnsDictStore.Group> groups,
                            Runnable onWordCard) {
        if (root == null || owner != activity) {
            detach();
            create(activity);
            owner = activity;
        }
        header.setText(term == null ? "" : term);
        body.removeAllViews();
        if (groups == null || groups.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText(com.core.engine.R.string.engine_ons_extract_def_none);
            empty.setTextColor(0xFF94A3B8);
            empty.setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8));
            body.addView(empty);
        } else {
            boolean first = true;
            for (OnsDictStore.Group g : groups) {
                StringBuilder sb = new StringBuilder();
                sb.append(g.term);
                if (g.reading != null && !g.reading.isEmpty() && !g.reading.equals(g.term)) {
                    sb.append("【").append(g.reading).append("】");
                }
                for (String gloss : g.glosses) {
                    sb.append("\n").append(gloss);
                }
                TextView tv = new TextView(activity);
                tv.setText(sb.toString());
                tv.setTextColor(0xFFE2E8F0);
                tv.setTextSize(13);
                tv.setLineSpacing(0f, 1.4f);
                tv.setPadding(dp(activity, 10), first ? dp(activity, 4) : dp(activity, 8),
                        dp(activity, 10), dp(activity, 4));
                body.addView(tv, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                first = false;
            }
        }
        // 词卡动作每次 show 重挂（面板实例可能旋转重建）
        actions.removeAllViews();
        if (onWordCard != null && groups != null && !groups.isEmpty()) {
            View card = makeIconButton(activity, com.core.engine.R.drawable.ic_book,
                    com.core.engine.R.string.engine_ons_extract_word_card, onWordCard);
            actions.addView(card);
        }
        View close = makeIconButton(activity, com.core.engine.R.drawable.ic_close,
                com.core.engine.R.string.engine_ons_extract_def_dismiss, OnsDictOverlay::hide);
        actions.addView(close);
        try {
            if (root.getParent() == null) {
                wm.addView(root, lp);
            } else {
                wm.updateViewLayout(root, lp);
            }
        } catch (Throwable t) {
            android.util.Log.w("OnsDictOverlay", "show failed (stale token?)", t);
            detach();
        }
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

    /** 完全丢弃当前悬浮窗（视图与窗口分离），下次 show 按 Activity 重建。 */
    private static void detach() {
        try {
            if (root != null && root.getParent() != null && wm != null) {
                wm.removeView(root);
            }
        } catch (Throwable ignored) {
        }
        root = null;
        header = null;
        body = null;
        actions = null;
        owner = null;
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

        // 头部：词条（读音在释义组里逐条展示）+ 动作（词卡/✕）
        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        header = new TextView(activity);
        header.setTextColor(0xFF2DD4BF);
        header.setTextSize(15);
        header.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.setPadding(dp * 6, dp * 4, dp * 4, dp * 4);
        head.addView(header, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        head.addView(actions);
        box.addView(head);

        body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroller = new ScrollView(activity);
        scroller.addView(body);
        box.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp * 90));

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp * 28; // 状态栏下方
        root = box;
    }

    private static View makeIconButton(Activity activity, int iconRes, int descRes, Runnable onClick) {
        android.widget.ImageView iv = new android.widget.ImageView(activity);
        iv.setImageResource(iconRes);
        iv.setContentDescription(activity.getString(descRes));
        // 与面板动作钮同款：深色圆底 + 白色图标
        iv.setColorFilter(0xFFFFFFFF);
        int side = dp(activity, 28);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
        lp.rightMargin = dp(activity, 6);
        int pad = dp(activity, 5);
        iv.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2E2E2E);
        bg.setCornerRadius(dp(activity, 14));
        iv.setBackground(bg);
        iv.setOnClickListener(v -> {
            try {
                onClick.run();
            } catch (Throwable t) {
                android.util.Log.w("OnsDictOverlay", "dict action failed", t);
            }
        });
        iv.setLayoutParams(lp);
        return iv;
    }

    private static int dp(Activity activity, int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
