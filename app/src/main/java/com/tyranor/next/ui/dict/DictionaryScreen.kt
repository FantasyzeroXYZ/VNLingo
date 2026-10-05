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
    var searching by remember { mutableStateOf(false) }
    var showManager by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    // 导入进度（term_bank 分包粒度，回调在 IO 线程）：大词典导入耗时分钟级，
    // 无进度反馈易被误判为卡死
    var importProgress by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<OnsDictStore.DictInfo?>(null) }
    val importFailedMessage = stringResource(R.string.dict_import_failed)

    var dicts by remember { mutableStateOf(OnsDictStore.get().listDicts(context)) }

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

    // 防抖搜索：SQLite 查询（最长前缀 + 词形还原）走 IO 线程；query 变化自动取消上一轮
    LaunchedEffect(query, dictsVersion) {
        if (query.isBlank()) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(250)
        val found = withContext(Dispatchers.IO) {
            runCatching { OnsDictStore.get().search(query.trim(), 20) }.getOrDefault(emptyList())
        }
        results = found
        searching = false
    }

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
                            items(results) { group ->
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
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** 词条数摘要（复用 engine 文案：%1$d 条）。 */
private fun Context.stringWithCount(dict: OnsDictStore.DictInfo): String =
    getString(EngineR.string.engine_ons_dict_entries_fmt, dict.count)
