package com.tyranor.next.core.sync

import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * WebDAV 客户端（参考 RinneMobile WebDavClient 移植；OkHttp → HttpURLConnection，
 * 保持零新增依赖）。支持坚果云、NextCloud 等任意 WebDAV 服务器。
 *
 * 安全约束：
 * - 拒绝远程明文 HTTP（回环主机 localhost/127.0.0.1 除外）
 * - 所有读取带声明长度 + 流式累计双重上限，防止异常服务端耗尽内存
 */
class WebDavClient(serverUrl: String, private val username: String, private val password: String) {

    private val serverUrl: String = normalizeServerUrl(serverUrl)

    init {
        if (isInsecureHttp(this.serverUrl)) {
            throw IllegalArgumentException(
                "WebDAV 不支持远程明文 HTTP 连接。" +
                    "请使用具有有效证书的 HTTPS 地址或反向代理。"
            )
        }
    }

    private inline fun <T> withConnection(url: String, block: (HttpURLConnection) -> T): T {
        val conn = open(url)
        try {
            return block(conn)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 测试连接：确保目录存在后写入探针文件再删除，失败抛出具体原因。
     *
     * 注意：**必须先建目录**。多数服务端（坚果云实测）对父目录不存在的 PUT 返回
     * 409 `AncestorsNotFound`，此前直接 PUT 探针导致"测试连接永远失败"。
     */
    @Throws(IOException::class)
    fun testConnectionOrThrow() {
        mkdirs(TEST_PROBE_DIR)
        writeText(TEST_PROBE_PATH, "ok")
        delete(TEST_PROBE_PATH)
    }

    fun testConnection(): Boolean = try {
        testConnectionOrThrow()
        true
    } catch (e: Exception) {
        Log.w(TAG, "connection test failed", e)
        false
    }

    /** 逐级确保远程目录存在（已存在时 MKCOL 报 405，忽略）。 */
    fun mkdirs(path: String): Boolean = try {
        val parts = path.split("/").filter { it.isNotEmpty() }
        var current = ""
        for (part in parts) {
            current += "$part/"
            if (!exists(current)) mkcol(current)
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "mkdirs failed: $path", e)
        false
    }

    /** 检查文件/目录是否存在（HEAD）。 */
    fun exists(path: String): Boolean = try {
        withConnection(resolveUrl(path)) { conn ->
            conn.requestMethod = "HEAD"
            conn.responseCode in 200..299
        }
    } catch (e: Exception) {
        false
    }

    /**
     * 读取文件内容，限制最大字节数。调用同步/导入路径时必须传入上限，
     * 防止异常 WebDAV 服务端用未知长度的响应耗尽应用内存。
     */
    @Throws(IOException::class)
    fun readFileLimited(path: String, maxBytes: Long): ByteArray {
        withConnection(resolveUrl(path)) { conn ->
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code >= 400) {
                throw IOException(friendlyHttpError(conn, code))
            }
            val input = conn.inputStream ?: throw IOException("Empty response body")
            return readBodyLimited(input, maxBytes, "远程同步文件")
        }
    }

    /** 写入文件（二进制，用于 gzip 压缩数据）。 */
    @Throws(IOException::class)
    fun writeFile(path: String, data: ByteArray) {
        withConnection(resolveUrl(path)) { conn ->
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(data.size)
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.outputStream.use { it.write(data) }
            val code = conn.responseCode
            if (code >= 400) {
                throw IOException("PUT $path 失败: " + friendlyHttpError(conn, code))
            }
        }
    }

    fun writeText(path: String, text: String) {
        writeFile(path, text.toByteArray(StandardCharsets.UTF_8))
    }

    /** 删除文件。 */
    fun delete(path: String): Boolean = try {
        withConnection(resolveUrl(path)) { conn ->
            conn.requestMethod = "DELETE"
            conn.responseCode in 200..299
        }
    } catch (e: Exception) {
        Log.w(TAG, "delete failed: $path", e)
        false
    }

    /** 获取文件最后修改时间（HEAD Last-Modified；失败返回 0）。 */
    fun getLastModified(path: String): Long = try {
        withConnection(resolveUrl(path)) { conn ->
            conn.requestMethod = "HEAD"
            parseHttpDate(conn.getHeaderField("Last-Modified"))
        }
    } catch (e: Exception) {
        0
    }

    // ---- 内部辅助 ----

    private fun mkcol(path: String): Boolean {
        // 不能用 HttpURLConnection：Android 的实现只允许
        // OPTIONS/GET/HEAD/POST/PUT/DELETE/TRACE/PATCH，setRequestMethod("MKCOL")
        // 直接抛 ProtocolException（异常被吞 → 目录永远建不出来 → 后续 PUT 报 409）。
        return try {
            val code = rawRequest("MKCOL", resolveUrl(path))
            code in 200..299 || code == 405 || code == 409
        } catch (t: Throwable) {
            Log.w(TAG, "MKCOL $path failed", t)
            false
        }
    }

    /**
     * 裸 socket 发送一个 HTTP 请求，只取状态码。
     *
     * 用途：Android 自带 HttpURLConnection 的方法白名单不含 WebDAV 的 MKCOL/PROPFIND，
     * 而这些方法无法用 `X-HTTP-Method-Override` 可靠替代（服务端普遍不支持）。
     * 这里实现所需的最小集合：HTTP/1.1 + Basic 认证 + `Content-Length: 0` +
     * `Connection: close`，读完状态行即返回（不解析响应体）。
     */
    private fun rawRequest(method: String, url: String): Int {
        val uri = URI.create(url)
        val host = uri.host ?: throw IOException("bad url: $url")
        val secure = !"http".equals(uri.scheme, ignoreCase = true)
        val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
        val path = buildString {
            append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }
        val socket: java.net.Socket = if (secure) {
            javax.net.ssl.SSLSocketFactory.getDefault().createSocket(host, port)
        } else {
            java.net.Socket(host, port)
        }
        socket.soTimeout = 20000
        socket.use { s ->
            val head = StringBuilder()
                .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append("\r\n")
                .append("Connection: close\r\n")
                .append("Content-Length: 0\r\n")
            if (username.isNotEmpty() || password.isNotEmpty()) {
                val cred = "$username:$password"
                head.append("Authorization: Basic ")
                    .append(Base64.encodeToString(
                        cred.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP))
                    .append("\r\n")
            }
            head.append("\r\n")
            val out = s.getOutputStream()
            out.write(head.toString().toByteArray(StandardCharsets.UTF_8))
            out.flush()
            val statusLine = s.getInputStream()
                .bufferedReader(StandardCharsets.ISO_8859_1)
                .readLine() ?: throw IOException("empty response")
            val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                ?: throw IOException("bad status line: $statusLine")
            Log.i(TAG, "$method $path -> $code")
            return code
        }
    }

    /** 规范化 WebDAV 地址：坚果云漏填 /dav/ 自动补上；保证末尾带 /。 */
    private fun normalizeServerUrl(raw: String?): String {
        var s = raw?.trim() ?: ""
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        val lower = s.lowercase()
        if (lower.contains("dav.jianguoyun.com") && !lower.contains("/dav")) {
            if (!s.endsWith("/")) s += "/"
            s += "dav/"
        }
        return if (s.endsWith("/")) s else "$s/"
    }

    /** 逐段 URL 编码拼 URL（空格/中文路径安全；保留路径分隔符）。 */
    private fun resolveUrl(path: String): String {
        val clean = path.removePrefix("/")
        val encoded = clean.split("/").filter { it.isNotEmpty() }.joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return serverUrl + encoded
    }

    private fun open(url: String): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        if (username.isNotEmpty() || password.isNotEmpty()) {
            val cred = "$username:$password"
            conn.setRequestProperty(
                "Authorization",
                "Basic " + Base64.encodeToString(cred.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP),
            )
        }
        return conn
    }

    private fun readErrorBody(conn: HttpURLConnection): String = try {
        val stream = conn.errorStream ?: return ""
        String(readBodyLimited(stream, 64 * 1024L, "WebDAV 错误响应"), StandardCharsets.UTF_8)
    } catch (ignored: Exception) {
        ""
    }

    /**
     * 把服务端错误转成对人友好的说明：
     * WebDAV 服务端普遍返回 XML（如坚果云 `<s:exception>AncestorsNotFound</s:exception>
     * <s:message>The ancestors of this location does not found</s:message>`），
     * 直接抛原始 XML 用户看不懂。这里抽出 message/exception，并补一句常见成因提示。
     */
    private fun friendlyHttpError(conn: HttpURLConnection, code: Int): String {
        val raw = readErrorBody(conn).trim()
        val message = extractXmlText(raw, "s:message")
            ?: extractXmlText(raw, "d:message")
            ?: extractXmlText(raw, "message")
        val exception = extractXmlText(raw, "s:exception")
            ?: extractXmlText(raw, "d:exception")
            ?: extractXmlText(raw, "exception")
        val detail = when {
            message != null && exception != null -> "$message ($exception)"
            message != null -> message
            exception != null -> exception
            raw.isEmpty() -> ""
            else -> raw.take(200)
        }
        val hint = when {
            code == 401 -> "；账号或应用密码不正确（坚果云需使用「应用密码」，不是登录密码）"
            code == 403 -> "；无权限访问该路径，请检查目录是否对该账号可见"
            code == 404 -> "；路径不存在，请检查服务器地址是否包含 WebDAV 目录（如 /dav/）"
            code == 405 -> "；服务端不允许该操作"
            code == 409 -> "；父目录不存在，请检查远端目录结构"
            code == 507 -> "；云端空间不足"
            code in 500..599 -> "；服务端错误，请稍后重试"
            else -> ""
        }
        return "HTTP $code" + (if (detail.isEmpty()) "" else ": $detail") + hint
    }

    /** 从 XML 文本里取某个标签的内容（大小写与命名空间容错）。 */
    private fun extractXmlText(xml: String, tag: String): String? {
        val lower = xml.lowercase()
        val open = "<${tag.lowercase()}"
        var idx = lower.indexOf(open)
        if (idx < 0) return null
        idx = lower.indexOf('>', idx)
        if (idx < 0) return null
        val end = lower.indexOf("</${tag.lowercase()}>", idx)
        if (end <= idx) return null
        return xml.substring(idx + 1, end).trim().takeIf { it.isNotEmpty() }
    }

    @Throws(IOException::class)
    private fun readBodyLimited(input: InputStream, maxBytes: Long, label: String): ByteArray {
        val bos = ByteArrayOutputStream(8192)
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (maxBytes >= 0 && total > maxBytes) {
                throw IOException("$label 过大（读取超过最大允许 $maxBytes 字节）")
            }
            bos.write(buffer, 0, read)
        }
        return bos.toByteArray()
    }


    companion object {
        private const val TAG = "WebDavClient"
        const val TEST_PROBE_DIR = "VNLingo"
        const val TEST_PROBE_PATH = "$TEST_PROBE_DIR/VNLingo_connection_test.txt"


        /**
         * 判断 URL 是否为不安全的明文 HTTP（非 localhost/127.0.0.1）。
         * 回环主机明文用于本地调试，精确放行。
         */
        @JvmStatic
        fun isInsecureHttp(url: String?): Boolean {
            if (url == null) return false
            return try {
                val parsed = URI.create(url)
                if (!"http".equals(parsed.scheme, ignoreCase = true)) return false
                val host = parsed.host
                !("localhost".equals(host, ignoreCase = true) || "127.0.0.1" == host)
            } catch (ignored: IllegalArgumentException) {
                url.regionMatches(0, "http://", 0, "http://".length, ignoreCase = true)
            }
        }

        /** 解析 HTTP 日期（RFC 1123）；解析失败返回 0。 */
        @JvmStatic
        fun parseHttpDate(value: String?): Long {
            if (value.isNullOrBlank()) return 0
            val formats = arrayOf(
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "EEEE, dd-MMM-yy HH:mm:ss zzz",
                "EEE MMM d HH:mm:ss yyyy",
            )
            for (format in formats) {
                try {
                    val parser = SimpleDateFormat(format, Locale.US)
                    parser.timeZone = TimeZone.getTimeZone("GMT")
                    val date: Date? = parser.parse(value.trim())
                    if (date != null) return date.time
                } catch (ignored: ParseException) {
                }
            }
            return 0
        }
    }
}
