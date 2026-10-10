package com.core.ons;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * 设置控件层（engine 侧）：**设置项一律下拉选择，不做点击循环**。
 *
 * <p>原实现里「点一次换下一个值」的行（翻译引擎、TTS 语速/音高/音量/引擎/循环次数、
 * 翻译测试源与目标、面板透明度…）统一改用 {@link #dropdownRow}；布尔项仍用开关
 * （见各调用方），文本项仍用输入框。
 */
public final class SettingsWidgets {

    private static final int TEXT_BODY = 0xFF1E293B;

    private SettingsWidgets() {
    }

    /**
     * 下拉选择行：可选图标 + 标题 + Spinner。
     *
     * @param selectedIndex 当前值下标（越界按 0）
     * @param onSelect      选中回调（注意：Spinner 首次布局也会回调一次，为当前值）
     */
    public static LinearLayout dropdownRow(Activity activity, int iconRes, CharSequence label,
                                           List<String> options, int selectedIndex,
                                           IntConsumer onSelect) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(activity, 6), 0, dp(activity, 6));

        if (iconRes != 0) {
            ImageView icon = new ImageView(activity);
            icon.setImageResource(iconRes);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(activity, 20), dp(activity, 20));
            icon.setLayoutParams(ip);
            row.addView(icon);
        }

        TextView title = new TextView(activity);
        title.setText(label);
        title.setTextColor(TEXT_BODY);
        title.setTextSize(14);
        title.setSingleLine(true);
        if (iconRes != 0) title.setPadding(dp(activity, 12), 0, dp(activity, 8), 0);
        row.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Spinner spinner = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        if (selectedIndex > 0 && selectedIndex < options.size()) spinner.setSelection(selectedIndex);
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                       int position, long id) {
                onSelect.accept(position);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        row.addView(spinner);
        return row;
    }

    /**
     * 进度条行：图标 + 标题 + 当前值文字（第一行），下面整行 SeekBar（第二行）。
     *
     * 用户要求：设置悬浮框里所有**百分比**类调节（音量/透明度/语速…）都用进度条，
     * 方便微调；下拉档位只能选固定几档，太粗。
     *
     * @param valueFmt 当前值文字格式（如「%d%%」）；显示时用 value 填充
     */
    public static LinearLayout sliderRow(Activity activity, int iconRes, CharSequence label,
                                         int min, int max, int value, String valueFmt,
                                         IntConsumer onChange) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(activity, 6), 0, dp(activity, 2));

        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        if (iconRes != 0) {
            ImageView icon = new ImageView(activity);
            icon.setImageResource(iconRes);
            icon.setLayoutParams(new LinearLayout.LayoutParams(dp(activity, 20), dp(activity, 20)));
            head.addView(icon);
        }
        TextView title = new TextView(activity);
        title.setText(label);
        title.setTextColor(TEXT_BODY);
        title.setTextSize(14);
        title.setSingleLine(true);
        if (iconRes != 0) title.setPadding(dp(activity, 12), 0, dp(activity, 8), 0);
        head.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView valueView = new TextView(activity);
        valueView.setTextColor(TEXT_BODY);
        valueView.setTextSize(13);
        head.addView(valueView);
        box.addView(head);

        android.widget.SeekBar bar = new android.widget.SeekBar(activity);
        final int span = Math.max(1, max - min);
        bar.setMax(span);
        bar.setProgress(Math.max(0, Math.min(span, value - min)));
        valueView.setText(String.format(valueFmt, value));
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                int v = min + progress;
                valueView.setText(String.format(valueFmt, v));
                if (fromUser) onChange.accept(v);
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar sb) {
                onChange.accept(min + sb.getProgress());
            }
        });
        box.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return box;
    }

    /**
     * 开关行：可选图标 + 标题 + Switch（布尔项不用文字行点按切换）。
     */
    public static LinearLayout switchRow(Activity activity, int iconRes, CharSequence label,
                                         boolean checked,
                                         java.util.function.Consumer<Boolean> onChange) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(activity, 6), 0, dp(activity, 6));

        if (iconRes != 0) {
            ImageView icon = new ImageView(activity);
            icon.setImageResource(iconRes);
            icon.setLayoutParams(new LinearLayout.LayoutParams(dp(activity, 20), dp(activity, 20)));
            row.addView(icon);
        }

        TextView title = new TextView(activity);
        title.setText(label);
        title.setTextColor(TEXT_BODY);
        title.setTextSize(14);
        if (iconRes != 0) title.setPadding(dp(activity, 12), 0, dp(activity, 8), 0);
        row.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.Switch sw = new android.widget.Switch(activity);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((btn, value) -> onChange.accept(value));
        row.addView(sw);
        return row;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
