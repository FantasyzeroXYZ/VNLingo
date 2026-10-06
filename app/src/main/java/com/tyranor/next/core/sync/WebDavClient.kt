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

    /** 测试连接：写入探针文件再删除，失败抛出具体原因。 */
    @Throws(IOException::class)
    fun testConnectionOrThrow() {
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
                val err = readErrorBody(conn)
                throw IOException("HTTP $code" + if (err.isEmpty()) "" else ": $err")
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
                val err = readErrorBody(conn)
                throw IOException("PUT $path failed: HTTP $code" + if (err.isEmpty()) "" else ": $err")
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

    /** 列出目录内容（PROPFIND Depth:1）。 */
    @Throws(IOException::class)
    fun listFiles(path: String): List<WebDavItem> {
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<D:propfind xmlns:D=\"DAV:\">\n" +
            "  <D:allprop/>\n" +
            "</D:propfind>"
        val body = xml.toByteArray(StandardCharsets.UTF_8)
        withConnection(resolveUrl(path)) { conn ->
            conn.requestMethod = "PROPFIND"
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
            conn.setRequestProperty("Content-Type", "application/xml")
            conn.setRequestProperty("Depth", "1")
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code >= 400) {
                val err = readErrorBody(conn)
                throw IOException("HTTP $code: $err")
            }
            val text = readBodyLimited(conn.inputStream, 1024 * 1024L, "WebDAV 目录列表")
                .toString(StandardCharsets.UTF_8)
            return parsePropfindResponse(text)
        }
    }

    // ---- 内部辅助 ----

    private fun mkcol(path: String): Boolean {
        withConnection(resolveUrl(path)) { conn ->
            conn.requestMethod = "MKCOL"
            val code = conn.responseCode
            return code in 200..299 || code == 405 || code == 409
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

    /** 解析 PROPFIND 响应（多命名空间容错）。 */
    private fun parsePropfindResponse(xml: String): List<WebDavItem> {
        val items = ArrayList<WebDavItem>()
        val responses = xml.split(Regex("<[Dd]:response>"))
        for (i in 1 until responses.size) {
            val resp = responses[i]
            val href = extractTag(resp, "D:href", "d:href") ?: continue
            val isDir = resp.contains("<D:collection/>") || resp.contains("<d:collection/>") ||
                resp.contains("<D:collection /") || resp.contains("<d:collection /")
            var lastModified = 0L
            extractTag(resp, "D:getlastmodified", "d:getlastmodified")?.let {
                lastModified = parseHttpDate(it)
            }
            var name = href
            if (name.endsWith("/")) name = name.substring(0, name.length - 1)
            val lastSlash = name.lastIndexOf('/')
            if (lastSlash >= 0) name = name.substring(lastSlash + 1)
            if (name.isEmpty() || name == ".") continue
            items.add(WebDavItem(name, href, isDir, lastModified))
        }
        return items
    }

    private fun extractTag(xml: String, vararg tags: String): String? {
        for (tag in tags) {
            val start = xml.indexOf("<$tag>")
            if (start >= 0) {
                val contentStart = start + tag.length + 2
                val end = xml.indexOf("</$tag>", contentStart)
                if (end > contentStart) return xml.substring(contentStart, end).trim()
            }
        }
        return null
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

    data class WebDavItem(
        val name: String,
        val href: String,
        val isDirectory: Boolean,
        val lastModified: Long,
    )

    companion object {
        private const val TAG = "WebDavClient"
        const val TEST_PROBE_PATH = "VNLingo/VNLingo_connection_test.txt"

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
