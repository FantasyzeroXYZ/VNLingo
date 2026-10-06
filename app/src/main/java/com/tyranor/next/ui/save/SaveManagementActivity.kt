package com.tyranor.next.ui.save

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.game.save.GameSaveManager
import com.tyranor.next.core.game.save.RpgSaveFormat
import com.tyranor.next.core.game.save.RpgSaveSync
import com.tyranor.next.core.game.launch.EngineLauncher
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.model.ScanGameIntents
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.glassShadow
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.BottomInsetSpacer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import com.core.ons.OnsSaveCloud
import java.io.File
import java.text.DateFormat
import java.util.Date

class SaveManagementActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val game = intent.readScanGame()
        if (game == null) {
            finish()
            return
        }

        setAppScreenContent {
            SaveManagementScreen(game = game)
        }
    }

    companion object {
        fun createIntent(context: Context, game: ScanGame): Intent =
            ScanGameIntents.putGame(Intent(context, SaveManagementActivity::class.java), game)

        private fun Intent.readScanGame(): ScanGame? = ScanGameIntents.getGame(this)
    }
}

@Composable
private fun SaveManagementScreen(game: ScanGame) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saveOperationFailedMessage = stringResource(R.string.save_operation_failed)
    val saveExportedCountFormat = stringResource(R.string.save_exported_count)
    val saveImportedCountFormat = stringResource(R.string.save_imported_count)
    val saveDeletedCountFormat = stringResource(R.string.save_deleted_count)
    val saveSyncResultFormat = stringResource(R.string.save_sync_result)
    val saveSyncNoChangeMessage = stringResource(R.string.save_sync_result_no_change)
    val saveSyncFailedFormat = stringResource(R.string.save_sync_result_failed)
    val saveSyncUnmappedFormat = stringResource(R.string.save_sync_unmapped)
    // 独立存档目录不可用（scoped 目录返回 null）时的同步提示，与存档位置解析共用同一文案
    val saveDirUnavailableMessage = stringResource(R.string.save_error_tyrano_external_unavailable)
    // 会话运行中/启动中：手动同步被拒绝的提示
    val saveBusyEngineRunningMessage = stringResource(R.string.save_busy_engine_running)
    val manager = remember { GameSaveManager(context) }
    var location by remember { mutableStateOf<GameSaveManager.SaveLocation?>(null) }
    var fileCount by remember { mutableStateOf(0) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // MV/MZ 导出格式选择（标准模式 / Tyranor 模式）；其他引擎直接导出。
    // 用 rememberSaveable：CreateDocument 系统页期间进程重建后仍按用户所选格式导出。
    var showExportFormatPicker by remember { mutableStateOf(false) }
    var exportFormat by rememberSaveable { mutableStateOf(GameSaveManager.ExportFormat.TYRANOR) }
    val rpgWebGame = RpgSaveFormat.isRpgWebEngine(game.engine)
    // 存档互通生效值（单游戏覆盖 > 全局）：L7——关闭时隐藏「立即同步」，
    // 避免用户在功能未开启时触发出人意料的删除归置语义
    var saveInteropEnabled by remember { mutableStateOf(false) }
    // 导入/导出/删除互斥：并发任务会互相清掉对方的暂存目录，破坏导入的原子性
    var taskRunning by remember { mutableStateOf(false) }

    // 云同步（OnsSaveCloud 独立存档通道）：远端 = <dir>/<title>.zip，与面板/导出命名一致
    val cloudGameName = remember(game.title) {
        game.title.replace(Regex("[\\/:*?\"<>|]"), "_").ifBlank { "game" }
    }
    var cloudStatus by remember { mutableStateOf("") }
    val cloudStatusFormat = stringResource(R.string.save_cloud_status_fmt)
    val cloudNoneText = stringResource(R.string.save_cloud_none)
    val cloudUnknownText = stringResource(R.string.save_cloud_unknown)

    // 目录解析与文件递归遍历均为磁盘 IO：统一切到 IO 线程，避免组合期/主线程卡顿
    suspend fun refresh() {
        val snapshot = withContext(Dispatchers.IO) {
            manager.resolveSaveLocation(game) to manager.listSaveFiles(game).size
        }
        location = snapshot.first
        fileCount = snapshot.second
    }

    fun refreshCloudStatus() {
        val appContext = context.applicationContext
        OnsSaveCloud.cloudModified(appContext, cloudGameName) { modified ->
            val last = OnsSaveCloud.lastUpload(appContext, cloudGameName)
            val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            scope.launch {
                cloudStatus = cloudStatusFormat.format(
                    if (last > 0) fmt.format(Date(last)) else cloudNoneText,
                    if (modified > 0) fmt.format(Date(modified)) else cloudUnknownText,
                )
            }
        }
    }

    LaunchedEffect(game) {
        refresh()
        // 互通开关读取命中 DB：挂起在 IO 线程取生效值
        saveInteropEnabled = EngineLauncher.isRpgSaveInteropEnabled(context, game)
        refreshCloudStatus()
    }

    /** 把同步结果格式化成用户可读文案：无变化提示、有变化给明细、失败与无法识别的追加说明。 */
    fun formatSyncResult(result: RpgSaveSync.Result): String {
        // failed > 0 时不能只看 changed==0 就说「两侧一致」——可能是处理失败什么都没做成
        val base = when {
            result.failed > 0 -> saveSyncFailedFormat.format(result.failed)
            result.changed == 0 -> saveSyncNoChangeMessage
            else -> {
                val overwritten = result.toTyranor + result.toStandard
                saveSyncResultFormat.format(result.imported, result.exported, overwritten, result.movedToDeleted)
            }
        }
        return if (result.unmapped > 0) "$base\n${saveSyncUnmappedFormat.format(result.unmapped)}" else base
    }

    fun runSaveTask(block: suspend () -> String) {
        if (taskRunning) return
        scope.launch {
            taskRunning = true
            try {
                val message = withContext(Dispatchers.IO) {
                    try {
                        block()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        t.toSaveErrorMessage(context, saveOperationFailedMessage)
                    }
                }
                refresh()
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            } finally {
                taskRunning = false
            }
        }
    }

    /** 云上传/下载（挂起包装 OnsSaveCloud 回调）；返回用户可读结果文案。 */
    suspend fun runCloudTask(upload: Boolean): String {
        val appContext = context.applicationContext
        if (!OnsSaveCloud.isConfigured(appContext)) {
            return "" // 空串 = 需要配置（调用方弹配置对话框）
        }
        val zip = withContext(Dispatchers.IO) {
            File(appContext.cacheDir, "cloud_$cloudGameName.zip")
        }
        if (upload) {
            withContext(Dispatchers.IO) {
                manager.exportToZip(game, Uri.fromFile(zip), GameSaveManager.ExportFormat.TYRANOR)
            }
        } else {
            zip.delete()
        }
        val ok = suspendCancellableCoroutine { cont ->
            if (upload) {
                OnsSaveCloud.upload(appContext, cloudGameName, zip) { success, _ ->
                    cont.resume(success) {}
                }
            } else {
                OnsSaveCloud.download(appContext, cloudGameName, zip) { success, _ ->
                    cont.resume(success) {}
                }
            }
        }
        if (upload) {
            zip.delete()
            if (!ok) throw IllegalStateException("upload failed")
            OnsSaveCloud.recordUpload(appContext, cloudGameName, System.currentTimeMillis())
            return context.getString(R.string.save_cloud_uploaded)
        }
        if (!ok) return context.getString(R.string.save_cloud_missing)
        val count = withContext(Dispatchers.IO) {
            manager.importFromZip(game, Uri.fromFile(zip))
        }
        zip.delete()
        return context.getString(R.string.save_cloud_downloaded, count)
    }

    fun startCloudTask(upload: Boolean) {
        val appContext = context.applicationContext
        if (!OnsSaveCloud.isConfigured(appContext)) {
            Toast.makeText(appContext, R.string.save_cloud_not_configured, Toast.LENGTH_LONG).show()
            // 复用引擎侧配置弹窗（模式/服务器/账号）；保存后直接续跑用户点的那个方向
            OnsSaveCloud.showConfigDialog(
                context as android.app.Activity,
                { startCloudTask(true) },
                { startCloudTask(false) },
                { refreshCloudStatus() },
            )
            return
        }
        runSaveTask {
            val message = runCloudTask(upload)
            refreshCloudStatus()
            message
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri != null) {
            runSaveTask {
                val count = manager.exportToZip(game, uri, exportFormat)
                saveExportedCountFormat.format(count)
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            runSaveTask {
                val count = manager.importFromZip(game, uri)
                saveImportedCountFormat.format(count)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = stringResource(R.string.save_management_title))

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    colors = CardDefaults.cardColors(containerColor = NavWhite),
                    shape = AppComponentShape,
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(game.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        location?.let { loadedLocation ->
                            Text(
                                loadedLocation.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Text(
                                if (loadedLocation.available) stringResource(R.string.save_file_count, fileCount) else stringResource(R.string.save_unmanageable),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            item {
                AppNavItem(
                    title = stringResource(R.string.save_export_zip),
                    showLeadingIcon = false,
                    showArrow = false,
                    onClick = {
                        if (rpgWebGame) {
                            showExportFormatPicker = true
                        } else {
                            exportFormat = GameSaveManager.ExportFormat.TYRANOR
                            exportLauncher.launch(defaultArchiveName(game))
                        }
                    },
                )
            }
            if (rpgWebGame && saveInteropEnabled) {
                item {
                    AppNavItem(
                        title = stringResource(R.string.save_sync_now),
                        showLeadingIcon = false,
                        showArrow = false,
                        onClick = {
                            runSaveTask {
                                when (val op = EngineLauncher.syncRpgSaves(context, game)) {
                                    is EngineLauncher.RpgSaveOpResult.Done -> formatSyncResult(op.value)
                                    // 会话运行中/启动中：未触碰存档，提示先退出游戏
                                    EngineLauncher.RpgSaveOpResult.Busy -> saveBusyEngineRunningMessage
                                    // 独立存档目录不可用：如实报告，绝不能显示「同步完成」
                                    EngineLauncher.RpgSaveOpResult.SaveDirUnavailable -> saveDirUnavailableMessage
                                }
                            }
                        },
                    )
                }
            }
            item {
                AppNavItem(
                    title = stringResource(R.string.save_import_zip),
                    showLeadingIcon = false,
                    showArrow = false,
                    onClick = { importLauncher.launch("application/zip") },
                )
            }
            item {
                AppNavItem(
                    title = stringResource(R.string.save_cloud_status_title),
                    summary = cloudStatus.ifEmpty { stringResource(R.string.save_cloud_unknown) },
                    showLeadingIcon = false,
                    showArrow = false,
                    onClick = { refreshCloudStatus() },
                )
            }
            item {
                AppNavItem(
                    title = stringResource(R.string.save_cloud_upload),
                    showLeadingIcon = false,
                    showArrow = false,
                    onClick = { startCloudTask(upload = true) },
                )
            }
            item {
                AppNavItem(
                    title = stringResource(R.string.save_cloud_download),
                    showLeadingIcon = false,
                    showArrow = false,
                    onClick = { startCloudTask(upload = false) },
                )
            }
            item {
                AppNavItem(
                    title = stringResource(R.string.save_delete_title),
                    showLeadingIcon = false,
                    showArrow = false,
                    onClick = { showDeleteConfirm = true },
                )
            }
            item { BottomInsetSpacer() }
        }
    }

    // MV/MZ 导出格式选择：标准模式（JoiPlay/PC 兼容）/ Tyranor 模式；选项用 AppNavItem（弹窗内反色）
    if (showExportFormatPicker) {
        AppAlertDialog(
            onDismissRequest = { showExportFormatPicker = false },
            title = { Text(stringResource(R.string.save_export_format_title), style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppNavItem(
                        title = stringResource(R.string.save_export_format_standard),
                        summary = stringResource(R.string.save_export_format_standard_summary),
                        leadingIcon = R.drawable.ic_save_export_format,
                        containerColor = DialogItemSurface,
                        showArrow = false,
                        onClick = {
                            showExportFormatPicker = false
                            exportFormat = GameSaveManager.ExportFormat.STANDARD
                            exportLauncher.launch(defaultArchiveName(game))
                        },
                    )
                    AppNavItem(
                        title = stringResource(R.string.save_export_format_tyranor),
                        summary = stringResource(R.string.save_export_format_tyranor_summary),
                        leadingIcon = R.drawable.ic_save_export_format,
                        containerColor = DialogItemSurface,
                        showArrow = false,
                        onClick = {
                            showExportFormatPicker = false
                            exportFormat = GameSaveManager.ExportFormat.TYRANOR
                            exportLauncher.launch(defaultArchiveName(game))
                        },
                    )
                }
            },
            confirmButton = {},
        )
    }

    if (showDeleteConfirm) {
        AppAlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.save_delete_title), style = MaterialTheme.typography.titleMedium) },
            text = { Text(stringResource(R.string.save_delete_message, game.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        runSaveTask {
                            val count = manager.deleteSaves(game)
                            saveDeletedCountFormat.format(count)
                        }
                    },
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

private fun defaultArchiveName(game: ScanGame): String {
    val safeTitle = game.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "game" }
    return "${safeTitle}_saves.zip"
}
