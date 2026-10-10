package com.tyranor.next.ui.game

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.tyranor.next.R
import com.tyranor.next.core.game.launch.EngineLauncher
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.game.shortcut.GameShortcutManager
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.ui.common.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面快捷方式回跳蹦床。
 *
 * 该 Activity 是 `ShortcutInfo.setIntent` 的目标，由桌面 Launcher 以显式 Intent 跨进程启动，
 * 因此 `exported=true` 是**必需**的（改为 false 会触发 Permission Denial），请勿回退。
 * 入参 `EXTRA_SHORTCUT_ID` 为库内游戏 uri 的 SHA-256，可通过 [GameShortcutActivity.createIntent]
 * 重新解析库内游戏并启动，必要时先做 Artemis 补丁确认。
 */
class GameShortcutActivity : ComponentActivity() {
    private val localizedContext: Context
        get() = AppLocaleController.wrap(this)

    /** Resolves the shortcut ID, confirms Artemis patch policy, and launches the game. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 配置变更/进程重建后继续（弹窗会重新出现），不要静默 finish，否则用户点快捷方式无反馈
        val shortcutId = intent.getStringExtra(EXTRA_SHORTCUT_ID)
        if (shortcutId.isNullOrBlank()) {
            showUnavailableAndFinish()
            return
        }

        lifecycleScope.launch {
            try {
                // 标题来源：① 快捷方式 intent 自带的 extra（新装快捷方式）；
                // ② 已固定快捷方式的 label（旧快捷方式也查得到，用于 uri 变化后的兜底）。
                val intentTitle = intent.getStringExtra(EXTRA_SHORTCUT_TITLE)
                val pinnedLabel = withContext(Dispatchers.IO) { pinnedShortcutLabel(shortcutId) }
                val shortcutTitle = intentTitle?.takeIf { it.isNotBlank() } ?: pinnedLabel
                val game = withContext(Dispatchers.IO) {
                    val games = GameLibraryFacade.loadGames(applicationContext)
                    games.firstOrNull { GameShortcutManager.shortcutId(it.uri) == shortcutId }
                        // 兜底：游戏目录被移动/重命名后库内 uri 会变，旧快捷方式 hash 失效——
                        // 之前表现正是「点快捷方式弹提示后回到桌面」（用户反馈的偶发启动退出桌面）。
                        // 用标题（intent extra 或快捷方式 label）找回同一个游戏。
                        ?: shortcutTitle?.takeIf { it.isNotBlank() }?.let { title ->
                            games.firstOrNull { it.title.trim() == title.trim() }
                        }
                }
                if (game == null) {
                    Log.w(TAG, "shortcut unresolved id=$shortcutId title=$shortcutTitle pinned=$pinnedLabel")
                    showUnavailable()
                    return@launch
                }
                if (GameShortcutManager.shortcutId(game.uri) != shortcutId) {
                    Log.i(TAG, "shortcut resolved by title fallback id=$shortcutId -> uri=${game.uri}")
                }

                val needsArtemisPatchConfirm = withContext(Dispatchers.IO) {
                    EngineLauncher.needsArtemisPatchConfirm(applicationContext, game)
                }
                val patchChoice = if (needsArtemisPatchConfirm) {
                    awaitArtemisPatchChoice(game) ?: return@launch
                } else {
                    null
                }

                // MV/MZ：未开启存档互通时，检测到标准格式存档才确认是否转化；
                // 开启互通时启动前同步已覆盖该语义，跳过弹窗。
                val interop = EngineLauncher.isRpgSaveInteropEnabled(applicationContext, game)
                if (!interop) {
                    val pending = withContext(Dispatchers.IO) {
                        EngineLauncher.rpgSaveFormatPending(applicationContext, game)
                    }
                    if (pending != null) {
                        val convert = awaitRpgSaveFormatChoice(pending.convertibleCount, pending.hashedCount)
                        if (convert) {
                            val op = try {
                                EngineLauncher.convertRpgSaveFormat(applicationContext, game)
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (_: Throwable) {
                                null
                            }
                            val message = rpgConvertResultMessage(
                                op,
                                localizedContext.getString(R.string.save_format_converted_count),
                                localizedContext.getString(R.string.save_format_converted_with_failures),
                                localizedContext.getString(R.string.save_format_convert_failed),
                                localizedContext.getString(R.string.save_busy_engine_running),
                            )
                            Toast.makeText(localizedContext, message, Toast.LENGTH_LONG).show()
                        }
                    }
                }

                val error = EngineLauncher.launch(this@GameShortcutActivity, game, patchChoice)
                    .userMessage(localizedContext)
                if (error != null) {
                    Toast.makeText(localizedContext, error, Toast.LENGTH_LONG).show()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showLaunchFailure(error)
            } finally {
                finish()
            }
        }
    }

    /** Shows the localized missing-game message without changing launcher state. */
    private fun showUnavailable() {
        Toast.makeText(localizedContext, R.string.game_desktop_shortcut_unavailable, Toast.LENGTH_LONG).show()
    }

    /**
     * 已固定快捷方式的显示名（= 游戏标题）。旧快捷方式的 intent 里没有标题 extra，
     * 但 launcher 里固定着的 ShortcutInfo 一定带 label，用它做 uri 变化后的兜底解析。
     */
    private fun pinnedShortcutLabel(shortcutId: String): String? = runCatching {
        val manager = getSystemService(android.content.pm.ShortcutManager::class.java) ?: return null
        val info = manager.pinnedShortcuts.firstOrNull { it.id == shortcutId } ?: return null
        val label = info.longLabel?.toString()?.takeIf { it.isNotBlank() }
            ?: info.shortLabel?.toString()?.takeIf { it.isNotBlank() }
        label
    }.getOrNull()

    /** Shows the missing-game message and closes the transparent trampoline. */
    private fun showUnavailableAndFinish() {
        showUnavailable()
        finish()
    }

    /** Converts an unexpected launch exception into a user-visible localized toast. */
    private fun showLaunchFailure(error: Throwable) {
        Log.e(TAG, "Shortcut game launch failed", error)
        Toast.makeText(localizedContext, R.string.launch_failed, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "GameShortcutActivity"
        private const val EXTRA_SHORTCUT_ID = "extra_shortcut_id"
        private const val EXTRA_SHORTCUT_TITLE = "extra_shortcut_title"

        /** Builds the explicit trampoline intent carried by a pinned shortcut. */
        fun createIntent(context: Context, gameUri: String, gameTitle: String? = null): Intent =
            Intent(context, GameShortcutActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_SHORTCUT_ID, GameShortcutManager.shortcutId(gameUri))
                // 标题随快捷方式一起保存：游戏目录被移动/重命名导致 uri 变化时用于兜底解析
                if (!gameTitle.isNullOrBlank()) putExtra(EXTRA_SHORTCUT_TITLE, gameTitle)
            }
    }
}
