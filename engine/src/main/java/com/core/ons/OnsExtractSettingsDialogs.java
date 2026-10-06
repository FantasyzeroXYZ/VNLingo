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

import com.core.anki.AnkiCardConfig;
import java.io.File;
import java.io.FileOutputStream;
import com.core.anki.AnkiDroidHelper;
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

        // 引擎配置行：按引擎打开对应配置（免费源无需配置）
        TextView engineCfgValue = new TextView(activity);
        Runnable syncEngineCfg = () -> engineCfgValue.setText(engineCfgName(activity,
                OnsTranslateClient.getEngine(activity)));
        syncEngineCfg.run();
        styleNavValue(activity, engineCfgValue);

        // 翻译引擎行：八引擎循环（API / 本地 / Google / Bing / DeepL / 百度 / Gemini / Claude）
        TextView engineValue = new TextView(activity);
        Runnable syncEngineText = () -> engineValue.setText(engineDisplayName(activity,
                OnsTranslateClient.getEngine(activity)));
        syncEngineText.run();
        styleNavValue(activity, engineValue);
        LinearLayout engineRow = settingNavRow(activity, R.drawable.ic_translate,
                R.string.engine_ons_translate_engine, engineValue);
        engineRow.setOnClickListener(v -> {
            String[] engines = OnsTranslateClient.ENGINES;
            String current = OnsTranslateClient.getEngine(activity);
            int idx = 0;
            for (int i = 0; i < engines.length; i++) {
                if (engines[i].equals(current)) {
                    idx = i;
                    break;
                }
            }
            String next = engines[(idx + 1) % engines.length];
            OnsTranslateClient.prefs(activity).edit()
                    .putString(OnsTranslateClient.KEY_ENGINE, next).apply();
            syncEngineText.run();
            syncEngineCfg.run();
        });
        box.addView(engineRow);

        LinearLayout engineCfgRow = settingNavRow(activity, R.drawable.ic_settings,
                R.string.engine_ons_translate_cfg, engineCfgValue);
        engineCfgRow.setOnClickListener(v -> {
            openEngineConfig(activity, OnsTranslateClient.getEngine(activity));
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

    /** 引擎码 → 显示名。 */
    private static String engineDisplayName(Activity activity, String engine) {
        switch (engine) {
            case OnsTranslateClient.ENGINE_LOCAL:
                return activity.getString(R.string.engine_ons_translate_engine_local);
            case OnsTranslateClient.ENGINE_GOOGLE:
                return activity.getString(R.string.engine_ons_translate_engine_google);
            case OnsTranslateClient.ENGINE_BING:
                return activity.getString(R.string.engine_ons_translate_engine_bing);
            case OnsTranslateClient.ENGINE_DEEPL:
                return activity.getString(R.string.engine_ons_translate_engine_deepl);
            case OnsTranslateClient.ENGINE_BAIDU:
                return activity.getString(R.string.engine_ons_translate_engine_baidu);
            case OnsTranslateClient.ENGINE_GEMINI:
                return activity.getString(R.string.engine_ons_translate_engine_gemini);
            case OnsTranslateClient.ENGINE_ANTHROPIC:
                return activity.getString(R.string.engine_ons_translate_engine_anthropic);
            default:
                return activity.getString(R.string.engine_ons_translate_engine_api);
        }
    }

    /** 引擎码 → 配置行显示名。 */
    private static String engineCfgName(Activity activity, String engine) {
        switch (engine) {
            case OnsTranslateClient.ENGINE_LOCAL:
                return activity.getString(R.string.engine_ons_translate_models);
            case OnsTranslateClient.ENGINE_GOOGLE:
            case OnsTranslateClient.ENGINE_BING:
                return activity.getString(R.string.engine_ons_translate_cfg_free);
            case OnsTranslateClient.ENGINE_DEEPL:
                return activity.getString(R.string.engine_ons_translate_cfg_deepl);
            case OnsTranslateClient.ENGINE_BAIDU:
                return activity.getString(R.string.engine_ons_translate_cfg_baidu);
            case OnsTranslateClient.ENGINE_GEMINI:
                return activity.getString(R.string.engine_ons_translate_cfg_gemini);
            case OnsTranslateClient.ENGINE_ANTHROPIC:
                return activity.getString(R.string.engine_ons_translate_cfg_anthropic);
            default:
                return activity.getString(R.string.engine_ons_translate_api_config);
        }
    }

    /** 按引擎打开对应配置弹窗（免费源提示后返回）。 */
    private static void openEngineConfig(Activity activity, String engine) {
        switch (engine) {
            case OnsTranslateClient.ENGINE_LOCAL:
                showLocalModelDialog(activity);
                return;
            case OnsTranslateClient.ENGINE_GOOGLE:
            case OnsTranslateClient.ENGINE_BING:
                Toast.makeText(activity, R.string.engine_ons_translate_cfg_free, Toast.LENGTH_SHORT).show();
                return;
            case OnsTranslateClient.ENGINE_DEEPL: {
                SharedPreferences p = OnsTranslateClient.prefs(activity);
                LinearLayout box = new LinearLayout(activity);
                box.setOrientation(LinearLayout.VERTICAL);
                int pad = dp(activity, 16);
                box.setPadding(pad, pad, pad, 0);
                EditText hostField = settingField(activity, box, R.string.engine_ons_deepl_hint_host,
                        p.getString(OnsTranslateClient.KEY_DEEPL_HOST, "https://api-free.deepl.com"));
                EditText keyField = settingField(activity, box, R.string.engine_ons_deepl_hint_key,
                        p.getString(OnsTranslateClient.KEY_DEEPL_KEY, ""));
                new android.app.AlertDialog.Builder(activity)
                        .setTitle(R.string.engine_ons_translate_cfg_deepl)
                        .setView(box)
                        .setPositiveButton(android.R.string.ok, (d, w) -> p.edit()
                                .putString(OnsTranslateClient.KEY_DEEPL_HOST, textOr(hostField,
                                        "https://api-free.deepl.com"))
                                .putString(OnsTranslateClient.KEY_DEEPL_KEY, textOr(keyField, ""))
                                .apply())
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return;
            }
            case OnsTranslateClient.ENGINE_BAIDU: {
                SharedPreferences p = OnsTranslateClient.prefs(activity);
                LinearLayout box = new LinearLayout(activity);
                box.setOrientation(LinearLayout.VERTICAL);
                int pad = dp(activity, 16);
                box.setPadding(pad, pad, pad, 0);
                EditText appidField = settingField(activity, box, R.string.engine_ons_baidu_hint_appid,
                        p.getString(OnsTranslateClient.KEY_BAIDU_APPID, ""));
                EditText keyField = settingField(activity, box, R.string.engine_ons_baidu_hint_key,
                        p.getString(OnsTranslateClient.KEY_BAIDU_KEY, ""));
                new android.app.AlertDialog.Builder(activity)
                        .setTitle(R.string.engine_ons_translate_cfg_baidu)
                        .setView(box)
                        .setPositiveButton(android.R.string.ok, (d, w) -> p.edit()
                                .putString(OnsTranslateClient.KEY_BAIDU_APPID, textOr(appidField, ""))
                                .putString(OnsTranslateClient.KEY_BAIDU_KEY, textOr(keyField, ""))
                                .apply())
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return;
            }
            case OnsTranslateClient.ENGINE_GEMINI: {
                SharedPreferences p = OnsTranslateClient.prefs(activity);
                LinearLayout box = new LinearLayout(activity);
                box.setOrientation(LinearLayout.VERTICAL);
                int pad = dp(activity, 16);
                box.setPadding(pad, pad, pad, 0);
                EditText keyField = settingField(activity, box, R.string.engine_ons_gemini_hint_key,
                        p.getString(OnsTranslateClient.KEY_GEMINI_KEY, ""));
                EditText modelField = settingField(activity, box, R.string.engine_ons_gemini_hint_model,
                        p.getString(OnsTranslateClient.KEY_GEMINI_MODEL, "gemini-1.5-flash"));
                new android.app.AlertDialog.Builder(activity)
                        .setTitle(R.string.engine_ons_translate_cfg_gemini)
                        .setView(box)
                        .setPositiveButton(android.R.string.ok, (d, w) -> p.edit()
                                .putString(OnsTranslateClient.KEY_GEMINI_KEY, textOr(keyField, ""))
                                .putString(OnsTranslateClient.KEY_GEMINI_MODEL, textOr(modelField,
                                        "gemini-1.5-flash"))
                                .apply())
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return;
            }
            case OnsTranslateClient.ENGINE_ANTHROPIC: {
                SharedPreferences p = OnsTranslateClient.prefs(activity);
                LinearLayout box = new LinearLayout(activity);
                box.setOrientation(LinearLayout.VERTICAL);
                int pad = dp(activity, 16);
                box.setPadding(pad, pad, pad, 0);
                EditText baseField = settingField(activity, box, R.string.engine_ons_anthropic_hint_base,
                        p.getString(OnsTranslateClient.KEY_ANTHROPIC_BASE, "https://api.anthropic.com"));
                EditText keyField = settingField(activity, box, R.string.engine_ons_anthropic_hint_key,
                        p.getString(OnsTranslateClient.KEY_ANTHROPIC_KEY, ""));
                EditText modelField = settingField(activity, box, R.string.engine_ons_anthropic_hint_model,
                        p.getString(OnsTranslateClient.KEY_ANTHROPIC_MODEL, "claude-3-5-haiku-latest"));
                new android.app.AlertDialog.Builder(activity)
                        .setTitle(R.string.engine_ons_translate_cfg_anthropic)
                        .setView(box)
                        .setPositiveButton(android.R.string.ok, (d, w) -> p.edit()
                                .putString(OnsTranslateClient.KEY_ANTHROPIC_BASE, textOr(baseField,
                                        "https://api.anthropic.com"))
                                .putString(OnsTranslateClient.KEY_ANTHROPIC_KEY, textOr(keyField, ""))
                                .putString(OnsTranslateClient.KEY_ANTHROPIC_MODEL, textOr(modelField,
                                        "claude-3-5-haiku-latest"))
                                .apply())
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return;
            }
            default:
                showApiSettings(activity);
            }
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
            OnsTranslateClient.translateWithEngine(activity, text, (translated, error) ->
                activity.runOnUiThread(() -> {
                    runBtn.setEnabled(true);
                    runBtn.setTextColor(ACCENT);
                    if (translated != null) output.setText(translated);
                    else output.setText(activity.getString(
                            R.string.engine_ons_extract_translate_failed, error));
                }));
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

    /** 字段映射行（标题为模型字段名字符串，非资源 id 的变体）。 */
    private static LinearLayout settingNavRowText(Activity activity, String title, TextView valueView) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(activity, 10), 0, dp(activity, 10));
        row.setClickable(true);
        TextView label = new TextView(activity);
        label.setText(title);
        label.setTextColor(TEXT_BODY);
        label.setTextSize(14);
        row.addView(label, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (valueView != null) row.addView(valueView);
        return row;
    }

    /** 制卡设置（AnkiDroid）：牌组 / 模型 / 字段映射（内容槽位→字段）+ 截图与语音源。
     *  参考 web game text 扩展 ankiFieldMap：模型字段列表自 AnkiDroid 读取，
     *  点字段行循环切换内容槽位（无/单词/读音/释义/例句/整页/译文/截图/例句语音/单词语音）。 */
    public static void showAnkiCardSettings(Activity activity) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        box.setPadding(pad, dp(activity, 8), pad, 0);

        EditText deckField = settingField(activity, box,
                R.string.engine_ons_anki_card_deck, AnkiCardConfig.deck(activity));
        EditText modelField = settingField(activity, box,
                R.string.engine_ons_anki_card_model, AnkiCardConfig.model(activity));

        // 例句语音源行（点击循环：自动→游戏→TTS→关）
        final String[] voiceKeys = {AnkiCardConfig.VOICE_AUTO, AnkiCardConfig.VOICE_GAME,
                AnkiCardConfig.VOICE_TTS, AnkiCardConfig.VOICE_OFF};
        final String[] voiceLabels = {
                activity.getString(R.string.engine_ons_anki_voice_auto),
                activity.getString(R.string.engine_ons_anki_voice_game),
                activity.getString(R.string.engine_ons_anki_voice_tts),
                activity.getString(R.string.engine_ons_anki_voice_off)};
        final String[] voiceState = {AnkiCardConfig.voiceSource(activity)};
        TextView voiceValue = new TextView(activity);
        styleNavValue(activity, voiceValue);
        Runnable syncVoice = () -> {
            int idx = 0;
            for (int i = 0; i < voiceKeys.length; i++) {
                if (voiceKeys[i].equals(voiceState[0])) { idx = i; break; }
            }
            voiceValue.setText(voiceLabels[idx]);
        };
        syncVoice.run();
        LinearLayout voiceRow = settingNavRow(activity, R.drawable.ic_volume,
                R.string.engine_ons_anki_card_voice, voiceValue);
        voiceRow.setOnClickListener(v -> {
            int idx = 0;
            for (int i = 0; i < voiceKeys.length; i++) {
                if (voiceKeys[i].equals(voiceState[0])) { idx = i; break; }
            }
            voiceState[0] = voiceKeys[(idx + 1) % voiceKeys.length];
            syncVoice.run();
        });
        box.addView(voiceRow);

        // 自动截图行（点击开关）
        final boolean[] captureState = {AnkiCardConfig.captureOnCard(activity)};
        TextView shotValue = new TextView(activity);
        styleNavValue(activity, shotValue);
        Runnable syncShot = () -> shotValue.setText(captureState[0]
                ? activity.getString(R.string.engine_ons_anki_on)
                : activity.getString(R.string.engine_ons_anki_off));
        syncShot.run();
        LinearLayout shotRow = settingNavRow(activity, R.drawable.ic_settings,
                R.string.engine_ons_anki_card_capture, shotValue);
        shotRow.setOnClickListener(v -> {
            captureState[0] = !captureState[0];
            syncShot.run();
        });
        box.addView(shotRow);

        TextView section = new TextView(activity);
        section.setText(R.string.engine_ons_anki_card_fields);
        section.setTextColor(TEXT_BUTTON);
        section.setTextSize(13);
        section.setPadding(0, dp(activity, 12), 0, 0);
        box.addView(section, matchWrap());

        // 模型字段读取：未装/未授权/模型不存在 → 提示 + 默认四字段兜底编辑
        String modelName = AnkiCardConfig.model(activity);
        java.util.List<String> modelFields = new java.util.ArrayList<>();
        String readError = null;
        try {
            AnkiDroidHelper helper = new AnkiDroidHelper(activity);
            if (!helper.isAnkiDroidInstalled()) {
                readError = activity.getString(R.string.engine_ons_extract_anki_not_installed);
            } else if (!helper.hasPermission()) {
                readError = activity.getString(R.string.engine_ons_extract_anki_need_permission);
            } else {
                Long mid = helper.findModelId(modelName);
                if (mid == null) {
                    readError = activity.getString(
                            R.string.engine_ons_extract_anki_model_missing, modelName);
                } else {
                    String[] fl = helper.getModelFieldNames(mid);
                    if (fl == null || fl.length == 0) {
                        readError = activity.getString(R.string.engine_ons_anki_card_field_read_failed);
                    } else {
                        java.util.Collections.addAll(modelFields, fl);
                    }
                }
            }
        } catch (Throwable t) {
            readError = activity.getString(R.string.engine_ons_anki_card_field_read_failed);
        }
        if (modelFields.isEmpty()) {
            java.util.Collections.addAll(modelFields, "Word", "Reading", "Meaning", "Sentence");
        }
        if (readError != null) {
            TextView warn = new TextView(activity);
            warn.setText(readError + activity.getString(R.string.engine_ons_anki_card_default_fields));
            warn.setTextColor(TEXT_DIM);
            warn.setTextSize(11);
            box.addView(warn, matchWrap());
        }

        // 字段行：点击循环内容槽位
        final java.util.Map<String, String> slotByField = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, String> e
                : AnkiCardConfig.fieldMap(activity).entrySet()) {
            if (!slotByField.containsKey(e.getValue())) slotByField.put(e.getValue(), e.getKey());
        }
        final String[] slotOrder = {null,
                AnkiCardConfig.SLOT_WORD, AnkiCardConfig.SLOT_READING,
                AnkiCardConfig.SLOT_MEANING, AnkiCardConfig.SLOT_SENTENCE,
                AnkiCardConfig.SLOT_PAGE, AnkiCardConfig.SLOT_TRANSLATION,
                AnkiCardConfig.SLOT_SCREENSHOT, AnkiCardConfig.SLOT_SENTENCE_AUDIO,
                AnkiCardConfig.SLOT_WORD_AUDIO};
        final String[] slotLabels = {
                activity.getString(R.string.engine_ons_anki_slot_none),
                activity.getString(R.string.engine_ons_anki_slot_word),
                activity.getString(R.string.engine_ons_anki_slot_reading),
                activity.getString(R.string.engine_ons_anki_slot_meaning),
                activity.getString(R.string.engine_ons_anki_slot_sentence),
                activity.getString(R.string.engine_ons_anki_slot_page),
                activity.getString(R.string.engine_ons_anki_slot_translation),
                activity.getString(R.string.engine_ons_anki_slot_screenshot),
                activity.getString(R.string.engine_ons_anki_slot_sentence_audio),
                activity.getString(R.string.engine_ons_anki_slot_word_audio)};
        for (final String field : modelFields) {
            TextView value = new TextView(activity);
            styleNavValue(activity, value);
            Runnable sync = () -> {
                String slot = slotByField.get(field);
                String label = slotLabels[0];
                if (slot != null) {
                    for (int i = 1; i < slotOrder.length; i++) {
                        if (slotOrder[i] != null && slotOrder[i].equals(slot)) {
                            label = slotLabels[i];
                            break;
                        }
                    }
                }
                value.setText(label);
            };
            sync.run();
            LinearLayout row = settingNavRowText(activity, field, value);
            row.setOnClickListener(v -> {
                String cur = slotByField.get(field);
                int idx2 = 0;
                for (int i = 1; i < slotOrder.length; i++) {
                    if (slotOrder[i] != null && slotOrder[i].equals(cur)) { idx2 = i; break; }
                }
                int next = (idx2 + 1) % slotOrder.length;
                String nextSlot = slotOrder[next];
                if (nextSlot == null) slotByField.remove(field); else slotByField.put(field, nextSlot);
                sync.run();
            });
            box.addView(row);
        }

        ScrollView scroller = new ScrollView(activity);
        scroller.addView(box);
        new android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.engine_ons_anki_card_title)
                .setView(scroller)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    AnkiCardConfig.setDeck(activity, textOr(deckField, AnkiCardConfig.DEFAULT_DECK));
                    AnkiCardConfig.setModel(activity, textOr(modelField, AnkiCardConfig.DEFAULT_MODEL));
                    AnkiCardConfig.setCaptureOnCard(activity, captureState[0]);
                    AnkiCardConfig.setVoiceSource(activity, voiceState[0]);
                    java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
                    for (java.util.Map.Entry<String, String> e : slotByField.entrySet()) {
                        if (e.getValue() != null && !e.getValue().isEmpty()) {
                            map.put(e.getValue(), e.getKey());
                        }
                    }
                    AnkiCardConfig.setFieldMap(activity, map);
                    Toast.makeText(activity, R.string.engine_ons_extract_api_saved,
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
    /** 档位循环辅助：取当前值的下一个（循环）。 */
    private static int cycleIndex(int[] values, int current) {
        return (current + 1) % values.length;
    }

    /** TTS 设置全量版（TrackReader TTS 设置对齐）：引擎 / 语速 / 音高 / 音量 /
     *  发音人 / MultiTTS 服务器 / HTTP 模板 / 试听。 */
    public static void showTtsSettings(Activity activity) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        box.setPadding(pad, dp(activity, 8), pad, 0);

        final String[] engineKeys = {OnsTtsEngines.ENGINE_MULTI, OnsTtsEngines.ENGINE_HTTP,
                OnsTtsEngines.ENGINE_SYSTEM};
        final String[] engineLabels = {
                activity.getString(R.string.engine_ons_tts_engine_multi),
                activity.getString(R.string.engine_ons_tts_engine_http),
                activity.getString(R.string.engine_ons_tts_engine_system)};

        // 自定义试听文本（用户可直接输入任意文本测试当前引擎与参数）
        TextView testHint = new TextView(activity);
        testHint.setText(R.string.engine_ons_tts_test_text);
        testHint.setTextColor(TEXT_DIM);
        testHint.setTextSize(11);
        final EditText testField = new EditText(activity);
        testField.setText(activity.getString(R.string.engine_ons_tts_test_default));
        testField.setTextSize(13);
        testField.setSingleLine(true);

        // HTTP 模板输入（声明在前供 lambda 引用；addView 顺序决定布局）
        TextView httpHint = new TextView(activity);
        httpHint.setText(R.string.engine_ons_tts_http_template);
        httpHint.setTextColor(TEXT_DIM);
        httpHint.setTextSize(11);
        final EditText httpField = new EditText(activity);
        httpField.setText(OnsTtsEngines.httpTemplate(activity));
        httpField.setTextSize(13);
        httpField.setSingleLine(true);
        final String[] engineState = {OnsTtsEngines.engine(activity)};
        Runnable syncHttpVisibility = () -> {
            boolean http = OnsTtsEngines.ENGINE_HTTP.equals(engineState[0]);
            httpHint.setAlpha(http ? 1f : 0.4f);
            httpField.setAlpha(http ? 1f : 0.4f);
            httpField.setEnabled(http);
        };

        // 语速 / 音高 / 音量（0..100，步进 25 档位循环）
        final int[] steps = {0, 25, 50, 75, 100};
        final String[] stepLabels = {"0", "25", "50", "75", "100"};

        TextView rateValue = new TextView(activity);
        styleNavValue(activity, rateValue);
        Runnable syncRate = () -> rateValue.setText(stepLabels[
                indexOfStep(steps, OnsTtsEngines.rate(activity))]);
        syncRate.run();
        LinearLayout rateRow = settingNavRow(activity, R.drawable.ic_volume,
                R.string.engine_ons_tts_rate, rateValue);
        rateRow.setOnClickListener(v -> {
            int i = indexOfStep(steps, OnsTtsEngines.rate(activity));
            int next = steps[cycleIndex(steps, i)];
            OnsTtsEngines.setRate(activity, next);
            syncRate.run();
        });
        box.addView(rateRow);

        TextView pitchValue = new TextView(activity);
        styleNavValue(activity, pitchValue);
        Runnable syncPitch = () -> pitchValue.setText(stepLabels[
                indexOfStep(steps, OnsTtsEngines.pitch(activity))]);
        syncPitch.run();
        LinearLayout pitchRow = settingNavRow(activity, R.drawable.ic_volume,
                R.string.engine_ons_tts_pitch, pitchValue);
        pitchRow.setOnClickListener(v -> {
            int i = indexOfStep(steps, OnsTtsEngines.pitch(activity));
            OnsTtsEngines.setPitch(activity, steps[cycleIndex(steps, i)]);
            syncPitch.run();
        });
        box.addView(pitchRow);

        TextView volumeValue = new TextView(activity);
        styleNavValue(activity, volumeValue);
        Runnable syncVolume = () -> volumeValue.setText(stepLabels[
                indexOfStep(steps, OnsTtsEngines.volume(activity))]);
        syncVolume.run();
        LinearLayout volumeRow = settingNavRow(activity, R.drawable.ic_volume,
                R.string.engine_ons_tts_volume, volumeValue);
        volumeRow.setOnClickListener(v -> {
            int i = indexOfStep(steps, OnsTtsEngines.volume(activity));
            OnsTtsEngines.setVolume(activity, steps[cycleIndex(steps, i)]);
            syncVolume.run();
        });
        box.addView(volumeRow);

        // 发音人行：点击按当前引擎拉取发音人列表（后台）弹单选
        TextView voiceValue = new TextView(activity);
        styleNavValue(activity, voiceValue);
        Runnable syncVoice = () -> {
            String v = OnsTtsEngines.voice(activity);
            voiceValue.setText(v.isEmpty()
                    ? activity.getString(R.string.engine_ons_tts_voice_default) : v);
        };
        syncVoice.run();
        LinearLayout voiceRow = settingNavRow(activity, R.drawable.ic_volume,
                R.string.engine_ons_tts_voice, voiceValue);
        voiceRow.setOnClickListener(v ->
                showVoicePicker(activity, engineState[0], syncVoice));
        box.addView(voiceRow);

        // MultiTTS 服务器行（点击弹输入框）
        TextView hostValue = new TextView(activity);
        styleNavValue(activity, hostValue);
        Runnable syncHost = () -> hostValue.setText(OnsTtsEngines.multiHost(activity).isEmpty()
                ? MultiTtsClient.HOST_PORT : OnsTtsEngines.multiHost(activity));
        syncHost.run();
        LinearLayout hostRow = settingNavRow(activity, R.drawable.ic_settings,
                R.string.engine_ons_tts_multi_host, hostValue);
        hostRow.setOnClickListener(v -> {
            LinearLayout inner = new LinearLayout(activity);
            inner.setOrientation(LinearLayout.VERTICAL);
            int pd = dp(activity, 16);
            inner.setPadding(pd, pd, pd, 0);
            EditText field = new EditText(activity);
            field.setText(OnsTtsEngines.multiHost(activity).isEmpty()
                    ? MultiTtsClient.HOST_PORT : OnsTtsEngines.multiHost(activity));
            field.setTextSize(13);
            field.setSingleLine(true);
            inner.addView(field, matchWrap());
            new android.app.AlertDialog.Builder(activity)
                    .setTitle(R.string.engine_ons_tts_multi_host)
                    .setView(inner)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        String hp = textOr(field, MultiTtsClient.HOST_PORT);
                        OnsTtsEngines.setMultiHost(activity, hp);
                        MultiTtsClient.setConfiguredHostPort(hp);
                        syncHost.run();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
        box.addView(hostRow);

        // 引擎行 + HTTP 模板
        TextView engineValue = new TextView(activity);
        styleNavValue(activity, engineValue);
        Runnable syncEngine = () -> {
            int idx = 0;
            for (int i = 0; i < engineKeys.length; i++) {
                if (engineKeys[i].equals(engineState[0])) { idx = i; break; }
            }
            engineValue.setText(engineLabels[idx]);
        };
        syncEngine.run();
        syncHttpVisibility.run();
        LinearLayout engineRow = settingNavRow(activity, R.drawable.ic_volume,
                R.string.engine_ons_tts_engine, engineValue);
        engineRow.setOnClickListener(v -> {
            int idx = 0;
            for (int i = 0; i < engineKeys.length; i++) {
                if (engineKeys[i].equals(engineState[0])) { idx = i; break; }
            }
            engineState[0] = engineKeys[(idx + 1) % engineKeys.length];
            syncEngine.run();
            syncHttpVisibility.run();
            syncVoice.run();
        });
        box.addView(engineRow);
        box.addView(testHint, matchWrap());
        box.addView(testField, matchWrap());
        box.addView(httpHint, matchWrap());
        box.addView(httpField, matchWrap());

        TextView systemHint = new TextView(activity);
        systemHint.setText(R.string.engine_ons_tts_system_hint);
        systemHint.setTextColor(TEXT_DIM);
        systemHint.setTextSize(11);
        box.addView(systemHint, matchWrap());

        // 自动朗读开关（无语音句自动 TTS；面板隐藏也生效——autoPlay 不依赖面板可见性）
        final boolean[] autoState = {com.core.ons.OnsTtsEngines.autoRead(activity)};
        TextView autoValue = new TextView(activity);
        styleNavValue(activity, autoValue);
        Runnable syncAuto = () -> autoValue.setText(autoState[0]
                ? activity.getString(R.string.engine_ons_anki_on)
                : activity.getString(R.string.engine_ons_anki_off));
        syncAuto.run();
        LinearLayout autoRow = settingNavRow(activity, R.drawable.ic_volume,
                R.string.engine_ons_tts_auto_read, autoValue);
        autoRow.setOnClickListener(v -> {
            autoState[0] = !autoState[0];
            com.core.ons.OnsTtsEngines.setAutoRead(activity, autoState[0]);
            syncAuto.run();
        });
        box.addView(autoRow);

        // 试听行：用当前引擎 + 全部参数朗读测试句
        LinearLayout testRow = settingNavRow(activity, R.drawable.ic_volume,
                R.string.engine_ons_tts_test, null);
        testRow.setOnClickListener(v -> {
            final String testText = textOr(testField,
                    activity.getString(R.string.engine_ons_tts_test_default));
            final String engine = engineState[0];
            if (OnsTtsEngines.ENGINE_SYSTEM.equals(engine)) {
                try {
                    android.speech.tts.TextToSpeech tts = new android.speech.tts.TextToSpeech(
                            activity, status -> { });
                    tts.setLanguage(java.util.Locale.CHINA);
                    tts.setSpeechRate(OnsTtsEngines.rate(activity) / 50f);
                    tts.setPitch(Math.max(0.5f, Math.min(2f, OnsTtsEngines.pitch(activity) / 50f)));
                    String vn = OnsTtsEngines.voice(activity);
                    if (!vn.isEmpty() && tts.getVoices() != null) {
                        for (android.speech.tts.Voice vo : tts.getVoices()) {
                            if (vo != null && vn.equals(vo.getName())) { tts.setVoice(vo); break; }
                        }
                    }
                    tts.speak(testText, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "tts_test");
                } catch (Throwable t) {
                    Toast.makeText(activity, String.valueOf(t.getMessage()), Toast.LENGTH_SHORT).show();
                }
                return;
            }
            new Thread(() -> {
                byte[] audio;
                if (OnsTtsEngines.ENGINE_HTTP.equals(engine)) {
                    audio = OnsTtsEngines.httpSynthesize(activity, testText);
                } else {
                    audio = MultiTtsClient.synthesizeOn(testText,
                            OnsTtsEngines.voice(activity),
                            OnsTtsEngines.rate(activity),
                            OnsTtsEngines.volume(activity),
                            OnsTtsEngines.pitch(activity),
                            OnsTtsEngines.multiHost(activity));
                }
                if (audio == null || audio.length == 0) return;
                try {
                    File out = new File(activity.getCacheDir(), "tts_settings_test.wav");
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        fos.write(audio);
                    }
                    android.media.MediaPlayer player = new android.media.MediaPlayer();
                    player.setDataSource(out.getAbsolutePath());
                    player.setOnCompletionListener(android.media.MediaPlayer::release);
                    player.prepare();
                    player.start();
                } catch (Throwable t) {
                    android.util.Log.w("OnsExtractSettingsDialogs", "tts test playback failed", t);
                }
            }).start();
            Toast.makeText(activity, R.string.engine_ons_tts_test_done, Toast.LENGTH_SHORT).show();
        });
        box.addView(testRow);

        ScrollView scroller = new ScrollView(activity);
        scroller.addView(box);
        new android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.engine_ons_tts_title)
                .setView(scroller)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    OnsTtsEngines.setEngine(activity, engineState[0]);
                    String tpl = httpField.getText() == null ? "" : httpField.getText().toString().trim();
                    if (tpl.isEmpty()) tpl = OnsTtsEngines.DEFAULT_HTTP_TEMPLATE;
                    OnsTtsEngines.setHttpTemplate(activity, tpl);
                    if (OnsTtsEngines.ENGINE_HTTP.equals(engineState[0])
                            && !OnsTtsEngines.isHttpTemplateValid(tpl)) {
                        Toast.makeText(activity, R.string.engine_ons_tts_http_template_invalid,
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static int indexOfStep(int[] steps, int value) {
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == value) return i;
        }
        return 2; // 默认 50
    }

    /** 发音人条目（UI 层）：value=存储值，display=显示文本（含语言代码）。 */
    private static class VoiceUiEntry {
        final String value;
        final String display;
        final String langTag;

        VoiceUiEntry(String value, String display, String langTag) {
            this.value = value;
            this.display = display;
            this.langTag = langTag == null ? "" : langTag;
        }
    }

    /** 从显示文本提取语言标签后缀（"xxx [zh-CN]" → "zh-CN"）。 */
    private static String langOfDisplay(String display) {
        int i = display.lastIndexOf('[');
        int j = display.lastIndexOf(']');
        return (i >= 0 && j > i) ? display.substring(i + 1, j) : "";
    }

    /** 发音人选择：按引擎拉取（含 locale）→ 先选语言（筛选）→ 再选发音人。 */
    private static void showVoicePicker(Activity activity, String engine, Runnable onChanged) {
        if (OnsTtsEngines.ENGINE_HTTP.equals(engine)) {
            Toast.makeText(activity, R.string.engine_ons_tts_voice_pick_http, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(activity, R.string.engine_ons_tts_voice_loading, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            java.util.List<VoiceUiEntry> entries = new java.util.ArrayList<>();
            if (OnsTtsEngines.ENGINE_MULTI.equals(engine)) {
                for (MultiTtsClient.VoiceEntry e
                        : MultiTtsClient.fetchVoicePairsOn(OnsTtsEngines.multiHost(activity))) {
                    String locale = e.locale;
                    entries.add(new VoiceUiEntry(e.value,
                            e.display + (locale.isEmpty() ? "" : "  [" + locale + "]"), locale));
                }
            } else {
                try {
                    java.util.concurrent.CountDownLatch latch =
                            new java.util.concurrent.CountDownLatch(1);
                    final java.util.List<android.speech.tts.Voice> voices =
                            new java.util.ArrayList<>();
                    android.speech.tts.TextToSpeech tts = new android.speech.tts.TextToSpeech(
                            activity, status -> latch.countDown());
                    latch.await(4, java.util.concurrent.TimeUnit.SECONDS);
                    java.util.Set<android.speech.tts.Voice> set = tts.getVoices();
                    if (set != null) voices.addAll(set);
                    tts.shutdown();
                    for (android.speech.tts.Voice vo : voices) {
                        java.util.Locale lc = vo.getLocale();
                        String tag = lc == null ? "" : lc.toLanguageTag();
                        entries.add(new VoiceUiEntry(vo.getName(),
                                vo.getName() + "  [" + tag + "]", tag));
                    }
                } catch (Throwable t) {
                    android.util.Log.w("OnsExtractSettingsDialogs", "system voices failed", t);
                }
            }
            activity.runOnUiThread(() -> showLanguageFilter(activity, entries, onChanged));
        }, "tts-voice-picker").start();
    }

    /** 第一级：语言筛选（从条目 locale 归并出语言代码列表 + 全部）。 */
    private static void showLanguageFilter(Activity activity,
            java.util.List<VoiceUiEntry> entries, Runnable onChanged) {
        java.util.Set<String> langs = new java.util.LinkedHashSet<>();
        for (VoiceUiEntry e : entries) {
            String tag = e.langTag;
            if (!tag.isEmpty()) {
                String lang = tag.contains("-") ? tag.substring(0, tag.indexOf('-')) : tag;
                langs.add(lang.toUpperCase(java.util.Locale.ROOT));
            }
        }
        java.util.List<String> options = new java.util.ArrayList<>();
        options.add(activity.getString(R.string.engine_ons_tts_voice_all));
        options.addAll(langs);
        String current = OnsTtsEngines.voice(activity);
        VoiceUiEntry currentEntry = null;
        for (VoiceUiEntry e : entries) {
            if (e.value.equals(current)) { currentEntry = e; break; }
        }
        int checked = 0;
        if (currentEntry != null) {
            String cur = langOfDisplay(currentEntry.display);
            if (!cur.isEmpty()) {
                String cl = cur.contains("-") ? cur.substring(0, cur.indexOf('-'))
                        .toUpperCase(java.util.Locale.ROOT) : cur.toUpperCase(java.util.Locale.ROOT);
                int idx = options.indexOf(cl);
                if (idx > 0) checked = idx;
            }
        }
        final int checkedFinal = checked;
        new android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.engine_ons_tts_voice_lang)
                .setSingleChoiceItems(options.toArray(new String[0]), checkedFinal, (d, which) -> {
                    String lang = which == 0 ? "" : options.get(which);
                    d.dismiss();
                    showVoiceList(activity, entries, lang, onChanged);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 第二级：语言筛选后的发音人单选（显示名带语言代码）。 */
    private static void showVoiceList(Activity activity, java.util.List<VoiceUiEntry> entries,
            String langFilter, Runnable onChanged) {
        java.util.List<VoiceUiEntry> filtered = new java.util.ArrayList<>();
        for (VoiceUiEntry e : entries) {
            if (langFilter.isEmpty()) {
                filtered.add(e);
            } else {
                String tag = e.langTag.toUpperCase(java.util.Locale.ROOT);
                if (tag.startsWith(langFilter + "-") || tag.equals(langFilter)) filtered.add(e);
            }
        }
        if (filtered.isEmpty()) {
            Toast.makeText(activity, R.string.engine_ons_tts_voice_none, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[filtered.size()];
        String current = OnsTtsEngines.voice(activity);
        int checked = 0;
        for (int i = 0; i < filtered.size(); i++) {
            items[i] = filtered.get(i).display;
            if (filtered.get(i).value.equals(current)) checked = i;
        }
        final int checkedFinal = checked;
        new android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.engine_ons_tts_voice)
                .setSingleChoiceItems(items, checkedFinal, (d, which) -> {
                    OnsTtsEngines.setVoice(activity, filtered.get(which).value);
                    onChanged.run();
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }


}
