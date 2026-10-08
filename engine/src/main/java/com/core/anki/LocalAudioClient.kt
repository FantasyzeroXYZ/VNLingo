package com.core.anki

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 本地音频库客户端（单词语音源 = 本地音频库时使用）。
 *
 * 对接 **AnkiConnect for Android**（`AnkiconnectAndroid`）的 localaudio 端点——这就是
 * 「Anki 支持的 local audio 插件」在 Android 上的形态（jpod / nhk16 / forvo 等本地音频
 * DB 由插件托管，桌面版 AnkiConnect 没有这两个端点）：
 *
 * - 查词：`GET /localaudio/get/?sources=<源列表>&term=<词>&reading=<读音>` →
 *   `{type:'audioSourceList', audioSources:[{source, file, …}]}`（也兼容裸数组返回）；
 * - 取音频：`GET /localaudio/<source>/<file>` → 音频字节。
 *
 * 调用方负责放到后台线程；失败一律返回 null/空表（不抛异常），并在 logcat 留痕。
 */
object LocalAudioClient {

    private const val TAG = "LocalAudioClient"
    private const val CONNECT_TIMEOUT_MS = 1500
    private const val READ_TIMEOUT_MS = 4000

    /** 一条可用音频：源名 + 文件名（file 用于拼取音频 URL）。 */
    data class Entry(val source: String, val file: String)

    /** 查词：按源顺序返回可用音频（空表 = 本地库没有这个词）。 */
    @JvmStatic
    fun lookup(
        host: String,
        port: Int,
        term: String,
        reading: String?,
        sources: String,
    ): List<Entry> {
        if (term.isBlank()) return emptyList()
        val h = host.ifBlank { "127.0.0.1" }
        val p = if (port > 0) port else AnkiCardConfig.DEFAULT_LOCAL_AUDIO_PORT
        val query = StringBuilder("term=").append(URLEncoder.encode(term, "UTF-8"))
        if (!reading.isNullOrBlank()) {
            query.append("&reading=").append(URLEncoder.encode(reading, "UTF-8"))
        }
        if (sources.isNotBlank()) {
            query.append("&sources=").append(URLEncoder.encode(sources, "UTF-8"))
        }
        val body = httpGet("http://$h:$p/localaudio/get/?$query") ?: return emptyList()
        return parseEntries(body)
    }

    /** 解析 /localaudio/get/ 响应：兼容 `{audioSources:[…]}` 与裸数组两种形态。 */
    internal fun parseEntries(body: String): List<Entry> {
        val arr: JSONArray = try {
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) JSONArray(trimmed)
            else JSONObject(trimmed).optJSONArray("audioSources") ?: JSONArray()
        } catch (t: Throwable) {
            Log.w(TAG, "localaudio 响应解析失败: ${body.take(160)}", t)
            return emptyList()
        }
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val src = o.optString("source")
            val file = o.optString("file")
            if (src.isNotBlank() && file.isNotBlank()) out.add(Entry(src, file))
        }
        return out
    }

    /** 取音频字节（失败返回 null）。 */
    @JvmStatic
    fun fetchBytes(host: String, port: Int, entry: Entry): ByteArray? {
        val h = host.ifBlank { "127.0.0.1" }
        val p = if (port > 0) port else AnkiCardConfig.DEFAULT_LOCAL_AUDIO_PORT
        val url = "http://$h:$p/localaudio/${entry.source}/${URLEncoder.encode(entry.file, "UTF-8")}"
        return httpGetBytes(url)
    }

    /**
     * 一步取「词 → 本地音频」：查到第一条可下载的音频写到 [outFile]（带源扩展名），
     * 返回是否成功。制卡时直接调用。
     */
    @JvmStatic
    fun fetchWordAudioToFile(
        host: String,
        port: Int,
        term: String,
        reading: String?,
        sources: String,
        outFile: File,
    ): Boolean {
        val entries = lookup(host, port, term, reading, sources)
        if (entries.isEmpty()) {
            Log.i(TAG, "本地音频库无此词: $term")
            return false
        }
        for (e in entries) {
            val bytes = fetchBytes(host, port, e) ?: continue
            if (bytes.isEmpty()) continue
            return try {
                FileOutputStream(outFile).use { it.write(bytes) }
                Log.i(TAG, "本地音频命中 ${e.source}/${e.file} → ${outFile.name} (${bytes.size}B)")
                true
            } catch (t: Throwable) {
                Log.w(TAG, "本地音频写盘失败: ${e.source}/${e.file}", t)
                false
            }
        }
        return false
    }

    /** 音频文件扩展名（按源文件名推断；未知按 mp3）。 */
    @JvmStatic
    fun extensionOf(file: String): String {
        val lower = file.lowercase()
        return when {
            lower.endsWith(".ogg") || lower.endsWith(".oga") -> "ogg"
            lower.endsWith(".wav") -> "wav"
            lower.endsWith(".m4a") -> "m4a"
            lower.endsWith(".opus") -> "opus"
            else -> "mp3"
        }
    }

    private fun httpGet(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
        }
        try {
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "localaudio HTTP ${conn.responseCode}: $url")
                null
            } else {
                conn.inputStream.bufferedReader().use { it.readText() }
            }
        } finally {
            conn.disconnect()
        }
    } catch (t: Throwable) {
        Log.w(TAG, "localaudio 请求失败: $url", t)
        null
    }

    private fun httpGetBytes(url: String): ByteArray? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
        }
        try {
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "localaudio 音频 HTTP ${conn.responseCode}: $url")
                null
            } else {
                BufferedInputStream(conn.inputStream).use { it.readBytes() }
            }
        } finally {
            conn.disconnect()
        }
    } catch (t: Throwable) {
        Log.w(TAG, "localaudio 音频请求失败: $url", t)
        null
    }
}
