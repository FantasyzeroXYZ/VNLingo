package com.core.ons;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 免费翻译链（TrackReader translate.ts 对齐）：无需密钥、开箱即用。
 *
 * - MyMemory web API（免费，匿名即用）：
 *   https://api.mymemory.translated.net/get?q=...&langpair=src|dst
 *   源语言启发式探测（假名→ja、谚文→ko、汉字→zh-CN、拉丁→en——
 *   探测顺序先假名后汉字，避免日文汉字误判为中文，比 TrackReader 原版更准）
 * - 内存缓存：同句不重复请求（TrackReader _cache 同构）
 *
 * 链式语义（TrackReader translate() 对齐）：用户已配置 API 时先用 API，
 * 失败回退 MyMemory；未配置直接 MyMemory。
 */
public final class OnsFreeTranslate {

    private static final String TAG = "OnsFreeTranslate";
    private static final String TARGET_LANG = "zh-CN";
    /** 翻译缓存：有界 LRU（访问序 + 64 条上限），synchronizedMap 包装——
     *  翻译在后台线程发生，裸 HashMap 并发读写有结构性损坏风险。 */
    private static final Map<String, String> CACHE =
            java.util.Collections.synchronizedMap(new LinkedHashMap<String, String>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 64;
                }
            });

    /** 翻译回调（回调线程 = 后台线程，实现方自行切主线程）。 */
    public interface Callback {
        void onResult(String translated, String error);
    }

    private OnsFreeTranslate() {
    }

    /**
     * 翻译链入口：已配置用户 API → 先 API 后 MyMemory；未配置 → 直接 MyMemory。
     * 必须在非主线程调用（内部做网络请求）。
     */
    public static String translate(Context context, String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String cached = cacheGet(text);
        if (cached != null) return cached;
        if (OnsTranslateClient.isConfigured(context)) {
            String viaApi = viaApiBlocking(context, text);
            if (viaApi != null && !viaApi.isEmpty()) {
                cachePut(text, viaApi);
                return viaApi;
            }
        }
        String viaMemory = translateMyMemory(text);
        if (viaMemory != null) cachePut(text, viaMemory);
        return viaMemory;
    }

    /** OnsTranslateClient 回调式 API 的同步包装（供链式调用）。 */
    private static String viaApiBlocking(Context context, String text) {
        final String[] out = {null};
        final Object lock = new Object();
        OnsTranslateClient.translateWithEngine(context, text, (translated, error) -> {
            synchronized (lock) {
                out[0] = translated;
                lock.notifyAll();
            }
        });
        synchronized (lock) {
            try { lock.wait(15000); } catch (InterruptedException ignored) { }
        }
        return out[0];
    }

    /** MyMemory 免费翻译（匿名即用；目标语 = 中文）。 */
    public static String translateMyMemory(String text) {
        String src = detectLang(text);
        String key = cacheKey(text);
        String cached = CACHE.get(key);
        if (cached != null) return cached;
        try {
            String q = URLEncoder.encode(text, "UTF-8");
            String url = "https://api.mymemory.translated.net/get?q=" + q
                    + "&langpair=" + src + "%7C" + TARGET_LANG
                    + "&de=nobody%40example.com";
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            final int code = conn.getResponseCode();
            if (code != 200) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            JSONObject obj = new JSONObject(bos.toString("UTF-8"));
            if (obj.optInt("responseStatus", 0) != 200) return null;
            String translated = obj.optJSONObject("responseData") != null
                    ? obj.optJSONObject("responseData").optString("translatedText", "")
                    : "";
            if (translated.isEmpty()) return null;
            CACHE.put(key, translated);
            Log.i(TAG, "mymemory ok: " + translated.substring(0, Math.min(40, translated.length())));
            return translated;
        } catch (Throwable t) {
            Log.w(TAG, "mymemory failed", t);
            return null;
        }
    }

    private static String cacheKey(String text) {
        return "t_" + TARGET_LANG + "_" + (text.length() > 80 ? text.substring(0, 80) : text);
    }

    private static String cacheGet(String text) {
        return CACHE.get(cacheKey(text));
    }

    private static void cachePut(String text, String translated) {
        CACHE.put(cacheKey(text), translated);
    }

    /**
     * 源语言启发式探测（TrackReader 同款思路，顺序改良：
     * 先假名/谚文后汉字——日文汉字不再误判为中文）。
     */
    private static String detectLang(String text) {
        if (text == null || text.isEmpty()) return "en";
        boolean hiraganaKatakana = false, hangul = false, ideograph = false, latin = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x3040 && c <= 0x30FF)) hiraganaKatakana = true;
            else if (c >= 0xAC00 && c <= 0xD7AF) hangul = true;
            else if (c >= 0x4E00 && c <= 0x9FFF) ideograph = true;
            else if (Character.isLetter(c)) latin = true;
        }
        if (hiraganaKatakana) return "ja";
        if (hangul) return "ko";
        if (ideograph) return "zh-CN";
        if (latin) return "en";
        return "en";
    }
}
