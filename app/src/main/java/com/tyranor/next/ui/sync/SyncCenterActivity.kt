package com.tyranor.next.ui.sync

import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Toast
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.sync.SyncManager
import com.tyranor.next.core.sync.WebDavClient
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.glassShadow
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppTopBar
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold

/**
 * 云同步中心（参考 RinneMobile LauncherSyncCenterFragment 重实现，Compose 版）：
 * WebDAV 配置（服务器/账号/密码/自动同步）+ 保存/测试连接/立即同步 + 状态行 +
 * 本地备份导出/导入（.vnlbak，SAF）。双侧冲突时弹对话框让用户选择
 * 使用本地/使用云端/智能合并/取消。
 */
class SyncCenterActivity : AppScreenActivity() {

    private val manager by lazy { SyncManager(this) }

    private val backupCreateLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) exportLocalBackup(uri)
        }
    private val backupOpenLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importLocalBackup(uri)
        }

    /** 冲突决定通道：同步线程阻塞等待用户在对话框的选择。 */
    private var conflictSink: java.util.concurrent.SynchronousQueue<Int>? = null

    private var conflictState by mutableStateOf<SyncManager.Conflict?>(null)
    private var showImportConfirm by mutableStateOf(false)
    private var statusText by mutableStateOf("")
    private var serverText by mutableStateOf("")
    private var userText by mutableStateOf("")
    private var passText by mutableStateOf("")
    private var autoSync by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadConfig()
        setAppScreenContent {
            SyncCenterScreen()
            conflictState?.let { conflict -> ConflictDialog(conflict) }
        }
    }

    private fun loadConfig() {
        val config = manager.config
        serverText = config.serverUrl
        userText = config.username
        passText = config.password
        autoSync = config.autoSync
        renderStatus()
    }

    private fun renderStatus() {
        val configured = manager.isConfigured
        val sb = StringBuilder()
        sb.append(getString(
            R.string.sync_status_prefix,
            getString(if (configured) R.string.sync_status_configured else R.string.sync_status_not_configured),
        ))
        if (configured) {
            val last = manager.lastSyncTime
            sb.append("\n").append(getString(
                R.string.sync_last_sync,
                if (last > 0) DateFormat.format("yyyy-MM-dd HH:mm", last).toString()
                else getString(R.string.sync_never),
            ))
            if (manager.isAutoSyncEnabled) sb.append(getString(R.string.sync_auto_suffix))
        }
        statusText = sb.toString()
    }

    /** 从输入框读取配置（未填完整返回 null 并提示）。 */
    private fun readConfigInput(): SyncManager.SyncConfig? {
        val url = serverText.trim()
        val user = userText.trim()
        val pass = passText
        if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, R.string.sync_save_first, Toast.LENGTH_SHORT).show()
            return null
        }
        return SyncManager.SyncConfig(url, user, pass, autoSync)
    }

    private fun saveConfig() {
        val config = readConfigInput() ?: return
        manager.saveConfig(config.serverUrl, config.username, config.password, config.autoSync)
        Toast.makeText(this, R.string.sync_config_saved, Toast.LENGTH_SHORT).show()
        renderStatus()
    }

    private fun testConnection() {
        val config = readConfigInput() ?: return
        manager.saveConfig(config.serverUrl, config.username, config.password, config.autoSync)
        Toast.makeText(this, R.string.sync_testing, Toast.LENGTH_SHORT).show()
        val appContext = applicationContext
        Thread {
            val ok = try {
                WebDavClient(config.serverUrl, config.username, config.password).testConnection()
            } catch (e: IllegalArgumentException) {
                Toast.makeText(appContext, e.message, Toast.LENGTH_LONG).show()
                false
            } catch (t: Throwable) {
                false
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) R.string.sync_test_ok else R.string.sync_test_failed,
                    Toast.LENGTH_SHORT,
                ).show()
                renderStatus()
            }
        }.start()
    }

    private fun syncNow() {
        val config = readConfigInput() ?: return
        manager.saveConfig(config.serverUrl, config.username, config.password, config.autoSync)
        Toast.makeText(this, R.string.sync_in_progress, Toast.LENGTH_SHORT).show()
        val appContext = applicationContext
        manager.sync(object : SyncManager.SyncListener {
            override fun onSyncStart() {}

            override fun onProgress(item: String, changed: Boolean) {}

            override fun onConflict(conflict: SyncManager.Conflict): Int {
                val sink = java.util.concurrent.SynchronousQueue<Int>()
                conflictSink = sink
                runOnUiThread { conflictState = conflict }
                // 等待用户选择；超时（2 分钟）按取消处理，避免同步线程悬挂
                val decision = try {
                    sink.poll(120, java.util.concurrent.TimeUnit.SECONDS) ?: SyncManager.RESOLVE_CANCEL
                } catch (e: InterruptedException) {
                    SyncManager.RESOLVE_CANCEL
                }
                runOnUiThread { conflictState = null }
                return decision
            }

            override fun onSyncComplete(result: SyncManager.SyncResult) {
                runOnUiThread {
                    val message = when {
                        result.cancelled -> R.string.sync_cancelled
                        result.uploaded -> R.string.sync_uploaded
                        result.downloaded -> R.string.sync_downloaded
                        result.merged -> R.string.sync_merged
                        result.noChanges -> R.string.sync_up_to_date
                        else -> R.string.sync_complete
                    }
                    Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                    renderStatus()
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    Toast.makeText(appContext, error, Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun exportLocalBackup(uri: Uri) {
        Toast.makeText(this, R.string.sync_backup_exporting, Toast.LENGTH_SHORT).show()
        val appContext = applicationContext
        Thread {
            try {
                val backup = manager.exportLocalBackupAsGzip()
                val out = appContext.contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException("openOutputStream failed")
                out.use {
                    it.write(backup.bytes)
                    it.flush()
                }
                runOnUiThread {
                    Toast.makeText(
                        appContext,
                        getString(R.string.sync_backup_done, backup.bytes.size / 1024, backup.originalSize / 1024),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(
                        appContext,
                        getString(R.string.sync_backup_failed, e.message ?: e.toString()),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun importLocalBackup(uri: Uri) {
        Toast.makeText(this, R.string.sync_backup_importing, Toast.LENGTH_SHORT).show()
        val appContext = applicationContext
        Thread {
            try {
                val bytes = readBytesFromUri(appContext, uri)
                manager.importLocalBackupFromBytes(bytes)
                runOnUiThread {
                    Toast.makeText(appContext, R.string.sync_backup_import_done, Toast.LENGTH_LONG).show()
                    renderStatus()
                }
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(
                        appContext,
                        getString(R.string.sync_backup_failed, e.message ?: e.toString()),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    @Throws(Exception::class)
    private fun readBytesFromUri(context: android.content.Context, uri: Uri): ByteArray {
        val maxBytes = com.tyranor.next.core.sync.SyncSnapshotCodec.MAX_LOCAL_BACKUP_BYTES.toLong()
        val input = context.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("openInputStream failed")
        input.use { stream ->
            val bos = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0L
            while (true) {
                val len = stream.read(buf)
                if (len == -1) break
                total += len
                if (total > maxBytes) throw java.io.IOException("backup too large")
                bos.write(buf, 0, len)
            }
            return bos.toByteArray()
        }
    }

    @Composable
    private fun SyncCenterScreen() {
        MiuixSettingsTheme {
            MiuixScaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = ComposeColor.Transparent,
                contentWindowInsets = WindowInsets(0.dp),
                topBar = { AppTopBar(title = stringResource(R.string.sync_title)) },
            ) { innerPadding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                        .padding(horizontal = 12.dp)
                        .padding(top = innerPadding.calculateTopPadding()),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        MiuixCard(
                            modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
                            cornerRadius = AppComponentCornerRadius,
                        ) {
                            Text(
                                stringResource(R.string.sync_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                    item {
                        MiuixCard(
                            modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
                            cornerRadius = AppComponentCornerRadius,
                        ) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                OutlinedTextField(
                                    value = serverText,
                                    onValueChange = { serverText = it },
                                    label = { Text(stringResource(R.string.sync_server)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = userText,
                                    onValueChange = { userText = it },
                                    label = { Text(stringResource(R.string.sync_username)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                )
                                OutlinedTextField(
                                    value = passText,
                                    onValueChange = { passText = it },
                                    label = { Text(stringResource(R.string.sync_password)) },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        stringResource(R.string.sync_auto),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(checked = autoSync, onCheckedChange = { autoSync = it })
                                }
                                Text(
                                    statusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }
                    item {
                        MiuixCard(
                            modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
                            cornerRadius = AppComponentCornerRadius,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                TextButton(onClick = { saveConfig() }) {
                                    Text(stringResource(R.string.sync_save))
                                }
                                TextButton(onClick = { testConnection() }) {
                                    Text(stringResource(R.string.sync_test))
                                }
                                TextButton(onClick = { syncNow() }) {
                                    Text(stringResource(R.string.sync_now))
                                }
                            }
                        }
                    }
                    item {
                        MiuixCard(
                            modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
                            cornerRadius = AppComponentCornerRadius,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                TextButton(onClick = {
                                    backupCreateLauncher.launch("vnlingo_backup_" + System.currentTimeMillis() + ".vnlbak")
                                }) {
                                    Text(stringResource(R.string.sync_backup_export))
                                }
                                TextButton(onClick = { showImportConfirm = true }) {
                                    Text(stringResource(R.string.sync_backup_import))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showImportConfirm) {
            AlertDialog(
                onDismissRequest = { showImportConfirm = false },
                title = { Text(stringResource(R.string.sync_backup_import_confirm_title)) },
                text = { Text(stringResource(R.string.sync_backup_import_confirm_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        showImportConfirm = false
                        backupOpenLauncher.launch(
                            arrayOf("application/octet-stream", "application/json", "text/*", "*/*"))
                    }) { Text(stringResource(R.string.sync_backup_import_choose)) }
                },
                dismissButton = {
                    TextButton(onClick = { showImportConfirm = false }) {
                        Text(stringResource(R.string.sync_cancel))
                    }
                },
            )
        }
    }

    @Composable
    private fun ConflictDialog(conflict: SyncManager.Conflict) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.sync_conflict_title)) },
            text = {
                Text(stringResource(
                    R.string.sync_conflict_message,
                    conflict.localBytes / 1024, conflict.remoteBytes / 1024,
                ))
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { decide(SyncManager.RESOLVE_USE_LOCAL) }) {
                        Text(stringResource(R.string.sync_conflict_use_local))
                    }
                    TextButton(onClick = { decide(SyncManager.RESOLVE_USE_REMOTE) }) {
                        Text(stringResource(R.string.sync_conflict_use_remote))
                    }
                    TextButton(onClick = { decide(SyncManager.RESOLVE_MERGE) }) {
                        Text(stringResource(R.string.sync_conflict_merge))
                    }
                    TextButton(onClick = { decide(SyncManager.RESOLVE_CANCEL) }) {
                        Text(stringResource(R.string.sync_cancel))
                    }
                }
            },
        )
    }

    private fun decide(decision: Int) {
        conflictSink?.offer(decision)
    }

    companion object {
        fun createIntent(context: android.content.Context): android.content.Intent =
            android.content.Intent(context, SyncCenterActivity::class.java)
    }
}
