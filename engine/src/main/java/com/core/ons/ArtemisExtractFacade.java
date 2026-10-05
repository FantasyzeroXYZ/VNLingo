package com.core.ons;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import com.ies_net.artemis.ArtemisActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Artemis 宿主（libartemis-clean clean-room 内核）的 ExtractFacade 实现：
 *
 * - 文本/语音提取：内核 extract_bridge 发射器（TagPrint 消息层累积文本 /
 *   TagAudio voplay 语音）经 ArtemisActivity.onArtemisExtract 上行到
 *   {@link ArtemisExtractBridge}，本 facade 注册监听并保存当前句与语音；
 * - 语音重播：内核在 voplay 时把包内语音副本写到缓存目录
 *   （<游戏根>/artemis_extract/voice/），readVoiceBytesByName/ensureVoiceBytes
 *   直接读缓存副本；
 * - 存档管理：savedata/ 与 save/ 双候选目录（与 KRKR facade 同构）。
 */
public class ArtemisExtractFacade implements ExtractFacade {

    /** 候选存档目录名（不同 Artemis 游戏系统脚本约定不同）。 */
    private static final String[] SAVE_DIR_CANDIDATES = {"savedata", "save"};

    private final Activity activity;
    private final File gameRoot;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final StringBuilder sentence = new StringBuilder();
    private volatile String voiceName = "";
    private volatile String voiceCachedPath = "";
    /** 待配对语音（voplay/seplay 先于台词到达；旁白行消费后清空）。 */
    private volatile String pendingVoice = "";

    public ArtemisExtractFacade(Activity activity, String gameRootPath) {
        this.activity = activity;
        this.gameRoot = gameRootPath == null ? null : new File(gameRootPath);
        registerExtractListener();
        registerExtractCacheDir();
    }

    /** 提取缓存目录（内核写语音副本；<游戏根>/artemis_extract/voice/）。 */
    private File extractVoiceDir() {
        File dir = new File(gameRoot != null ? gameRoot
                : new File(activity.getExternalFilesDir(null), "artemis"), "artemis_extract/voice");
        dir.mkdirs();
        return dir;
    }

    private void registerExtractListener() {
        ArtemisExtractBridge.setListener((text, voiceFile, voiceCached) -> {
            synchronized (this) {
                // 只认纯语音事件（text 为空，voplay/seplay 专属发射）进入待配队列。
                // 内核 EmitExtractText 还会把「最近一次 voplay」重复挂在每个文本
                // 事件上且从不清除——若也接收，无语音的行将一直继承上一句语音
                //（实测 Blossom 旁白行挂着上一台词的语音），配对必须按下述时序：
                if ((text == null || text.isEmpty()) && voiceFile != null
                        && !voiceFile.isEmpty() && !isBgmName(voiceFile)) {
                    pendingVoice = voiceFile;
                    voiceCachedPath = voiceCached == null ? "" : voiceCached;
                }
                if (text != null && !text.isEmpty()) {
                    // 打字机分段：累积文本单调增长 = 同一句，语音标注保持粘滞
                    //（否则同句后续分段会把 ♪ 标注冲掉，显示无语音却可重播）；
                    // 句更替（文本非前缀延伸）才重置并消费待配语音
                    boolean sameSentenceGrowing = sentence.length() > 0
                            && text.length() > sentence.length()
                            && text.startsWith(sentence.toString());
                    sentence.setLength(0);
                    sentence.append(text);
                    if (!sameSentenceGrowing) {
                        voiceName = "";
                    }
                    if (!pendingVoice.isEmpty()) {
                        voiceName = pendingVoice;
                        pendingVoice = "";
                    }
                }
            }
            main.post(() -> {
                OnsExtractBridge.Listener l = extractListener;
                if (l != null) l.onExtractUpdated();
            });
        });
    }

    /** BGM 名过滤：voplay/seplay 与 bgm 共用音频通道，bgm 不参与台词配对。 */
    private static boolean isBgmName(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("bgm") || lower.contains("music");
    }

    /** 内核注册语音缓存目录（官方内核无此导出 → 静默降级为无提取）。 */
    private void registerExtractCacheDir() {
        android.util.Log.i("ArtemisExtract", "registerExtractCacheDir enter, dir="
                + extractVoiceDir().getAbsolutePath());
        try {
            ((ArtemisActivity) activity).nativeSetExtractCacheDir(
                    extractVoiceDir().getAbsolutePath() + File.separator);
        } catch (Throwable t) {
            android.util.Log.i("ArtemisExtract", "extract bridge unavailable (official kernel?)");
        }
        // 延迟重试提取钩子（ANativeActivity_onCreate 阶段 shadowhook_init 可能失败）
        try {
            ((ArtemisActivity) activity).nativeInstallExtractHook();
        } catch (Throwable t) {
            android.util.Log.i("ArtemisExtract", "late extract hook install unavailable (official kernel?)");
        }
    }

    private OnsExtractBridge.Listener extractListener;

    @Override
    public void setListener(OnsExtractBridge.Listener listener) {
        this.extractListener = listener;
    }

    // ─── 提取数据源（内核 extract_bridge 实时状态） ───────────────────

    @Override public synchronized String getSentenceText() {
        return sentence.toString();
    }

    @Override public String getPageText() {
        return getSentenceText();
    }

    @Override public synchronized String getVoiceName() {
        int slash = voiceName.lastIndexOf('/');
        return slash >= 0 ? voiceName.substring(slash + 1) : voiceName;
    }

    @Override public String getLockedCharset() { return "UTF-8"; }

    @Override
    public byte[] ensureVoiceBytes() {
        String path = voiceCachedPath;
        return path.isEmpty() ? null : readFile(new File(path));
    }

    @Override
    public byte[] readVoiceBytesByName(String name) {
        if (name == null || name.isEmpty()) return null;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        File f = new File(extractVoiceDir(), name);
        byte[] bytes = readFile(f);
        if (bytes != null) return bytes;
        // 缓存副本未命中（该行尚未在本次会话播放过）：按名字模糊扫缓存
        File dir = extractVoiceDir();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File c : files) {
                if (c.getName().contains(name)) return readFile(c);
            }
        }
        // 原生钩子不落盘（语音副本机制未随带桥内核分发）：退游戏目录常见语音位置按名探测
        if (gameRoot != null && gameRoot.isDirectory()) {
            for (File hit : probeVoiceInGameDir(gameRoot, name)) {
                bytes = readFile(hit);
                if (bytes != null) return bytes;
            }
        }
        return null;
    }

    /** 在游戏根常见语音目录（voice/ 及根目录）按名探测（含常见音频扩展名与模糊包含）。 */
    private static List<File> probeVoiceInGameDir(File root, String name) {
        List<File> out = new ArrayList<>();
        String baseName = name;
        int dot = baseName.lastIndexOf('.');
        if (dot > 0) baseName = baseName.substring(0, dot);
        String[] dirs = {"voice", ""};
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

    @Override public void setScreenshot(Bitmap bmp) { }

    @Override public Bitmap getScreenshot() { return null; }

    // ─── 宿主能力 ─────────────────────────────────────────────────────

    @Override public Activity getActivity() { return activity; }

    @Override public String gameDisplayName() {
        return gameRoot == null ? "artemis" : gameRoot.getName();
    }

    // ─── 存档管理（savedata/save 双候选，同 KRKR facade） ─────────────

    /** 当前游戏的存档目录（第一个存在的候选；导入用第一个候选）。 */
    private File primarySaveDir() {
        if (gameRoot == null) return null;
        for (String name : SAVE_DIR_CANDIDATES) {
            File dir = new File(gameRoot, name);
            if (dir.isDirectory()) return dir;
        }
        return null;
    }

    @Override
    public File[] listSaves() {
        List<File> saves = new ArrayList<>();
        for (String name : SAVE_DIR_CANDIDATES) {
            File dir = gameRoot == null ? null : new File(gameRoot, name);
            File[] files = dir == null ? null : dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (f.isFile()) saves.add(f);
            }
        }
        saves.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return saves.toArray(new File[0]);
    }

    @Override
    public File zipSavesToCache() {
        File saveDir = primarySaveDir();
        if (saveDir == null) return null;
        File out = new File(activity.getCacheDir(), "artemis_cloud_saves.zip");
        try (java.io.OutputStream os = new java.io.FileOutputStream(out)) {
            return SaveZipUtil.zipDirectory(saveDir, os) >= 0 ? out : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public int importSavesFromZip(File zip) {
        try (java.io.InputStream in = new java.io.FileInputStream(zip)) {
            File saveDir = primarySaveDir();
            if (saveDir == null && gameRoot != null) {
                saveDir = new File(gameRoot, SAVE_DIR_CANDIDATES[0]);
            }
            if (saveDir == null) return -1;
            if (!saveDir.isDirectory()) saveDir.mkdirs();
            return SaveZipUtil.unzipInto(in, saveDir);
        } catch (Throwable t) {
            return -1;
        }
    }

    @Override
    public void exportSaveArchive() {
        WebSaveArchive.export(activity, primarySaveDir(), gameDisplayName());
    }

    @Override
    public void importSaveArchive() {
        WebSaveArchive.importArchive(activity);
    }

    @Override
    public void importDictionary() {
        // 词典查词依赖文本提取，钩子已接入但查词词典导入入口仍按需启用
        try {
            // 与 ONS 宿主一致：SAF 导入词典 zip → OnsDictStore
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            activity.startActivityForResult(i, 43003);
        } catch (Throwable t) {
            android.util.Log.w("ArtemisExtract", "dict import launch failed", t);
        }
    }

    /** SAF 词典导入回调（宿主 onActivityResult 委托）。 */
    public void onDictImportPicked(Intent uri) {
        if (uri == null || uri.getData() == null) return;
        android.net.Uri data = uri.getData();
        new Thread(() -> {
            try {
                int count = OnsDictStore.get().importFromFile(activity, data,
                        msg -> main.post(() -> Toast(msg)));
                String name = OnsDictStore.get().getDictName();
                main.post(() -> Toast(activity.getString(
                        com.core.engine.R.string.engine_ons_extract_dict_imported, name, count)));
            } catch (Throwable t) {
                android.util.Log.w("ArtemisExtract", "dict import failed", t);
                main.post(() -> Toast(com.core.engine.R.string.engine_ons_extract_action_failed));
            }
        }, "artemis-dict-import").start();
    }

    private void Toast(String msg) {
        android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void Toast(int res) {
        Toast(activity.getString(res));
    }

    /** 宿主 onActivityResult 直接委托；返回 true 表示已消费。 */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 43003) {
            onDictImportPicked(data);
            return true;
        }
        return WebSaveArchive.onActivityResult(requestCode, resultCode, data,
                primarySaveDir(), activity, main);
    }
}
