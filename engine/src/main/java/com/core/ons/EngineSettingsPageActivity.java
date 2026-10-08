package com.core.ons;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.core.engine.R;

/**
 * 引擎侧设置独立页宿主：把 {@link OnsExtractSettingsDialogs} 的设置内容当成**整页**展示
 * （顶栏：返回 / 标题 / 保存），供设置页各入口点击进入，替代原来的悬浮弹窗。
 *
 * <p>内容构建与保存动作完全复用弹窗那套（{@code buildXxxPage}），所以两种入口行为一致；
 * 弹窗版本仍保留给游戏内/面板入口使用。
 */
public class EngineSettingsPageActivity extends Activity {

    public static final String EXTRA_KIND = "engine_settings_kind";

    public static final String KIND_TRANSLATE = "translate";
    public static final String KIND_ANKI = "anki";
    public static final String KIND_TTS = "tts";

    private static final int TEXT_TITLE = 0xFF1E293B;
    private static final int TEXT_DIM = 0xFF64748B;
    private static final int ACCENT = 0xFF007AFF;
    private static final int BG = 0xFFF6F7FB;

    public static Intent createIntent(Context context, String kind) {
        Intent intent = new Intent(context, EngineSettingsPageActivity.class);
        intent.putExtra(EXTRA_KIND, kind);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String kind = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_KIND);
        OnsExtractSettingsDialogs.Page page;
        if (KIND_ANKI.equals(kind)) {
            page = OnsExtractSettingsDialogs.buildAnkiCardPage(this);
        } else if (KIND_TTS.equals(kind)) {
            page = OnsExtractSettingsDialogs.buildTtsPage(this);
        } else {
            page = OnsExtractSettingsDialogs.buildTranslatePage(this);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.addView(buildTopBar(page), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 内容本体（buildXxxPage 返回的是可滚动容器）填满剩余空间
        View content = page.content;
        if (content instanceof ScrollView) {
            root.addView(content, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            ScrollView wrap = new ScrollView(this);
            wrap.addView(content);
            root.addView(wrap, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        setContentView(root);
        applyLightStatusBar();
    }

    /**
     * 与其它设置页一致的浅色状态栏（白底 + 深色图标）。
     * 必须放在 {@code setContentView} 之后：decor view 未创建时
     * {@code getWindow().getInsetsController()} 会 NPE（踩过一次）。
     */
    private void applyLightStatusBar() {
        try {
            getWindow().setStatusBarColor(Color.WHITE);
            getWindow().setNavigationBarColor(BG);   // 与页面底色一致（其它页也是浅色）
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                android.view.WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    c.setSystemBarsAppearance(
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        } catch (Throwable ignored) {
            // 状态栏样式失败不影响页面可用
        }
    }

    /** 顶栏：返回 / 标题 / 保存。 */
    private View buildTopBar(OnsExtractSettingsDialogs.Page page) {
        int pad = dp(16);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(pad, dp(12), pad, dp(12));
        bar.setBackgroundColor(Color.WHITE);

        TextView back = new TextView(this);
        back.setText("\u2039");
        back.setTextSize(26);
        back.setTextColor(TEXT_TITLE);
        back.setPadding(0, 0, dp(12), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText(page.titleRes);
        title.setTextSize(18);
        title.setTextColor(TEXT_TITLE);
        bar.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView save = new TextView(this);
        save.setText(R.string.engine_ons_settings_page_save);
        save.setTextSize(16);
        save.setTextColor(ACCENT);
        save.setPadding(dp(12), dp(4), 0, dp(4));
        save.setOnClickListener(v -> {
            page.save.run();
            finish();
        });
        // 点了就生效的页（翻译设置）没有保存语义：不显示保存按钮
        save.setVisibility(page.hasSave ? View.VISIBLE : View.GONE);
        bar.addView(save);

        View divider = new View(this);
        divider.setBackgroundColor(0xFFE2E8F0);
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        holder.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        return holder;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
