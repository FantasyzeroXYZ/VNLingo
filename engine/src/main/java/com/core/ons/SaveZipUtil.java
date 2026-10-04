package com.core.ons;

import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 存档目录 zip 打包/解包工具：ONScripter（SAF Uri 流）与 Web 宿主
 * （本地 File 流）共用，避免两份手写遍历。
 */
public final class SaveZipUtil {

    private SaveZipUtil() {
    }

    /** 递归打包目录到输出流，返回文件数；目录不存在返回 0。 */
    public static int zipDirectory(File dir, OutputStream output) throws IOException {
        if (dir == null || !dir.isDirectory()) return 0;
        ZipOutputStream zip = new ZipOutputStream(output);
        int[] count = {0};
        try {
            zipDirectoryInner(dir, dir, zip, count);
        } finally {
            zip.close();
        }
        return count[0];
    }

    /** SAF 流入口（ONScripter 用）。 */
    public static int zipDirectory(File dir, Uri dest, android.content.ContentResolver resolver)
            throws IOException {
        try (OutputStream out = resolver.openOutputStream(dest)) {
            if (out == null) throw new IOException("openOutputStream null");
            return zipDirectory(dir, out);
        }
    }

    private static void zipDirectoryInner(File root, File dir, ZipOutputStream zip, int[] count)
            throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                zipDirectoryInner(root, f, zip, count);
                continue;
            }
            String entryName = root.toPath().relativize(f.toPath()).toString().replace('\\', '/');
            zip.putNextEntry(new ZipEntry(entryName));
            try (InputStream in = new java.io.FileInputStream(f)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
            }
            zip.closeEntry();
            count[0]++;
        }
    }

    /** 解包输入流到目标目录（覆盖写入），返回文件数。 */
    public static int unzipInto(InputStream input, File targetDir) throws IOException {
        if (input == null || targetDir == null) return 0;
        if (!targetDir.isDirectory() && !targetDir.mkdirs()) {
            throw new IOException("mkdirs failed: " + targetDir);
        }
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File out = new File(targetDir, entry.getName());
                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("mkdirs failed: " + parent);
                }
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = zip.read(buf)) > 0) fos.write(buf, 0, n);
                }
                count++;
                zip.closeEntry();
            }
        }
        return count;
    }

    /** SAF 流入口（ONScripter 用）。 */
    public static int unzipInto(Uri src, File targetDir, android.content.ContentResolver resolver)
            throws IOException {
        try (InputStream in = resolver.openInputStream(src)) {
            if (in == null) throw new IOException("openInputStream null");
            return unzipInto(in, targetDir);
        }
    }
}
