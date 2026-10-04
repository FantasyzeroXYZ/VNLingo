package com.core.ons;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Web 引擎（Tyrano / RPG MV/MZ / VN / WebOther）提取桥：承接注入脚本
 * __tn_extract.js 经 addJavascriptInterface 上行的事件。
 *
 * 事件语义（与 webgametxt 对齐）：
 *  - {"type":"dialogue","name":"...","text":"..."}  当前对话全量（UTF-8）
 *  - {"type":"voice","path":"data/voice/xxx.ogg"}   语音文件相对路径
 *
 * 配对规则与 ONS 桥一致：dialogue 到达时取「上一 dialogue 之后最近一次 voice」；
 * 语音字节直接按相对路径读游戏目录文件（本地 HTTP 服务供数的文件都在磁盘），
 * blob:/外链跳过。
 */
public final class WebExtractBridge {

    private static final String TAG = "WebExtract";
    private static final long MAX_VOICE_BYTES = 32L * 1024 * 1024;
    private static final WebExtractBridge INSTANCE = new WebExtractBridge();

    public static WebExtractBridge get() {
        return INSTANCE;
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile String pageText = "";
    private volatile String sentenceText = "";
    private volatile String voiceName = "";
    private volatile byte[] voiceBytes;
    private final List<String> pendingVoices = new ArrayList<>();
    private File gameRoot;
    /** 本地 HTTP 服务端口（asar 打包的游戏文件只在归档内，经服务器读取）；0=不可用。 */
    private volatile int serverPort;
    private OnsExtractBridge.Listener listener;

    private WebExtractBridge() {
    }

    /** 宿主创建时挂接（语音相对路径解析根 + 本地 HTTP 端口，asar 语音经服务器读）。 */
    public void attach(File gameRootFile, int port) {
        gameRoot = gameRootFile;
        serverPort = port;
    }

    public void detach() {
        gameRoot = null;
        listener = null;
    }

    public void setListener(OnsExtractBridge.Listener l) {
        this.listener = l;
    }

    /** addJavascriptInterface 入口（JS 线程）。 */
    public void post(String json) {
        if (json == null || json.isEmpty() || json.length() > 2 * 1024 * 1024) return;
        try {
            JSONObject obj = new JSONObject(json);
            String type = obj.optString("type", "");
            if ("dialogue".equals(type)) {
                handleDialogue(obj.optString("name", ""), obj.optString("text", ""));
            } else if ("voice".equals(type)) {
                handleVoice(obj.optString("path", ""));
            }
        } catch (Throwable t) {
            Log.w(TAG, "post parse failed", t);
        }
    }

    /** MV/RMMZ 内置系统音效名（basename 前缀匹配，lowercase）。 */
    private static final String[] BUILTIN_SE_PREFIXES = {
        "cursor", "decision", "cancel", "buzzer", "equip", "save", "load",
        "battle", "attack", "damage", "escape", "enemycollapse", "bosscollapse",
        "recovery", "miss", "evasion", "magic", "itemuse", "reflect",
        "disappearance", "darkness", "blow", "bell", "chime", "chest",
        "open", "close", "switch", "walk", "jump",
    };

    private static boolean isBuiltinSe(String lowerPath) {
        int slash = lowerPath.lastIndexOf('/');
        String base = slash >= 0 ? lowerPath.substring(slash + 1) : lowerPath;
        for (String prefix : BUILTIN_SE_PREFIXES) {
            if (base.startsWith(prefix)) return true;
        }
        return false;
    }

    private void handleDialogue(String name, String text) {
        if (text == null || text.isEmpty()) return;
        String sentence = (name == null || name.isEmpty()) ? text : "【" + name + "】" + text;
        pageText = sentence;
        sentenceText = sentence;
        synchronized (pendingVoices) {
            if (!pendingVoices.isEmpty()) {
                voiceName = pendingVoices.get(pendingVoices.size() - 1);
                pendingVoices.clear();
            } else {
                voiceName = "";
            }
        }
        voiceBytes = null;
        OnsExtractBridge.Listener l = listener;
        if (l != null) main.post(l::onExtractUpdated);
    }

    private void handleVoice(String path) {
        if (path == null || path.isEmpty() || path.startsWith("blob:")) return;
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        // 视频与 BGM 不是对话语音（开场动画等会被 HTMLMediaElement 钩子捕获）
        if (lower.contains("/video/") || lower.contains("/bgm/") || lower.contains("/music/")
                || lower.contains("/movies/")
                || lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".m4v")
                || lower.endsWith(".mov")) {
            return;
        }
        // se/ 目录通常是音效，但存在「SE 当对话语音」的游戏（如 Хрень 的
        // 1_9_output.ogg 紧跟 Show Text 播放）——只剔 MV/RMMZ 内置系统音效名，
        // 其余保留为语音候选
        if (lower.contains("/se/") && isBuiltinSe(lower)) {
            return;
        }
        synchronized (pendingVoices) {
            pendingVoices.add(path);
            if (pendingVoices.size() > 8) pendingVoices.remove(0);
        }
    }

    /** 相对路径 → 游戏目录文件（剥 localhost 前缀与 URL 转义，防目录穿越）。 */
    private File voiceFile(String path) {
        File root = gameRoot;
        if (root == null || path == null || path.isEmpty()) return null;
        String rel = path;
        int scheme = rel.indexOf("://");
        if (scheme > 0) {
            int slash = rel.indexOf('/', scheme + 3);
            if (slash < 0) return null;
            rel = rel.substring(slash + 1);
        }
        if (rel.startsWith("/")) rel = rel.substring(1);
        try {
            rel = URLDecoder.decode(rel, "UTF-8");
        } catch (Throwable ignored) {
        }
        int query = rel.indexOf('?');
        if (query >= 0) rel = rel.substring(0, query);
        File f = new File(root, rel);
        try {
            if (!f.getCanonicalPath().startsWith(root.getCanonicalPath())) return null;
        } catch (Throwable t) {
            return null;
        }
        return f.isFile() ? f : null;
    }

    /** 经本地 HTTP 服务器读取游戏内文件（剥 localhost 前缀，失败返回 null）。 */
    private byte[] httpGet(String path) {
        String rel = path;
        int scheme = rel.indexOf("://");
        if (scheme > 0) {
            int slash = rel.indexOf('/', scheme + 3);
            if (slash < 0) return null;
            rel = rel.substring(slash + 1);
        }
        if (rel.startsWith("/")) rel = rel.substring(1);
        int query = rel.indexOf('?');
        if (query >= 0) rel = rel.substring(0, query);
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL("http://127.0.0.1:" + serverPort + "/" + rel).openConnection();
            try {
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(30000);
                int code = conn.getResponseCode();
                if (code != 200) return null;
                long len = conn.getContentLengthLong();
                if (len > MAX_VOICE_BYTES) return null;
                java.io.InputStream in = conn.getInputStream();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                byte[] data = out.toByteArray();
                return data.length > 0 ? data : null;
            } finally {
                conn.disconnect();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private byte[] readVoiceFile(String path) {
        // 磁盘文件优先；asar 打包游戏经本地 HTTP 服务器读归档内文件
        if (serverPort > 0) {
            byte[] viaHttp = httpGet(path);
            if (viaHttp != null) return viaHttp;
        }
        File f = voiceFile(path);
        if (f == null || f.length() <= 0 || f.length() > MAX_VOICE_BYTES) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int off = 0;
            int n;
            while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) {
                off += n;
            }
            return off > 0 ? data : null;
        } catch (Throwable t) {
            Log.w(TAG, "readVoiceFile failed for " + path, t);
            return null;
        }
    }

    // ─── 供面板读取 ─────────────────────────────────────────────────────

    public String getPageText() {
        return pageText;
    }

    public String getSentenceText() {
        return sentenceText;
    }

    public String getVoiceName() {
        return voiceName;
    }

    public String getLockedCharset() {
        return "UTF-8";
    }

    public byte[] ensureVoiceBytes() {
        byte[] cached = voiceBytes;
        if (cached != null && cached.length > 0) return cached;
        String name = voiceName;
        if (name == null || name.isEmpty()) return null;
        byte[] byName = readVoiceFile(name);
        if (byName != null) voiceBytes = byName;
        return voiceBytes;
    }

    public byte[] readVoiceBytesByName(String name) {
        return name == null || name.isEmpty() ? null : readVoiceFile(name);
    }

    /** addJavascriptInterface 桥（JS 侧 window.tnExtractBridge.post(json)）。 */
    public static class JsBridge {
        @android.webkit.JavascriptInterface
        public void post(String json) {
            get().post(json);
        }
    }
}
