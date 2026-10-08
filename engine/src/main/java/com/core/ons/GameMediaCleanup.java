package com.core.ons;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * 提取面板的媒体文件清理（音频不保留；图片走独立「截图管理」页）。
 *
 * 应用启动与面板安装时各调用一次，覆盖「用户不启动游戏也要清掉旧缓存」的场景。
 */
public final class GameMediaCleanup {

    private static final String TAG = "GameMediaCleanup";

    private GameMediaCleanup() {
    }

    /** 清掉旧版本的语音缓存目录（历史回放的落盘语音，现已不再生成）。 */
    public static void purgeLegacyAudioCache(Context context) {
        try {
            File dir = new File(context.getCacheDir(), "ons_extract_history");
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.delete()) Log.w(TAG, "语音缓存删除失败: " + f.getName());
                }
            }
            if (dir.exists() && !dir.delete()) Log.w(TAG, "语音缓存目录删除失败: " + dir);
        } catch (Throwable t) {
            Log.w(TAG, "purgeLegacyAudioCache failed", t);
        }
    }

    /**
     * 清掉制卡用的临时媒体（`extract/anki_*.png|wav`）：已经交给 AnkiDroid 复制进
     * collection.media，本地副本没有保留价值（图片由「截图管理」页负责）。
     */
    public static void purgeStaleCardMedia(Context context) {
        try {
            File dir = new File(context.getExternalFilesDir(null), "extract");
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                String name = f.getName();
                if (name.startsWith("anki_") && !f.delete()) {
                    Log.w(TAG, "临时制卡媒体删除失败: " + name);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "purgeStaleCardMedia failed", t);
        }
    }
}
