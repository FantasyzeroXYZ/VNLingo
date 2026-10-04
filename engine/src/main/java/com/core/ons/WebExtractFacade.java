package com.core.ons;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.core.engine.R;

import java.io.File;

/**
 * Web 宿主（TyranoActivity / RpgMakerActivity）的 ExtractFacade 实现：
 * 事件委托 WebExtractBridge，存档能力基于宿主 resolveSaveDirectory 的目录
 * （SaveZipUtil 打包/解包 + SAF 选择器，本类持有请求码并处理回调）。
 */
public final class WebExtractFacade implements ExtractFacade {

    private static final String TAG = "WebExtract";

    private final Activity activity;
    private final WebExtractBridge bridge = WebExtractBridge.get();
    private final File saveDir;
    private final String gameName;
    private final Handler main = new Handler(Looper.getMainLooper());

    public WebExtractFacade(Activity activity, File saveDir, String gameName) {
        this.activity = activity;
        this.saveDir = saveDir;
        this.gameName = gameName;
    }

    @Override
    public String getSentenceText() {
        return bridge.getSentenceText();
    }

    @Override
    public String getPageText() {
        return bridge.getPageText();
    }

    @Override
    public String getVoiceName() {
        return bridge.getVoiceName();
    }

    @Override
    public String getLockedCharset() {
        return bridge.getLockedCharset();
    }

    @Override
    public byte[] ensureVoiceBytes() {
        return bridge.ensureVoiceBytes();
    }

    @Override
    public byte[] readVoiceBytesByName(String name) {
        return bridge.readVoiceBytesByName(name);
    }

    @Override
    public void setScreenshot(android.graphics.Bitmap bmp) {
        WebExtractScreenshotHolder.bmp = bmp;
    }

    @Override
    public android.graphics.Bitmap getScreenshot() {
        return WebExtractScreenshotHolder.bmp;
    }

    @Override
    public void setListener(OnsExtractBridge.Listener listener) {
        bridge.setListener(listener);
    }

    @Override
    public Activity getActivity() {
        return activity;
    }

    @Override
    public String gameDisplayName() {
        return gameName;
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
        File out = new File(activity.getCacheDir(), "web_cloud_saves.zip");
        try (java.io.OutputStream os = new java.io.FileOutputStream(out)) {
            return SaveZipUtil.zipDirectory(saveDir, os) >= 0 ? out : null;
        } catch (Throwable t) {
            Log.w(TAG, "zipSavesToCache failed", t);
            return null;
        }
    }

    @Override
    public int importSavesFromZip(File zip) {
        try (java.io.InputStream in = new java.io.FileInputStream(zip)) {
            return SaveZipUtil.unzipInto(in, saveDir);
        } catch (Throwable t) {
            Log.w(TAG, "importSavesFromZip failed", t);
            return -1;
        }
    }

    @Override
    public void exportSaveArchive() {
        if (saveDir == null || !saveDir.isDirectory()) {
            Toast.makeText(activity, R.string.engine_ons_extract_action_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_TITLE, gameName + "_saves.zip");
            activity.startActivityForResult(intent, WebSaveArchive.REQ_EXPORT);
        } catch (Throwable t) {
            Log.w(TAG, "export saves launch failed", t);
        }
    }

    @Override
    public void importSaveArchive() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            activity.startActivityForResult(intent, WebSaveArchive.REQ_IMPORT);
        } catch (Throwable t) {
            Log.w(TAG, "import saves launch failed", t);
        }
    }

    @Override
    public void importDictionary() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            activity.startActivityForResult(intent, WebSaveArchive.REQ_IMPORT_DICT);
        } catch (Throwable t) {
            Log.w(TAG, "dict import launch failed", t);
        }
    }

    /** 宿主 onActivityResult 直接委托；返回 true 表示已消费。 */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        return WebSaveArchive.onActivityResult(requestCode, resultCode, data, saveDir, activity, main);
    }

    /** Web 宿主 gameDisplayName 由宿主覆写语义，这里提供目录名兜底。 */
    private static final class WebExtractScreenshotHolder {
        static android.graphics.Bitmap bmp;
    }
}
