package com.core.ons;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;

import java.io.File;

/**
 * 提取面板（剧情文本/查词/制卡/翻译/存档）与宿主解耦的门面接口：
 * ONS 宿主（OnsExtractFacade 委托 OnsExtractBridge + ONScripter）与
 * Web 宿主（WebExtractFacade 委托 WebExtractBridge + Tyrano/RpgMaker Activity）
 * 各自实现，面板代码完全复用。
 */
public interface ExtractFacade {

    // ─── 事件源（当前句/页/配对语音） ─────────────────────────────────

    /** 本句增量文本（web 宿主为整句）。 */
    String getSentenceText();

    /** 整页文本（复制/制卡兜底）。 */
    String getPageText();

    /** 当前句配对语音名（空串表示无语音）。 */
    String getVoiceName();

    /** 会话锁定编码（SJIS/GBK/UTF-8），供 TTS 选语言；web 宿主返回 UTF-8。 */
    String getLockedCharset();

    /** 当前句语音字节（配对缓存优先，缺了按名补读）。 */
    byte[] ensureVoiceBytes();

    /** 历史回放：按存储名读语音字节（不影响当前配对缓存）。 */
    byte[] readVoiceBytesByName(String name);

    /** 面板截图（Anki 制卡用）。 */
    void setScreenshot(Bitmap bmp);

    Bitmap getScreenshot();

    /** 新提取事件回调（已切主线程由宿主保证或面板自行 main.post）。 */
    void setListener(OnsExtractBridge.Listener listener);

    // ─── 宿主能力 ─────────────────────────────────────────────────────

    Activity getActivity();

    String gameDisplayName();

    /** 存档面板：当前存档目录文件（按 mtime 倒序，过滤日志）。 */
    File[] listSaves();

    /** 云同步：当前存档目录 zip 到缓存文件（失败 null）。 */
    File zipSavesToCache();

    /** 云同步：zip 覆盖导入当前存档目录（返回文件数，失败 -1）。 */
    int importSavesFromZip(File zip);

    /** SAF：导出当前存档目录为 zip（系统文件选择器）。 */
    void exportSaveArchive();

    /** SAF：从系统文件选择器选 zip 导入存档。 */
    void importSaveArchive();

    /** SAF：选择词典文件导入（宿主 onActivityResult 转交面板 onDictImportPicked）。 */
    void importDictionary();

    /**
     * 宿主 onActivityResult 委托（SAF 导出/导入/词典选择回调）。
     * 返回 true 表示已消费。默认不消费。
     */
    default boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        return false;
    }
}
