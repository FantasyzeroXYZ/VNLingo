package com.core.ons;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 游戏截图存储：getExternalFilesDir(null)/screenshots/<游戏名>/xxxx.png。
 * 游戏内「截图」按键与提取面板截图共用本目录；主页「截图管理」页按本目录展示。
 */
public final class GameScreenshots {

    private GameScreenshots() {
    }

    /** 单游戏的截图目录（游戏名为目录名，非法字符转下划线）。 */
    public static File dir(Context context, String gameName) {
        File root = new File(context.getExternalFilesDir(null), "screenshots");
        String safe = sanitize(gameName);
        return new File(root, safe.isEmpty() ? "unknown" : safe);
    }

    /** 全部截图（跨游戏目录，按修改时间倒序）。 */
    public static List<File> list(Context context) {
        File root = new File(context.getExternalFilesDir(null), "screenshots");
        List<File> out = new ArrayList<>();
        File[] games = root.listFiles();
        if (games != null) {
            for (File game : games) {
                File[] shots = game.listFiles();
                if (shots == null) continue;
                Collections.addAll(out, shots);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    /** 截图所属游戏名（父目录名；根目录散图归 unknown）。 */
    public static String gameOf(File shot) {
        File parent = shot.getParentFile();
        return parent == null ? "unknown" : parent.getName();
    }

    private static String sanitize(String name) {
        if (name == null) return "";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
