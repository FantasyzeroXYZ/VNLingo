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
import java.util.Locale;

/**
 * ONS 存档云同步：把 {@link ONScripter#zipSavesToCache()} 产出的存档 zip
 * 上传 / 下载到 WebDAV（坚果云等）或 GitHub 仓库（Contents API）。
 *
 * 配置持久化在 prefs（默认文件），凭据只存本机 SharedPreferences。
 * 路径约定：<远程目录>/<游戏目录名>.zip（按游戏隔离，互不覆盖）。
 *
 * WebDAV 凭据跟随：app 层经 {@link #setWebDavCredentials} 注入云同步中心
 * 的活动账户；自有 WebDAV 字段（server）留空时自动改用该账户，填写后
 * 以自有配置为准（显式覆盖）。GitHub 模式不参与跟随。
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
    /** 每游戏上次成功上传时间（存档管理页状态显示）。 */
    private static final String KEY_UPLOAD_PREFIX = "ul_";

    public static final String MODE_WEBDAV = "webdav";
    public static final String MODE_GITHUB = "github";
    private static final String GITHUB_API = "https://api.github.com";
    private static final String DEFAULT_DIR = "TyranorNext/saves";

    /** WebDAV 凭据外部来源：返回 {server, username, password}，null/缺项 = 未配置。 */
    public interface WebDavCredentials {
        String[] get();
    }

    private static volatile WebDavCredentials webDavCredentials;

    /** app 启动时注入云同步中心活动账户来源（engine 不反向依赖 app）。 */
    public static void setWebDavCredentials(WebDavCredentials provider) {
        webDavCredentials = provider;
    }

    /** 当前跟随的账户（未注入/未配置返回 null）。 */
    public static String[] webDavAccount() {
        WebDavCredentials provider = webDavCredentials;
        if (provider == null) return null;
        try {
            String[] c = provider.get();
            if (c == null || c.length < 3) return null;
            if (c[0] == null || c[1] == null || c[2] == null) return null;
            String server = c[0].trim();
            String user = c[1].trim();
            if (server.isEmpty() || user.isEmpty() || c[2].isEmpty()) return null;
            return new String[]{server, user, c[2]};
        } catch (Throwable t) {
            Log.w(TAG, "webDavAccount provider failed", t);
            return null;
        }
    }

    /** ok=true 时 message 为成功提示；ok=false 时为错误信息。 */
    public interface Callback {
        void onResult(boolean ok, String message);
    }

    /** 云端时间查询回调（ms；0 = 未知/不支持）。 */
    public interface TimeCallback {
        void onResult(long epochMs);
    }

    private OnsSaveCloud() {
    }

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------
    // 状态查询（存档管理页云同步区用）
    // ------------------------------------------------------------------

    /** 远端路径名与 run() 一致：dir/name.zip。 */
    private static String remotePath(SharedPreferences p, String gameName) {
        String dir = p.getString(KEY_DIR, DEFAULT_DIR);
        return (dir.endsWith("/") ? dir : dir + "/")
                + (gameName == null || gameName.isEmpty() ? "ons" : gameName) + ".zip";
    }

    /** 是否已配置到可用程度（WebDAV 要 server；GitHub 要 repo）。 */
    public static boolean isConfigured(Context context) {
        SharedPreferences p = prefs(context);
        if (MODE_GITHUB.equals(p.getString(KEY_MODE, MODE_WEBDAV))) {
            return !p.getString(KEY_REPO, "").isEmpty();
        }
        return !p.getString(KEY_SERVER, "").isEmpty() || webDavAccount() != null;
    }

    /**
     * WebDAV 实际使用的 {server, user, password}：自有 server 非空则用自有配置
     * （user/token 可部分留空），否则回退云同步中心活动账户，再无则原样返回空串。
     */
    private static String[] webdavTarget(SharedPreferences p) {
        String server = p.getString(KEY_SERVER, "");
        String user = p.getString(KEY_USER, "");
        String token = p.getString(KEY_TOKEN, "");
        if (!server.isEmpty() || MODE_GITHUB.equals(p.getString(KEY_MODE, MODE_WEBDAV))) {
            return new String[]{server, user, token};
        }
        String[] account = webDavAccount();
        if (account != null) return account;
        return new String[]{server, user, token};
    }

    /** 本机记录的上次成功上传时间（0 = 从未；上传成功后由调用方经 recordUpload 记录）。 */
    public static long lastUpload(Context context, String gameName) {
        return prefs(context).getLong(KEY_UPLOAD_PREFIX + gameName, 0);
    }

    /** 上传成功后记录时间（调用方在上传回调 ok 后调用）。 */
    public static void recordUpload(Context context, String gameName, long epochMs) {
        prefs(context).edit().putLong(KEY_UPLOAD_PREFIX + gameName, epochMs).apply();
    }

    /** 查询云端文件更新时间（WebDAV HEAD Last-Modified；GitHub 不支持返回 0）。后台线程。 */
    public static void cloudModified(Context context, String gameName, TimeCallback callback) {
        SharedPreferences p = prefs(context);
        new Thread(() -> {
            long result = 0;
            try {
                if (!MODE_GITHUB.equals(p.getString(KEY_MODE, MODE_WEBDAV))) {
                    String[] target = webdavTarget(p);
                    String base = target[0];
                    if (!base.isEmpty()) {
                        HttpURLConnection conn = open((base.endsWith("/") ? base : base + "/")
                                + remotePath(p, gameName),
                                target[1], target[2]);
                        try {
                            conn.setRequestMethod("HEAD");
                            if (conn.getResponseCode() < 400) {
                                String date = conn.getHeaderField("Last-Modified");
                                if (date != null) {
                                    result = parseHttpDate(date);
                                }
                            }
                        } finally {
                            conn.disconnect();
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "cloudModified failed", t);
            }
            callback.onResult(result);
        }, "ons-cloud-time").start();
    }

    /** RFC 1123（ Last-Modified）解析；失败返回 0。 */
    private static long parseHttpDate(String value) {
        String[] formats = {"EEE, dd MMM yyyy HH:mm:ss zzz", "EEEE, dd-MMM-yy HH:mm:ss zzz"};
        for (String format : formats) {
            try {
                java.text.SimpleDateFormat parser = new java.text.SimpleDateFormat(format, Locale.US);
                parser.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
                java.util.Date date = parser.parse(value.trim());
                if (date != null) return date.getTime();
            } catch (Throwable ignored) {
            }
        }
        return 0;
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
        String[] target = webdavTarget(p);
        String base = target[0];
        if (base.isEmpty()) throw new IllegalStateException("server not configured");
        HttpURLConnection conn = open((base.endsWith("/") ? base : base + "/") + remotePath,
                target[1], target[2]);
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
            String[] target = webdavTarget(p);
            String base = target[0];
            if (base.isEmpty()) throw new IllegalStateException("server not configured");
            String dir = p.getString(KEY_DIR, DEFAULT_DIR);
            String[] parts = dir.split("/");
            StringBuilder path = new StringBuilder();
            for (String part : parts) {
                if (part.isEmpty()) continue;
                path.append(part).append('/');
                HttpURLConnection conn = open(base.endsWith("/") ? base + path : base + "/" + path,
                        target[1], target[2]);
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

        // 跟随提示：WebDAV 模式且云同步中心有活动账户时，说明留空字段的回退行为
        if (!MODE_GITHUB.equals(mode)) {
            String[] account = webDavAccount();
            if (account != null) {
                android.widget.TextView followHint = new android.widget.TextView(activity);
                followHint.setText(activity.getString(
                        com.core.engine.R.string.engine_ons_extract_cloud_hint_follow, account[1]));
                followHint.setTextSize(11);
                followHint.setPadding(0, dp(activity, 8), 0, 0);
                box.addView(followHint, new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
            }
        }

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
