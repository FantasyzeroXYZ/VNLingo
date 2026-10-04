package com.core.ons;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.widget.Toast;

import com.core.engine.R;

import java.io.File;

/**
 * Web 宿主的存档 SAF 导出/导入（系统文件选择器），后台线程执行 + toast 结果。
 * 宿主 onActivityResult 一行委托 {@link #onActivityResult}。
 */
public final class WebSaveArchive {

    public static final int REQ_EXPORT = 43001;
    public static final int REQ_IMPORT = 43002;
    public static final int REQ_IMPORT_DICT = 43003;

    private WebSaveArchive() {
    }

    public static void export(Activity activity, File dir, String gameName) {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_TITLE, gameName + "_saves.zip");
            activity.startActivityForResult(intent, REQ_EXPORT);
        } catch (Throwable ignored) {
        }
    }

    public static void importArchive(Activity activity) {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            activity.startActivityForResult(intent, REQ_IMPORT);
        } catch (Throwable ignored) {
        }
    }

    /** 返回 true 表示该回调已被本类消费。 */
    public static boolean onActivityResult(int requestCode, int resultCode, Intent data,
                                           File saveDir, Activity activity, Handler main) {
        if (requestCode != REQ_EXPORT && requestCode != REQ_IMPORT) return false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) {
            new Thread(() -> {
                int count;
                try (java.io.OutputStream out =
                             activity.getContentResolver().openOutputStream(uri)) {
                    count = SaveZipUtil.zipDirectory(saveDir, out);
                } catch (Throwable t) {
                    toast(activity, main, activity.getString(
                            R.string.engine_ons_extract_action_failed));
                    return;
                }
                toast(activity, main, activity.getString(
                        R.string.engine_ons_extract_export_done, count));
            }, "web-save-export").start();
        } else {
            new Thread(() -> {
                int count;
                try (java.io.InputStream in =
                             activity.getContentResolver().openInputStream(uri)) {
                    count = SaveZipUtil.unzipInto(in, saveDir);
                } catch (Throwable t) {
                    toast(activity, main, activity.getString(
                            R.string.engine_ons_extract_action_failed));
                    return;
                }
                toast(activity, main, activity.getString(
                        R.string.engine_ons_extract_import_done, count));
            }, "web-save-import").start();
        }
        return true;
    }

    private static void toast(Activity activity, Handler main, String message) {
        main.post(() -> Toast.makeText(activity, message, Toast.LENGTH_LONG).show());
    }
}
