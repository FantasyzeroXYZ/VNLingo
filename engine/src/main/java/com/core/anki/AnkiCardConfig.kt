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
    // **必须与参考 web game text / TrackReader 的要素表一致**
    // （packages/domain/src/anki/field-mapper.ts 的 WORD_FIELDS：word / reading /
    // definition / audio / sentence / translation / sentenceAudio / source /
    // wordTags / deinflection / image …）。不自造 key：新增要素要先在参考侧存在。
    const val SLOT_WORD = "word"                     // 单词
    const val SLOT_READING = "reading"               // 读音
    const val SLOT_DEFINITION = "definition"         // 释义（HTML）
    const val SLOT_AUDIO = "audio"                   // 单词音频 [sound:]（TTS）
    const val SLOT_SENTENCE = "sentence"             // 句子
    const val SLOT_TRANSLATION = "translation"       // 句子翻译
    const val SLOT_SENTENCE_AUDIO = "sentenceAudio"  // 句子音频 [sound:]（游戏配对优先，回退 TTS）
    const val SLOT_SOURCE = "source"                 // 来源
    const val SLOT_WORD_TAGS = "wordTags"            // 单词标签（查词词典名）
    const val SLOT_DEINFLECTION = "deinflection"     // 词形还原（还原路径文本）
    const val SLOT_IMAGE = "image"                   // 图片（游戏截图 <img>）

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
    private const val KEY_LAST_MODEL_FIELDS = "last_model_fields"

    // 不预设任何牌组/模板名：必须由用户从 AnkiDroid 现有内容里选择。
    // 未选择时返回空串，制卡入口据此提示「先选择」，绝不自动创建同名牌组/模板。

    /**
     * 默认映射：与参考 web game text 一致（`{ word: 'Front', reading: '', definition:
     * 'Back', sentence: '' }`，见 reference `domain/settings` 的 ankiFieldMap 初值）。
     * 用户没保存过映射时按它填；模板字段不同时在制卡设置里改。
     */
    val DEFAULT_FIELD_MAP: Map<String, String> = mapOf(
        SLOT_WORD to "Front",
        SLOT_DEFINITION to "Back",
    )

    /** 旧版本自造 key → 参考要素 key（读取时迁移，避免老配置失效）。 */
    private val LEGACY_SLOT_KEYS: Map<String, String> = mapOf(
        "meaning" to SLOT_DEFINITION,
        "screenshot" to SLOT_IMAGE,
        "sentence_audio" to SLOT_SENTENCE_AUDIO,
        "word_audio" to SLOT_AUDIO,
        "word_tags" to SLOT_WORD_TAGS,
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @JvmStatic
    fun deck(context: Context): String =
        prefs(context).getString(KEY_DECK, "")?.trim().orEmpty()

    @JvmStatic
    fun setDeck(context: Context, deck: String) {
        prefs(context).edit().putString(KEY_DECK, deck.trim()).apply()
    }

    @JvmStatic
    fun model(context: Context): String =
        prefs(context).getString(KEY_MODEL, "")?.trim().orEmpty()

    @JvmStatic
    fun setModel(context: Context, model: String) {
        prefs(context).edit().putString(KEY_MODEL, model.trim()).apply()
    }

    /** 槽位 → Anki 模型字段名；未配置时返回默认映射。旧 key 读取时自动迁移。 */
    @JvmStatic
    fun fieldMap(context: Context): Map<String, String> {
        val raw = prefs(context).getString(KEY_FIELD_MAP, null) ?: return DEFAULT_FIELD_MAP
        return runCatching {
            val out = mutableMapOf<String, String>()
            val obj = org.json.JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val rawKey = keys.next()
                val v = obj.optString(rawKey, "")
                if (v.isEmpty()) continue
                val k = LEGACY_SLOT_KEYS[rawKey] ?: rawKey
                // 已废弃的自造要素（page / match_type）不再迁移：它们不在参考要素表里
                if (rawKey == "page" || rawKey == "match_type") continue
                out[k] = v
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

    /**
     * 最近一次成功读取的模型字段列表（按模型名缓存）。
     *
     * 用途：AnkiDroid 未安装/未授权时，设置页仍能按上次的字段列表编辑字段映射
     * （对齐 `设置项补全与交互改造方案.md` §4.2「未连接时保留上次字段列表」）。
     */
    @JvmStatic
    fun lastModelFields(context: Context, modelName: String): List<String> {
        if (modelName.isBlank()) return emptyList()
        val raw = prefs(context).getString(KEY_LAST_MODEL_FIELDS, null) ?: return emptyList()
        return runCatching {
            val obj = org.json.JSONObject(raw)
            val arr = obj.optJSONArray(modelName) ?: return emptyList()
            (0 until arr.length()).mapNotNull { arr.optString(it, "").ifEmpty { null } }
        }.getOrDefault(emptyList())
    }

    @JvmStatic
    fun setLastModelFields(context: Context, modelName: String, fields: Array<String>?) {
        if (modelName.isBlank() || fields == null || fields.isEmpty()) return
        val raw = prefs(context).getString(KEY_LAST_MODEL_FIELDS, null)
        val obj = runCatching { org.json.JSONObject(raw ?: "{}") }.getOrDefault(org.json.JSONObject())
        val arr = org.json.JSONArray()
        for (f in fields) if (f.isNotEmpty()) arr.put(f)
        obj.put(modelName, arr)
        prefs(context).edit().putString(KEY_LAST_MODEL_FIELDS, obj.toString()).apply()
    }
}
