package com.core.ons;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;


import java.io.File;

/**
 * Kirikiroid2（KR2 宿主）的 ExtractFacade 实现：
 * - 存档管理完整可用（savedata 目录检测/导出/导入/云同步，目录 = <游戏根>/savedata）
 * - 文本提取（krkr2-main 带钩内核）：内核 tTJSNI_BaseLayer::DrawText 咽喉
 *   → KR2Activity.onKrkr2Extract → KrkrExtractBridge（src=krkr 缝合语义）
 *   → OnsExtractBridge 对话状态，本 facade 委派读取（与 ONS 同构）；
 *   旧内核 + FT 钩子（krkr_extract_hook，随 libkrkr_bridge_v2 分发）经
 *   NativeBridge.onKrkrText 走同一座桥，双通道由 KrkrExtractBridge.kernelBridgeActive 防重。
 * - 语音提取（krkr2-main 带钩内核）：tTJSNI_WaveSoundBuffer::Open 咽喉
 *   （/vo|voice 路径启发）→ 内核落盘缓存副本 → KrkrExtractBridge 上行副本路径，
 *   本 facade 配对当前句（打字机粘滞/换句清零/BGM 名过滤），重播直读缓存副本。
 */
public class KrkrExtractFacade implements ExtractFacade {

    private final Activity activity;
    private final File gameRoot;
    private final File saveDir;
    /** 内核语音缓存副本目录（<filesDir>/krkr_voice_cache，krkr2-main ExtractBridge 落盘处）。 */
    private final File voiceCacheDir;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 当前句配对语音显示名（空 = 无语音）。 */
    private volatile String voiceName = "";
    /** 当前句配对语音缓存副本路径（与 voiceName 成对，换句清零）。 */
    private volatile String voiceCachedPath = "";
    /** 待配对语音（WaveSoundBuffer::Open 先于该句 drawText 到达）。 */
    private String pendingVoice = "";
    private String pendingVoicePath = "";
    /** 最近一次对白文本（打字机分段粘滞判定用；显示累积仍归 OnsExtractBridge）。 */
    private final StringBuilder lastSentence = new StringBuilder();

    public KrkrExtractFacade(Activity activity, String gameRootPath, File saveDir) {
        this.activity = activity;
        this.gameRoot = gameRootPath == null ? null : new File(gameRootPath);
        this.saveDir = saveDir;
        this.voiceCacheDir = new File(activity.getFilesDir(), "krkr_voice_cache");
        registerExtractListener();
    }

    /**
     * 内核提取桥监听：纯 voice 事件进待配队列；dialogue 事件换句时消费
     * （打字机分段 = 前缀延伸，语音标注粘滞，对齐 ArtemisExtractFacade 时序）。
     */
    private void registerExtractListener() {
        KrkrExtractBridge.setListener((text, voice, cached) -> {
            synchronized (this) {
                if (text == null || text.isEmpty()) {
                    if (voice != null && !voice.isEmpty() && !isBgmName(voice)) {
                        pendingVoice = voice;
                        pendingVoicePath = cached == null ? "" : cached;
                    }
                } else {
                    boolean sameSentenceGrowing = lastSentence.length() > 0
                            && text.length() > lastSentence.length()
                            && text.startsWith(lastSentence.toString());
                    lastSentence.setLength(0);
                    lastSentence.append(text);
                    if (!sameSentenceGrowing) {
                        // 换句必须同时清语音名与缓存路径，否则无语音的行点重播
                        // 会放出上一句的音频（Artemis 实测踩坑）
                        this.voiceName = "";
                        this.voiceCachedPath = "";
                    }
                    if (!pendingVoice.isEmpty()) {
                        this.voiceName = pendingVoice;
                        this.voiceCachedPath = pendingVoicePath;
                        pendingVoice = "";
                        pendingVoicePath = "";
                    }
                }
            }
        });
    }

    // ─── 提取数据源（内核 drawText/WaveSoundBuffer 咽喉 → KrkrExtractBridge → OnsExtractBridge）───

    @Override public String getSentenceText() {
        return OnsExtractBridge.get().getSentenceText();
    }

    @Override public String getPageText() {
        return OnsExtractBridge.get().getPageText();
    }

    @Override public java.util.List<String> getSentenceCandidates() {
        return OnsExtractBridge.get().getCandidates();
    }

    @Override public String getVoiceName() {
        // 内核桥配对结果（WaveSoundBuffer::Open → 缓存副本 → 显示名）
        return voiceName;
    }
    @Override public String getLockedCharset() { return "UTF-8"; }
    @Override public byte[] ensureVoiceBytes() {
        String path = voiceCachedPath;
        if (path.isEmpty()) return null;
        byte[] bytes = readFile(new File(path));
        if (bytes != null) return bytes;
        // 缓存副本缺失（未配置缓存目录时内核只发存储名）：按显示名探测兜底
        return readVoiceBytesByName(voiceName);
    }
    @Override public byte[] readVoiceBytesByName(String name) {
        if (name == null || name.isEmpty()) return null;
        // 1) 缓存副本目录：精确名 → 包含名（副本名带 _tn_voice_ 前缀与净化目录段）
        File dir = voiceCacheDir;
        if (dir.isDirectory()) {
            byte[] bytes = readFile(new File(dir, name));
            if (bytes != null) return bytes;
            File[] files = dir.listFiles();
            if (files != null) {
                for (File c : files) {
                    if (c.getName().contains(name)) {
                        bytes = readFile(c);
                        if (bytes != null) return bytes;
                    }
                }
            }
        }
        // 2) 游戏目录常见语音位置按名探测（缓存副本未落盘的兜底）
        if (gameRoot != null && gameRoot.isDirectory()) {
            for (File hit : probeVoiceInGameDir(gameRoot, name)) {
                byte[] bytes = readFile(hit);
                if (bytes != null) return bytes;
            }
        }
        return null;
    }
    @Override public void setScreenshot(Bitmap bmp) { }
    @Override public Bitmap getScreenshot() { return null; }
    @Override public void setListener(OnsExtractBridge.Listener listener) {
        this.extractListener = listener;
        // 必须转发到桥：FT 钩子上行（NativeBridge.onKrkrText → onEvent）经桥的
        // listener 通知面板刷新——只存本地会导致面板永远收不到更新（推进对白
        // 后文本不变、要重开面板才刷新的根因；ONS facade 同款转发）
        OnsExtractBridge.get().setListener(listener);
    }

    private OnsExtractBridge.Listener extractListener;

    /** 面板刷新回调（setListener 透传 OnsExtractBridge 通知）。 */
    public OnsExtractBridge.Listener extractListener() {
        return extractListener;
    }

    /** BGM 名过滤（内核 /vo|voice 路径启发之外的兜底）：bgm/music 不参与台词配对。 */
    private static boolean isBgmName(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("bgm") || lower.contains("music");
    }

    /** 在游戏根常见语音目录（voice/vo 及根目录）按名探测（含常见音频扩展名与模糊包含）。 */
    private static java.util.List<File> probeVoiceInGameDir(File root, String name) {
        java.util.List<File> out = new java.util.ArrayList<>();
        String baseName = name;
        int dot = baseName.lastIndexOf('.');
        if (dot > 0) baseName = baseName.substring(0, dot);
        String[] dirs = {"voice", "vo", ""};
        String[] exts = {"", ".ogg", ".opus", ".wav", ".m4a"};
        for (String dir : dirs) {
            File base = dir.isEmpty() ? root : new File(root, dir);
            if (!base.isDirectory()) continue;
            for (String ext : exts) {
                File candidate = new File(base, baseName + ext);
                if (candidate.isFile()) out.add(candidate);
            }
            File[] children = base.listFiles();
            if (children != null) {
                for (File c : children) {
                    if (c.isFile() && c.getName().contains(baseName)) out.add(c);
                }
            }
        }
        return out;
    }

    private static byte[] readFile(File f) {
        try {
            byte[] bytes = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0, n;
                while (off < bytes.length && (n = in.read(bytes, off, bytes.length - off)) > 0) off += n;
                return off == bytes.length ? bytes : null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    // ─── 宿主能力 ─────────────────────────────────────────────────────

    @Override public Activity getActivity() { return activity; }



    @Override public String gameDisplayName() {
        return gameRoot == null ? "krkr2" : gameRoot.getName();
    }

    @Override
    public File[] listSaves() {
        if (saveDir == null || !saveDir.isDirectory()) return new File[0];
        File[] files = saveDir.listFiles();
        if (files == null) return new File[0];
        java.util.List<File> saves = new java.util.ArrayList<>();
        for (File f : files) {
            if (f.isFile()) saves.add(f);
        }
        saves.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return saves.toArray(new File[0]);
    }

    @Override
    public File zipSavesToCache() {
        if (saveDir == null || !saveDir.isDirectory()) return null;
        File out = new File(activity.getCacheDir(), "krkr_cloud_saves.zip");
        try (java.io.OutputStream os = new java.io.FileOutputStream(out)) {
            return SaveZipUtil.zipDirectory(saveDir, os) >= 0 ? out : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public int importSavesFromZip(File zip) {
        try (java.io.InputStream in = new java.io.FileInputStream(zip)) {
            if (saveDir != null && !saveDir.isDirectory()) saveDir.mkdirs();
            return SaveZipUtil.unzipInto(in, saveDir);
        } catch (Throwable t) {
            return -1;
        }
    }

    @Override
    public void exportSaveArchive() {
        WebSaveArchive.export(activity, saveDir, gameDisplayName());
    }

    @Override
    public void importSaveArchive() {
        WebSaveArchive.importArchive(activity);
    }

    @Override
    public void importDictionary() {
        // 词典查词依赖 KRKR 文本提取，未启用前无入口
    }

    /** 宿主 onActivityResult 直接委托；返回 true 表示已消费。 */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        return WebSaveArchive.onActivityResult(requestCode, resultCode, data, saveDir, activity, main);
    }
}
