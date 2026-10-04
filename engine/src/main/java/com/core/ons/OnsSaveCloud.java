package com.core.ons;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * ONS 存档云同步：把 {@link ONScripter#zipSavesToCache()} 产出的存档 zip
 * 上传 / 下载到 WebDAV（坚果云等）或 GitHub 仓库（Contents API）。
 *
 * 配置持久化在 prefs（默认文件），凭据只存本机 SharedPreferences。
 * 路径约定：<远程目录>/<游戏目录名>.zip（按游戏隔离，互不覆盖）。
 */
public final class OnsSaveCloud {

    private static final String TAG = "OnsSaveCloud";
    private static final String PREFS = "ons_extract_cloud";
    public static final String KEY_MODE = "mode";           // webdav | github
    public static final String KEY_SERVER = "server";       // WebDAV 基础 URL
    public static final String KEY_REPO = "repo";           // GitHub owner/repo
    public static final String KEY_USER = "user";
    public static final String KEY_TOKEN = "token";
    public static final String KEY_DIR = "dir";

    public static final String MODE_WEBDAV = "webdav";
    public static final String MODE_GITHUB = "github";
    private static final String GITHUB_API = "https://api.github.com";
    private static final String DEFAULT_DIR = "TyranorNext/saves";

    /** ok=true 时 message 为成功提示；ok=false 时为错误信息。 */
    public interface Callback {
        void onResult(boolean ok, String message);
    }

    private OnsSaveCloud() {
    }

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 上传 zip（后台线程执行，回调经调用方自行切主线程）。 */
    public static void upload(Context context, String gameName, File zip, Callback callback) {
        run(context, gameName, callback, new Transfer() {
            @Override
            public void run(SharedPreferences p, String remotePath) throws Exception {
                if (MODE_GITHUB.equals(p.getString(KEY_MODE, MODE_WEBDAV))) {
                    githubPut(p, remotePath, zip);
                } else {
                    webdavEnsureDir(p);
                    webdavRequest(p, "PUT", remotePath, zip);
                }
            }
        });
    }

    /** 下载 zip 到 dest（后台线程执行）。云端不存在时 callback(false, "404")。 */
    public static void download(Context context, String gameName, File dest, Callback callback) {
        run(context, gameName, callback, new Transfer() {
            @Override
            public void run(SharedPreferences p, String remotePath) throws Exception {
                if (MODE_GITHUB.equals(p.getString(KEY_MODE, MODE_WEBDAV))) {
                    githubGet(p, remotePath, dest);
                } else {
                    webdavRequest(p, "GET", remotePath, dest);
                }
            }
        });
    }

    private interface Transfer {
        void run(SharedPreferences p, String remotePath) throws Exception;
    }

    private static void run(Context context, String gameName, Callback callback, Transfer transfer) {
        SharedPreferences p = prefs(context);
        new Thread(() -> {
            try {
                String dir = p.getString(KEY_DIR, DEFAULT_DIR);
                String remote = (dir.endsWith("/") ? dir : dir + "/")
                        + (gameName == null || gameName.isEmpty() ? "ons" : gameName) + ".zip";
                transfer.run(p, remote);
                callback.onResult(true, remote);
            } catch (Throwable t) {
                Log.w(TAG, "cloud transfer failed", t);
                callback.onResult(false, t.getMessage() == null ? t.toString() : t.getMessage());
            }
        }, "ons-cloud").start();
    }

    // ------------------------------------------------------------------
    // WebDAV（Basic 认证）
    // ------------------------------------------------------------------

    private static void webdavRequest(SharedPreferences p, String method, String remotePath,
                                      File file) throws Exception {
        String base = p.getString(KEY_SERVER, "");
        if (base.isEmpty()) throw new IllegalStateException("server not configured");
        HttpURLConnection conn = open((base.endsWith("/") ? base : base + "/") + remotePath,
                p.getString(KEY_USER, ""), p.getString(KEY_TOKEN, ""));
        try {
            conn.setRequestMethod(method);
            if ("PUT".equals(method)) {
                conn.setDoOutput(true);
                byte[] payload = readFile(file);
                conn.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(payload);
                }
            }
            int code = conn.getResponseCode();
            if (code >= 400) {
                throw new IllegalStateException(method + " HTTP " + code);
            }
            if ("GET".equals(method)) {
                InputStream in = conn.getInputStream();
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                } finally {
                    in.close();
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    /** 逐级确保远程目录存在（已存在时 MKCOL 报 405，忽略）。 */
    private static void webdavEnsureDir(SharedPreferences p) {
        try {
            String base = p.getString(KEY_SERVER, "");
            String dir = p.getString(KEY_DIR, DEFAULT_DIR);
            String[] parts = dir.split("/");
            StringBuilder path = new StringBuilder();
            for (String part : parts) {
                if (part.isEmpty()) continue;
                path.append(part).append('/');
                HttpURLConnection conn = open(base.endsWith("/") ? base + path : base + "/" + path,
                        p.getString(KEY_USER, ""), p.getString(KEY_TOKEN, ""));
                try {
                    conn.setRequestMethod("MKCOL");
                    conn.getResponseCode();
                } finally {
                    conn.disconnect();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "webdav mkcol skipped", t);
        }
    }

    // ------------------------------------------------------------------
    // GitHub Contents API（token 认证，base64 内容）
    // ------------------------------------------------------------------

    private static void githubPut(SharedPreferences p, String remotePath, File file) throws Exception {
        String repo = p.getString(KEY_REPO, "");
        if (repo.isEmpty()) throw new IllegalStateException("repo not configured");
        String url = GITHUB_API + "/repos/" + repo + "/contents/" + remotePath;
        // 覆盖已有文件必须带原 sha
        String sha = null;
        HttpURLConnection head = open(url, p.getString(KEY_USER, ""), p.getString(KEY_TOKEN, ""));
        try {
            head.setRequestMethod("GET");
            if (head.getResponseCode() == 200) {
                JSONObject obj = new JSONObject(readText(head.getInputStream()));
                sha = obj.optString("sha", null);
            }
        } finally {
            head.disconnect();
        }
        JSONObject body = new JSONObject();
        body.put("message", "TyranorNext save sync: " + remotePath);
        body.put("content", Base64.encodeToString(readFile(file), Base64.NO_WRAP));
        if (sha != null) body.put("sha", sha);
        HttpURLConnection conn = open(url, p.getString(KEY_USER, ""), p.getString(KEY_TOKEN, ""));
        try {
            conn.setRequestMethod("PUT");
            conn.setDoOutput(true);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            int code = conn.getResponseCode();
            if (code >= 400) throw new IllegalStateException("GitHub PUT HTTP " + code);
        } finally {
            conn.disconnect();
        }
    }

    private static void githubGet(SharedPreferences p, String remotePath, File dest) throws Exception {
        String repo = p.getString(KEY_REPO, "");
        if (repo.isEmpty()) throw new IllegalStateException("repo not configured");
        String url = GITHUB_API + "/repos/" + repo + "/contents/" + remotePath;
        HttpURLConnection conn = open(url, p.getString(KEY_USER, ""), p.getString(KEY_TOKEN, ""));
        try {
            int code = conn.getResponseCode();
            if (code == 404) throw new IllegalStateException("404 not found");
            if (code >= 400) throw new IllegalStateException("GitHub GET HTTP " + code);
            JSONObject obj = new JSONObject(readText(conn.getInputStream()));
            byte[] data = Base64.decode(obj.optString("content", ""), Base64.DEFAULT);
            try (FileOutputStream fos = new FileOutputStream(dest)) {
                fos.write(data);
            }
        } finally {
            conn.disconnect();
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static HttpURLConnection open(String url, String user, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        if (!user.isEmpty() || !token.isEmpty()) {
            String cred = user + ":" + token;
            conn.setRequestProperty("Authorization", "Basic "
                    + Base64.encodeToString(cred.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        }
        return conn;
    }

    private static byte[] readFile(File file) throws Exception {
        if (file == null || !file.exists() || file.length() == 0) {
            throw new IllegalStateException("save zip missing");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String readText(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // 云同步配置弹窗（存档面板 ☁ 与游戏内「设置」共用）
    // ------------------------------------------------------------------

    private static final int BG_BUTTON = 0xFFF1F5F9;
    private static final int ACCENT = 0xFF007AFF;

    /**
     * 配置弹窗：模式切换（WebDAV/GitHub）+ 连接字段， Positive/Neutral 保存后分别
     * 触发 onUpload/onDownload（为 null 时隐藏对应按钮，纯配置场景）。
     */
    public static void showConfigDialog(android.app.Activity activity,
                                        Runnable onUpload, Runnable onDownload,
                                        Runnable onConfigSaved) {
        SharedPreferences p = prefs(activity);
        android.widget.LinearLayout box = new android.widget.LinearLayout(activity);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        box.setPadding(pad, pad, pad, 0);

        android.widget.LinearLayout modeRow = new android.widget.LinearLayout(activity);
        modeRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        String mode = p.getString(KEY_MODE, MODE_WEBDAV);
        android.widget.TextView webdavBtn = modeButton(activity, modeRow,
                com.core.engine.R.string.engine_ons_extract_cloud_mode_webdav, MODE_WEBDAV.equals(mode));
        android.widget.TextView githubBtn = modeButton(activity, modeRow,
                com.core.engine.R.string.engine_ons_extract_cloud_mode_github, MODE_GITHUB.equals(mode));
        webdavBtn.setOnClickListener(v -> setModeButton(webdavBtn, githubBtn, MODE_WEBDAV));
        githubBtn.setOnClickListener(v -> setModeButton(githubBtn, webdavBtn, MODE_GITHUB));
        box.addView(modeRow, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        android.widget.EditText serverField = field(activity, box,
                com.core.engine.R.string.engine_ons_extract_cloud_hint_server, p.getString(KEY_SERVER, ""));
        android.widget.EditText repoField = field(activity, box,
                com.core.engine.R.string.engine_ons_extract_cloud_hint_repo, p.getString(KEY_REPO, ""));
        android.widget.EditText userField = field(activity, box,
                com.core.engine.R.string.engine_ons_extract_cloud_hint_user, p.getString(KEY_USER, ""));
        android.widget.EditText tokenField = field(activity, box,
                com.core.engine.R.string.engine_ons_extract_cloud_hint_token, p.getString(KEY_TOKEN, ""));
        android.widget.EditText dirField = field(activity, box,
                com.core.engine.R.string.engine_ons_extract_cloud_hint_dir, p.getString(KEY_DIR, DEFAULT_DIR));

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(activity)
                .setTitle(com.core.engine.R.string.engine_ons_extract_cloud_title)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null);
        if (onUpload != null) {
            builder.setPositiveButton(com.core.engine.R.string.engine_ons_extract_cloud_upload, null);
        }
        if (onDownload != null) {
            builder.setNeutralButton(com.core.engine.R.string.engine_ons_extract_cloud_download, null);
        }
        android.app.AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            if (onUpload != null) {
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    saveConfig(p, webdavBtn, serverField, repoField, userField, tokenField, dirField);
                    dialog.dismiss();
                    onUpload.run();
                });
            }
            if (onDownload != null) {
                dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    saveConfig(p, webdavBtn, serverField, repoField, userField, tokenField, dirField);
                    dialog.dismiss();
                    onDownload.run();
                });
            }
            if (onConfigSaved != null) onConfigSaved.run();
        });
        dialog.show();
    }

    /** 保存当前弹窗字段到 prefs（模式取自模式按钮 tag）。 */
    private static void saveConfig(SharedPreferences p, android.widget.TextView modeBtn,
                                   android.widget.EditText server, android.widget.EditText repo,
                                   android.widget.EditText user, android.widget.EditText token,
                                   android.widget.EditText dir) {
        String mode = modeBtn.getTag() instanceof String ? (String) modeBtn.getTag() : MODE_WEBDAV;
        p.edit()
                .putString(KEY_MODE, mode)
                .putString(KEY_SERVER, textOr(server, ""))
                .putString(KEY_REPO, textOr(repo, ""))
                .putString(KEY_USER, textOr(user, ""))
                .putString(KEY_TOKEN, textOr(token, ""))
                .putString(KEY_DIR, textOr(dir, DEFAULT_DIR))
                .apply();
    }

    private static android.widget.TextView modeButton(android.app.Activity activity,
                                                      android.widget.LinearLayout row,
                                                      int labelRes, boolean active) {
        android.widget.TextView tv = new android.widget.TextView(activity);
        tv.setText(labelRes);
        tv.setTextSize(12);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setPadding(dp(activity, 14), dp(activity, 6), dp(activity, 14), dp(activity, 6));
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(activity, 6);
        tv.setBackground(rounded(activity, active ? ACCENT : BG_BUTTON));
        row.addView(tv, lp);
        return tv;
    }

    private static void setModeButton(android.widget.TextView active, android.widget.TextView inactive,
                                      String mode) {
        active.setBackground(rounded(active.getContext(), ACCENT));
        inactive.setBackground(rounded(inactive.getContext(), BG_BUTTON));
        active.setTag(mode);
    }

    private static android.widget.EditText field(android.app.Activity activity,
                                                 android.widget.LinearLayout box,
                                                 int hintRes, String value) {
        android.widget.TextView hint = new android.widget.TextView(activity);
        hint.setText(hintRes);
        hint.setTextSize(11);
        box.addView(hint, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        android.widget.EditText field = new android.widget.EditText(activity);
        field.setText(value);
        field.setTextSize(13);
        field.setSingleLine(true);
        box.addView(field, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        return field;
    }

    private static String textOr(android.widget.EditText field, String fallback) {
        String s = field.getText() == null ? "" : field.getText().toString().trim();
        return s.isEmpty() ? fallback : s;
    }

    private static android.graphics.drawable.GradientDrawable rounded(android.content.Context ctx,
                                                                      int color) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(ctx, 10));
        return d;
    }

    private static int dp(android.content.Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
