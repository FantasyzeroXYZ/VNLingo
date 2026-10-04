package com.core.ons;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 本地离线翻译引擎（ML Kit on-device translate），对齐参考实现
 * D:\Desktop\test-flutter\anki 的 core/translate/translation_service.dart：
 * - 按语言管理模型（zh/en/ja/ko，各约 30MB，按需下载/删除）；
 * - translate 需源/目标两侧模型就位（由调用方先 downloadModel）。
 * 全部同步阻塞接口（Tasks.await），调用方自行放后台线程。
 */
public final class OnsMlKitTranslator {

    private static final String TAG = "OnsMlKitTranslate";

    /** 支持的语言码（与参考 TranslateLanguageOption 对齐）。 */
    public static final String[] LANGS = {"zh", "en", "ja", "ko"};

    public static boolean isSupported(String code) {
        for (String lang : LANGS) {
            if (lang.equalsIgnoreCase(code)) return true;
        }
        return false;
    }

    private static String mlCode(String code) {
        switch (code.toLowerCase(Locale.ROOT)) {
            case "zh": return TranslateLanguage.CHINESE;
            case "en": return TranslateLanguage.ENGLISH;
            case "ja": return TranslateLanguage.JAPANESE;
            case "ko": return TranslateLanguage.KOREAN;
            default: return TranslateLanguage.CHINESE;
        }
    }

    /** 模型是否已下载。 */
    public static boolean isModelDownloaded(Context context, String code) {
        try {
            Set<TranslateRemoteModel> models = Tasks.await(
                    RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class),
                    10, TimeUnit.SECONDS);
            String ml = mlCode(code);
            for (TranslateRemoteModel model : models) {
                if (ml.equals(model.getLanguage())) return true;
            }
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "isModelDownloaded failed", t);
            return false;
        }
    }

    /** 各语言下载状态（键为语言码）。 */
    public static Set<String> downloadedModels(Context context) {
        Set<String> out = new HashSet<>();
        try {
            Set<TranslateRemoteModel> models = Tasks.await(
                    RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class),
                    10, TimeUnit.SECONDS);
            for (TranslateRemoteModel model : models) {
                out.add(model.getLanguage());
            }
        } catch (Throwable t) {
            Log.w(TAG, "downloadedModels failed", t);
        }
        return out;
    }

    /** 按需下载模型（阻塞，最长 5 分钟）。 */
    public static void downloadModel(Context context, String code) throws Exception {
        TranslateRemoteModel model = new TranslateRemoteModel.Builder(mlCode(code)).build();
        Tasks.await(RemoteModelManager.getInstance().download(model, new DownloadConditions.Builder().build()),
                5, TimeUnit.MINUTES);
    }

    /** 删除模型。 */
    public static void deleteModel(Context context, String code) throws Exception {
        TranslateRemoteModel model = new TranslateRemoteModel.Builder(mlCode(code)).build();
        Tasks.await(RemoteModelManager.getInstance().deleteDownloadedModel(model), 30, TimeUnit.SECONDS);
    }

    /** 翻译文本（阻塞；源/目标语言不同才调 ML Kit，相同直接返回原文）。 */
    public static String translate(Context context, String text, String source, String target) throws Exception {
        if (text == null || text.trim().isEmpty()) return "";
        String src = mlCode(source);
        String dst = mlCode(target);
        if (src.equals(dst)) return text;
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(src)
                .setTargetLanguage(dst)
                .build();
        Translator translator = Translation.getClient(options);
        try {
            DownloadConditions conditions = new DownloadConditions.Builder().build();
            Tasks.await(translator.downloadModelIfNeeded(conditions), 5, TimeUnit.MINUTES);
            return Tasks.await(translator.translate(text), 60, TimeUnit.SECONDS);
        } finally {
            translator.close();
        }
    }
}
