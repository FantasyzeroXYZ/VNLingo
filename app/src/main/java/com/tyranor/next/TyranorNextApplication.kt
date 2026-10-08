package com.tyranor.next

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.tyranor.next.core.diag.CrashLogWriter
import com.tyranor.next.core.game.storage.GameLibraryRepository
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.core.settings.PrefsRenameMigration
import com.tyranor.next.core.updater.BackgroundUpdateWorker
import com.tyranor.next.core.updater.UpdateNotificationManager

/** 在整个应用进入后台时安排一次静默更新检查。 */
class TyranorNextApplication : Application(), DefaultLifecycleObserver, Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super<Application>.onCreate()
        // 崩溃日志落盘（主进程与引擎子进程都经此处安装）：现场设备崩溃可凭
        // filesDir/crash/ 下的堆栈定位；必须最早安装，覆盖后续一切初始化崩溃。
        CrashLogWriter.install(this)
        // 运行时诊断日志（调试日志页「运行时日志」数据源）；引擎子进程同样安装
        com.core.diag.DiagLog.install(this)
        // 游玩会话制统计生命周期钩子
        com.tyranor.next.core.play.PlaySessionLifecycle.install(this)
        // 存档云通道（OnsSaveCloud）凭据跟随：自有 WebDAV 配置留空时回退到
        // 云同步中心的活动账户（engine 不反向依赖 app，此处注入凭据来源）
        com.core.ons.OnsSaveCloud.setWebDavCredentials {
            val manager = com.tyranor.next.core.sync.SyncManager(this)
            if (!manager.isConfigured) null
            else manager.config.let { arrayOf(it.serverUrl, it.username, it.password) }
        }
        // AI 助手「游玩统计」工具数据源（PlaySessionTracker 在 app 模块，
        // engine 不反向依赖 app，同样此处注入）
        com.core.ons.OnsAgentDialog.setPlayStatsProvider { gameName ->
            com.tyranor.next.core.play.AgentPlayStats.describe(this, gameName)
        }
        // 共享 prefs 文件更名（yukihub_prefs → tyranor_prefs）：所有进程（含引擎子进程）
        // 启动最早时机一次性迁移，必须先于任何 EngineSettingsStore/引擎偏好读取。
        PrefsRenameMigration.migrate(this)
        // 被删的旧 Winlator 设置实现遗留键清理（一次性，标记位保证只跑一次）。
        EngineSettingsStore.cleanupLegacyWinlatorKeys(this)
        UpdateNotificationManager.createChannel(this)
        // 游戏库 Room 迁移检查 + 首页缓存预热，避免主线程首次读库阻塞（迁移方案阶段 0）。
        GameLibraryRepository.init(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        BackgroundUpdateWorker.enqueue(this)
    }
}
