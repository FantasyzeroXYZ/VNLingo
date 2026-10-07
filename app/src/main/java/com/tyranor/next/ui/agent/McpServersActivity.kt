package com.tyranor.next.ui.agent

import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.core.agent.McpHttpClient
import com.core.agent.McpServerStore
import com.tyranor.next.R
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.glassShadow
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppTopBar
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold

/**
 * MCP 服务器管理页：列出本机已确认保存的 Streamable HTTP 服务器，
 * 支持悬浮框添加（McpServerStore 校验 HTTPS/loopback 与名称）、
 * 连接测试（initialize + tools/list）与删除。游戏内 AI 助手经
 * McpServerProxy 调用同一注册表。
 */
class McpServersActivity : AppScreenActivity() {

    private var servers by mutableStateOf(listOf<McpServerStore.Server>())
    private var showAdd by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refresh()
        setAppScreenContent {
            McpServersScreen()
            if (showAdd) AddServerDialog()
        }
    }

    private fun refresh() {
        servers = McpServerStore.list(this)
    }

    private fun addServer(name: String, endpoint: String) {
        try {
            val server = McpServerStore.add(this, name, endpoint)
            showAdd = false
            refresh()
            Toast.makeText(this, getString(R.string.mcp_added, server.name), Toast.LENGTH_SHORT).show()
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun removeServer(server: McpServerStore.Server) {
        try {
            McpServerStore.remove(this, server.id ?: return)
            refresh()
            Toast.makeText(this, R.string.mcp_removed, Toast.LENGTH_SHORT).show()
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }

    /** 连接测试：initialize 建会话 + tools/list 数工具数。 */
    private fun testServer(server: McpServerStore.Server) {
        Toast.makeText(this, getString(R.string.mcp_testing, server.name), Toast.LENGTH_SHORT).show()
        val appContext = applicationContext
        Thread {
            val message = try {
                val client = McpHttpClient()
                val session = client.open(server)
                val tools = client.listTools(session)
                getString(R.string.mcp_test_ok, tools.optInt("tool_count"))
            } catch (t: Throwable) {
                getString(R.string.mcp_test_failed, t.message ?: t.toString())
            }
            runOnUiThread {
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    @Composable
    private fun McpServersScreen() {
        MiuixSettingsTheme {
            MiuixScaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = ComposeColor.Transparent,
                contentWindowInsets = WindowInsets(0.dp),
                topBar = { AppTopBar(title = stringResource(R.string.mcp_title)) },
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
                                stringResource(R.string.mcp_summary),
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
                                if (servers.isEmpty()) {
                                    Text(
                                        stringResource(R.string.mcp_empty),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    )
                                }
                                servers.forEach { server ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                server.name ?: "",
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                server.endpoint ?: "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        TextButton(onClick = { testServer(server) }) {
                                            Text(stringResource(R.string.sync_test))
                                        }
                                        TextButton(onClick = { removeServer(server) }) {
                                            Text(stringResource(R.string.sync_account_delete))
                                        }
                                    }
                                }
                                TextButton(onClick = { showAdd = true }) {
                                    Text(stringResource(R.string.mcp_add))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AddServerDialog() {
        var name by remember { mutableStateOf("") }
        var endpoint by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.mcp_add)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.mcp_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        label = { Text(stringResource(R.string.mcp_endpoint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { addServer(name, endpoint) }) {
                    Text(stringResource(R.string.sync_account_login))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) {
                    Text(stringResource(R.string.sync_cancel))
                }
            },
        )
    }

    companion object {
        fun createIntent(context: android.content.Context): android.content.Intent =
            android.content.Intent(context, McpServersActivity::class.java)
    }
}
