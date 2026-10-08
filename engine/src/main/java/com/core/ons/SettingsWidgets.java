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
