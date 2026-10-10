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
    /**
     * 本软件在云端**只占一个主文件夹** `VNLingo/`，内部再按用途分子目录：
     * `VNLingo/VNLingo_sync.json` 主同步快照、`VNLingo/saves/<游戏>/saves.zip` 存档。
     * （旧版本存档目录是 `TyranorNext/saves`，见 [LEGACY_DIR] 兼容处理。）
     */
    private static final String DEFAULT_DIR = "VNLingo/saves";
    /** 旧版本用过的存档主目录：仍读取/下载，并在下次上传时清理。 */
    private static final String LEGACY_DIR = "TyranorNext/saves";

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

    /**
     * 云端目录布局（每个游戏一个专属子文件夹）：
     * `<dir>/<标题去非法字符>/saves.zip`
     *
     * 旧版本是 `<dir>/<标题>.zip`（同目录下平铺）；[legacyRemotePath] 保留兼容，
     * 读到旧文件仍可下载，下一次上传会迁到新布局并清掉旧的。
     */
    private static final String SAVE_FILE_NAME = "saves.zip";

    /** 游戏专属远端目录（含末尾 /）。 */
    private static String remoteDirForGame(SharedPreferences p, String gameName) {
        String dir = p.getString(KEY_DIR, DEFAULT_DIR);
        String base = dir.endsWith("/") ? dir : dir + "/";
        return base + cloudNameFor(gameName) + "/";
    }

    /** 远端 zip 路径（新布局）：dir/name/saves.zip。 */
    private static String remotePath(SharedPreferences p, String gameName) {
        return remoteDirForGame(p, gameName) + SAVE_FILE_NAME;
    }

    /** 旧布局路径：dir/name.zip（仅兼容读取/清理）。 */
    private static String legacyRemotePath(SharedPreferences p, String gameName) {
        String dir = p.getString(KEY_DIR, DEFAULT_DIR);
        return (dir.endsWith("/") ? dir : dir + "/") + cloudNameFor(gameName) + ".zip";
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

    // ------------------------------------------------------------------
    // 宿主（app 层）查询用只读访问器：云同步中心要列出"云端已有存档的游戏"
    // ------------------------------------------------------------------

    /** 远端目录（相对 server 根的路径，如 TyranorNext/saves）。 */
    public static String remoteDir(Context context) {
        String dir = prefs(context).getString(KEY_DIR, DEFAULT_DIR);
        return dir == null ? DEFAULT_DIR : dir;
    }

    /** GitHub 模式（列表/删除需要走 Contents API，宿主据此决定是否支持）。 */
    public static boolean isGithubMode(Context context) {
        return MODE_GITHUB.equals(prefs(context).getString(KEY_MODE, MODE_WEBDAV));
    }

    /**
     * 实际生效的 WebDAV 目标 {server, user, password}：自有 server 优先，
     * 否则回退云同步中心活动账户（与 upload/download 完全同一套判定）。
     */
    public static String[] webdavTargetForHost(Context context) {
        return webdavTarget(prefs(context));
    }

    /** 游戏标题 → 云端 zip 名（与面板/存档管理页同一规则：非法字符换下划线）。 */
    public static String cloudNameFor(String title) {
        if (title == null) return "game";
        String name = title.replaceAll("[\\\\/:*?\"<>|]", "_");
        return name.trim().isEmpty() ? "game" : name;
    }

    /** 远端内容路径（新布局）：dir/name/saves.zip。 */
    public static String remoteZipPath(Context context, String cloudName) {
        return remotePath(prefs(context), cloudName);
    }

    /**
     * 兼容路径（按优先级，仅在未自定义目录时返回旧目录候选）：
     * 旧主目录的新布局 → 旧主目录平铺 → 当前目录平铺。读到即可下载，上传时会清理。
     */
    public static String[] fallbackRemoteZipPaths(Context context, String cloudName) {
        SharedPreferences p = prefs(context);
        String dir = p.getString(KEY_DIR, DEFAULT_DIR);
        String name = cloudNameFor(cloudName);
        java.util.List<String> out = new java.util.ArrayList<>();
        String current = dir.endsWith("/") ? dir : dir + "/";
        boolean custom = !dir.equals(DEFAULT_DIR) && !dir.equals(LEGACY_DIR);
        if (!custom) {
            out.add(LEGACY_DIR + "/" + name + "/" + SAVE_FILE_NAME);
            out.add(LEGACY_DIR + "/" + name + ".zip");
        }
        out.add(current + name + ".zip"); // 同主目录的旧平铺布局
        return out.toArray(new String[0]);
    }

    /** 旧的平铺路径：dir/name.zip（仅用于兼容读取/迁移清理）。 */
    public static String legacyRemoteZipPath(Context context, String cloudName) {
        return legacyRemotePath(prefs(context), cloudName);
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
                        // 先看新布局（VNLingo/saves/<游戏>/saves.zip），再按旧布局候选回退
                        result = headModified(p, base, target, remotePath(p, gameName));
                        if (result == 0) {
                            for (String legacy : legacyCandidates(p, gameName)) {
                                result = headModified(p, base, target, legacy);
                                if (result > 0) break;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "cloudModified failed", t);
            }
            callback.onResult(result);
        }, "ons-cloud-time").start();
    }

    /** HEAD 一个远端路径，返回 Last-Modified（ms；不存在/失败返回 0）。 */
    private static long headModified(SharedPreferences p, String base, String[] target, String path) {
        try {
            HttpURLConnection conn = open(remoteUrl(base, path), target[1], target[2]);
            try {
                conn.setRequestMethod("HEAD");
                if (conn.getResponseCode() < 400) {
                    String date = conn.getHeaderField("Last-Modified");
                    if (date != null) return parseHttpDate(date);
                    // 服务端没给 Last-Modified：至少说明存在
                    return 1L;
                }
            } finally {
                conn.disconnect();
            }
        } catch (Throwable t) {
            Log.w(TAG, "HEAD " + path + " failed", t);
        }
        return 0;
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
                    // 新布局：先建 dir 与 dir/<游戏>/ 两级目录，再写 saves.zip
                    webdavEnsureDir(p, gameName);
                    webdavRequest(p, "PUT", remotePath, zip);
                    // 迁移清理：旧目录/旧平铺布局里的同名内容（失败无所谓）
                    for (String legacy : legacyCandidates(p, gameName)) {
                        try {
                            webdavRequest(p, "DELETE", legacy, null);
                            Log.i(TAG, "legacy remote removed: " + legacy);
                        } catch (Throwable ignored) {
                        }
                    }
                    // 顺带删旧目录留下的空文件夹（服务端对非空目录会拒绝，安全）
                    if (!MODE_GITHUB.equals(p.getString(KEY_MODE, MODE_WEBDAV))) {
                        for (String dir : legacyEmptyDirs(p)) {
                            try {
                                mkcolQuietDelete(p, dir);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }
        });
    }

    /** 旧布局候选（旧主目录新布局 / 旧主目录平铺 / 当前主目录平铺）。 */
    private static java.util.List<String> legacyCandidates(SharedPreferences p, String gameName) {
        String dir = p.getString(KEY_DIR, DEFAULT_DIR);
        String name = cloudNameFor(gameName);
        java.util.List<String> out = new java.util.ArrayList<>();
        boolean custom = !dir.equals(DEFAULT_DIR) && !dir.equals(LEGACY_DIR);
        String current = dir.endsWith("/") ? dir : dir + "/";
        if (!custom) {
            out.add(LEGACY_DIR + "/" + name + "/" + SAVE_FILE_NAME);
            out.add(LEGACY_DIR + "/" + name + ".zip");
        }
        out.add(current + name + ".zip");
        return out;
    }

    /**
     * 旧目录里应当被清掉的空文件夹（删除空 collection 成功、非空会被服务端拒绝，
     * 因此顺序放在文件删除之后是安全的）。
     */
    private static java.util.List<String> legacyEmptyDirs(SharedPreferences p) {
        String dir = p.getString(KEY_DIR, DEFAULT_DIR);
        boolean custom = !dir.equals(DEFAULT_DIR) && !dir.equals(LEGACY_DIR);
        java.util.List<String> out = new java.util.ArrayList<>();
        if (custom) return out;
        out.add(LEGACY_DIR);                       // TyranorNext/saves
        out.add(LEGACY_DIR.substring(0, LEGACY_DIR.indexOf('/'))); // TyranorNext
        return out;
    }

    /** DELETE 一个 collection（空目录才会成功；失败仅记日志）。 */
    private static void mkcolQuietDelete(SharedPreferences p, String path) {
        try {
            String[] target = webdavTarget(p);
            String base = target[0];
            if (base.isEmpty()) return;
            int code = rawRequest("DELETE",
                    remoteUrl(base, path),
                    target[1], target[2]);
            Log.i(TAG, "DELETE dir " + path + " -> " + code);
        } catch (Throwable t) {
            Log.w(TAG, "DELETE dir " + path + " failed", t);
        }
    }

    /**
     * 下载 zip 到 dest（后台线程执行）。云端不存在时 callback(false, "404")。
     * 新布局找不到时按 [legacyCandidates] 顺序回退（老用户上传的内容仍可下载）。
     */
    public static void download(Context context, String gameName, File dest, Callback callback) {
        run(context, gameName, callback, new Transfer() {
            @Override
            public void run(SharedPreferences p, String remotePath) throws Exception {
                Transfer get = new Transfer() {
                    @Override
                    public void run(SharedPreferences pp, String path) throws Exception {
                        if (MODE_GITHUB.equals(pp.getString(KEY_MODE, MODE_WEBDAV))) {
                            githubGet(pp, path, dest);
                        } else {
                            webdavRequest(pp, "GET", path, dest);
                        }
                    }
                };
                try {
                    get.run(p, remotePath);
                } catch (Throwable first) {
                    Throwable last = first;
                    for (String legacy : legacyCandidates(p, gameName)) {
                        try {
                            Log.w(TAG, "download " + remotePath + " failed, try " + legacy, last);
                            get.run(p, legacy);
                            return;
                        } catch (Throwable t) {
                            last = t;
                        }
                    }
                    throw last instanceof Exception ? (Exception) last : new IllegalStateException(last);
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
                String remote = remotePath(p, gameName);
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
        HttpURLConnection conn = open(remoteUrl(base, remotePath),
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

    /**
     * 把**相对路径**按段做百分号编码（保留分隔符）。
     *
     * 必需：[cloudNameFor] 会保留空格/中文等合法文件名字符（例如 "The Shell Part I 手机"），
     * 直接拼进 URL 会让 `URI.create` 抛 URISyntaxException（空格非法）→ MKCOL 建目录失败 →
     * PUT 报 409 AncestorsNotFound。实测 ESG-13556（纯 ASCII）能传、带空格/中文的传不上去。
     */
    private static String encodePath(String path) {
        if (path == null || path.isEmpty()) return "";
        boolean trailingSlash = path.endsWith("/");
        StringBuilder sb = new StringBuilder();
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            if (sb.length() > 0) sb.append('/');
            try {
                sb.append(java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20"));
            } catch (Throwable t) {
                sb.append(seg);
            }
        }
        if (trailingSlash && sb.length() > 0) sb.append('/');
        return sb.toString();
    }

    /** 拼远端绝对 URL：base + 编码后的相对路径。 */
    private static String remoteUrl(String base, String relativePath) {
        return (base.endsWith("/") ? base : base + "/") + encodePath(relativePath);
    }

    /** 逐级确保远端目录存在（已存在时 MKCOL 报 405/409，忽略）。含游戏专属子文件夹。 */
    private static void webdavEnsureDir(SharedPreferences p, String gameName) {
        try {
            String[] target = webdavTarget(p);
            String base = target[0];
            if (base.isEmpty()) throw new IllegalStateException("server not configured");
            String dir = p.getString(KEY_DIR, DEFAULT_DIR);
            StringBuilder path = new StringBuilder();
            String[] parts = dir.split("/");
            for (String part : parts) {
                if (part.isEmpty()) continue;
                path.append(part).append('/');
                mkcolQuiet(p, base, target, path.toString());
            }
            // 每个游戏一个专属子文件夹：dir/<标题>/
            path.append(cloudNameFor(gameName)).append('/');
            mkcolQuiet(p, base, target, path.toString());
        } catch (Throwable t) {
            Log.w(TAG, "webdav mkcol skipped", t);
        }
    }

    /** 发一次 MKCOL（已存在/无权限都不抛；仅记日志）。 */
    private static void mkcolQuiet(SharedPreferences p, String base, String[] target, String path) {
        try {
            // 必须用裸 socket：Android 的 HttpURLConnection 方法白名单不含 MKCOL，
            // setRequestMethod("MKCOL") 抛 ProtocolException（原来被 catch 吞掉 →
            // 目录永远建不出来 → upload 的 PUT 报 409 AncestorsNotFound）。
            int code = rawRequest("MKCOL",
                    remoteUrl(base, path.toString()),
                    target[1], target[2]);
            Log.i(TAG, "MKCOL " + path + " -> " + code);
        } catch (Throwable t) {
            Log.w(TAG, "MKCOL " + path + " failed", t);
        }
    }

    /**
     * 裸 socket 发一个 HTTP 请求（只取状态码）。
     * Android 自带 HttpURLConnection 只允许 OPTIONS/GET/HEAD/POST/PUT/DELETE/TRACE/PATCH，
     * WebDAV 的 MKCOL/PROPFIND 无法用它发送，也无法用 X-HTTP-Method-Override 可靠替代。
     *
     * @return HTTP 状态码；异常时抛出
     */
    private static int rawRequest(String method, String url,
                                  String user, String token) throws Exception {
        java.net.URI uri = java.net.URI.create(url);
        String host = uri.getHost();
        if (host == null) throw new IllegalStateException("bad url: " + url);
        boolean secure = !"http".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort() > 0 ? uri.getPort() : (secure ? 443 : 80);
        String reqPath = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        java.net.Socket socket = secure
                ? javax.net.ssl.SSLSocketFactory.getDefault().createSocket(host, port)
                : new java.net.Socket(host, port);
        socket.setSoTimeout(20000);
        try {
            StringBuilder head = new StringBuilder()
                    .append(method).append(' ').append(reqPath).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(host).append("\r\n")
                    .append("Connection: close\r\n")
                    .append("Content-Length: 0\r\n");
            if ((user != null && !user.isEmpty()) || (token != null && !token.isEmpty())) {
                String cred = (user == null ? "" : user) + ":" + (token == null ? "" : token);
                head.append("Authorization: Basic ")
                        .append(android.util.Base64.encodeToString(
                                cred.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                android.util.Base64.NO_WRAP))
                        .append("\r\n");
            }
            head.append("\r\n");
            java.io.OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(socket.getInputStream(),
                            java.nio.charset.StandardCharsets.ISO_8859_1));
            String statusLine = reader.readLine();
            if (statusLine == null) throw new IllegalStateException("empty response");
            String[] bits = statusLine.split(" ");
            if (bits.length < 2) throw new IllegalStateException("bad status line: " + statusLine);
            return Integer.parseInt(bits[1]);
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
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
