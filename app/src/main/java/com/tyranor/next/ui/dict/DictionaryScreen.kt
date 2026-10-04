package com.tyranor.next.ui.dict

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.core.engine.R as EngineR
import com.core.ons.OnsDictStore
import com.tyranor.next.R
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.glassShadow
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.glassNavBottomInset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 词典页（底栏 Tab）：多词典管理与导入（Yomichan zip / MDX jsonl）。
 * 数据源为 engine 的 [OnsDictStore]（SQLite，跨进程文件锁串行化，低频操作），
 * 与游戏内查词共用同一份词典库。
 */
@Composable
fun DictionaryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dicts by remember { mutableStateOf(OnsDictStore.get().listDicts(context)) }
    var importing by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<OnsDictStore.DictInfo?>(null) }
    val importFailedMessage = stringResource(R.string.dict_import_failed)

    fun refresh() {
        dicts = OnsDictStore.get().listDicts(context)
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        OnsDictStore.get().importFromFile(context, uri) { }
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

    MiuixSettingsTheme {
        MiuixScaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = { AppTopBar(title = stringResource(R.string.nav_dict)) },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize()
                    .padding(horizontal = 12.dp)
                    .padding(top = innerPadding.calculateTopPadding()),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp + glassNavBottomInset()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    MiuixCard(
                        modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
                        cornerRadius = AppComponentCornerRadius,
                    ) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            ArrowPreference(
                                title = stringResource(EngineR.string.engine_ons_dict_import),
                                summary = if (importing) {
                                    stringResource(EngineR.string.engine_ons_extract_dict_importing)
                                } else {
                                    null
                                },
                                onClick = { if (!importing) importPicker.launch(arrayOf("*/*")) },
                            )
                        }
                    }
                }
                if (dicts.isEmpty()) {
                    item {
                        MiuixCard(
                            modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
                            cornerRadius = AppComponentCornerRadius,
                        ) {
                            Text(
                                stringResource(EngineR.string.engine_ons_dict_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                        }
                    }
                }
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
