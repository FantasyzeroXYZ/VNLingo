package com.core.ons;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 翻译客户端：OpenAI 兼容 chat API（OpenAI / 智谱 / DeepSeek / Moonshot /
 * Ollama 均可），与参考实现 D:\Desktop\test-flutter\anki 的 api_client.dart
 * 翻译路径一致（POST {base}/chat/completions，system 提示词指定目标语言、
 * temperature 0，解析 choices[0]）。无 DeepL/Google 云依赖。
 *
 * 配置持久化在 prefs（默认文件名），UI 由面板承载。
 */
public final class OnsTranslateClient {

    private static final String TAG = "OnsTranslate";
    private static final String PREFS = "ons_extract_api";
    public static final String KEY_BASE_URL = "base_url";
    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_MODEL = "model";
    public static final String KEY_TARGET = "target";
    /** 翻译引擎："api"（OpenAI 兼容，默认）| "local"（ML Kit 离线）| 扩展引擎（见 ENGINES）。 */
    public static final String KEY_ENGINE = "engine";
    /** 本地引擎源语言："auto"（启发式，默认）| zh/en/ja/ko。 */
    public static final String KEY_SOURCE = "source";

    public static final String ENGINE_API = "api";
    public static final String ENGINE_LOCAL = "local";

    // ---- 扩展翻译引擎（实现参考 MoeTranslate / overlay-translator，见 OnsTranslateEngines）----
    /** 免费：Google 非官方 gtx 端点（无 key，国内需代理）。 */
    public static final String ENGINE_GOOGLE = "google";
    /** 免费：Bing 网页翻译（无 key，国内直连）。 */
    public static final String ENGINE_BING = "bing";
    /** DeepL API（key + host：free=api-free.deepl.com / pro=api.deepl.com）。 */
    public static final String ENGINE_DEEPL = "deepl";
    /** 百度翻译开放平台（appid + key）。 */
    public static final String ENGINE_BAIDU = "baidu";
    /** Google Gemini AI（key + model）。 */
    public static final String ENGINE_GEMINI = "gemini";
    /** Anthropic Claude AI（key + model + base 可配）。 */
    public static final String ENGINE_ANTHROPIC = "anthropic";

    /** 扩展引擎清单（翻译设置弹窗按此循环）。 */
    public static final String[] ENGINES = {
            ENGINE_API, ENGINE_LOCAL, ENGINE_GOOGLE, ENGINE_BING,
            ENGINE_DEEPL, ENGINE_BAIDU, ENGINE_GEMINI, ENGINE_ANTHROPIC,
    };

    // ---- 扩展引擎配置键（同 prefs 文件 ons_extract_api）----
    public static final String KEY_DEEPL_HOST = "deepl_host";
    public static final String KEY_DEEPL_KEY = "deepl_key";
    public static final String KEY_BAIDU_APPID = "baidu_appid";
    public static final String KEY_BAIDU_KEY = "baidu_key";
    public static final String KEY_GEMINI_KEY = "gemini_key";
    public static final String KEY_GEMINI_MODEL = "gemini_model";
    public static final String KEY_ANTHROPIC_BASE = "anthropic_base";
    public static final String KEY_ANTHROPIC_KEY = "anthropic_key";
    public static final String KEY_ANTHROPIC_MODEL = "anthropic_model";

    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_MODEL = "gpt-4o-mini";
    public static final String DEFAULT_TARGET = "中文";
    public static final String DEFAULT_SOURCE = "auto";

    public interface Callback {
        /** translated 为 null 时 error 携带失败信息。 */
        void onResult(String translated, String error);
    }

    private OnsTranslateClient() {
    }

    /** MULTI_PROCESS：翻译设置现由应用设置页（主进程）写入、引擎进程读取，
     *  每次 getSharedPreferences 都检查文件变更重载，避免引擎进程读到陈旧缓存。 */
    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS);
    }

    /** 已配置即可翻译：base/model/target 为空时回退默认（Ollama 等本地服务可无 key）。 */
    public static boolean isConfigured(Context context) {
        String engine = getEngine(context);
        if (ENGINE_LOCAL.equals(engine)) return true;
        if (ENGINE_GOOGLE.equals(engine) || ENGINE_BING.equals(engine)) return true; // 免费免配置
        SharedPreferences p = prefs(context);
        if (ENGINE_DEEPL.equals(engine)) return !p.getString(KEY_DEEPL_KEY, "").isEmpty();
        if (ENGINE_BAIDU.equals(engine)) return !p.getString(KEY_BAIDU_APPID, "").isEmpty()
                && !p.getString(KEY_BAIDU_KEY, "").isEmpty();
        if (ENGINE_GEMINI.equals(engine)) return !p.getString(KEY_GEMINI_KEY, "").isEmpty();
        if (ENGINE_ANTHROPIC.equals(engine)) return !p.getString(KEY_ANTHROPIC_KEY, "").isEmpty();
        return !p.getString(KEY_BASE_URL, "").isEmpty();
    }

    public static String getEngine(Context context) {
        return prefs(context).getString(KEY_ENGINE, ENGINE_API);
    }

    /** 按所选引擎翻译（本地 ML Kit / OpenAI 兼容 API / 扩展引擎）；后台线程执行。 */
    public static void translateWithEngine(Context context, String text, Callback callback) {
        String engine = getEngine(context);
        if (ENGINE_LOCAL.equals(engine)) {
            new Thread(() -> {
                try {
                    String source = prefs(context).getString(KEY_SOURCE, DEFAULT_SOURCE);
                    String resolved = "auto".equals(source) ? guessLanguage(text) : source;
                    String target = targetCode(context);
                    String result = OnsMlKitTranslator.translate(context, text, resolved, target);
                    callback.onResult(result, null);
                } catch (Throwable t) {
                    Log.w(TAG, "local translate failed", t);
                    callback.onResult(null, t.getMessage() == null ? t.toString() : t.getMessage());
                }
            }, "ons-translate-local").start();
            return;
        }
        if (ENGINE_GOOGLE.equals(engine) || ENGINE_BING.equals(engine)
                || ENGINE_DEEPL.equals(engine) || ENGINE_BAIDU.equals(engine)
                || ENGINE_GEMINI.equals(engine) || ENGINE_ANTHROPIC.equals(engine)) {
            new Thread(() -> {
                String translated = null;
                String error = null;
                try {
                    translated = translateViaExtension(context, engine, text);
                } catch (Throwable t) {
                    Log.w(TAG, engine + " translate failed", t);
                    error = t.getMessage() == null ? t.toString() : t.getMessage();
                }
                // 引擎线程不得因消费方回调异常而死亡（回调方负责自行切主线程）
                try {
                    callback.onResult(translated, error);
                } catch (Throwable t) {
                    Log.w(TAG, "translate callback failed", t);
                }
            }, "ons-translate-ext").start();
            return;
        }
        translate(context, text, callback);
    }

    /** 扩展引擎路由（实现移植自 MoeTranslate / overlay-translator）。 */
    private static String translateViaExtension(Context context, String engine, String text)
            throws Exception {
        SharedPreferences p = prefs(context);
        String targetCode = targetCode(context);
        String sourceCode = p.getString(KEY_SOURCE, DEFAULT_SOURCE);
        String targetName = targetDisplayName(targetCode);
        String sourceName = "auto".equals(sourceCode) ? "自动检测" : sourceDisplayName(sourceCode);
        switch (engine) {
            case ENGINE_GOOGLE:
                return OnsTranslateEngines.google(text, sourceCode, targetCode);
            case ENGINE_BING:
                return OnsTranslateEngines.bing(text, sourceCode, targetCode);
            case ENGINE_DEEPL:
                return OnsTranslateEngines.deepl(p.getString(KEY_DEEPL_HOST, ""),
                        p.getString(KEY_DEEPL_KEY, ""), text, sourceCode, targetCode);
            case ENGINE_BAIDU:
                return OnsTranslateEngines.baidu(p.getString(KEY_BAIDU_APPID, ""),
                        p.getString(KEY_BAIDU_KEY, ""), text, sourceCode, targetCode);
            case ENGINE_GEMINI:
                return OnsTranslateEngines.gemini(p.getString(KEY_GEMINI_KEY, ""),
                        p.getString(KEY_GEMINI_MODEL, "gemini-1.5-flash"),
                        text, targetName, sourceName);
            case ENGINE_ANTHROPIC:
                return OnsTranslateEngines.anthropic(p.getString(KEY_ANTHROPIC_BASE, ""),
                        p.getString(KEY_ANTHROPIC_KEY, ""),
                        p.getString(KEY_ANTHROPIC_MODEL, "claude-3-5-haiku-latest"),
                        text, targetName, sourceName);
            default:
                throw new IllegalStateException("unknown engine: " + engine);
        }
    }

    /** 引擎码 → 显示名（设置弹窗用；资源在调用方按引擎码映射）。 */
    public static String targetDisplayName(String code) {
        if ("en".equals(code)) return "英语";
        if ("ja".equals(code)) return "日语";
        if ("ko".equals(code)) return "韩语";
        return "中文";
    }

    public static String sourceDisplayName(String code) {
        return targetDisplayName(code);
    }

    /** 目标语言显示名 → ML Kit 语言码（本地引擎用）。 */
    public static String targetCode(Context context) {
        String target = prefs(context).getString(KEY_TARGET, DEFAULT_TARGET);
        if (target.contains("英")) return "en";
        if (target.contains("日")) return "ja";
        if (target.contains("韩")) return "ko";
        return "zh";
    }

    /** 轻量语言启发（本地引擎 auto 源）：假名→日、谚文→韩、CJK 汉字→中、默认英。 */
    static String guessLanguage(String text) {
        if (text == null || text.isEmpty()) return "zh";
        for (char c : text.toCharArray()) {
            int cp = (int) c;
            if ((cp >= 0x3041 && cp <= 0x309F) || (cp >= 0x30A1 && cp <= 0x30FF)) return "ja";
            if (cp >= 0xAC00 && cp <= 0xD7AF) return "ko";
        }
        for (char c : text.toCharArray()) {
            int cp = (int) c;
            if ((cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x4E00 && cp <= 0x9FFF)) return "zh";
        }
        return "en";
    }

    /** 翻译文本（后台线程执行，回调切回调用线程不保证——面板侧自行 main.post）。 */
    public static void translate(Context context, String text, Callback callback) {
        SharedPreferences p = prefs(context);
        String baseUrl = p.getString(KEY_BASE_URL, DEFAULT_BASE_URL);
        String apiKey = p.getString(KEY_API_KEY, "");
        String model = p.getString(KEY_MODEL, DEFAULT_MODEL);
        String target = p.getString(KEY_TARGET, DEFAULT_TARGET);
        if (baseUrl.isEmpty()) {
            callback.onResult(null, "not configured");
            return;
        }
        new Thread(() -> {
            try {
                String translated = chatCompletion(baseUrl, apiKey, model,
                        "你是专业翻译。将用户输入翻译为「" + target + "」，只输出译文，"
                                + "不要解释、不要引号、不要保留原文。", text);
                callback.onResult(translated, null);
            } catch (Throwable t) {
                Log.w(TAG, "translate failed", t);
                callback.onResult(null, t.getMessage() == null ? t.toString() : t.getMessage());
            }
        }, "ons-translate").start();
    }

    /** OpenAI 兼容 chat/completions 单轮调用，返回首条回复文本。 */
    private static String chatCompletion(String baseUrl, String apiKey, String model,
                                         String system, String user) throws Exception {
        String url = baseUrl.endsWith("/") ? baseUrl + "chat/completions"
                : baseUrl + "/chat/completions";
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", system));
        messages.put(new JSONObject().put("role", "user").put("content", user));
        body.put("messages", messages);

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String response = stream == null ? "" : readAll(stream);
            if (code >= 400) {
                throw new IllegalStateException("HTTP " + code + ": " + truncate(response));
            }
            JSONObject obj = new JSONObject(response);
            JSONArray choices = obj.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject first = choices.optJSONObject(0);
                if (first != null) {
                    JSONObject message = first.optJSONObject("message");
                    String content = message == null
                            ? first.optString("text", "") : message.optString("content", "");
                    if (!content.isEmpty()) return content.trim();
                }
            }
            throw new IllegalStateException("empty choices: " + truncate(response));
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
