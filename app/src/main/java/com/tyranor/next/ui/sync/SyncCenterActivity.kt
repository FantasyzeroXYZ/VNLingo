package com.tyranor.next.ui.sync

import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.sync.SaveCloudCatalog
import com.tyranor.next.core.sync.SyncManager
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
 * 账号管理（多账户列表 + 「添加账户」悬浮框登录，点账户切换活动账户）+ 自动同步开关 +
 * 测试连接/立即同步 + 状态行 + 本地备份导出/导入（.vnlbak，SAF）。
 * 双侧冲突时弹对话框让用户选择 使用本地/使用云端/智能合并/取消。
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
    private var accounts by mutableStateOf(listOf<SyncManager.WebDavAccount>())
    private var autoSync by mutableStateOf(false)
    private var showAddAccount by mutableStateOf(false)

    /** 正在编辑的账户（null = 新增模式）；点账号行上的「编辑」进入。 */
    private var editingAccount by mutableStateOf<SyncManager.WebDavAccount?>(null)

    /** 连接测试结果（null = 不显示；空串 = 成功）。 */
    private var testResult by mutableStateOf<String?>(null)

    /** 存档云同步列表（本地已有存档的游戏 ∪ 云端已有 zip 的游戏）。 */
    private var saveRows by mutableStateOf(listOf<SaveCloudCatalog.Row>())
    private var saveLoading by mutableStateOf(false)
    /** 正在处理的行（cloudName），用于禁用按钮并显示"处理中"。 */
    private var saveBusy by mutableStateOf<String?>(null)
    private val saveCatalog by lazy { SaveCloudCatalog(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadConfig()
        setAppScreenContent {
            SyncCenterScreen()
            conflictState?.let { conflict -> ConflictDialog(conflict) }
            if (showAddAccount || editingAccount != null) {
                AccountDialog(editingAccount)
            }
            testResult?.let { result -> TestResultDialog(result) }
        }
    }

    private fun loadConfig() {
        accounts = manager.accounts()
        autoSync = manager.isAutoSyncEnabled
        renderStatus()
        refreshSaves()
    }

    /**
     * 刷新存档云同步列表：本地存档遍历 + 云端目录列表都是磁盘/网络 IO，
     * 统一切到后台线程，回来后落 Compose 状态。
     */
    private fun refreshSaves() {
        if (saveLoading) return
        saveLoading = true
        Thread({
            val rows = try {
                saveCatalog.list()
            } catch (t: Throwable) {
                emptyList<SaveCloudCatalog.Row>()
            }
            runOnUiThread {
                saveRows = rows
                saveLoading = false
            }
        }, "savecloud-list").start()
    }

    /** 单行操作（上传/下载/删除云端）：结果用 Toast 提示并刷新时间列。 */
    private fun runSaveAction(row: SaveCloudCatalog.Row, action: String) {
        if (saveBusy != null) return
        saveBusy = row.cloudName
        val appContext = applicationContext
        val done: (Boolean, String) -> Unit = { ok, message ->
            runOnUiThread {
                saveBusy = null
                Toast.makeText(
                    appContext,
                    if (ok) message else getString(R.string.sync_test_failed) + "：" + message,
                    Toast.LENGTH_LONG,
                ).show()
                refreshSaves()
            }
        }
        when (action) {
            "upload" -> Thread { saveCatalog.upload(row, done) }.start()
            "download" -> Thread { saveCatalog.download(row, done) }.start()
            "delete" -> saveCatalog.deleteRemote(row, done)
        }
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

    /** 添加账户（悬浮框登录）；成功即设为活动账户。 */
    private fun addAccount(server: String, user: String, pass: String) {
        try {
            accounts = manager.addAccount(server, user, pass, autoSync)
            autoSync = manager.isAutoSyncEnabled
            showAddAccount = false
            renderStatus()
            Toast.makeText(this, getString(R.string.sync_account_added, user), Toast.LENGTH_SHORT).show()
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 保存编辑结果（就地更新，不再是"删掉再加"）。
     * 密码留空 = 保持原密码（对话框不回显密码，避免误清空）。
     */
    private fun saveEditedAccount(
        original: SyncManager.WebDavAccount,
        server: String,
        user: String,
        pass: String,
    ) {
        try {
            accounts = manager.updateAccount(
                original.serverUrl, original.username, server, user, pass, autoSync)
            autoSync = manager.isAutoSyncEnabled
            editingAccount = null
            renderStatus()
            Toast.makeText(this, getString(R.string.sync_account_updated, user), Toast.LENGTH_SHORT).show()
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun activateAccount(account: SyncManager.WebDavAccount) {
        if (manager.isActiveAccount(account)) {
            Toast.makeText(this, R.string.sync_account_already_active, Toast.LENGTH_SHORT).show()
            return
        }
        manager.setActiveAccount(account, autoSync)
        renderStatus()
        Toast.makeText(this, getString(R.string.sync_account_switched, account.username), Toast.LENGTH_SHORT).show()
    }

    private fun removeAccount(account: SyncManager.WebDavAccount) {
        accounts = manager.removeAccount(account.serverUrl, account.username, autoSync)
        renderStatus()
        Toast.makeText(this, R.string.sync_account_removed, Toast.LENGTH_SHORT).show()
    }

    private fun testConnection() {
        if (!manager.isConfigured) {
            Toast.makeText(this, R.string.sync_not_configured_error, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.sync_testing, Toast.LENGTH_SHORT).show()
        Thread {
            // 带原因：失败时把 HTTP 状态 + 服务端 message + 常见成因提示展示出来，
            // 不再只有一句"连接失败"（用户无法自查）。
            val reason = try {
                manager.testConnectionDetail()
            } catch (e: IllegalArgumentException) {
                e.message ?: e.toString()
            } catch (t: Throwable) {
                t.message ?: t.toString()
            }
            runOnUiThread {
                testResult = reason ?: ""
                renderStatus()
            }
        }.start()
    }

    private fun syncNow() {
        if (!manager.isConfigured) {
            Toast.makeText(this, R.string.sync_not_configured_error, Toast.LENGTH_SHORT).show()
            return
        }
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
                                Text(
                                    stringResource(R.string.sync_account_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(vertical = 6.dp),
                                )
                                if (accounts.isEmpty()) {
                                    Text(
                                        stringResource(R.string.sync_account_none),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 4.dp),
                                    )
                                }
                                accounts.forEach { account ->
                                    val active = manager.isActiveAccount(account)
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f).clickable { activateAccount(account) },
                                        ) {
                                            Text(
                                                account.username + if (active) {
                                                    "　" + stringResource(R.string.sync_account_badge_active)
                                                } else "",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                account.serverUrl,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        TextButton(onClick = { editingAccount = account }) {
                                            Text(stringResource(R.string.sync_account_edit))
                                        }
                                        TextButton(onClick = { removeAccount(account) }) {
                                            Text(stringResource(R.string.sync_account_delete))
                                        }
                                    }
                                }
                                TextButton(onClick = { showAddAccount = true }) {
                                    Text(stringResource(R.string.sync_account_add))
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        stringResource(R.string.sync_auto),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(
                                        checked = autoSync,
                                        onCheckedChange = {
                                            autoSync = it
                                            val c = manager.config
                                            manager.saveConfig(c.serverUrl, c.username, c.password, it)
                                            renderStatus()
                                        },
                                    )
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
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        stringResource(R.string.sync_save_section_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                                    )
                                    TextButton(onClick = { refreshSaves() }, enabled = !saveLoading) {
                                        Text(stringResource(R.string.sync_save_refresh))
                                    }
                                }
                                Text(
                                    stringResource(R.string.sync_save_section_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (!saveCatalog.isListable()) {
                                    Text(
                                        stringResource(R.string.sync_save_github_unsupported),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                }
                                if (saveRows.isEmpty()) {
                                    Text(
                                        stringResource(R.string.sync_save_empty),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                    )
                                }
                                saveRows.forEach { row -> SaveCloudRowItem(row) }
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

    /**
     * 账户悬浮框：新增与编辑共用（[editing] 非空即编辑模式，字段预填）。
     *
     * 编辑模式下密码留空表示保持原密码（不回显，避免误清空）。
     */
    @Composable
    private fun AccountDialog(editing: SyncManager.WebDavAccount?) {
        val isEdit = editing != null
        var server by remember(editing) { mutableStateOf(editing?.serverUrl ?: "") }
        var user by remember(editing) { mutableStateOf(editing?.username ?: "") }
        var pass by remember(editing) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {
                showAddAccount = false
                editingAccount = null
            },
            title = {
                Text(stringResource(
                    if (isEdit) R.string.sync_account_edit_title else R.string.sync_account_add))
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = { Text(stringResource(R.string.sync_server)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        label = { Text(stringResource(R.string.sync_username)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text(stringResource(R.string.sync_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    if (isEdit) {
                        Text(
                            stringResource(R.string.sync_password_keep),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (isEdit) {
                        saveEditedAccount(editing!!, server, user, pass)
                    } else {
                        addAccount(server, user, pass)
                    }
                }) {
                    Text(stringResource(
                        if (isEdit) R.string.sync_account_save else R.string.sync_account_login))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddAccount = false
                    editingAccount = null
                }) {
                    Text(stringResource(R.string.sync_cancel))
                }
            },
        )
    }

    /** 存档云同步的单行：标题 + 时间 + 本地存档数 + 上传/下载/删除云端。 */
    @Composable
    private fun SaveCloudRowItem(row: SaveCloudCatalog.Row) {
        val busy = saveBusy == row.cloudName
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!row.hasCloud) {
                    Text(
                        stringResource(R.string.sync_save_local_only),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (!row.hasLocal) {
                    Text(
                        stringResource(R.string.sync_save_cloud_only),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                stringResource(
                    R.string.sync_save_cloud_time,
                    if (row.cloudModified > 0) {
                        DateFormat.format("yyyy-MM-dd HH:mm", row.cloudModified).toString()
                    } else {
                        stringResource(R.string.sync_save_never)
                    },
                ) + "　" + stringResource(
                    R.string.sync_save_local_time,
                    if (row.localUploadAt > 0) {
                        DateFormat.format("yyyy-MM-dd HH:mm", row.localUploadAt).toString()
                    } else {
                        stringResource(R.string.sync_save_never)
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.sync_save_local_files, row.localSaveCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (busy) {
                    Text(
                        stringResource(R.string.sync_save_working),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp, top = 8.dp),
                    )
                }
                TextButton(
                    onClick = { runSaveAction(row, "upload") },
                    enabled = !busy && saveBusy == null && row.hasLocal,
                ) { Text(stringResource(R.string.sync_save_upload)) }
                TextButton(
                    onClick = { runSaveAction(row, "download") },
                    enabled = !busy && saveBusy == null && row.hasCloud && row.game != null,
                ) { Text(stringResource(R.string.sync_save_download)) }
                TextButton(
                    onClick = { runSaveAction(row, "delete") },
                    enabled = !busy && saveBusy == null && row.hasCloud,
                ) { Text(stringResource(R.string.sync_save_delete_cloud)) }
            }
        }
    }

    /** 连接测试结果弹窗：成功显示"连接成功"，失败显示具体原因（HTTP 码+服务端说明+提示）。 */
    @Composable
    private fun TestResultDialog(result: String) {
        AlertDialog(
            onDismissRequest = { testResult = null },
            title = { Text(stringResource(R.string.sync_test_result_title)) },
            text = {
                Text(
                    if (result.isEmpty()) stringResource(R.string.sync_test_ok) else result,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { testResult = null }) {
                    Text(stringResource(R.string.sync_close))
                }
            },
        )
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
