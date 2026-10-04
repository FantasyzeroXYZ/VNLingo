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

    private MultiTtsClient() {
    }

    /** 合成并返回 WAV 字节（后台线程调用）；失败返回 null。 */
    public static byte[] synthesize(String text, String voice, int speed, int volume, int pitch) {
        if (text == null || text.isEmpty()) return null;
        try {
            String v = voice == null || voice.isEmpty() ? DEFAULT_VOICE : voice;
            String query = "text=" + URLEncoder.encode(text, "UTF-8")
                    + "&speed=" + clamp(speed, 0, 100)
                    + "&volume=" + clamp(volume, 0, 100)
                    + "&pitch=" + clamp(pitch, 0, 100)
                    + "&voice=" + URLEncoder.encode(v, "UTF-8");
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://" + HOST_PORT + "/forward?" + query).openConnection();
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
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "http://" + HOST_PORT + "/voices").openConnection();
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
}
