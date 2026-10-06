package com.core.anki

import android.content.Context

/**
 * 制卡配置（全局）：牌组 / 模型 / 字段映射（逻辑槽位 → Anki 模型字段名）/
 * 截图与语音源策略。参考 web game text 扩展的 ankiFieldMap 方案：
 * 用户在 AnkiDroid 里可以自建任意字段组合的模型，这里按「槽位 → 字段名」
 * 声明每个槽位的内容填到哪个字段；映射里没有的字段留空。
 *
 * 槽位常量即 fieldMap JSON 的 key；模型不存在时按默认模型四字段兜底创建。
 */
object AnkiCardConfig {

    // ---- 逻辑槽位（fieldMap 的 key）----
    const val SLOT_WORD = "word"                 // 查词词（释义命中的词）
    const val SLOT_READING = "reading"           // 读音
    const val SLOT_MEANING = "meaning"           // 释义（HTML）
    const val SLOT_SENTENCE = "sentence"         // 当前句
    const val SLOT_PAGE = "page"                 // 整页文本
    const val SLOT_TRANSLATION = "translation"   // 译文（有则填）
    const val SLOT_SCREENSHOT = "screenshot"     // 游戏截图 <img>
    const val SLOT_SENTENCE_AUDIO = "sentence_audio" // 例句语音 [sound:]（游戏配对优先，回退 TTS）
    const val SLOT_WORD_AUDIO = "word_audio"     // 单词语音 [sound:]（TTS）

    // ---- 语音源策略 ----
    const val VOICE_AUTO = "auto"   // 游戏配对语音优先，无则 TTS
    const val VOICE_GAME = "game"   // 仅游戏配对语音
    const val VOICE_TTS = "tts"     // 仅 TTS 生成
    const val VOICE_OFF = "off"     // 不要语音

    internal const val PREFS = "anki_card_config"
    private const val KEY_DECK = "deck"
    private const val KEY_MODEL = "model"
    private const val KEY_FIELD_MAP = "field_map"
    private const val KEY_CAPTURE = "capture_on_card"
    private const val KEY_VOICE_SOURCE = "voice_source"

    const val DEFAULT_DECK = "TyranorNext"
    const val DEFAULT_MODEL = "TyranorNext Word"

    /** 默认映射：与内置默认模型 TyranorNext Word（Word/Reading/Meaning/Sentence）对应。 */
    val DEFAULT_FIELD_MAP: Map<String, String> = mapOf(
        SLOT_WORD to "Word",
        SLOT_READING to "Reading",
        SLOT_MEANING to "Meaning",
        SLOT_SENTENCE to "Sentence",
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @JvmStatic
    fun deck(context: Context): String =
        prefs(context).getString(KEY_DECK, DEFAULT_DECK)?.ifEmpty { DEFAULT_DECK } ?: DEFAULT_DECK

    @JvmStatic
    fun setDeck(context: Context, deck: String) {
        prefs(context).edit().putString(KEY_DECK, deck.trim()).apply()
    }

    @JvmStatic
    fun model(context: Context): String =
        prefs(context).getString(KEY_MODEL, DEFAULT_MODEL)?.ifEmpty { DEFAULT_MODEL } ?: DEFAULT_MODEL

    @JvmStatic
    fun setModel(context: Context, model: String) {
        prefs(context).edit().putString(KEY_MODEL, model.trim()).apply()
    }

    /** 槽位 → Anki 模型字段名；未配置时返回默认映射。 */
    @JvmStatic
    fun fieldMap(context: Context): Map<String, String> {
        val raw = prefs(context).getString(KEY_FIELD_MAP, null) ?: return DEFAULT_FIELD_MAP
        return runCatching {
            val out = mutableMapOf<String, String>()
            val obj = org.json.JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optString(k, "")
                if (v.isNotEmpty()) out[k] = v
            }
            if (out.isEmpty()) DEFAULT_FIELD_MAP else out
        }.getOrDefault(DEFAULT_FIELD_MAP)
    }

    @JvmStatic
    fun setFieldMap(context: Context, map: Map<String, String>) {
        val obj = org.json.JSONObject()
        for ((k, v) in map) if (v.isNotEmpty()) obj.put(k, v)
        prefs(context).edit().putString(KEY_FIELD_MAP, obj.toString()).apply()
    }

    /** 制卡时是否自动截取当前游戏画面（关 = 使用最近一次手动截图，无则跳过图片）。 */
    @JvmStatic
    fun captureOnCard(context: Context): Boolean = prefs(context).getBoolean(KEY_CAPTURE, true)

    @JvmStatic
    fun setCaptureOnCard(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_CAPTURE, value).apply()
    }

    @JvmStatic
    fun voiceSource(context: Context): String =
        prefs(context).getString(KEY_VOICE_SOURCE, VOICE_AUTO) ?: VOICE_AUTO

    @JvmStatic
    fun setVoiceSource(context: Context, value: String) {
        prefs(context).edit().putString(KEY_VOICE_SOURCE, value).apply()
    }
}
