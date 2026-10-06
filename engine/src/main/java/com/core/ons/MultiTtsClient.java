package com.core.ons;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * MultiTTS HTTP TTS 客户端（插件名 Forwarding service / 端口 8774）：
 * - 合成：GET /forward?text=..&speed=0..100&volume=0..100&pitch=0..100&voice=..（WAV）
 * - 音色：GET /voices（JSON）
 * 注意：MultiTTS 的「旁白 speaker」未设置时 /forward 返回 500，必须带 voice。
 */
public final class MultiTtsClient {

    private static final String TAG = "MultiTts";
    public static final String HOST_PORT = "127.0.0.1:8774";
    public static final String DEFAULT_VOICE = "bdetts_xiao-chen-duo-yu-yan";

    /** 可配置服务地址（host:port）：TTS 设置页写入；空 = 默认 127.0.0.1:8774。 */
    private static volatile String configuredHostPort = "";

    /** 供 TTS 设置页注入服务器地址（host:port）。 */
    public static void setConfiguredHostPort(String hostPort) {
        configuredHostPort = hostPort == null ? "" : hostPort.trim();
    }

    private static String hostPort() {
        String configured = configuredHostPort;
        return configured == null || configured.isEmpty() ? HOST_PORT : configured;
    }

    private MultiTtsClient() {
    }

    /** 合成并返回 WAV 字节（后台线程调用）；失败返回 null。 */
    public static byte[] synthesize(String text, String voice, int speed, int volume, int pitch) {
        return synthesizeOn(text, voice, speed, volume, pitch, hostPort());
    }

    /** 指定服务地址的合成（设置页试听/面板共用）。 */
    public static byte[] synthesizeOn(String text, String voice, int speed, int volume,
                                      int pitch, String hostPort) {
        if (text == null || text.isEmpty()) return null;
        String hp = hostPort == null || hostPort.isEmpty() ? HOST_PORT : hostPort;
        try {
            String v = voice == null || voice.isEmpty() ? DEFAULT_VOICE : voice;
            String query = "text=" + URLEncoder.encode(text, "UTF-8")
                    + "&speed=" + clamp(speed, 0, 100)
                    + "&volume=" + clamp(volume, 0, 100)
                    + "&pitch=" + clamp(pitch, 0, 100)
                    + "&voice=" + URLEncoder.encode(v, "UTF-8");
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://" + hp + "/forward?" + query).openConnection();
            try {
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(60_000);
                int code = conn.getResponseCode();
                if (code != 200) {
                    Log.w(TAG, "forward HTTP " + code);
                    return null;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                java.io.InputStream in = conn.getInputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                byte[] data = out.toByteArray();
                // WAV 魔数校验
                if (data.length < 12 || data[0] != 'R' || data[1] != 'I'
                        || data[2] != 'F' || data[3] != 'F') {
                    Log.w(TAG, "forward payload not WAV, len=" + data.length);
                    return null;
                }
                return data;
            } finally {
                conn.disconnect();
            }
        } catch (Throwable t) {
            Log.w(TAG, "synthesize failed", t);
            return null;
        }
    }

    /** MultiTTS HTTP 服务是否可用（voices 探测）。 */
    public static boolean isServiceUp() {
        return isServiceUpOn(hostPort());
    }

    /** 指定服务地址的可用性探测。 */
    public static boolean isServiceUpOn(String hostPort) {
        String hp = hostPort == null || hostPort.isEmpty() ? HOST_PORT : hostPort;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://" + hp + "/voices").openConnection();
            try {
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(5000);
                return conn.getResponseCode() == 200;
            } finally {
                conn.disconnect();
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * 抓取发音人名字列表（/voices；catalog JSON 展平为「目录/名字」或名字）。
     * 后台线程调用；失败返回空列表。
     */
    public static java.util.List<String> fetchVoiceNames() {
        return fetchVoiceNamesOn(hostPort());
    }

    /** 指定服务地址的发音人列表抓取。 */
    public static java.util.List<String> fetchVoiceNamesOn(String hostPort) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String hp = hostPort == null || hostPort.isEmpty() ? HOST_PORT : hostPort;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://" + hp + "/voices").openConnection();
            try {
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(10_000);
                if (conn.getResponseCode() != 200) return out;
                java.io.InputStream in = conn.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                org.json.JSONObject root = new org.json.JSONObject(bos.toString("UTF-8"));
                if (!root.optBoolean("success", false)) return out;
                org.json.JSONObject data = root.optJSONObject("data");
                org.json.JSONObject catalog = data == null ? null : data.optJSONObject("catalog");
                if (catalog != null) {
                    java.util.Iterator<String> keys = catalog.keys();
                    while (keys.hasNext()) {
                        String catalogName = keys.next();
                        org.json.JSONArray voices = catalog.optJSONArray(catalogName);
                        if (voices == null) continue;
                        for (int i = 0; i < voices.length(); i++) {
                            org.json.JSONObject v = voices.optJSONObject(i);
                            String name = v == null ? "" : v.optString("name", "");
                            if (!name.isEmpty()) out.add(catalogName + "/" + name);
                        }
                    }
                } else if (data != null) {
                    org.json.JSONArray arr = data.optJSONArray("voices");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject v = arr.optJSONObject(i);
                            String name = v == null ? "" : v.optString("name", "");
                            if (!name.isEmpty()) out.add(name);
                        }
                    }
                }
            } finally {
                conn.disconnect();
            }
        } catch (Throwable t) {
            Log.w(TAG, "fetchVoiceNames failed", t);
        }
        return out;
    }
}