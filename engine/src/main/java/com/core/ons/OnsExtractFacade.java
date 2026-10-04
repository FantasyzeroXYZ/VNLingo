package com.core.ons;

import android.app.Activity;
import android.graphics.Bitmap;

import com.yuri.onscripter.ONScripter;

import java.io.File;

/**
 * ONS 宿主的 ExtractFacade 实现：事件委托 OnsExtractBridge，宿主能力委托 ONScripter
 * 既有方法（存档目录/zip/SAF 导入导出）。
 */
public final class OnsExtractFacade implements ExtractFacade {

    private final ONScripter activity;

    public OnsExtractFacade(ONScripter activity) {
        this.activity = activity;
    }

    @Override
    public String getSentenceText() {
        return OnsExtractBridge.get().getSentenceText();
    }

    @Override
    public String getPageText() {
        return OnsExtractBridge.get().getPageText();
    }

    @Override
    public String getVoiceName() {
        return OnsExtractBridge.get().getVoiceName();
    }

    @Override
    public String getLockedCharset() {
        return OnsExtractBridge.get().getLockedCharset();
    }

    @Override
    public byte[] ensureVoiceBytes() {
        return OnsExtractBridge.get().ensureVoiceBytes();
    }

    @Override
    public byte[] readVoiceBytesByName(String name) {
        return OnsExtractBridge.get().readVoiceBytesByName(name);
    }

    @Override
    public void setScreenshot(Bitmap bmp) {
        OnsExtractBridge.get().setLastScreenshot(bmp);
    }

    @Override
    public Bitmap getScreenshot() {
        return OnsExtractBridge.get().getLastScreenshot();
    }

    @Override
    public void setListener(OnsExtractBridge.Listener listener) {
        OnsExtractBridge.get().setListener(listener);
    }

    @Override
    public Activity getActivity() {
        return activity;
    }

    @Override
    public String gameDisplayName() {
        return activity.gameDisplayName();
    }

    @Override
    public File[] listSaves() {
        return activity.listSaves();
    }

    @Override
    public File zipSavesToCache() {
        return activity.zipSavesToCache();
    }

    @Override
    public int importSavesFromZip(File zip) {
        return activity.importSavesFromZip(zip);
    }

    @Override
    public void exportSaveArchive() {
        activity.exportSaveArchive();
    }

    @Override
    public void importSaveArchive() {
        activity.importSaveArchive();
    }

    @Override
    public void importDictionary() {
        activity.importDictionarySaf();
    }
}
