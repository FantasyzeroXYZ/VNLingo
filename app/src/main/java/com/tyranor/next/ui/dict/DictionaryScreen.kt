package com.tyranor.next.ui.dict

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.core.engine.R as EngineR
import com.core.ons.OnsDictStore
import com.tyranor.next.R
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.glassShadow
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppSearchField
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.TopBarIcon
import com.tyranor.next.ui.common.glassNavBottomInset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * 词典页（底栏 Tab）：主体为单词搜索查询（输入即查，走 OnsDictStore.search
 * 的最长前缀 + 词形还原分层，与游戏内查词同一份词典库）；
 * 词典管理（导入 / 启停 / 设当前 / 删除）收进右上角入口的底部悬浮框。
 */
@Composable
fun DictionaryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 管理操作（导入/启停/设当前/删除）后递增：驱动词典状态与搜索重算
    var dictsVersion by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<OnsDictStore.Group>>(emptyList()) }
    // 查询语言模式：按词条文字脚本筛选结果（全部/日本語/中文/罗马字）
    var langFilter by remember { mutableStateOf(LangFilter.ALL) }
    var searching by remember { mutableStateOf(false) }
    var showManager by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    // 导入进度（term_bank 分包粒度，回调在 IO 线程）：大词典导入耗时分钟级，
    // 无进度反馈易被误判为卡死
    var importProgress by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<OnsDictStore.DictInfo?>(null) }
    // M6 查询测试：输入任意词 → 命中词形/matchType/还原路径/深度/释义组数（+ 可选诊断轨迹）
    var testQuery by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf("") }
    var testRunning by remember { mutableStateOf(false) }
    var testTrace by remember { mutableStateOf(false) }
    // M4 查词设置（全局默认；每游戏覆盖在游戏设置页）
    var dictQueryLang by remember { mutableStateOf(EngineSettingsStore.getDictQueryLang(context)) }
    var dictReading by remember { mutableStateOf(EngineSettingsStore.isDictReading(context)) }
    var dictHanPriority by remember { mutableStateOf(EngineSettingsStore.getDictHanPriority(context)) }
    val importFailedMessage = stringResource(R.string.dict_import_failed)

    var dicts by remember { mutableStateOf(OnsDictStore.get().listDicts(context)) }

    // B4：大词典存量库首次打开会在后台另开连接补建 reading 索引（不阻塞查词），
    // 这里轮询状态给出「正在优化词典库索引」提示；建完自动消失
    var indexBuilding by remember { mutableStateOf(false) }
    LaunchedEffect(dictsVersion) {
        indexBuilding = OnsDictStore.isReadingIndexBuilding()
        while (indexBuilding) {
            kotlinx.coroutines.delay(1000)
            indexBuilding = OnsDictStore.isReadingIndexBuilding()
        }
    }

    val hasDict = remember(dictsVersion) { OnsDictStore.get().hasDictionary() }
    val dictName = remember(dictsVersion) { OnsDictStore.get().getDictName() }
    val entryCount = remember(dictsVersion) { OnsDictStore.get().getEntryCount() }

    fun refresh() {
        dicts = OnsDictStore.get().listDicts(context)
        dictsVersion++
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            importProgress = ""
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        OnsDictStore.get().importFromFile(context, uri) { importProgress = it }
                    }
                }
                importing = false
                result.onSuccess { count ->
                    val name = OnsDictStore.get().getDictName()
                    Toast.makeText(
                        context,
                        context.getString(EngineR.string.engine_ons_extract_dict_imported, name, count),
                        Toast.LENGTH_SHORT,
                    ).show()
                    refresh()
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    Toast.makeText(context, importFailedMessage, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 防抖搜索：走 IO 线程；query 变化自动取消上一轮。
    // TrackReader searchTermWithDeinflect 语义：①精确 term ②词形还原→term；
    // 都未命中再退 searchByPrefix 式前缀浏览（词典浏览体验保留）
    LaunchedEffect(query, dictsVersion) {
        if (query.isBlank()) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(250)
        val found = withContext(Dispatchers.IO) {
            runCatching {
                val q = query.trim()
                val m = OnsDictStore.get().searchTermWithDeinflect(
                    q, 20, OnsDictStore.isDeinflectEnabled(context))
                m?.groups
                    ?: OnsDictStore.get().search(q, 20)
            }.getOrDefault(emptyList())
        }
        results = found
        searching = false
    }

    // 语言筛选（客户端过滤；分类启发式见 langOf）
    val filteredResults = results.filter { langFilter == LangFilter.ALL || langOf(it) == langFilter }

    MiuixSettingsTheme {
        MiuixScaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.nav_dict),
                    trailing = {
                        TopBarIcon(
                            painterResource(R.drawable.ic_engine_manage),
                            stringResource(R.string.dict_manager_content_description),
                            MaterialTheme.colorScheme.primary,
                        ) { showManager = true }
                    },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier.fillMaxSize()
                    .padding(horizontal = 12.dp)
                    .padding(top = innerPadding.calculateTopPadding()),
            ) {
                AppSearchField(query = query, onQueryChange = { query = it })
                // 词形还原开关（词典页设置，游戏内点词查词共用同一开关）
                var deinflect by remember { mutableStateOf(OnsDictStore.isDeinflectEnabled(context)) }
                MiuixCard(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        .glassShadow().glassBorder(),
                    cornerRadius = AppComponentCornerRadius,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(EngineR.string.engine_ons_dict_deinflect),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                stringResource(EngineR.string.engine_ons_dict_deinflect_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = deinflect,
                            onCheckedChange = {
                                OnsDictStore.setDeinflectEnabled(context, it)
                                deinflect = it
                                dictsVersion++ // 触发搜索重算
                            },
                        )
                    }
                }
                if (indexBuilding) {
                    Text(
                        stringResource(EngineR.string.engine_ons_dict_index_building),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    )
                }
                if (!results.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        langChips.forEach { chip ->
                            TextButton(
                                onClick = { langFilter = chip.first },
                                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                    contentColor = if (langFilter == chip.first)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            ) {
                                Text(
                                    stringResource(chip.second) +
                                        if (langFilter == chip.first) " ●" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                when {
                    !hasDict -> DictHint(stringResource(R.string.dict_search_no_dict))
                    query.isBlank() -> DictHint(
                        stringResource(R.string.dict_search_idle, dictName, entryCount)
                    )
                    else -> Box(Modifier.weight(1f)) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp + glassNavBottomInset()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (searching && results.isEmpty()) {
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                            if (!searching && results.isEmpty()) {
                                item {
                                    DictHint(
                                        stringResource(EngineR.string.engine_ons_extract_def_none)
                                    )
                                }
                            }
                            items(filteredResults) { group ->
                                DictEntryCard(group)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showManager) {
        AppAlertDialog(
            onDismissRequest = { showManager = false },
            title = {
                Text(
                    stringResource(EngineR.string.engine_ons_dict_manager),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MiuixCard(
                        modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
                        cornerRadius = AppComponentCornerRadius,
                    ) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            ArrowPreference(
                                title = stringResource(EngineR.string.engine_ons_dict_import),
                                summary = when {
                                    importProgress.isNotEmpty() -> importProgress
                                    importing -> stringResource(EngineR.string.engine_ons_extract_dict_importing)
                                    else -> null
                                },
                                onClick = { if (!importing) importPicker.launch(arrayOf("*/*")) },
                            )
                        }
                    }
                    if (dicts.isEmpty()) {
                        Text(
                            stringResource(EngineR.string.engine_ons_dict_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        )
                    }
                    // ---- M4 查词设置（查询语言 / 读音查询 / 汉字优先）----
                    MiuixCard(
                        modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
                        cornerRadius = AppComponentCornerRadius,
                    ) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                stringResource(R.string.dict_query_settings),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                            CycleRow(
                                title = stringResource(R.string.dict_query_lang),
                                value = queryLangLabel(dictQueryLang),
                                onNext = {
                                    val idx = EngineSettingsStore.DICT_LANG_ORDER
                                        .indexOf(dictQueryLang).coerceAtLeast(0)
                                    dictQueryLang = EngineSettingsStore.DICT_LANG_ORDER[
                                        (idx + 1) % EngineSettingsStore.DICT_LANG_ORDER.size]
                                    EngineSettingsStore.setDictQueryLang(context, dictQueryLang)
                                },
                            )
                            if (EngineSettingsStore.DICT_LANG_AUTO == dictQueryLang) {
                                CycleRow(
                                    title = stringResource(R.string.dict_han_priority),
                                    value = if (dictHanPriority == EngineSettingsStore.DICT_HAN_ZH)
                                        stringResource(R.string.dict_lang_zh)
                                    else stringResource(R.string.dict_lang_ja),
                                    onNext = {
                                        dictHanPriority =
                                            if (dictHanPriority == EngineSettingsStore.DICT_HAN_ZH)
                                                EngineSettingsStore.DICT_HAN_JA
                                            else EngineSettingsStore.DICT_HAN_ZH
                                        EngineSettingsStore.setDictHanPriority(context, dictHanPriority)
                                    },
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.dict_reading),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        stringResource(R.string.dict_reading_summary),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(checked = dictReading, onCheckedChange = {
                                    dictReading = it
                                    EngineSettingsStore.setDictReading(context, it)
                                })
                            }
                        }
                    }
                    // ---- M6 查询测试（验收工具：看清「该命中却没命中」是规则/语言/词典问题）----
                    MiuixCard(
                        modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
                        cornerRadius = AppComponentCornerRadius,
                    ) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                stringResource(R.string.dict_lookup_test),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                            OutlinedTextField(
                                value = testQuery,
                                onValueChange = { testQuery = it },
                                singleLine = true,
                                label = { Text(stringResource(R.string.dict_lookup_test_hint)) },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            ) {
                                TextButton(
                                    enabled = !testRunning && testQuery.isNotBlank(),
                                    onClick = {
                                        val q = testQuery
                                        val trace = testTrace
                                        testRunning = true
                                        testResult = ""
                                        scope.launch {
                                            val text = withContext(Dispatchers.IO) {
                                                // 词典页此前不装载规则（只有引擎面板装载）：
                                                // 查询测试要先 init（幂等，装载一次）
                                                com.core.ons.OnsDeinflector.init(context)
                                                formatLookupTest(OnsDictStore.get()
                                                    .lookupDetailed(q, null, true, true, trace))
                                            }
                                            testResult = text
                                            testRunning = false
                                        }
                                    },
                                ) { Text(stringResource(R.string.dict_lookup_test_run)) }
                                Text(
                                    stringResource(R.string.dict_lookup_test_trace),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(checked = testTrace, onCheckedChange = { testTrace = it })
                            }
                            if (testResult.isNotEmpty()) {
                                Text(
                                    testResult,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(dicts, key = { it.id }) { dict ->
                            DictRow(
                                dict = dict,
                                onEnabledChanged = { enabled ->
                                    OnsDictStore.get().setEnabled(context, dict.id, enabled)
                                    refresh()
                                },
                                onSetCurrent = {
                                    OnsDictStore.get().setCurrent(context, dict.id)
                                    refresh()
                                },
                                onDelete = { deleteTarget = dict },
                                onScopeChanged = { scope ->
                                    OnsDictStore.get().setDictScope(context, dict.id, scope)
                                    refresh()
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showManager = false }) {
                    Text(stringResource(R.string.common_done))
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AppAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = {
                Text(
                    stringResource(R.string.dict_delete_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    stringResource(EngineR.string.engine_ons_dict_delete_confirm, target.name),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    OnsDictStore.get().deleteDict(context, target.id)
                    deleteTarget = null
                    refresh()
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/** 查询区提示卡（无词典 / 空查询状态 / 未查到）。 */
@Composable
private fun DictHint(text: String) {
    MiuixCard(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).glassShadow().glassBorder(),
        cornerRadius = AppComponentCornerRadius,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

/** 单条查询结果：词条 + 读音 + 释义多行。 */
@Composable
private fun DictEntryCard(group: OnsDictStore.Group) {
    MiuixCard(
        modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
        cornerRadius = AppComponentCornerRadius,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    group.term,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (group.reading.isNotEmpty() && group.reading != group.term) {
                    Text(
                        "【" + group.reading + "】",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            for (gloss in group.glosses) {
                Text(
                    gloss,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun DictRow(
    dict: OnsDictStore.DictInfo,
    onEnabledChanged: (Boolean) -> Unit,
    onSetCurrent: () -> Unit,
    onDelete: () -> Unit,
    onScopeChanged: (String) -> Unit,
) {
    val context = LocalContext.current
    MiuixCard(
        modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
        cornerRadius = AppComponentCornerRadius,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        dict.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        context.stringWithCount(dict),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                androidx.compose.material3.Switch(checked = dict.enabled, onCheckedChange = onEnabledChanged)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!dict.current) {
                    TextButton(onClick = onSetCurrent) {
                        Text(stringResource(EngineR.string.engine_ons_dict_set_current))
                    }
                } else {
                    Text(
                        stringResource(EngineR.string.engine_ons_dict_current),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
                // M4/G8 词典语言范围：点一次切下一个（全部→日本語→中文→한국어→English→Tiếng Việt）
                TextButton(onClick = {
                    val next = OnsDictStore.SCOPES[
                        (OnsDictStore.SCOPES.indexOf(dict.scope).coerceAtLeast(0) + 1)
                            % OnsDictStore.SCOPES.size]
                    onScopeChanged(next)
                }) {
                    Text(stringResource(R.string.dict_scope_row, scopeLabel(dict.scope)))
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** 词典 scope 的显示名（universal/ja/zh/ko/en/vi）。 */
@Composable
private fun scopeLabel(scope: String): String = when (scope) {
    "ja" -> stringResource(R.string.dict_lang_ja)
    "zh" -> stringResource(R.string.dict_lang_zh)
    "ko" -> stringResource(R.string.dict_lang_ko)
    "en" -> stringResource(R.string.dict_lang_en)
    "vi" -> stringResource(R.string.dict_lang_vi)
    else -> stringResource(R.string.dict_scope_universal)
}

/** 查询语言显示名。 */
@Composable
private fun queryLangLabel(lang: String): String = when (lang) {
    "ja" -> stringResource(R.string.dict_lang_ja)
    "zh" -> stringResource(R.string.dict_lang_zh)
    "ko" -> stringResource(R.string.dict_lang_ko)
    "en" -> stringResource(R.string.dict_lang_en)
    "vi" -> stringResource(R.string.dict_lang_vi)
    else -> stringResource(R.string.dict_lang_auto)
}

/** 单行循环选择（点击切下一个取值）；与设置页的选择行交互一致。 */
@Composable
private fun CycleRow(title: String, value: String, onNext: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = onNext) { Text("$value ▾") }
    }
}

/** 词条数摘要（复用 engine 文案：%1$d 条）。 */
private fun Context.stringWithCount(dict: OnsDictStore.DictInfo): String =
    getString(EngineR.string.engine_ons_dict_entries_fmt, dict.count)

/**
 * M6：查询测试结果格式化（纯文本，便于复制/对照 DiagLog）。
 * 输出命中词形 / matchType / 语言 / 深度 / 还原路径 / 释义组数与前几组释义，
 * 以及（诊断模式下的）规则还原候选与逐层轨迹。
 */
private fun formatLookupTest(m: OnsDictStore.DetailedMatch): String {
    if (m.query.isEmpty()) return ""
    val sb = StringBuilder()
    if (m.hit()) {
        sb.append("命中：").append(m.matchedTerm).append("  [").append(m.matchType).append(']')
        sb.append("\n语言：").append(m.language)
        if (m.score > 0) sb.append("  深度：").append(m.score)
        if (m.deinflectionPath.isNotEmpty()) sb.append("  路径：").append(m.deinflectionPath)
        sb.append("  释义组：").append(m.groups.size)
        m.groups.take(3).forEach { g ->
            val head = if (g.reading.isNotEmpty()) "${g.term}（${g.reading}）" else g.term
            val gloss = g.glosses.firstOrNull()?.replace('\n', ' ')?.take(60) ?: ""
            sb.append("\n  · ").append(head).append("：").append(gloss)
        }
    } else {
        sb.append("未命中：").append(m.query).append("  语言：").append(m.language)
        if (m.candidates.isEmpty()) sb.append("  （无规则还原候选）")
    }
    if (m.candidates.isNotEmpty()) {
        sb.append("\n候选（按深度）：")
        m.candidates.take(8).forEach { c ->
            sb.append("\n  ").append(c.term).append(" (s").append(c.score).append(')')
            if (c.path.isNotEmpty()) sb.append(' ').append(c.pathString())
        }
        if (m.candidates.size > 8) sb.append("\n  …共 ").append(m.candidates.size).append(" 条")
    }
    if (m.trace.isNotEmpty()) {
        sb.append("\n轨迹（诊断）：")
        m.trace.take(12).forEach { sb.append("\n  ").append(it) }
        if (m.trace.size > 12) sb.append("\n  …共 ").append(m.trace.size).append(" 步")
    }
    return sb.toString()
}


/** 查询语言模式（词典页筛选；词条文字脚本启发式分类）。 */
private enum class LangFilter { ALL, JA, ZH, ROMAJI }

private val langChips = listOf(
    LangFilter.ALL to R.string.dict_filter_all,
    LangFilter.JA to R.string.dict_filter_ja,
    LangFilter.ZH to R.string.dict_filter_zh,
    LangFilter.ROMAJI to R.string.dict_filter_romaji,
)

/**
 * 词条语言启发式分类：
 * - 含假名（词条或读音）→ 日本語
 * - 词条纯 ASCII → 罗马字/英文（Yomichan 词典无此类，多为测试别名）
 * - 其余 CJK 词条 → 中文（中文词典/汉化词条）
 */
private fun langOf(group: OnsDictStore.Group): LangFilter {
    val term = group.term
    // 词条纯 ASCII：罗马字别名（日语词的 QWERTY 查询入口）——即使读音含假名也归罗马字
    if (term.none { it.code > 0x7F }) return LangFilter.ROMAJI
    if (hasKana(term) || hasKana(group.reading)) return LangFilter.JA
    return LangFilter.ZH
}

private fun hasKana(s: String): Boolean {
    for (c in s) {
        val cp = c.code
        if (cp in 0x3041..0x309F || cp in 0x30A1..0x30FF || cp in 0x31F0..0x31FF) return true
    }
    return false
}
