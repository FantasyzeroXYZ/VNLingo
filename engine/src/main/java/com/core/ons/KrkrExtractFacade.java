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
 * - 文本提取：cocos2d Label::setString inline hook（krkr_extract_hook，随
 *   libkrkr_bridge_v2 分发，三个 Kirikiroid2 版本符号一致）→ NativeBridge.onKrkrText
 *   （CJK 启发式过滤）→ OnsExtractBridge 对话状态，本 facade 委派读取（与 ONS 同构）。
 * - 语音提取待 krkr2 WaveSoundBuffer 钩子（当前 ♪ 无语音）。
 */
public class KrkrExtractFacade implements ExtractFacade {

    private final Activity activity;
    private final File gameRoot;
    private final File saveDir;
    private final Handler main = new Handler(Looper.getMainLooper());

    public KrkrExtractFacade(Activity activity, String gameRootPath, File saveDir) {
        this.activity = activity;
        this.gameRoot = gameRootPath == null ? null : new File(gameRootPath);
        this.saveDir = saveDir;
    }

    // ─── 提取数据源（GdipDrawString 钩子 → onKrkrText → OnsExtractBridge）───

    @Override public String getSentenceText() {
        return OnsExtractBridge.get().getSentenceText();
    }

    @Override public String getPageText() {
        return OnsExtractBridge.get().getPageText();
    }

    @Override public String getVoiceName() {
        // KRKR 语音钩子（WaveSoundBuffer）待实现；当前恒空（面板 ♪ 无语音）
        return "";
    }
    @Override public String getLockedCharset() { return "UTF-8"; }
    @Override public byte[] ensureVoiceBytes() { return null; }
    @Override public byte[] readVoiceBytesByName(String name) { return null; }
    @Override public void setScreenshot(Bitmap bmp) { }
    @Override public Bitmap getScreenshot() { return null; }
    @Override public void setListener(OnsExtractBridge.Listener listener) {
        this.extractListener = listener;
    }

    private OnsExtractBridge.Listener extractListener;

    /** 面板刷新回调（setListener 透传 OnsExtractBridge 通知）。 */
    public OnsExtractBridge.Listener extractListener() {
        return extractListener;
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
