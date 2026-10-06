package com.core.ons;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 扩展翻译引擎集（实现参考 MoeTranslate-5.5.1 与 overlay-translator-0.4.7
 * 移植，OkHttp/kotlinx-serialization → HttpURLConnection/org.json，零新增依赖）：
 *
 * 免费源（无 key）：
 *   - [google]：translate.googleapis.com 非官方 gtx 端点（overlay GoogleTranslator）；
 *     可能限流/改端点，国内需代理
 *   - [bing]：cn.bing.com 网页翻译（MoeTranslate BingTranslation）——先抓翻译页
 *     提取 IG/IID/token/key（含 Cookie），再 POST ttranslatev3；国内直连可用
 * 常用 API：
 *   - [deepl]：DeepL API /v2/translate，DeepL-Auth-Key，host 可配
 *     （free 用 api-free.deepl.com，pro 用 api.deepl.com）
 *   - [baidu]：百度翻译开放平台 fanyi-api.baidu.com，sign = md5(appid+q+salt+key)，
 *     q 原文（含换行）参与签名，表单体走 URL 编码
 * AI：
 *   - [gemini]：Google AI generateContent REST（MoeTranslate geminiapi 的等价
 *     REST 实现），安全档位全部 BLOCK_NONE
 *   - [anthropic]：Claude Messages API（overlay AnthropicApi），x-api-key +
 *     anthropic-version 2023-06-01
 *
 * Sakura 等 galgame 向 LLM 为 OpenAI 兼容端点，直接用现有 api 引擎填其地址即可。
 */
public final class OnsTranslateEngines {

    private static final String TAG = "OnsTranslateEngines";
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 60000;
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/107.0.0.0 Safari/537.36";

    private OnsTranslateEngines() {
    }

    // ------------------------------------------------------------------
    // Google（免费，非官方 gtx 端点）
    // ------------------------------------------------------------------

    public static String google(String text, String source, String target) throws Exception {
        String sl = "zh".equals(source) || "en".equals(source) || "ja".equals(source)
                || "ko".equals(source) ? source : "auto";
        String tl = googleLang(target);
        String url = "https://translate.googleapis.com/translate_a/single?client=gtx"
                + "&sl=" + sl + "&tl=" + tl + "&dt=t&q="
                + URLEncoder.encode(text, "UTF-8");
        HttpURLConnection conn = open(url);
        try {
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", UA);
            int code = conn.getResponseCode();
            String body = readBody(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) throw new IllegalStateException("Google HTTP " + code + ": " + trunc(body));
            // 响应为深嵌套数组：[[[译片段,原片段,null,...],...], ...]——取 [0][i][0] 拼接
            JSONArray root = new JSONArray(body);
            JSONArray segments = root.optJSONArray(0);
            if (segments == null) throw new IllegalStateException("Google 响应缺 segments: " + trunc(body));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < segments.length(); i++) {
                JSONArray seg = segments.optJSONArray(i);
                if (seg != null && !seg.isNull(0)) sb.append(seg.optString(0));
            }
            String out = sb.toString().trim();
            if (out.isEmpty()) throw new IllegalStateException("Google 译文为空");
            return out;
        } finally {
            conn.disconnect();
        }
    }

    private static String googleLang(String code) {
        return "zh".equals(code) ? "zh-CN" : code;
    }

    // ------------------------------------------------------------------
    // Bing（免费，网页翻译 token 抓取；国内直连可用）
    // ------------------------------------------------------------------

    private static final Pattern BING_IG = Pattern.compile("IG:\"(.*?)\"");
    private static final Pattern BING_IID =
            Pattern.compile("<div[ ]+id=\"tta_outGDCont\"[ ]+data-iid=\"(.*?)\">");
    private static final Pattern BING_TOKEN =
            Pattern.compile("var params_AbusePreventionHelper = (.*?);");

    public static String bing(String text, String source, String target) throws Exception {
        String[] token = bingTokenInfo();
        String from = bingLang(source);
        String to = bingLang(target);
        String url = "https://cn.bing.com/ttranslatev3?isVertical=1&IG="
                + URLEncoder.encode(token[0], "UTF-8")
                + "&IID=" + URLEncoder.encode(token[1], "UTF-8");
        String form = "fromLang=" + URLEncoder.encode(from, "UTF-8")
                + "&to=" + URLEncoder.encode(to, "UTF-8")
                + "&text=" + URLEncoder.encode(text, "UTF-8")
                + "&tryFetchingGenderDebiasedTranslations=true"
                + "&token=" + URLEncoder.encode(token[3], "UTF-8")
                + "&key=" + URLEncoder.encode(token[2], "UTF-8");
        HttpURLConnection conn = open(url);
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", "https://cn.bing.com/Translator");
            conn.setRequestProperty("Cookie", token[4]);
            conn.setDoOutput(true);
            byte[] payload = form.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            int code = conn.getResponseCode();
            String body = readBody(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) throw new IllegalStateException("Bing HTTP " + code + ": " + trunc(body));
            JSONArray root = new JSONArray(body);
            JSONObject first = root.optJSONObject(0);
            if (first == null) throw new IllegalStateException("Bing 响应异常: " + trunc(body));
            String translated = first.optJSONArray("translations") != null
                    ? first.optJSONArray("translations").optJSONObject(0) != null
                    ? first.optJSONArray("translations").optJSONObject(0).optString("text", "") : ""
                    : "";
            if (translated.isEmpty()) throw new IllegalStateException("Bing 译文为空: " + trunc(body));
            return translated;
        } finally {
            conn.disconnect();
        }
    }

    /** 抓翻译页提取 IG/IID/key/token 与 Cookie；返回 [ig, iid, key, token, cookie]。 */
    private static String[] bingTokenInfo() throws Exception {
        HttpURLConnection conn = open("https://cn.bing.com/Translator");
        List<String> cookies = new ArrayList<>();
        String html;
        try {
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", UA);
            int code = conn.getResponseCode();
            html = readBody(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) throw new IllegalStateException("Bing 页面 HTTP " + code);
            for (java.util.Map.Entry<String, List<String>> h : conn.getHeaderFields().entrySet()) {
                if (h.getKey() != null && "set-cookie".equalsIgnoreCase(h.getKey()) && h.getValue() != null) {
                    for (String c : h.getValue()) {
                        if (c != null && !c.isEmpty()) cookies.add(c.split(";", 2)[0]);
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
        Matcher ig = BING_IG.matcher(html);
        Matcher iid = BING_IID.matcher(html);
        Matcher token = BING_TOKEN.matcher(html);
        if (!ig.find() || !iid.find() || !token.find()) {
            throw new IllegalStateException("Bing token 提取失败（页面结构变化）");
        }
        String params = token.group(1).trim();
        if (params.startsWith("[")) params = params.substring(1, params.length() - 1);
        String[] parts = params.split(",");
        if (parts.length < 2) throw new IllegalStateException("Bing token 参数不完整");
        String key = stripQuotes(parts[0].trim());
        String value = stripQuotes(parts[1].trim());
        StringBuilder cookie = new StringBuilder();
        for (String c : cookies) cookie.append(c).append("; ");
        return new String[]{ig.group(1), iid.group(1), key, value, cookie.toString()};
    }

    private static String bingLang(String code) {
        if (code == null || code.isEmpty() || "auto".equals(code)) return "auto";
        if ("zh".equals(code)) return "zh-Hans";
        return code;
    }

    // ------------------------------------------------------------------
    // DeepL API（host 可配：free=api-free.deepl.com / pro=api.deepl.com）
    // ------------------------------------------------------------------

    public static String deepl(String host, String apiKey, String text,
                               String source, String target) throws Exception {
        if (apiKey == null || apiKey.isEmpty()) throw new IllegalStateException("DeepL key 未配置");
        String normalized = host == null || host.isEmpty()
                ? "https://api-free.deepl.com" : host.trim();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://" + normalized;
        }
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        JSONObject body = new JSONObject();
        body.put("text", new JSONArray().put(text));
        body.put("target_lang", target.toUpperCase(Locale.ROOT));
        if (source != null && !"auto".equals(source)) {
            body.put("source_lang", source.toUpperCase(Locale.ROOT));
        }
        HttpURLConnection conn = open(normalized + "/v2/translate");
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "DeepL-Auth-Key " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            int code = conn.getResponseCode();
            String resp = readBody(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) throw new IllegalStateException("DeepL HTTP " + code + ": " + trunc(resp));
            JSONArray translations = new JSONObject(resp).optJSONArray("translations");
            String out = translations != null && translations.length() > 0
                    ? translations.getJSONObject(0).optString("text", "") : "";
            if (out.isEmpty()) throw new IllegalStateException("DeepL 译文为空");
            return out;
        } finally {
            conn.disconnect();
        }
    }

    // ------------------------------------------------------------------
    // 百度翻译开放平台（sign = md5(appid + q + salt + key)，q 原文参与签名）
    // ------------------------------------------------------------------

    public static String baidu(String appId, String appKey, String text,
                               String source, String target) throws Exception {
        if (appId == null || appId.isEmpty() || appKey == null || appKey.isEmpty()) {
            throw new IllegalStateException("百度翻译 APPID / 密钥未配置");
        }
        String salt = String.valueOf(System.currentTimeMillis());
        String sign = md5Hex(appId + text + salt + appKey);
        String form = "q=" + URLEncoder.encode(text, "UTF-8")
                + "&from=" + URLEncoder.encode(baiduLang(source), "UTF-8")
                + "&to=" + URLEncoder.encode(baiduLang(target), "UTF-8")
                + "&appid=" + URLEncoder.encode(appId, "UTF-8")
                + "&salt=" + salt
                + "&sign=" + sign;
        HttpURLConnection conn = open("https://fanyi-api.baidu.com/api/trans/vip/translate");
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            byte[] payload = form.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            int code = conn.getResponseCode();
            String resp = readBody(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) throw new IllegalStateException("百度翻译 HTTP " + code + ": " + trunc(resp));
            JSONObject obj = new JSONObject(resp);
            String err = obj.optString("error_code", "");
            if (!err.isEmpty() && !"52000".equals(err)) {
                throw new IllegalStateException("百度翻译 " + err + ": " + obj.optString("error_msg", ""));
            }
            JSONArray results = obj.optJSONArray("trans_result");
            String out = results != null && results.length() > 0
                    ? results.getJSONObject(0).optString("dst", "") : "";
            if (out.isEmpty()) throw new IllegalStateException("百度翻译译文为空");
            return out;
        } finally {
            conn.disconnect();
        }
    }

    /** 百度语言码反直觉项：日文 jp / 韩文 kor / 繁中 cht。 */
    private static String baiduLang(String code) {
        if (code == null || code.isEmpty() || "auto".equals(code)) return "auto";
        if ("ja".equals(code)) return "jp";
        if ("ko".equals(code)) return "kor";
        return code;
    }

    private static String md5Hex(String s) throws Exception {
        byte[] bytes = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Google Gemini（generateContent REST；安全档位全关）
    // ------------------------------------------------------------------

    public static String gemini(String apiKey, String model, String text,
                                String targetName, String sourceName) throws Exception {
        if (apiKey == null || apiKey.isEmpty()) throw new IllegalStateException("Gemini key 未配置");
        String modelName = model == null || model.isEmpty() ? "gemini-1.5-flash" : model;
        JSONObject body = new JSONObject();
        body.put("systemInstruction", new JSONObject().put("parts",
                new JSONArray().put(new JSONObject().put("text", systemPrompt(targetName, sourceName)))));
        body.put("contents", new JSONArray().put(new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text", text)))));
        body.put("generationConfig", new JSONObject().put("temperature", 0));
        JSONArray safety = new JSONArray();
        for (String category : new String[]{
                "HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT"}) {
            safety.put(new JSONObject().put("category", category).put("threshold", "BLOCK_NONE"));
        }
        body.put("safetySettings", safety);
        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + modelName + ":generateContent?key=" + URLEncoder.encode(apiKey, "UTF-8");
        HttpURLConnection conn = open(url);
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            int code = conn.getResponseCode();
            String resp = readBody(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) throw new IllegalStateException("Gemini HTTP " + code + ": " + trunc(resp));
            JSONArray candidates = new JSONObject(resp).optJSONArray("candidates");
            String out = candidates != null && candidates.length() > 0
                    ? geminiPartsText(candidates.getJSONObject(0).optJSONObject("content")) : "";
            if (out.isEmpty()) throw new IllegalStateException("Gemini 译文为空: " + trunc(resp));
            return out;
        } finally {
            conn.disconnect();
        }
    }

    private static String geminiPartsText(JSONObject content) {
        if (content == null) return "";
        JSONArray parts = content.optJSONArray("parts");
        if (parts == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part != null) sb.append(part.optString("text", ""));
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------
    // Anthropic Claude（Messages API）
    // ------------------------------------------------------------------

    public static String anthropic(String baseUrl, String apiKey, String model, String text,
                                   String targetName, String sourceName) throws Exception {
        if (apiKey == null || apiKey.isEmpty()) throw new IllegalStateException("Anthropic key 未配置");
        String base = baseUrl == null || baseUrl.isEmpty()
                ? "https://api.anthropic.com" : baseUrl.trim().replaceAll("/+$", "");
        if (!base.endsWith("/v1")) base = base + "/v1";
        JSONObject body = new JSONObject();
        body.put("model", model == null || model.isEmpty() ? "claude-3-5-haiku-latest" : model);
        body.put("max_tokens", 1024);
        body.put("temperature", 0);
        body.put("system", systemPrompt(targetName, sourceName));
        body.put("messages", new JSONArray().put(new JSONObject()
                .put("role", "user").put("content", text)));
        HttpURLConnection conn = open(base + "/messages");
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", "2023-06-01");
            conn.setRequestProperty("Content-Type", "application/json");
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            int code = conn.getResponseCode();
            String resp = readBody(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) throw new IllegalStateException("Anthropic HTTP " + code + ": " + trunc(resp));
            JSONArray content = new JSONObject(resp).optJSONArray("content");
            StringBuilder sb = new StringBuilder();
            if (content != null) {
                for (int i = 0; i < content.length(); i++) {
                    JSONObject block = content.optJSONObject(i);
                    if (block != null && "text".equals(block.optString("type"))) {
                        sb.append(block.optString("text", ""));
                    }
                }
            }
            String out = sb.toString().trim();
            if (out.isEmpty()) throw new IllegalStateException("Anthropic 译文为空: " + trunc(resp));
            return out;
        } finally {
            conn.disconnect();
        }
    }

    // ------------------------------------------------------------------
    // 共用
    // ------------------------------------------------------------------

    private static String systemPrompt(String targetName, String sourceName) {
        String source = sourceName == null || sourceName.isEmpty() || "自动检测".equals(sourceName)
                ? "" : "源语言为" + sourceName + "。";
        return "你是专业翻译。" + source + "将用户输入翻译为「" + targetName + "」，"
                + "只输出译文，不要解释、不要引号、不要保留原文。";
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        return conn;
    }

    private static String readBody(InputStream in) throws Exception {
        if (in == null) return "";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String trunc(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
