package com.core.engine.runtime

import android.content.Context
import java.io.File
import com.core.nativeplugin.NativePluginConstants
import com.core.nativeplugin.NativePluginInstallState
import com.core.nativeplugin.NativePluginManager

/**
 * 可拆卸游戏运行时目录（数据驱动，扩展点）：
 * 三类运行时（Native 插件 / Web 注入资源包 / 未来新增环境）统一在此登记，
 * 新增环境只需追加枚举项并实现 install/uninstall 委托。
 *
 * 用户意图模型：removed（用户显式卸载）→ 引导期不再自动还原 bundled 资产，
 * 启动该类游戏时返回 NotInstalled 由 UI 引导重新下载/恢复。
 */
enum class GameRuntime(
    val id: String,
    val kind: Kind,
) {
    KIRIKIROID2("kirikiroid2", Kind.NATIVE_PLUGIN),
    ONS("ons", Kind.NATIVE_PLUGIN),
    ARTEMIS("artemis", Kind.NATIVE_PLUGIN),
    WEB_RPGMV_V1("web_rpgmv_v1", Kind.WEB_ASSETS),
    ;

    enum class Kind { NATIVE_PLUGIN, WEB_ASSETS }

    companion object {
        fun byId(id: String): GameRuntime? = entries.firstOrNull { it.id == id }

        fun byNativeEngineId(engineId: String): GameRuntime? = when (engineId) {
            NativePluginConstants.ENGINE_KIRIKIROID2 -> KIRIKIROID2
            NativePluginConstants.ENGINE_ONS -> ONS
            NativePluginConstants.ENGINE_ARTEMIS -> ARTEMIS
            else -> null
        }
    }

    /** 用户意图与状态存取（prefs 键前缀 game_runtime.<id>.*）。 */
    object Store {
        private fun prefs(context: Context) =
            context.getSharedPreferences("game_runtime", Context.MODE_PRIVATE)

        fun isRemoved(context: Context, runtime: GameRuntime): Boolean =
            prefs(context).getBoolean(key(runtime, "removed"), false)

        fun setRemoved(context: Context, runtime: GameRuntime, removed: Boolean) {
            prefs(context).edit().putBoolean(key(runtime, "removed"), removed).apply()
        }

        private fun key(runtime: GameRuntime, suffix: String) =
            "game_runtime.${runtime.id}.$suffix"
    }

    // ─── Native 插件状态/操作委托 ─────────────────────────────────────

    fun installState(context: Context): NativePluginInstallState = when (this) {
        KIRIKIROID2 -> NativePluginManager.kirikiroid2InstallState(context)
        ONS -> NativePluginManager.onsInstallState(context)
        ARTEMIS -> NativePluginManager.artemisInstallState(context)
        WEB_RPGMV_V1 -> if (webOverlayDir(context).resolve("js/rpg_core.js").isFile) {
            NativePluginInstallState.INSTALLED_ENABLED
        } else {
            NativePluginInstallState.NOT_INSTALLED
        }
    }

    /** 卸载：删除实体并标记用户意图（引导期不再自动还原）。 */
    fun uninstall(context: Context) {
        when (this) {
            KIRIKIROID2 -> NativePluginManager.deleteKirikiroid2(context)
            ONS -> NativePluginManager.deleteOns(context)
            ARTEMIS -> NativePluginManager.deleteArtemis(context)
            WEB_RPGMV_V1 -> webOverlayDir(context).deleteRecursively()
        }
        Store.setRemoved(context, this, true)
    }

    /** 恢复用户意图为已安装（下载安装/手动导入完成后调用）。 */
    fun markRestored(context: Context) {
        Store.setRemoved(context, this, false)
    }

    /** 用户是否显式卸载（引擎页操作可用性判断用）。 */
    fun isRemoved(context: Context): Boolean = Store.isRemoved(context, this)

    /**
     * 启用/停用（仅 Native 插件有停用语义，停用后启动预检返回 Disabled 而非静默重启）。
     * Web 资源包无停用语义，返回 false。
     */
    fun setEnabled(context: Context, enabled: Boolean): Boolean = when (this) {
        KIRIKIROID2 -> { NativePluginManager.setKirikiroid2Enabled(context, enabled); true }
        ONS -> { NativePluginManager.setOnsEnabled(context, enabled); true }
        ARTEMIS -> { NativePluginManager.setArtemisEnabled(context, enabled); true }
        WEB_RPGMV_V1 -> false
    }

    /** Web 注入资源外置目录：filesDir/runtimes/web/<id>/。 */
    fun webOverlayDir(context: Context): File =
        File(File(context.filesDir, "runtimes/web"), id)
}
