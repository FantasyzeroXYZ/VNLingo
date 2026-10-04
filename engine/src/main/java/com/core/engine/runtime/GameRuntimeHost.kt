package com.core.engine.runtime

import android.content.Context
import com.core.nativeplugin.NativePluginInstallState

/**
 * 游戏运行时宿主 SPI：统一「可拆卸运行时」生命周期契约——
 * 状态查询、卸载、启停、zip 安装。引擎页 UI 与启动预检基于本接口工作，
 * 扩展新运行环境时实现接口 + 在 GameRuntime 枚举注册即可，无需改调用方。
 *
 * 内置实现：
 * - [NativePluginRuntimeHost]：kirikiroid2/ons/artemis 原生 .so 插件
 * - [WebAssetRuntimeHost]：Web 注入资源包（rpgmv-v1 overlay 等）
 *
 * 外置 APK 模块与外置模拟器跳转维持既有独立体系（包名探测 + intent），
 * 不在本 SPI 管辖内。
 */
internal interface GameRuntimeHost {

    /** 运行时标识（与 GameRuntime.id 一致）。 */
    val id: String

    /** 当前安装状态。 */
    fun installState(context: Context): NativePluginInstallState

    /** 卸载实体并落用户意图（removed=true，引导期不再自动还原）。 */
    fun uninstall(context: Context)

    /** 启用/禁用（无禁用语义的实现返回 false）。 */
    fun setEnabled(context: Context, enabled: Boolean): Boolean = false

    /** 从 zip 字节安装（下载或导入通道，实现自行校验与落意图）。 */
    fun installFromZip(context: Context, bytes: ByteArray): Boolean = false
}

/** Native 插件实现：委托 GameRuntime（状态/删除/启停统一在目录层）。 */
internal class NativePluginRuntimeHost(
    override val id: String,
) : GameRuntimeHost {

    private val runtime = GameRuntime.byId(id)
        ?: error("unknown native runtime id: $id")

    override fun installState(context: Context): NativePluginInstallState = runtime.installState(context)

    override fun uninstall(context: Context) = runtime.uninstall(context)

    override fun setEnabled(context: Context, enabled: Boolean): Boolean = runtime.setEnabled(context, enabled)
}

/** Web 注入资源实现：状态/卸载委托 GameRuntime，zip 安装委托 WebRuntimeAssets。 */
internal class WebAssetRuntimeHost(
    override val id: String,
) : GameRuntimeHost {

    private val runtime = GameRuntime.byId(id)
        ?: error("unknown web runtime id: $id")

    override fun installState(context: Context): NativePluginInstallState = runtime.installState(context)

    override fun uninstall(context: Context) = runtime.uninstall(context)

    override fun installFromZip(context: Context, bytes: ByteArray): Boolean =
        WebRuntimeAssets.installFromZip(context, runtime, bytes)
}

/** 宿主注册表：按 id 取 SPI 实例。 */
internal object GameRuntimeHosts {

    private val nativeIds = setOf("kirikiroid2", "ons", "artemis")

    fun forId(id: String): GameRuntimeHost? = when {
        id in nativeIds -> NativePluginRuntimeHost(id)
        id == "web_rpgmv_v1" -> WebAssetRuntimeHost(id)
        else -> null
    }
}
