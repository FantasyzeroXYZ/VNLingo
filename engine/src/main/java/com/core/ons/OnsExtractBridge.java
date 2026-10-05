package com.core.ons;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import com.yuri.onscripter.ONScripter;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ONS 对话/语音提取桥：承接 libonsyuri [ONS-BRIDGE] 的 JNI 上行事件。
 *
 * 事件语义（与 web 端 OnsExtract 对齐）：
 *  - {"type":"dialogue","payload":{"b64":...}}  当前页完整快照（脚本原始编码字节）；
 *    页缓冲会被引擎改写，宿主做前缀差分得到本句增量后缀。
 *  - {"type":"sound","payload":{"file":"...","ch":...}}  wave/dwave 语音候选文件名。
 *
 * 配对规则：dialogue 事件到达时，取「上一次 dialogue 之后最近一次 sound」为当前句
 * 语音；配对后立即经 nativeOnsWaveBytes 拉走活捕获字节并 drop，防止后续语音覆盖
 * 导致串句；历史语音可经 nativeOnsReadFile 按存储名补读（档案层解压字节）。
 */
public final class OnsExtractBridge {

    private static final String TAG = "OnsExtract";

    /** 提取面板/其他消费方实现的更新回调；回调已切主线程。 */
    public interface Listener {
        void onExtractUpdated();
    }

    private static final OnsExtractBridge INSTANCE = new OnsExtractBridge();

    public static OnsExtractBridge get() {
        return INSTANCE;
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    /** 会话内锁定的文本编码（首片段成功解码后锁定，同 web 宿主 v4 策略）。 */
    private String lockedCharset;

    /** 当前页完整文本（解码后）。 */
    private volatile String pageText = "";
    /** 与上一页做前缀差分后的本句增量。 */
    private volatile String sentenceText = "";
    /** 最近原始页字节（差分用）。 */
    private byte[] lastPageBytes = new byte[0];

    /** 上一次 dialogue 事件之后到达的语音候选（文件名）。 */
    private final List<String> pendingVoices = new ArrayList<>();
    /** 当前句配对的语音名。 */
    private volatile String voiceName = "";
    /** FT 钩子候选（KRKR 重绘链派生；空 = 无候选）。 */
    private volatile List<String> candidates = new ArrayList<>();
    /** 配对语音字节（拉取后缓存，重播/保存用）。 */
    private volatile byte[] voiceBytes = null;

    private ONScripter host;
    private Listener listener;
    /** 面板/消费方持有的最近一张截图。 */
    private volatile Bitmap lastScreenshot;

    private OnsExtractBridge() {
    }

    /** Activity 创建时挂接；onBridgeEvent 需要 host 实例拉取语音字节。 */
    public void attach(ONScripter activity) {
        this.host = activity;
    }

    public void detach() {
        this.host = null;
        this.listener = null;
        this.lastScreenshot = null;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** 引擎侧 upcall 入口（SDL 线程）。payload 为完整 UTF-8 JSON 事件。 */
    public void onEvent(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > 4 * 1024 * 1024) return;
        try {
            JSONObject obj = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            String type = obj.optString("type", "");
            JSONObject data = obj.optJSONObject("payload");
            if (data == null) return;
            if ("dialogue".equals(type)) {
                handleDialogue(data.optString("b64", ""));
            } else if ("sound".equals(type)) {
                handleSound(data.optString("file", ""));
            } else if ("candidates".equals(type)) {
                handleCandidates(data.optString("b64", ""));
            }
        } catch (Throwable t) {
            Log.w(TAG, "onEvent parse failed", t);
        }
    }

    /**
     * KRKR FT 钩子的原始重绘链候选（[FTRAW]）：解析出候选列表——
     * [0]=原始链原样；[1]=最长重复后缀（最终整行重绘≈当前句真身）。
     * 面板「候选切换」由用户选最适配的显示（LunaTranslator 式）。
     */
    private void handleCandidates(String b64) {
        byte[] raw;
        try {
            raw = Base64.decode(b64, Base64.DEFAULT);
        } catch (Throwable t) {
            return;
        }
        String chain = new String(raw, StandardCharsets.UTF_8).trim();
        if (chain.isEmpty()) return;
        List<String> list = new ArrayList<>(2);
        list.add(chain);
        String best = longestRepeatedSuffix(chain);
        if (best != null && !best.equals(chain)) list.add(best);
        candidates = list;
        Listener l = listener;
        if (l != null) main.post(l::onExtractUpdated);
    }

    /** 最长重复后缀：suffix t（长度 k 从大到小）满足 indexOf(t) < 出现于末尾。 */
    private static String longestRepeatedSuffix(String s) {
        for (int k = s.length() - 1; k >= 2; k--) {
            String t = s.substring(s.length() - k);
            int first = s.indexOf(t);
            if (first >= 0 && first < s.length() - k) return t;
        }
        return null;
    }

    private void handleDialogue(String b64) {
        byte[] raw;
        try {
            raw = Base64.decode(b64, Base64.DEFAULT);
        } catch (Throwable t) {
            Log.w(TAG, "dialogue b64 decode failed", t);
            return;
        }
        if (raw.length == 0) return;

        // 前缀差分：字节级最长公共前缀，得到本句增量后缀（对齐多字节边界由
        // 解码器容错兜底）；显示存完整页，增量喂句面板。
        // 打字机延伸（新 raw 完整包含旧 raw）例外：KRKR FT 钩子按重绘推进，
        // 半句→整句是前缀延伸，此时"本句"= 整句而非尾部增量（制卡例句/朗读
        // 都要完整句；新增行仍走增量语义，保持 ONS 多行页取末行行为）。
        int common = commonPrefixLength(lastPageBytes, raw);
        boolean extension = common == lastPageBytes.length && raw.length > lastPageBytes.length;
        byte[] suffix = new byte[raw.length - common];
        System.arraycopy(raw, common, suffix, 0, suffix.length);

        String page = decode(raw, true);
        String sentence = (suffix.length == 0 || extension)
                ? page : decode(suffix, false);
        lastPageBytes = raw;
        pageText = page;
        sentenceText = sentence;

        // 配对：取本句之前最近一次语音候选；随后立即拉取字节并 drop 活捕获
        String candidate = null;
        synchronized (pendingVoices) {
            if (!pendingVoices.isEmpty()) {
                candidate = pendingVoices.get(pendingVoices.size() - 1);
                pendingVoices.clear();
            }
        }
        voiceName = candidate == null ? "" : candidate;
        voiceBytes = null;
        if (candidate != null) {
            pullVoiceBytes(candidate);
        }
        notifyListener();
    }

    private void handleSound(String file) {
        if (file == null || file.isEmpty()) return;
        synchronized (pendingVoices) {
            pendingVoices.add(file);
            if (pendingVoices.size() > 8) pendingVoices.remove(0);
        }
    }

    /** 拉取配对语音字节：优先活捕获，名称不一致或为空时按存储名补读。 */
    private void pullVoiceBytes(String name) {
        ONScripter act = host;
        if (act == null) return;
        try {
            byte[] liveName = act.nativeOnsWaveName();
            byte[] live = act.nativeOnsWaveBytes();
            if (live != null && live.length > 0 && liveName != null && nameEquals(liveName, name)) {
                voiceBytes = live;
            } else {
                byte[] byName = act.nativeOnsReadFile(name.getBytes(StandardCharsets.UTF_8));
                if (byName != null && byName.length > 0) voiceBytes = byName;
            }
            act.nativeOnsWaveDrop();
        } catch (Throwable t) {
            Log.w(TAG, "pullVoiceBytes failed for " + name, t);
        }
    }

    /** 重播/保存前兜底：缓存为空时按名补读。 */
    public byte[] ensureVoiceBytes() {
        byte[] cached = voiceBytes;
        if (cached != null && cached.length > 0) return cached;
        String name = voiceName;
        ONScripter act = host;
        if (name == null || name.isEmpty() || act == null) return null;
        try {
            byte[] byName = act.nativeOnsReadFile(name.getBytes(StandardCharsets.UTF_8));
            if (byName != null && byName.length > 0) voiceBytes = byName;
        } catch (Throwable t) {
            Log.w(TAG, "ensureVoiceBytes failed", t);
        }
        return voiceBytes;
    }

    /** 历史回放：按存储名补读语音字节（不经当前配对缓存，不影响 current voiceBytes）。 */
    public byte[] readVoiceBytesByName(String name) {
        if (name == null || name.isEmpty()) return null;
        ONScripter act = host;
        if (act == null) return null;
        try {
            byte[] byName = act.nativeOnsReadFile(name.getBytes(StandardCharsets.UTF_8));
            if (byName != null && byName.length > 0) return byName;
        } catch (Throwable t) {
            Log.w(TAG, "readVoiceBytesByName failed for " + name, t);
        }
        return null;
    }

    private static boolean nameEquals(byte[] a, String b) {
        try {
            return new String(a, StandardCharsets.UTF_8).equals(b);
        } catch (Throwable t) {
            return false;
        }
    }

    private static int commonPrefixLength(byte[] prev, byte[] cur) {
        int n = Math.min(prev.length, cur.length);
        for (int i = 0; i < n; i++) {
            if (prev[i] != cur[i]) return i;
        }
        return n;
    }

    /** 脚本原始编码字节解码：严格 UTF-8 → SJIS → GBK，成功后锁定编码。 */
    private String decode(byte[] data, boolean lock) {
        String charset = lockedCharset;
        if (charset != null) {
            try {
                return new String(data, charset);
            } catch (Throwable t) {
                // 锁定编码解码失败则重新嗅探
            }
        }
        String found = sniff(data);
        if (found == null) return "";
        if (lock && lockedCharset == null) lockedCharset = found;
        try {
            return new String(data, found);
        } catch (Throwable t) {
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private static String sniff(byte[] data) {
        if (looksLikeStrictUtf8(data)) return "UTF-8";
        String sj = decodesStrictly(data, "SJIS") ? "SJIS" : null;
        String gbk = decodesStrictly(data, "GBK") ? "GBK" : null;
        if (sj != null && gbk != null) {
            // 两者都能严格解码：GB 谜之扫雷——含假名（ひらがな/カタカナ）判 SJIS，
            // 否则优先 GBK（中文汉化包；GBK 字节序列常被 SJIS 误收）
            String sjText = safeDecode(data, sj);
            if (containsKana(sjText)) return "SJIS";
            return "GBK";
        }
        if (sj != null) return "SJIS";
        if (gbk != null) return "GBK";
        return "UTF-8";
    }

    private static String safeDecode(byte[] data, String charset) {
        try {
            return new String(data, charset);
        } catch (Throwable e) {
            return "";
        }
    }

    private static boolean containsKana(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x3040 && c <= 0x30FF) || (c >= 0x31F0 && c <= 0x31FF)) return true;
        }
        return false;
    }

    private static boolean looksLikeStrictUtf8(byte[] data) {
        try {
            CharsetDecoder d = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            d.decode(ByteBuffer.wrap(data));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static boolean decodesStrictly(byte[] data, String charset) {
        try {
            CharsetDecoder d = java.nio.charset.Charset.forName(charset).newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            d.decode(ByteBuffer.wrap(data));
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private void notifyListener() {
        Listener l = listener;
        if (l == null) return;
        main.post(l::onExtractUpdated);
    }

    // ------------------------------------------------------------------
    // 供面板读取的状态
    // ------------------------------------------------------------------

    public String getPageText() {
        return pageText;
    }

    public String getSentenceText() {
        return sentenceText;
    }

    public String getVoiceName() {
        return voiceName;
    }

    /** 候选列表（≥2 才有切换意义；ONS/Web 宿主恒空）。 */
    public List<String> getCandidates() {
        return candidates;
    }

    /** 会话内锁定的文本编码（SJIS/GBK/UTF-8），供 TTS 选择朗读语言。 */
    public String getLockedCharset() {
        return lockedCharset;
    }

    public void setLastScreenshot(Bitmap bmp) {
        if (lastScreenshot != null && lastScreenshot != bmp) {
            lastScreenshot.recycle();
        }
        lastScreenshot = bmp;
    }

    public Bitmap getLastScreenshot() {
        return lastScreenshot;
    }
}
