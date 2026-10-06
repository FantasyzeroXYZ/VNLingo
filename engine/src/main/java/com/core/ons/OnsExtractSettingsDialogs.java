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
        box.addView(httpHint, matchWrap());
        box.addView(httpField, matchWrap());

        TextView systemHint = new TextView(activity);
        systemHint.setText(R.string.engine_ons_tts_system_hint);
        systemHint.setTextColor(TEXT_DIM);
        systemHint.setTextSize(11);
        box.addView(systemHint, matchWrap());

        // 试听行：用当前引擎 + 全部参数朗读测试句
        LinearLayout testRow = settingNavRow(activity, R.drawable.ic_volume,
                R.string.engine_ons_tts_test, null);
        testRow.setOnClickListener(v -> {
            final String testText = "音声テスト。语音测试。Voice test.";
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

    /** 发音人选择（按引擎拉取列表，后台线程 + 主线程单选弹窗）。 */
    private static void showVoicePicker(Activity activity, String engine, Runnable onChanged) {
        if (OnsTtsEngines.ENGINE_HTTP.equals(engine)) {
            Toast.makeText(activity, R.string.engine_ons_tts_voice_pick_http, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(activity, R.string.engine_ons_tts_voice_loading, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            java.util.List<String> names = new java.util.ArrayList<>();
            names.add("");
            if (OnsTtsEngines.ENGINE_MULTI.equals(engine)) {
                names.addAll(MultiTtsClient.fetchVoiceNamesOn(OnsTtsEngines.multiHost(activity)));
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
                    java.util.Set<String> locales = new java.util.HashSet<>();
                    for (android.speech.tts.Voice vo : voices) {
                        java.util.Locale lc = vo.getLocale();
                        String tag = lc == null ? "" : lc.getLanguage();
                        if (!tag.isEmpty() && locales.add(tag)) {
                            names.add("#" + tag.toUpperCase(java.util.Locale.ROOT));
                        }
                    }
                    for (android.speech.tts.Voice vo : voices) {
                        names.add(vo.getName());
                    }
                } catch (Throwable t) {
                    android.util.Log.w("OnsExtractSettingsDialogs", "system voices failed", t);
                }
            }
            final java.util.List<String> finalNames = names;
            activity.runOnUiThread(() -> {
                String[] items = finalNames.toArray(new String[0]);
                String current = OnsTtsEngines.voice(activity);
                int checked = 0;
                for (int i = 0; i < items.length; i++) {
                    if (items[i].equals(current)) { checked = i; break; }
                }
                new android.app.AlertDialog.Builder(activity)
                        .setTitle(R.string.engine_ons_tts_voice)
                        .setSingleChoiceItems(items, checked, (d, which) -> {
                            OnsTtsEngines.setVoice(activity, items[which]);
                            onChanged.run();
                            d.dismiss();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        }, "tts-voice-picker").start();
    }

}
