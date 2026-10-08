package com.tyranor.next.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.core.engine.R as EngineR
import com.tyranor.next.R
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.glassShadow
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.BottomInsetSpacer
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 引擎侧设置页（翻译 / 制卡 / TTS）的 Compose 正文。
 *
 * **样式约定（见 MEMORY「二级设置页风格一致性」）**：所有二级设置页必须是
 * `AppScreenActivity` + `MiuixSettingsTheme` + `MiuixScaffold` + `AppTopBar` +
 * `MiuixCard(glassShadow().glassBorder(), AppComponentCornerRadius)` + Miuix preference
 * （OverlayDropdownPreference / SwitchPreference / ArrowPreference）的组合，
 * 行内不出现自绘 Android View，也不使用自定义顶栏 —— 与「应用设置 / 引擎管理 / 云同步」
 * 等页面完全一致。
 */
@Composable
internal fun EngineSettingsPage(
    titleRes: Int,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    MiuixSettingsTheme {
        MiuixScaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = ComposeColor.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                AppTopBar(
                    title = stringResource(titleRes),
                    background = ComposeColor.Transparent,
                    contentColor = MiuixTheme.colorScheme.onBackground,
                )
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize()
                    .padding(horizontal = 12.dp)
                    .padding(top = innerPadding.calculateTopPadding()),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
                item { BottomInsetSpacer() }
            }
        }
    }
}

/** 一张设置卡片（内容行集合）。 */
@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    MiuixCard(
        modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
        cornerRadius = AppComponentCornerRadius,
    ) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}

/** 说明文字块（卡片内灰色小字说明）。 */
@Composable
internal fun SettingsHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

// ---------------------------------------------------------------------------
// 翻译设置
// ---------------------------------------------------------------------------

@Composable
internal fun TranslateSettingsScreen() {
    val ctx = LocalContext.current
    var engine by remember { mutableStateOf(com.core.ons.OnsTranslateClient.getEngine(ctx)) }
    val engines = com.core.ons.OnsTranslateClient.ENGINES.toList()
    val labels = engines.map { translateEngineLabel(it) }
    val index = engines.indexOf(engine).coerceAtLeast(0)

    EngineSettingsPage(EngineR.string.engine_ons_settings_section_translate) {
        item {
            SettingsCard {
                OverlayDropdownPreference(
                    title = stringResource(EngineR.string.engine_ons_translate_engine),
                    items = labels,
                    selectedIndex = index,
                    onSelectedIndexChange = { i ->
                        engines.getOrNull(i)?.let { picked ->
                            engine = picked
                            com.core.ons.OnsTranslateClient.prefs(ctx).edit()
                                .putString(com.core.ons.OnsTranslateClient.KEY_ENGINE, picked)
                                .apply()
                        }
                    },
                )
                ArrowPreference(
                    title = stringResource(EngineR.string.engine_ons_translate_cfg),
                    onClick = {
                        AppLocaleController.findActivity(ctx)?.let { act ->
                            com.core.ons.OnsExtractSettingsDialogs.showEngineConfigDialog(act, engine)
                        }
                    },
                )
                ArrowPreference(
                    title = stringResource(EngineR.string.engine_ons_translate_test),
                    onClick = {
                        AppLocaleController.findActivity(ctx)?.let { act ->
                            com.core.ons.OnsExtractSettingsDialogs.showTranslateTestDialog(act)
                        }
                    },
                )
            }
        }
    }
}

/** 翻译引擎显示名（与 engine 侧一致）。 */
@Composable
private fun translateEngineLabel(code: String): String {
    val res = when (code) {
        com.core.ons.OnsTranslateClient.ENGINE_LOCAL -> EngineR.string.engine_ons_translate_engine_local
        com.core.ons.OnsTranslateClient.ENGINE_GOOGLE -> EngineR.string.engine_ons_translate_engine_google
        com.core.ons.OnsTranslateClient.ENGINE_BING -> EngineR.string.engine_ons_translate_engine_bing
        com.core.ons.OnsTranslateClient.ENGINE_DEEPL -> EngineR.string.engine_ons_translate_engine_deepl
        com.core.ons.OnsTranslateClient.ENGINE_BAIDU -> EngineR.string.engine_ons_translate_engine_baidu
        com.core.ons.OnsTranslateClient.ENGINE_GEMINI -> EngineR.string.engine_ons_translate_engine_gemini
        com.core.ons.OnsTranslateClient.ENGINE_ANTHROPIC -> EngineR.string.engine_ons_translate_engine_anthropic
        else -> EngineR.string.engine_ons_translate_engine_api
    }
    return stringResource(res)
}

// ---------------------------------------------------------------------------
// TTS 设置
// ---------------------------------------------------------------------------

@Composable
internal fun TtsSettingsScreen() {
    val ctx = LocalContext.current
    val ttsPrefs = remember {
        ctx.getSharedPreferences("ons_extract_tts", android.content.Context.MODE_PRIVATE)
    }
    var engine by remember { mutableStateOf(com.core.ons.OnsTtsEngines.engine(ctx)) }
    var rate by remember { mutableStateOf(com.core.ons.OnsTtsEngines.rate(ctx)) }
    var pitch by remember { mutableStateOf(com.core.ons.OnsTtsEngines.pitch(ctx)) }
    var volume by remember { mutableStateOf(com.core.ons.OnsTtsEngines.volume(ctx)) }
    var voice by remember { mutableStateOf(com.core.ons.OnsTtsEngines.voice(ctx)) }
    var host by remember { mutableStateOf(com.core.ons.OnsTtsEngines.multiHost(ctx)) }
    var httpTemplate by remember { mutableStateOf(com.core.ons.OnsTtsEngines.httpTemplate(ctx)) }
    val defaultTestText = stringResource(EngineR.string.engine_ons_tts_test_default)
    var testText by remember { mutableStateOf(defaultTestText) }
    var autoRead by remember { mutableStateOf(com.core.ons.OnsTtsEngines.autoRead(ctx)) }
    var judou by remember { mutableStateOf(ttsPrefs.getBoolean("tts_judou", false)) }
    var loop by remember { mutableStateOf(ttsPrefs.getInt("tts_loop", 0)) }
    var showHostDialog by remember { mutableStateOf(false) }

    val steps = listOf(0, 25, 50, 75, 100)
    val engineCodes = listOf(
        com.core.ons.OnsTtsEngines.ENGINE_MULTI,
        com.core.ons.OnsTtsEngines.ENGINE_HTTP,
        com.core.ons.OnsTtsEngines.ENGINE_SYSTEM,
    )
    val engineLabels = listOf(
        stringResource(EngineR.string.engine_ons_tts_engine_multi),
        stringResource(EngineR.string.engine_ons_tts_engine_http),
        stringResource(EngineR.string.engine_ons_tts_engine_system),
    )

    EngineSettingsPage(EngineR.string.engine_ons_tts_title) {
        item {
            SettingsCard {
                TtsStepRow(EngineR.string.engine_ons_tts_rate, steps, rate) {
                    rate = it
                    com.core.ons.OnsTtsEngines.setRate(ctx, it)
                }
                TtsStepRow(EngineR.string.engine_ons_tts_pitch, steps, pitch) {
                    pitch = it
                    com.core.ons.OnsTtsEngines.setPitch(ctx, it)
                }
                TtsStepRow(EngineR.string.engine_ons_tts_volume, steps, volume) {
                    volume = it
                    com.core.ons.OnsTtsEngines.setVolume(ctx, it)
                }
                ArrowPreference(
                    title = stringResource(EngineR.string.engine_ons_tts_voice),
                    summary = voice.ifEmpty { null },
                    onClick = {
                        AppLocaleController.findActivity(ctx)?.let { act ->
                            com.core.ons.OnsExtractSettingsDialogs.showVoicePickerDialog(act, engine) {
                                voice = com.core.ons.OnsTtsEngines.voice(ctx)
                            }
                        }
                    },
                )
                if (com.core.ons.OnsTtsEngines.ENGINE_MULTI == engine) {
                    ArrowPreference(
                        title = stringResource(EngineR.string.engine_ons_tts_multi_host),
                        summary = host,
                        onClick = { showHostDialog = true },
                    )
                }
            }
        }
        item {
            SettingsCard {
                OverlayDropdownPreference(
                    title = stringResource(EngineR.string.engine_ons_tts_engine),
                    items = engineLabels,
                    selectedIndex = engineCodes.indexOf(engine).coerceAtLeast(0),
                    onSelectedIndexChange = { i ->
                        engineCodes.getOrNull(i)?.let { picked ->
                            engine = picked
                            com.core.ons.OnsTtsEngines.setEngine(ctx, picked)
                            if (com.core.ons.OnsTtsEngines.ENGINE_MULTI == picked
                                || com.core.ons.OnsTtsEngines.ENGINE_HTTP == picked) {
                                voice = com.core.ons.OnsTtsEngines.voice(ctx)
                            }
                        }
                    },
                )
                SettingsHint(stringResource(EngineR.string.engine_ons_tts_system_hint))
                SettingsHint(stringResource(EngineR.string.engine_ons_tts_http_template))
                androidx.compose.material3.OutlinedTextField(
                    value = httpTemplate,
                    onValueChange = {
                        httpTemplate = it
                        if (it.isNotBlank()) com.core.ons.OnsTtsEngines.setHttpTemplate(ctx, it)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                SettingsHint(stringResource(EngineR.string.engine_ons_tts_test_text))
                androidx.compose.material3.OutlinedTextField(
                    value = testText,
                    onValueChange = { testText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
        }
        item {
            SettingsCard {
                SwitchPreference(
                    title = stringResource(EngineR.string.engine_ons_tts_auto_read),
                    checked = autoRead,
                    onCheckedChange = {
                        autoRead = it
                        com.core.ons.OnsTtsEngines.setAutoRead(ctx, it)
                    },
                )
                SwitchPreference(
                    title = stringResource(EngineR.string.engine_ons_tts_judou),
                    checked = judou,
                    onCheckedChange = {
                        judou = it
                        ttsPrefs.edit().putBoolean("tts_judou", it).apply()
                    },
                )
                OverlayDropdownPreference(
                    title = stringResource(EngineR.string.engine_ons_tts_loop),
                    items = listOf(
                        stringResource(EngineR.string.engine_ons_tts_loop_off),
                        stringResource(EngineR.string.engine_ons_tts_loop_n, 1),
                        stringResource(EngineR.string.engine_ons_tts_loop_n, 2),
                        stringResource(EngineR.string.engine_ons_tts_loop_n, 3),
                    ),
                    selectedIndex = loop.coerceIn(0, 3),
                    onSelectedIndexChange = {
                        loop = it
                        ttsPrefs.edit().putInt("tts_loop", it).apply()
                    },
                )
                ArrowPreference(
                    title = stringResource(EngineR.string.engine_ons_tts_test),
                    onClick = {
                        AppLocaleController.findActivity(ctx)?.let { act ->
                            com.core.ons.OnsExtractSettingsDialogs.runTtsTest(act, testText)
                        }
                    },
                )
            }
        }
    }

    if (showHostDialog) {
        HostPortDialog(
            initial = host,
            onDismiss = { showHostDialog = false },
            onConfirm = {
                host = it
                com.core.ons.OnsTtsEngines.setMultiHost(ctx, it)
                com.core.ons.MultiTtsClient.setConfiguredHostPort(it)
                showHostDialog = false
            },
        )
    }
}

/** TTS 档位下拉（语速 / 音高 / 音量）。 */
@Composable
private fun TtsStepRow(
    titleRes: Int,
    steps: List<Int>,
    value: Int,
    onSelect: (Int) -> Unit,
) {
    OverlayDropdownPreference(
        title = stringResource(titleRes),
        items = steps.map { it.toString() },
        selectedIndex = steps.indexOf(value).coerceAtLeast(0),
        onSelectedIndexChange = { i -> steps.getOrNull(i)?.let(onSelect) },
    )
}

/** MultiTTS 服务器地址输入弹窗。 */
@Composable
private fun HostPortDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    com.tyranor.next.ui.common.AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(EngineR.string.engine_ons_tts_multi_host)) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(text) }) {
                Text(stringResource(R.string.common_confirm))
            }
        },
    )
}

// ---------------------------------------------------------------------------
// 制卡设置（AnkiDroid）
// ---------------------------------------------------------------------------

/**
 * 制卡设置页：牌组/模板**只能从 AnkiDroid 现有内容里选**（首项「请选择…」= 清空配置），
 * 字段映射为「要素 → 模型字段」下拉；所有改动即时保存（与其它设置页一致，无保存按钮）。
 */
@Composable
internal fun AnkiCardSettingsScreen() {
    val ctx = LocalContext.current
    val activity = AppLocaleController.findActivity(ctx)
    val helper = remember { activity?.let { com.core.anki.AnkiDroidHelper(it) } }

    var deck by remember { mutableStateOf(com.core.anki.AnkiCardConfig.deck(ctx)) }
    var model by remember { mutableStateOf(com.core.anki.AnkiCardConfig.model(ctx)) }
    var voiceSource by remember { mutableStateOf(com.core.anki.AnkiCardConfig.voiceSource(ctx)) }
    var captureOnCard by remember { mutableStateOf(com.core.anki.AnkiCardConfig.captureOnCard(ctx)) }
    var fieldMap by remember {
        mutableStateOf(com.core.anki.AnkiCardConfig.fieldMap(ctx).toMutableMap())
    }
    val deckNames = remember {
        runCatching { helper?.getDeckNames() ?: emptyList() }.getOrDefault(emptyList())
    }
    val modelNames = remember {
        runCatching { helper?.getModelNames() ?: emptyList() }.getOrDefault(emptyList())
    }
    val modelFields = remember(model, modelNames) {
        runCatching {
            val mid = helper?.findModelId(model) ?: return@runCatching emptyList<String>()
            helper.getModelFieldNames(mid)?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
    }

    val placeholder = stringResource(EngineR.string.engine_ons_anki_pick_placeholder)
    val none = stringResource(EngineR.string.engine_ons_anki_slot_none)

    EngineSettingsPage(EngineR.string.engine_ons_anki_card_title) {
        if (deckNames.isEmpty() && modelNames.isEmpty()) {
            item { SettingsCard { SettingsHint(stringResource(EngineR.string.engine_ons_extract_anki_not_installed)) } }
        }
        item {
            SettingsCard {
                // 牌组：首项「请选择…」= 清空
                OverlayDropdownPreference(
                    title = stringResource(EngineR.string.engine_ons_anki_card_deck),
                    items = listOf(placeholder) + deckNames,
                    selectedIndex = (deckNames.indexOf(deck) + 1).coerceAtLeast(0),
                    onSelectedIndexChange = { i ->
                        deck = if (i <= 0) "" else deckNames[i - 1]
                        com.core.anki.AnkiCardConfig.setDeck(ctx, deck)
                    },
                )
                OverlayDropdownPreference(
                    title = stringResource(EngineR.string.engine_ons_anki_card_model),
                    items = listOf(placeholder) + modelNames,
                    selectedIndex = (modelNames.indexOf(model) + 1).coerceAtLeast(0),
                    onSelectedIndexChange = { i ->
                        model = if (i <= 0) "" else modelNames[i - 1]
                        com.core.anki.AnkiCardConfig.setModel(ctx, model)
                    },
                )
                ArrowPreference(
                    title = stringResource(EngineR.string.engine_ons_anki_scheme),
                    summary = com.core.anki.AnkiCardSchemes.activeName(ctx).ifEmpty {
                        stringResource(EngineR.string.engine_ons_anki_scheme_none)
                    },
                    onClick = {
                        activity?.let { act ->
                            com.core.ons.OnsExtractSettingsDialogs.showSchemeManagerDialog(act) {
                                deck = com.core.anki.AnkiCardConfig.deck(ctx)
                                model = com.core.anki.AnkiCardConfig.model(ctx)
                                fieldMap = com.core.anki.AnkiCardConfig.fieldMap(ctx).toMutableMap()
                            }
                        }
                    },
                )
            }
        }
        item {
            SettingsCard {
                OverlayDropdownPreference(
                    title = stringResource(EngineR.string.engine_ons_anki_card_voice),
                    items = listOf(
                        stringResource(EngineR.string.engine_ons_anki_voice_auto),
                        stringResource(EngineR.string.engine_ons_anki_voice_game),
                        stringResource(EngineR.string.engine_ons_anki_voice_tts),
                        stringResource(EngineR.string.engine_ons_anki_voice_off),
                    ),
                    selectedIndex = voiceOptions.indexOf(voiceSource).coerceAtLeast(0),
                    onSelectedIndexChange = { i ->
                        voiceSource = voiceOptions[i]
                        com.core.anki.AnkiCardConfig.setVoiceSource(ctx, voiceSource)
                    },
                )
                SwitchPreference(
                    title = stringResource(EngineR.string.engine_ons_anki_card_capture),
                    checked = captureOnCard,
                    onCheckedChange = {
                        captureOnCard = it
                        com.core.anki.AnkiCardConfig.setCaptureOnCard(ctx, it)
                    },
                )
            }
        }
        item {
            SettingsCard {
                SettingsHint(stringResource(EngineR.string.engine_ons_anki_card_fields))
                if (model.isEmpty()) {
                    SettingsHint(stringResource(EngineR.string.engine_ons_anki_card_model_unset))
                } else {
                    // 要素 → 模型字段（下拉）：与参考 WORD_FIELDS 的 key/标签一致
                    elementSlots.forEach { element ->
                        val options = listOf(none) + modelFields
                        val current = fieldMap[element.key] ?: ""
                        val idx = (modelFields.indexOf(current) + 1).coerceAtLeast(0)
                        OverlayDropdownPreference(
                            title = stringResource(element.labelRes),
                            items = options,
                            selectedIndex = idx,
                            onSelectedIndexChange = { i ->
                                val next = fieldMap.toMutableMap()
                                if (i <= 0) next.remove(element.key)
                                else next[element.key] = modelFields[i - 1]
                                fieldMap = next
                                com.core.anki.AnkiCardConfig.setFieldMap(
                                    ctx,
                                    next.filterValues { v -> v.isNotEmpty() && modelFields.contains(v) },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 语音源取值顺序（与引擎侧一致）。 */
private val voiceOptions = listOf(
    com.core.anki.AnkiCardConfig.VOICE_AUTO,
    com.core.anki.AnkiCardConfig.VOICE_GAME,
    com.core.anki.AnkiCardConfig.VOICE_TTS,
    com.core.anki.AnkiCardConfig.VOICE_OFF,
)

/** 制卡要素表：**key 与标签都对参考 WORD_FIELDS**（不自造）。 */
private data class CardElement(val key: String, val labelRes: Int)

private val elementSlots = listOf(
    CardElement(com.core.anki.AnkiCardConfig.SLOT_WORD, EngineR.string.engine_ons_anki_slot_word),
    CardElement(com.core.anki.AnkiCardConfig.SLOT_READING, EngineR.string.engine_ons_anki_slot_reading),
    CardElement(com.core.anki.AnkiCardConfig.SLOT_DEFINITION, EngineR.string.engine_ons_anki_slot_definition),
    CardElement(com.core.anki.AnkiCardConfig.SLOT_AUDIO, EngineR.string.engine_ons_anki_slot_audio),
    CardElement(com.core.anki.AnkiCardConfig.SLOT_SENTENCE, EngineR.string.engine_ons_anki_slot_sentence),
    CardElement(com.core.anki.AnkiCardConfig.SLOT_TRANSLATION, EngineR.string.engine_ons_anki_slot_translation),
    CardElement(com.core.anki.AnkiCardConfig.SLOT_SENTENCE_AUDIO, EngineR.string.engine_ons_anki_slot_sentence_audio),
    CardElement(com.core.anki.AnkiCardConfig.SLOT_SOURCE, EngineR.string.engine_ons_anki_slot_source),
    CardElement(com.core.anki.AnkiCardConfig.SLOT_WORD_TAGS, EngineR.string.engine_ons_anki_slot_word_tags),
    CardElement(com.core.anki.AnkiCardConfig.SLOT_DEINFLECTION, EngineR.string.engine_ons_anki_slot_deinflection),
    CardElement(com.core.anki.AnkiCardConfig.SLOT_IMAGE, EngineR.string.engine_ons_anki_slot_image),
)
