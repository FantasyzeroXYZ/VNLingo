package com.tyranor.next.ui.engine

import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.tyranor.next.R
import com.core.engine.runtime.GameRuntime
import com.core.nativeplugin.NativePluginInstallState
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.engine.external.ExternalEngineLauncher
import com.tyranor.next.core.engine.external.ExternalEngineModuleRegistry
import com.tyranor.next.core.game.launch.EngineLauncher
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.theme.AdvancedGlassSurfaceHigh
import com.tyranor.next.theme.glassShadow
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.theme.GlassSurfaceHigh
import com.tyranor.next.theme.GlassText
import com.tyranor.next.theme.GlassTextSecondary
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.glassNavBottomInset
import com.tyranor.next.ui.game.startActivityWithPageTransition
import com.tyranor.next.ui.settings.EngineSettingsActivity
import com.tyranor.next.ui.settings.artVersionOptions
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import androidx.compose.ui.graphics.Color
import android.widget.Toast

/** 引擎页：列表行展示已集成的游戏引擎；行点击直达该引擎设置（合并原引擎设置入口列表）。 */
@Composable
fun EngineScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val engineOpenDownloadFailedMessage = stringResource(R.string.engine_open_download_failed)
    val engines = EngineLauncher.supportedEngines
    var externalInstallStates by remember {
        mutableStateOf(refreshExternalInstallStates(context, engines))
    }
    var moduleStates by remember {
        mutableStateOf(refreshModuleStates(context))
    }
    var moduleDialogEngine by remember { mutableStateOf<EngineType?>(null) }
    // 可拆卸 Native 运行时实时状态（行状态与弹窗管理操作共用）；卸载确认按引擎挂起
    var runtimeStates by remember {
        mutableStateOf(refreshRuntimeStates(context))
    }
    var uninstallConfirmEngine by remember { mutableStateOf<EngineType?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        externalInstallStates = refreshExternalInstallStates(context, engines)
        moduleStates = refreshModuleStates(context)
        runtimeStates = refreshRuntimeStates(context)
    }

    Column(modifier.fillMaxSize()) {
        AppTopBar(title = stringResource(R.string.nav_engine))

        // 顶部分页（可在应用设置中关闭）：关闭时平铺展示全部引擎项
        val categorizeEngines by AppSettingsStore.engineTabsState.collectAsState()
        var selectedTab by remember { mutableIntStateOf(0) }
        if (categorizeEngines) {
            val tabs = listOf(
                stringResource(R.string.engine_tab_gal),
                stringResource(R.string.engine_tab_rpgm),
                stringResource(R.string.engine_tab_web),
            )
            MiuixSettingsTheme {
                val tabModifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
                if (AppThemeColors.isGlass) {
                    // 玻璃外观：Miuix TabRow 不支持指示器描边，改用自绘玻璃指示器（亮卡 + 0.5dp 描边）
                    GlassTabRow(
                        tabs = tabs,
                        selectedTabIndex = selectedTab,
                        onTabSelected = { selectedTab = it },
                        modifier = tabModifier,
                    )
                } else {
                    TabRow(
                        tabs = tabs,
                        selectedTabIndex = selectedTab,
                        onTabSelected = { selectedTab = it },
                        // 圆角与组件统一入口同源：默认 8dp
                        cornerRadius = AppComponentCornerRadius,
                        // 容器背景透明（只保留选中指示器）：Miuix 默认取 surface，深色下呈不透明黑块
                        colors = TabRowDefaults.tabRowColors(backgroundColor = Color.Transparent),
                        modifier = tabModifier,
                    )
                }
            }
        }

        val tabEngines = (if (categorizeEngines) {
            engines.filter { engineTabOf(it) == EngineTab.entries[selectedTab] }
        } else {
            engines
        }).distinctBy { engineDisplayName(it) }

        // 引擎列表
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp + glassNavBottomInset()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                items = tabEngines,
                key = { it.name },
                contentType = { "engine" },
            ) { engine ->
                val module = ExternalEngineModuleRegistry.moduleForEngine(engine)
                val runtimeState = nativeRuntimeByEngine[engine]?.let { runtimeStates[engine] }
                val installed = when {
                    module != null -> externalInstallStates[engine] == true
                    runtimeState != null -> runtimeState == NativePluginInstallState.INSTALLED_ENABLED
                    else -> true
                }
                val statusRes = when {
                    module != null ->
                        if (installed) R.string.engine_module_installed else R.string.engine_module_not_installed

                    runtimeState == NativePluginInstallState.INSTALLED_DISABLED -> R.string.engine_runtime_disabled
                    runtimeState == NativePluginInstallState.INSTALLED_ENABLED -> R.string.engine_runtime_enabled
                    runtimeState != null -> R.string.engine_runtime_not_installed
                    else -> R.string.engine_integrated
                }
                // 行点击直达引擎设置；无独立设置页的引擎（WebOther/VN 等）与
                // 状态图标点击沿用「版本条目/安装管理」弹窗
                val settingsKind = engineSettingsKindOf(engine)
                val canOpenDialog = module != null || engine in dialogOnlyEngines
                EngineRow(
                    engine = engine,
                    statusTextRes = statusRes,
                    installed = installed,
                    onClickEnabled = settingsKind != null || canOpenDialog,
                    onRowClick = {
                        if (settingsKind != null) {
                            startActivityWithPageTransition(
                                context,
                                EngineSettingsActivity.createIntent(context, settingsKind),
                            )
                        } else if (canOpenDialog) {
                            moduleDialogEngine = engine
                        }
                    },
                    onStatusClick = if (canOpenDialog) {
                        { moduleDialogEngine = engine }
                    } else {
                        null
                    },
                )
            }
        }
    }

    // 版本模块列表弹窗：复用「加入群聊」弹窗的 AppNavItem 条目，展示该引擎各版本
    // 模块的安装状态；未安装的条目点击直达对应下载页。
    moduleDialogEngine?.let { dialogEngine ->
        AppAlertDialog(
            onDismissRequest = { moduleDialogEngine = null },
            title = {
                Text(
                    stringResource(R.string.engine_list_title, engineDisplayName(dialogEngine)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Column(
                    // 条目较多（如 Artemis 全版本）时超出弹窗高度上限，需可滚动避免末条被截断
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    engineDialogEntries(context, moduleStates, dialogEngine).forEach { entry ->
                        AppNavItem(
                            title = entry.title,
                            summary = stringResource(entry.summaryRes),
                            leadingIcon = R.drawable.ic_engine_chip,
                            containerColor = DialogItemSurface,
                        ) {
                            if (!entry.installed && entry.installUrl != null) {
                                val opened = ExternalEngineLauncher.openInstallPage(context, entry.installUrl)
                                if (!opened) {
                                    Toast.makeText(context, engineOpenDownloadFailedMessage, Toast.LENGTH_SHORT).show()
                                }
                            }
                            moduleDialogEngine = null
                        }
                    }

                    // 可拆卸 Native 运行时管理：启停 + 卸载（卸载需确认，恢复走重新下载/导入）
                    nativeRuntimeByEngine[dialogEngine]?.let { runtime ->
                        val currentState = runtimeStates[dialogEngine] ?: runtime.installState(context)
                        if (currentState == NativePluginInstallState.INSTALLED_ENABLED ||
                            currentState == NativePluginInstallState.INSTALLED_DISABLED
                        ) {
                            val disabled = currentState == NativePluginInstallState.INSTALLED_DISABLED
                            AppNavItem(
                                title = stringResource(
                                    if (disabled) R.string.engine_runtime_action_toggle_on
                                    else R.string.engine_runtime_action_toggle_off,
                                ),
                                summary = stringResource(
                                    if (disabled) R.string.engine_runtime_disabled
                                    else R.string.engine_runtime_enabled,
                                ),
                                leadingIcon = R.drawable.ic_engine_chip,
                                containerColor = DialogItemSurface,
                            ) {
                                runtime.setEnabled(context, disabled)
                                runtimeStates = refreshRuntimeStates(context)
                            }
                            AppNavItem(
                                title = stringResource(R.string.engine_runtime_action_uninstall),
                                summary = stringResource(R.string.engine_runtime_uninstall_summary),
                                leadingIcon = R.drawable.ic_engine_chip,
                                containerColor = DialogItemSurface,
                            ) {
                                uninstallConfirmEngine = dialogEngine
                            }
                        }
                    }
                }
            },
            // 不放取消按钮：点击条目或遮罩即关闭（confirmButton 槽位必填，传空）
            confirmButton = {},
        )
    }

    // 运行时卸载确认：删除实体并落 removed 用户意图（引导期不再自动还原），恢复走重新下载/导入
    uninstallConfirmEngine?.let { confirmEngine ->
        AppAlertDialog(
            onDismissRequest = { uninstallConfirmEngine = null },
            title = {
                Text(
                    stringResource(R.string.engine_runtime_action_uninstall),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    stringResource(R.string.engine_runtime_uninstall_confirm, engineDisplayName(confirmEngine)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    nativeRuntimeByEngine[confirmEngine]?.uninstall(context)
                    Toast.makeText(
                        context,
                        context.getString(R.string.engine_runtime_uninstalled, engineDisplayName(confirmEngine)),
                        Toast.LENGTH_SHORT,
                    ).show()
                    runtimeStates = refreshRuntimeStates(context)
                    uninstallConfirmEngine = null
                    moduleDialogEngine = null
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { uninstallConfirmEngine = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun EngineRow(
    engine: EngineType,
    @StringRes statusTextRes: Int,
    installed: Boolean,
    onClickEnabled: Boolean,
    onRowClick: () -> Unit,
    onStatusClick: (() -> Unit)?,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClickEnabled, onClick = onRowClick)
            .glassShadow()
            .glassBorder(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = NavWhite),
        shape = AppComponentShape,
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_engine_icon),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                modifier = Modifier.size(28.dp),
            )
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(
                    engineDisplayName(engine),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    engineDescription(engine),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            val statusDescription = stringResource(statusTextRes)
            Icon(
                if (installed) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                contentDescription = statusDescription,
                tint = if (installed) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                // 状态图标 = 版本条目/安装管理弹窗入口（扩大触区到 ~40dp）
                modifier = Modifier
                    .clip(AppComponentShape)
                    .clickable(enabled = onStatusClick != null) { onStatusClick?.invoke() }
                    .padding(6.dp)
                    .size(20.dp),
            )
        }
    }
}

/**
 * 运行时管理卡片（KRKR/ONS/Artemis 引擎设置页顶部）：安装状态 + 启用开关 + 卸载。
 * 启停/卸载即 [GameRuntime.setEnabled]/[GameRuntime.uninstall]（卸载删除实体并标记
 * removed，引导期不再自动还原）；未安装时提示回引擎列表重新下载（行尾状态图标）。
 */
@Composable
internal fun NativeRuntimeManageCard(engine: EngineType) {
    val context = LocalContext.current
    val runtime = nativeRuntimeByEngine[engine] ?: return
    var state by remember { mutableStateOf(runtime.installState(context)) }
    var confirmUninstall by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        state = runtime.installState(context)
    }

    val installed = state == NativePluginInstallState.INSTALLED_ENABLED ||
        state == NativePluginInstallState.INSTALLED_DISABLED
    val statusRes = when (state) {
        NativePluginInstallState.INSTALLED_ENABLED -> R.string.engine_runtime_enabled
        NativePluginInstallState.INSTALLED_DISABLED -> R.string.engine_runtime_disabled
        else -> R.string.engine_runtime_not_installed
    }

    MiuixCard(
        modifier = Modifier.fillMaxWidth().glassShadow().glassBorder(),
        cornerRadius = AppComponentCornerRadius,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                stringResource(R.string.engine_runtime_manage_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (state == NativePluginInstallState.INSTALLED_ENABLED) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Filled.Cancel
                    },
                    contentDescription = stringResource(statusRes),
                    tint = if (state == NativePluginInstallState.INSTALLED_ENABLED) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    stringResource(statusRes),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                Switch(
                    checked = state == NativePluginInstallState.INSTALLED_ENABLED,
                    enabled = installed,
                    onCheckedChange = { enabled ->
                        runtime.setEnabled(context, enabled)
                        state = runtime.installState(context)
                    },
                )
            }
            if (!installed) {
                Text(
                    stringResource(R.string.engine_runtime_manage_hint_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                TextButton(onClick = { confirmUninstall = true }) {
                    Text(
                        stringResource(R.string.engine_runtime_action_uninstall),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (confirmUninstall) {
        AppAlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = {
                Text(
                    stringResource(R.string.engine_runtime_action_uninstall),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(stringResource(R.string.engine_runtime_uninstall_confirm, engine.displayName))
            },
            confirmButton = {
                TextButton(onClick = {
                    runtime.uninstall(context)
                    state = runtime.installState(context)
                    confirmUninstall = false
                    Toast.makeText(
                        context,
                        context.getString(R.string.engine_runtime_uninstalled, engine.displayName),
                        Toast.LENGTH_SHORT,
                    ).show()
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/**
 * 玻璃外观专用 TabRow：Miuix TabRow 的选中指示器不支持描边，
 * 这里自绘「玻璃亮卡（GlassSurfaceHigh）+ 0.5dp 玻璃描边」指示器，
 * 圆角与 [AppComponentCornerRadius] 同源。
 */
@Composable
private fun GlassTabRow(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    val shape = RoundedCornerShape(AppComponentCornerRadius)
    val spacing = 6.dp
    BoxWithConstraints(modifier.fillMaxWidth().height(42.dp)) {
        val tabWidth = (maxWidth - spacing * (tabs.size - 1)) / tabs.size
        val indicatorOffset by animateDpAsState(
            targetValue = (tabWidth + spacing) * selectedTabIndex,
            animationSpec = tween(durationMillis = 200, easing = LinearEasing),
            label = "glassTabIndicator",
        )
        // 指示器绘制在底层，文字行覆盖其上
        Box(
            Modifier
                .offset(x = indicatorOffset)
                .width(tabWidth)
                .fillMaxHeight()
                .clip(shape)
                .background(if (AppThemeColors.isAdvancedGlass) AdvancedGlassSurfaceHigh else GlassSurfaceHigh)
                .glassBorder(shape = shape),
        )
        Row(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, label ->
                val selected = index == selectedTabIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .clickable { onTabSelected(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = if (selected) GlassText else GlassTextSecondary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 引擎页顶部分页（顺序即展示顺序）：GAL / RPGM / 网页。 */
private enum class EngineTab { GAL, RPGM, WEB }

private fun engineTabOf(engine: EngineType): EngineTab = when (engine) {
    EngineType.RPGMAKER, EngineType.RPG_MV, EngineType.RPG_MZ -> EngineTab.RPGM
    // 已裁剪引擎（Siglus/RealLive/AVG32/UK2/FVP/YURIS/CatSystem2/PC/PSP/NS）不会出现在
    // supportedEngines，此分支仅为 when 穷举保留
    EngineType.KIRIKIRI, EngineType.ONS, EngineType.ARTEMIS, EngineType.SIGLUS,
    EngineType.REALLIVE, EngineType.AVG32, EngineType.UK2, EngineType.FVP,
    EngineType.RENPY, EngineType.YURIS, EngineType.CATSYSTEM2,
    EngineType.PSP, EngineType.NINTENDO_SWITCH, EngineType.PC -> EngineTab.GAL
    EngineType.TYRANO, EngineType.WEB_OTHER, EngineType.VN -> EngineTab.WEB
    EngineType.UNKNOWN -> EngineTab.WEB
}

/** 列表展示名：RPG Maker MV 与 MZ、WebOther 与 VN 各合并为一项。 */
private fun engineDisplayName(engine: EngineType): String = when (engine) {
    EngineType.RPG_MV, EngineType.RPG_MZ -> "RPG Maker MV/MZ"
    EngineType.WEB_OTHER, EngineType.VN -> "WebOther/VN"
    else -> engine.displayName
}

@Composable
private fun engineDescription(engine: EngineType): String = when (engine) {
    EngineType.KIRIKIRI -> stringResource(R.string.engine_desc_kirikiri)
    EngineType.ONS -> stringResource(R.string.engine_desc_ons)
    EngineType.TYRANO -> stringResource(R.string.engine_desc_tyrano)
    EngineType.RPGMAKER -> stringResource(R.string.engine_desc_rpgmaker)
    EngineType.RPG_MV, EngineType.RPG_MZ -> stringResource(R.string.engine_desc_rpg_mv_mz)
    EngineType.VN, EngineType.WEB_OTHER -> stringResource(R.string.engine_desc_web_other_vn)
    EngineType.ARTEMIS -> stringResource(R.string.engine_desc_artemis)
    EngineType.SIGLUS -> stringResource(R.string.engine_desc_siglus)
    EngineType.REALLIVE -> stringResource(R.string.engine_desc_reallive)
    EngineType.AVG32 -> stringResource(R.string.engine_desc_avg32)
    EngineType.UK2 -> stringResource(R.string.engine_desc_uk2)
    EngineType.FVP -> stringResource(R.string.engine_desc_fvp)

    EngineType.YURIS -> stringResource(R.string.engine_desc_yuris)
    EngineType.CATSYSTEM2 -> stringResource(R.string.engine_desc_cs2)
    EngineType.PC -> stringResource(R.string.engine_desc_pc)
    EngineType.RENPY -> stringResource(R.string.engine_desc_renpy)
    EngineType.PSP -> stringResource(R.string.engine_desc_psp)
    EngineType.NINTENDO_SWITCH -> stringResource(R.string.engine_desc_nintendo_switch)
    EngineType.UNKNOWN -> stringResource(R.string.engine_desc_unknown)
}

/** 可拆卸 Native 运行时 ↔ 引擎类型映射：行状态与启停/卸载操作的数据源。
 *  Web 资源包运行时（rpgmv overlay）宿主尚未接入 WebRuntimeAssets，暂保持「已集成」展示。 */
private val nativeRuntimeByEngine: Map<EngineType, GameRuntime> = mapOf(
    EngineType.KIRIKIRI to GameRuntime.KIRIKIROID2,
    EngineType.ONS to GameRuntime.ONS,
    EngineType.ARTEMIS to GameRuntime.ARTEMIS,
)

/** 引擎行 → 细分设置页；无独立设置页的引擎返回 null（行点击走版本弹窗）。 */
private fun engineSettingsKindOf(engine: EngineType): EngineSettingsKind? = when (engine) {
    EngineType.KIRIKIRI -> EngineSettingsKind.KRKR
    EngineType.ONS -> EngineSettingsKind.ONS
    EngineType.ARTEMIS -> EngineSettingsKind.ARTEMIS
    EngineType.RPGMAKER, EngineType.RPG_MV, EngineType.RPG_MZ -> EngineSettingsKind.RPG_MAKER
    EngineType.TYRANO -> EngineSettingsKind.TYRANO
    EngineType.RENPY -> EngineSettingsKind.RENPY
    else -> null
}

/** 各 Native 运行时的实时安装状态（行状态与弹窗管理操作共用）。 */
private fun refreshRuntimeStates(context: android.content.Context): Map<EngineType, NativePluginInstallState> =
    nativeRuntimeByEngine.keys.associateWith { engine ->
        nativeRuntimeByEngine[engine]?.installState(context) ?: NativePluginInstallState.NOT_INSTALLED
    }

private fun refreshExternalInstallStates(
    context: android.content.Context,
    engines: List<EngineType>,
): Map<EngineType, Boolean> =
    engines.mapNotNull { engine ->
        // 安装状态与下载/启动一致：Ren'Py 按全局版本解析目标模块（而非「任一版本已装」）
        val module = ExternalEngineModuleRegistry.resolveModule(
            engine,
            EngineSettingsStore.getRenpyVersion(context),
        ) ?: return@mapNotNull null
        engine to ExternalEngineLauncher.isPackageInstalled(context, module)
    }.toMap()

/** 内置 Tyranor 引擎版本名（Tyrano / WebOther/VN / RPG Maker MV/MZ 的网页壳共用）。 */
private const val TYRANOR_BUILTIN_VERSION = "Tyranor-2.3.4"

/** 内置版本条目：恒「已集成」，无下载动作。 */
private fun builtinEntry(id: String, title: String): EngineDialogEntry =
    EngineDialogEntry(id = id, title = title, summaryRes = R.string.engine_integrated, installed = true)

private val tyranorWebBuiltin = listOf(builtinEntry("tyranor-builtin", TYRANOR_BUILTIN_VERSION))

/** RPG Maker MV/MZ 弹窗条目：内置网页壳 + TyranorNext 系列（0.1/0.2 合并显示）。 */
private val rpgMakerWebBuiltin = listOf(
    builtinEntry("tyranor-builtin", TYRANOR_BUILTIN_VERSION),
    builtinEntry("tyranornext-01-02", "TyranorNext-0.1/0.2"),
)

/** 各内置引擎的「版本条目」弹窗内容（条目顺序即展示顺序）。 */
private val builtinDialogEntries: Map<EngineType, List<EngineDialogEntry>> = mapOf(
    EngineType.TYRANO to tyranorWebBuiltin,
    EngineType.WEB_OTHER to tyranorWebBuiltin,
    EngineType.VN to tyranorWebBuiltin,
    EngineType.RPG_MV to rpgMakerWebBuiltin,
    EngineType.RPG_MZ to rpgMakerWebBuiltin,
    EngineType.ONS to listOf(
        builtinEntry("ons-builtin", "ONScripter-0.7.7"),
    ),
    EngineType.KIRIKIRI to listOf(
        builtinEntry("krkr-139", "Kirikiroid2-1.3.9"),
        builtinEntry("krkr-134", "Kirikiroid2-1.3.4"),
        builtinEntry("krkr-126", "Kirikiroid2-1.2.6"),
        builtinEntry("krkr-sdl3", "Krkrsdl3-tyn"),
    ),
)

/** 无外置模块、但点击仍展示「版本条目」弹窗的引擎（Artemis 的版本列表来自引擎设置）。 */
private val dialogOnlyEngines: Set<EngineType> = builtinDialogEntries.keys + EngineType.ARTEMIS

/** 弹窗条目：标题 + 状态文案 + 可选下载地址。 */
private data class EngineDialogEntry(
    val id: String,
    val title: String,
    @get:StringRes val summaryRes: Int,
    val installed: Boolean,
    val installUrl: String? = null,
)

/** 弹窗条目列表：外置引擎取注册表模块；Artemis 列引擎设置中的内置版本；其余内置引擎见 [builtinDialogEntries]。 */
@Composable
private fun engineDialogEntries(
    context: android.content.Context,
    moduleStates: Map<String, Boolean>,
    engine: EngineType,
): List<EngineDialogEntry> {
    val modules = ExternalEngineModuleRegistry.modules.filter { it.engine == engine }
    if (modules.isNotEmpty()) {
        return modules.sortedBy { it.displayName }.map { module ->
            val installed = moduleStates[module.id] == true
            EngineDialogEntry(
                id = module.id,
                title = module.displayName(context),
                summaryRes = if (installed) R.string.engine_module_installed else R.string.engine_module_not_installed,
                installed = installed,
                installUrl = module.installUrl,
            )
        }
    }
    return when {
        engine == EngineType.ARTEMIS -> artemisDialogEntries()
        engine in builtinDialogEntries -> builtinDialogEntries[engine].orEmpty()
        else -> emptyList()
    }
}

/** Artemis 弹窗条目：与引擎设置的版本选项同源同序（去掉 auto，非具体版本），全部为内置版本。 */
@Composable
private fun artemisDialogEntries(): List<EngineDialogEntry> =
    artVersionOptions()
        .filterNot { it.first == EngineSettingsStore.ART_ENGINE_AUTO }
        .map { (key, label) ->
            EngineDialogEntry(
                id = "artemis-$key",
                title = artemisDialogTitle(label),
                summaryRes = R.string.engine_integrated,
                installed = true,
            )
        } + EngineDialogEntry(
            // 自研 clean-room 内核：随插件包内置，与官方 revision 同列为「已集成」
            id = "artemis-${EngineSettingsStore.ART_KERNEL_CLEAN}",
            title = "TyranorNext/artemis-compat",
            summaryRes = R.string.engine_integrated,
            installed = true,
        )

/** 「v1（Tyranor/Rev.2762）」→「Tyranor/Rev.2762」：去掉内部版本号与全角括号；不匹配时原样返回。 */
private fun artemisDialogTitle(label: String): String =
    Regex("""^[vV]\d+（(.+)）$""").find(label)?.groupValues?.get(1) ?: label

/** 各外置模块的独立安装状态（按 module id，与行图标的全局版本解析互不影响）。 */
private fun refreshModuleStates(context: android.content.Context): Map<String, Boolean> =
    ExternalEngineModuleRegistry.modules.associate { module ->
        module.id to ExternalEngineLauncher.isPackageInstalled(context, module)
    }
