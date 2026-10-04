package com.core.ons;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.core.engine.R;


/**
 * 提取功能线设置弹窗（宿主无关版）：翻译设置（引擎切换 / API 配置 / 本地模型 / 翻译测试）。
 *
 * 原实现在 {@link OnsExtractPanel} 的设置弹窗内（游戏画面），按产品调整移到应用设置页：
 * 构建逻辑与视觉与原面板一致（浅色弹窗、同款行/按钮样式），数据仍走
 * {@link OnsTranslateClient} / {@link OnsMlKitTranslator} 的同一份 SharedPreferences，
 * 游戏内翻译直接生效（跨进程写入见 OnsTranslateClient.prefs 的 MULTI_PROCESS 说明）。
 */
public final class OnsExtractSettingsDialogs {

    private static final int TEXT_BODY = 0xFF1E293B;
    private static final int TEXT_BUTTON = 0xFF334155;
    private static final int TEXT_DIM = 0xFF64748B;
    private static final int BG_BUTTON = 0xFFF1F5F9;
    private static final int STROKE = 0xFFE2E8F0;
    private static final int ACCENT = 0xFF007AFF;

    private OnsExtractSettingsDialogs() {
    }

    /** 翻译设置主弹窗：引擎切换（API / 本地）+ 配置入口 + 翻译测试。 */
    public static void showTranslateSettings(Activity activity) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        box.setPadding(pad, dp(activity, 8), pad, 0);

        // 翻译引擎行：API（OpenAI 兼容）/ 本地（ML Kit 离线），点行切换
        TextView engineValue = new TextView(activity);
        Runnable syncEngineText = () -> engineValue.setText(
                OnsTranslateClient.ENGINE_LOCAL.equals(OnsTranslateClient.getEngine(activity))
                        ? activity.getString(R.string.engine_ons_translate_engine_local)
                        : activity.getString(R.string.engine_ons_translate_engine_api));
        syncEngineText.run();
        styleNavValue(activity, engineValue);
        LinearLayout engineRow = settingNavRow(activity, R.drawable.ic_translate,
                R.string.engine_ons_translate_engine, engineValue);
        engineRow.setOnClickListener(v -> {
            String next = OnsTranslateClient.ENGINE_LOCAL.equals(OnsTranslateClient.getEngine(activity))
                    ? OnsTranslateClient.ENGINE_API : OnsTranslateClient.ENGINE_LOCAL;
            OnsTranslateClient.prefs(activity).edit()
                    .putString(OnsTranslateClient.KEY_ENGINE, next).apply();
            syncEngineText.run();
        });
        box.addView(engineRow);

        // 引擎配置行：API → 连接配置；本地 → 模型管理（下载/删除）
        TextView engineCfgValue = new TextView(activity);
        Runnable syncEngineCfg = () -> engineCfgValue.setText(
                OnsTranslateClient.ENGINE_LOCAL.equals(OnsTranslateClient.getEngine(activity))
                        ? activity.getString(R.string.engine_ons_translate_models)
                        : activity.getString(R.string.engine_ons_translate_api_config));
        syncEngineCfg.run();
        styleNavValue(activity, engineCfgValue);
        LinearLayout engineCfgRow = settingNavRow(activity, R.drawable.ic_settings,
                R.string.engine_ons_translate_cfg, engineCfgValue);
        engineCfgRow.setOnClickListener(v -> {
            if (OnsTranslateClient.ENGINE_LOCAL.equals(OnsTranslateClient.getEngine(activity))) {
                showLocalModelDialog(activity);
            } else {
                showApiSettings(activity);
            }
            syncEngineCfg.run();
        });
        box.addView(engineCfgRow);

        // 翻译测试行：源/目标 + 输入 + 运行 + 结果
        LinearLayout testRow = settingNavRow(activity, R.drawable.ic_autorenew,
                R.string.engine_ons_translate_test, null);
        testRow.setOnClickListener(v -> showTranslateTestDialog(activity));
        box.addView(testRow);

        ScrollView scroller = new ScrollView(activity);
        scroller.addView(box);
        new android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.engine_ons_settings_section_translate)
                .setView(scroller)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** 翻译 API（OpenAI 兼容）连接配置。 */
    public static void showApiSettings(Activity activity) {
        SharedPreferences p = OnsTranslateClient.prefs(activity);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        box.setPadding(pad, pad, pad, 0);
        EditText baseField = settingField(activity, box, R.string.engine_ons_extract_api_hint_base,
                p.getString(OnsTranslateClient.KEY_BASE_URL, ""));
        EditText keyField = settingField(activity, box, R.string.engine_ons_extract_api_hint_key,
                p.getString(OnsTranslateClient.KEY_API_KEY, ""));
        EditText modelField = settingField(activity, box, R.string.engine_ons_extract_api_hint_model,
                p.getString(OnsTranslateClient.KEY_MODEL, ""));
        EditText targetField = settingField(activity, box, R.string.engine_ons_extract_api_hint_target,
                p.getString(OnsTranslateClient.KEY_TARGET, ""));

        new android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.engine_ons_extract_api_title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    SharedPreferences.Editor e = p.edit();
                    e.putString(OnsTranslateClient.KEY_BASE_URL, textOr(baseField,
                            OnsTranslateClient.DEFAULT_BASE_URL));
                    e.putString(OnsTranslateClient.KEY_API_KEY, textOr(keyField, ""));
                    e.putString(OnsTranslateClient.KEY_MODEL, textOr(modelField,
                            OnsTranslateClient.DEFAULT_MODEL));
                    e.putString(OnsTranslateClient.KEY_TARGET, textOr(targetField,
                            OnsTranslateClient.DEFAULT_TARGET));
                    e.apply();
                    Toast.makeText(activity, R.string.engine_ons_extract_api_saved, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 本地翻译模型管理（ML Kit 按语言下载/删除）。 */
    public static void showLocalModelDialog(Activity activity) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        box.setPadding(pad, dp(activity, 8), pad, 0);
        String[][] langs = {{"zh", "中文"}, {"en", "英语"}, {"ja", "日语"}, {"ko", "韩语"}};
        for (String[] lang : langs) {
            box.addView(modelRowView(activity, lang[0], lang[1]));
        }
        ScrollView scroller = new ScrollView(activity);
        scroller.addView(box);
        new android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.engine_ons_translate_models)
                .setView(scroller)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 翻译测试：源/目标循环切换 + 输入 + 运行 + 结果。 */
    public static void showTranslateTestDialog(Activity activity) {
        SharedPreferences p = OnsTranslateClient.prefs(activity);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        box.setPadding(pad, dp(activity, 8), pad, 0);
        String[] srcCodes = {"auto", "zh", "en", "ja", "ko"};
        String[][] codeNames = {{"zh", "中文"}, {"en", "英语"}, {"ja", "日语"}, {"ko", "韩语"}};
        TextView srcView = new TextView(activity);
        TextView tgtView = new TextView(activity);
        Runnable syncLangs = () -> {
            String src = p.getString(OnsTranslateClient.KEY_SOURCE, OnsTranslateClient.DEFAULT_SOURCE);
            String srcName = "auto".equals(src)
                    ? activity.getString(R.string.engine_ons_translate_auto)
                    : codeName(codeNames, src);
            srcView.setText(activity.getString(R.string.engine_ons_translate_src_set, srcName));
            tgtView.setText(activity.getString(
                    R.string.engine_ons_translate_tgt, codeName(codeNames, OnsTranslateClient.targetCode(activity))));
        };
        srcView.setTextColor(TEXT_BODY);
        srcView.setTextSize(13);
        int pad8 = dp(activity, 8);
        srcView.setPadding(0, pad8, 0, 0);
        srcView.setOnClickListener(v -> {
            String cur = p.getString(OnsTranslateClient.KEY_SOURCE, OnsTranslateClient.DEFAULT_SOURCE);
            p.edit().putString(OnsTranslateClient.KEY_SOURCE, nextCode(srcCodes, cur)).apply();
            syncLangs.run();
        });
        box.addView(srcView);
        tgtView.setTextColor(TEXT_BODY);
        tgtView.setTextSize(13);
        tgtView.setPadding(0, pad8, 0, 0);
        tgtView.setOnClickListener(v -> {
            String next = nextCode(new String[]{"zh", "en", "ja", "ko"},
                    OnsTranslateClient.targetCode(activity));
            p.edit().putString(OnsTranslateClient.KEY_TARGET, codeName(codeNames, next)).apply();
            syncLangs.run();
        });
        box.addView(tgtView);
        syncLangs.run();
        EditText input = new EditText(activity);
        input.setHint(R.string.engine_ons_translate_test);
        input.setTextSize(13);
        input.setMinLines(1);
        input.setMaxLines(3);
        box.addView(input);
        TextView output = new TextView(activity);
        output.setTextColor(TEXT_BODY);
        output.setTextSize(14);
        output.setPadding(0, pad8, 0, 0);
        TextView runBtn = dialogActionButton(activity, activity.getString(R.string.engine_ons_translate_run), null);
        runBtn.setOnClickListener(v -> {
            String text = input.getText() == null ? "" : input.getText().toString().trim();
            if (text.isEmpty()) return;
            runBtn.setEnabled(false);
            runBtn.setTextColor(TEXT_DIM);
            OnsTranslateClient.translateWithEngine(activity, text, (translated, error) -> {
                if (translated != null) output.setText(translated);
                else output.setText(activity.getString(
                        R.string.engine_ons_extract_translate_failed, error));
            });
        });
        box.addView(runBtn);
        box.addView(output);
        ScrollView scroller = new ScrollView(activity);
        scroller.addView(box);
        new android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.engine_ons_translate_test_title)
                .setView(scroller)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------
    // 构建控件（与 OnsExtractPanel 设置弹窗同款样式）
    // ------------------------------------------------------------------

    /** 单语言模型行：状态文本 + 下载/删除动作按钮（动作完成后原位刷新）。 */
    private static View modelRowView(Activity activity, String code, String label) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        int pad10 = dp(activity, 10);
        row.setPadding(0, pad10, 0, pad10);
        TextView title = new TextView(activity);
        title.setText(label);
        title.setTextColor(TEXT_BODY);
        title.setTextSize(14);
        row.addView(title);
        TextView state = new TextView(activity);
        state.setTextColor(TEXT_DIM);
        state.setTextSize(12);
        row.addView(state);
        TextView action = dialogSmallButton(activity, "", TEXT_BUTTON, null);
        row.addView(action);
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            boolean downloaded = OnsMlKitTranslator.isModelDownloaded(activity, code);
            state.setText(activity.getString(downloaded
                    ? R.string.engine_ons_translate_model_downloaded
                    : R.string.engine_ons_translate_model_not_dl));
            action.setText(activity.getString(downloaded
                    ? R.string.engine_ons_dict_delete
                    : R.string.engine_ons_translate_model_download));
            action.setOnClickListener(v -> new Thread(() -> {
                try {
                    if (downloaded) {
                        OnsMlKitTranslator.deleteModel(activity, code);
                        Toast.makeText(activity, label, Toast.LENGTH_SHORT).show();
                    } else {
                        OnsMlKitTranslator.downloadModel(activity, code);
                        Toast.makeText(activity, label, Toast.LENGTH_SHORT).show();
                    }
                    android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                    main.post(refresh[0]);
                } catch (Throwable t) {
                    Toast.makeText(activity,
                            t.getMessage() == null ? t.toString() : t.getMessage(),
                            Toast.LENGTH_SHORT).show();
                }
            }, "ons-model-toggle").start());
        };
        refresh[0].run();
        return row;
    }

    /** 导航型设置行骨架：图标 + 标题（占满）+ 右侧值视图，点击行为由调用方绑定。 */
    private static LinearLayout settingNavRow(Activity activity, int iconRes, int labelRes, TextView valueView) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(activity, 10), 0, dp(activity, 10));
        row.setClickable(true);

        android.widget.ImageView icon = new android.widget.ImageView(activity);
        icon.setImageResource(iconRes);
        icon.setColorFilter(TEXT_BUTTON);
        icon.setContentDescription(activity.getString(labelRes));
        int side = dp(activity, 22);
        row.addView(icon, new LinearLayout.LayoutParams(side, side));

        TextView label = new TextView(activity);
        label.setText(labelRes);
        label.setTextColor(TEXT_BODY);
        label.setTextSize(14);
        label.setPadding(dp(activity, 12), 0, 0, 0);
        row.addView(label, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (valueView != null) row.addView(valueView);
        return row;
    }

    private static void styleNavValue(Activity activity, TextView value) {
        value.setTextColor(TEXT_DIM);
        value.setTextSize(13);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        value.setMaxWidth(dp(activity, 170));
    }

    private static EditText settingField(Activity activity, LinearLayout box, int hintRes, String value) {
        TextView hint = new TextView(activity);
        hint.setText(hintRes);
        hint.setTextColor(TEXT_DIM);
        hint.setTextSize(11);
        box.addView(hint, matchWrap());
        EditText field = new EditText(activity);
        field.setText(value);
        field.setTextSize(13);
        field.setSingleLine(true);
        box.addView(field, matchWrap());
        return field;
    }

    private static String textOr(EditText field, String fallback) {
        String s = field.getText() == null ? "" : field.getText().toString().trim();
        return s.isEmpty() ? fallback : s;
    }

    private static String codeName(String[][] table, String code) {
        for (String[] row : table) {
            if (row[0].equals(code)) return row[1];
        }
        return code;
    }

    private static String nextCode(String[] codes, String current) {
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(current)) {
                return codes[(i + 1) % codes.length];
            }
        }
        return codes[0];
    }

    /** 弹窗内主操作按钮（全宽圆角）。 */
    private static TextView dialogActionButton(Activity activity, String label, Runnable action) {
        TextView tv = new TextView(activity);
        tv.setText(label);
        tv.setTextColor(TEXT_BUTTON);
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        int padV = dp(activity, 10);
        tv.setPadding(padV, padV, padV, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(activity, 10);
        tv.setLayoutParams(lp);
        tv.setBackground(rounded(activity, BG_BUTTON, dp(activity, 10), STROKE, dp(activity, 1)));
        if (action != null) tv.setOnClickListener(v -> action.run());
        return tv;
    }

    /** 弹窗内小操作按钮（胶囊）。 */
    private static TextView dialogSmallButton(Activity activity, String label, int bgColor, Runnable action) {
        TextView tv = new TextView(activity);
        tv.setText(label);
        tv.setTextColor(bgColor == ACCENT ? Color.WHITE : TEXT_BUTTON);
        tv.setTextSize(12);
        int padH = dp(activity, 12);
        int padV = dp(activity, 6);
        tv.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(activity, 8);
        tv.setLayoutParams(lp);
        tv.setBackground(rounded(activity, bgColor == ACCENT ? ACCENT : BG_BUTTON, dp(activity, 10), STROKE, dp(activity, 1)));
        if (action != null) tv.setOnClickListener(v -> action.run());
        return tv;
    }

    private static android.graphics.drawable.GradientDrawable rounded(
            Activity activity, int color, float radius, int strokeColor, float strokeW) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (strokeW > 0) {
            d.setStroke((int) strokeW, strokeColor);
        }
        return d;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
