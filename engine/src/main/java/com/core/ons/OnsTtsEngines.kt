package com.core.ons

import android.content.Context
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * TTS 引擎路由（参考 TrackReader 共享库 src/domain/tts 的 Provider 方案）：
 *
 * - [ENGINE_SYSTEM] 安卓自带 TTS（android.speech.tts.TextToSpeech，本地直呼，
 *   无字节产物——仅实时朗读，不用于制卡音频）
 * - [ENGINE_MULTI]  MultiTTS（HTTP 合成，返回 WAV 字节；见 MultiTtsClient）
 * - [ENGINE_HTTP]   自定义 HTTP API（通用 GET 模板，返回音频字节；兼容
 *   TTS Server `/api/tts?text=`、LunaTranslator `/api/tts?text=` 等常见格式）
 *
 * 引擎选择与 HTTP 模板持久化于 tts 引擎 prefs（全局）。
 */
object OnsTtsEngines {

    const val ENGINE_SYSTEM = "system"
    const val ENGINE_MULTI = "multi"
    const val ENGINE_HTTP = "http"

    /** HTTP 单响应字节上限（保险丝，正常 TTS 音频 <10MB）。 */
    private const val MAX_HTTP_AUDIO_BYTES = 32L * 1024 * 1024

    private const val PREFS = "ons_tts_engine"
    private const val KEY_ENGINE = "engine"
    private const val KEY_HTTP_TEMPLATE = "http_template"

    /** 默认 HTTP 模板：TTS Server / LunaTranslator 兼容格式。 */
    const val DEFAULT_HTTP_TEMPLATE = "http://127.0.0.1:1221/api/tts?text={text}"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 当前引擎（全局）；未配置默认 MultiTTS（与面板历史默认行为一致）。 */
    @JvmStatic
    fun engine(context: Context): String =
        prefs(context).getString(KEY_ENGINE, ENGINE_MULTI)?.ifEmpty { ENGINE_MULTI } ?: ENGINE_MULTI

    @JvmStatic
    fun setEngine(context: Context, engine: String) {
        prefs(context).edit().putString(KEY_ENGINE, engine).apply()
    }

    /** HTTP URL 模板：必须包含 {text} 占位符（合成时替换为 URL 编码后的文本）。 */
    @JvmStatic
    fun httpTemplate(context: Context): String =
        prefs(context).getString(KEY_HTTP_TEMPLATE, DEFAULT_HTTP_TEMPLATE)
            ?.ifEmpty { DEFAULT_HTTP_TEMPLATE } ?: DEFAULT_HTTP_TEMPLATE

    @JvmStatic
    fun setHttpTemplate(context: Context, template: String) {
        prefs(context).edit().putString(KEY_HTTP_TEMPLATE, template.trim()).apply()
    }

    /** 模板是否合法（含 {text} 占位符且为 http/https）。 */
    @JvmStatic
    fun isHttpTemplateValid(template: String): Boolean =
        template.contains("{text}") && template.startsWith("http")

    /** HTTP 引擎合成：GET 模板（{text} → URL 编码文本）→ 音频字节；失败返回 null。 */
    @JvmStatic
    fun httpSynthesize(context: Context, text: String): ByteArray? {
        val template = httpTemplate(context)
        if (!isHttpTemplateValid(template) || text.isEmpty()) return null
        return try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = template.replace("{text}", encoded)
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 60000
                requestMethod = "GET"
            }
            try {
                if (conn.responseCode !in 200..299) return null
                val expected = conn.contentLengthLong
                val input = conn.inputStream
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16384)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_HTTP_AUDIO_BYTES) return null // 炸弹保险丝
                    out.write(buf, 0, n)
                }
                if (expected > 0 && total != expected) return null // 截断流
                out.toByteArray()
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            android.util.Log.w("OnsTtsEngines", "http synthesize failed", t)
            null
        }
    }
}
