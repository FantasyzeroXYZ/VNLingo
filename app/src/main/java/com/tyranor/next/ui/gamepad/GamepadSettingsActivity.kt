package com.tyranor.next.ui.gamepad

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.core.engine.GamepadRemap
import com.core.engine.VirtualMouseBindings
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
 * 手柄设置页（应用设置进入）：手柄按钮重映射 + 虚拟鼠标按键绑定。
 *
 * - 手柄重映射：把手柄按钮替换为键盘按键或其他手柄按键，由 engine 的
 *   [GamepadRemap] 在各引擎宿主 dispatchKeyEvent 最前端消费。
 * - 虚拟鼠标：为 [com.core.engine.EngineVirtualMouse] 的五个动作（上/下/左/右/
 *   确认）绑定任意按键（手柄或键盘，统一 keyCode、不区分来源），由
 *   [VirtualMouseBindings] 持久化；方向键长按连续移动，默认确认 = 手柄 A /
 *   键盘回车 / DPAD_CENTER。
 *
 * 交互均为「按下捕获」：点击行进入捕获态，随后按下的第一个按键即生效，
 * 返回键取消；捕获在本 Activity 的 dispatchKeyEvent 实现（Compose 无法
 * 可靠收到手柄按键）。
 */
class GamepadSettingsActivity : AppScreenActivity() {

    /** 捕获态：非空表示正在为该源按钮等待目标按键（手柄重映射）。 */
    private var captureSource by mutableStateOf<Int?>(null)

    /** 捕获态：非空表示正在为该虚拟鼠标动作等待绑定按键。 */
    private var captureVmAction by mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GamepadRemap.refresh(this)
        setAppScreenContent {
            GamepadSettingsScreen(
                captureSource = captureSource,
                onCaptureStart = { captureSource = it },
                captureVmAction = captureVmAction,
                onVmCaptureStart = { captureVmAction = it },
                onCaptureCancel = { captureSource = null; captureVmAction = null },
                onClear = { src ->
                    GamepadRemap.clear(this, src)
                    Toast.makeText(this, R.string.gamepad_cleared, Toast.LENGTH_SHORT).show()
                },
                onVmBind = { action, keyCode ->
                    VirtualMouseBindings.addBinding(this, action, keyCode)
                    Toast.makeText(this, R.string.gamepad_mapped, Toast.LENGTH_SHORT).show()
                },
                onVmRemoveKey = { action, keyCode ->
                    VirtualMouseBindings.removeBinding(this, action, keyCode)
                    Toast.makeText(this, R.string.gamepad_cleared, Toast.LENGTH_SHORT).show()
                },
                onVmReset = { action ->
                    VirtualMouseBindings.resetAction(this, action)
                    Toast.makeText(this, R.string.gamepad_cleared, Toast.LENGTH_SHORT).show()
                },
            )
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val src = captureSource
        val vmAction = captureVmAction
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && (src != null || vmAction != null)) {
                captureSource = null
                captureVmAction = null
                return true
            }
            if (src != null) {
                val isGamepad = (event.source and android.view.InputDevice.SOURCE_GAMEPAD) != 0 ||
                    (event.source and android.view.InputDevice.SOURCE_JOYSTICK) != 0
                GamepadRemap.setMapping(this, src, event.keyCode, isGamepad)
                captureSource = null
                Toast.makeText(this, R.string.gamepad_mapped, Toast.LENGTH_SHORT).show()
                return true
            }
            if (vmAction != null) {
                VirtualMouseBindings.addBinding(this, vmAction, event.keyCode)
                captureVmAction = null
                Toast.makeText(this, R.string.gamepad_mapped, Toast.LENGTH_SHORT).show()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        fun createIntent(context: android.content.Context): android.content.Intent =
            android.content.Intent(context, GamepadSettingsActivity::class.java)
    }
}

@Composable
private fun GamepadSettingsScreen(
    captureSource: Int?,
    onCaptureStart: (Int) -> Unit,
    captureVmAction: Int?,
    onVmCaptureStart: (Int) -> Unit,
    onCaptureCancel: () -> Unit,
    onClear: (Int) -> Unit,
    onVmBind: (Int, Int) -> Unit,
    onVmRemoveKey: (Int, Int) -> Unit,
    onVmReset: (Int) -> Unit,
) {
    val context = LocalContext.current
    // mappings 读取在重组期（低频设置页，数据量小），捕获态变化触发刷新
    val mappings = remember(captureSource, captureVmAction) { GamepadRemap.mappings(context) }
    val vmBindings = remember(captureSource, captureVmAction) { VirtualMouseBindings.bindings(context) }
    val vmActions = remember(captureSource, captureVmAction) {
        listOf(
            VirtualMouseBindings.ACTION_UP to R.string.vm_action_up,
            VirtualMouseBindings.ACTION_DOWN to R.string.vm_action_down,
            VirtualMouseBindings.ACTION_LEFT to R.string.vm_action_left,
            VirtualMouseBindings.ACTION_RIGHT to R.string.vm_action_right,
            VirtualMouseBindings.ACTION_CONFIRM to R.string.vm_action_confirm,
        )
    }

    MiuixSettingsTheme {
        MiuixScaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = ComposeColor.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = { AppTopBar(title = stringResource(R.string.gamepad_settings_title)) },
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
                            stringResource(R.string.gamepad_settings_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
                items(GamepadRemap.mappableButtons, key = { "gp_" + it.first }) { (srcCode, srcName) ->
                    val mapping = mappings[srcCode]
                    MiuixCard(
                        modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
                        cornerRadius = AppComponentCornerRadius,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    srcName,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val mappingText = if (mapping == null) {
                                    stringResource(R.string.gamepad_unmapped)
                                } else {
                                    val target: Int = mapping.first
                                    val isGamepad: Boolean = mapping.second
                                    stringResource(
                                        if (isGamepad) R.string.gamepad_target_gamepad else R.string.gamepad_target_keyboard,
                                        GamepadRemap.targetName(target, isGamepad),
                                    )
                                }
                                Text(
                                    mappingText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            TextButton(onClick = { onCaptureStart(srcCode) }) {
                                Text(stringResource(R.string.gamepad_remap_action))
                            }
                            if (mapping != null) {
                                TextButton(onClick = { onClear(srcCode) }) {
                                    Text(
                                        stringResource(R.string.common_delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    MiuixCard(
                        modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
                        cornerRadius = AppComponentCornerRadius,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(
                                stringResource(R.string.vm_bindings_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                stringResource(R.string.vm_bindings_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                items(vmActions, key = { "vm_" + it.first }) { (action, nameRes) ->
                    val keys = vmBindings[action] ?: emptyList()
                    MiuixCard(
                        modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
                        cornerRadius = AppComponentCornerRadius,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(nameRes),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                TextButton(onClick = { onVmCaptureStart(action) }) {
                                    Text(stringResource(R.string.gamepad_remap_action))
                                }
                            }
                            if (keys.isEmpty()) {
                                Text(
                                    stringResource(R.string.gamepad_unmapped),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    keys.forEach { keyCode ->
                                        TextButton(onClick = { onVmRemoveKey(action, keyCode) }) {
                                            Text(
                                                VirtualMouseBindings.keyName(keyCode) + " ×",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                }
                                Text(
                                    stringResource(R.string.gamepad_capture_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            TextButton(onClick = { onVmReset(action) }) {
                                Text(
                                    stringResource(R.string.vm_reset_default),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (captureSource != null || captureVmAction != null) {
        // 捕获遮罩：全屏半透明黑，按键由 Activity.dispatchKeyEvent 捕获（BACK 取消）
        val captureName = if (captureSource != null) {
            GamepadRemap.targetName(captureSource, true)
        } else {
            stringResource(
                vmActions.firstOrNull { it.first == captureVmAction }?.second ?: R.string.vm_action_confirm,
            )
        }
        Box(
            modifier = Modifier.fillMaxSize().background(ComposeColor(0xCC000000)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.gamepad_capture_title, captureName),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ComposeColor.White,
                )
                Text(
                    stringResource(R.string.gamepad_capture_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ComposeColor(0xFFCBD5E1),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
