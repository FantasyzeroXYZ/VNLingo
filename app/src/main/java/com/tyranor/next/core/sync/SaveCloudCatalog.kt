package com.tyranor.next.core.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import com.core.ons.OnsSaveCloud
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.save.GameSaveManager
import java.io.File

/**
 * 云同步中心「存档云同步」列表的数据层。
 *
 * 远端命名沿用既有独立通道（[OnsSaveCloud]）：`<dir>/<标题去非法字符>.zip`，
 * 本地则复用 [GameSaveManager]（列存档 / 导出 zip / 导入 zip），保证与游戏内面板、
 * 存档管理页三条路径完全同一套语义，不新造规则。
 *
 * 列表内容 = 「本地有存档的游戏」∪「云端已有 zip 的游戏」：
 * - 本地有、云端没有 → 可上传；
 * - 两边都有 → 展示云端时间/本机上次上传时间，可上传（覆盖）/下载/删除云端；
 * - 只有云端（本地已删游戏或改过标题）→ 展示云端时间，可删除云端。
 */
class SaveCloudCatalog(private val context: Context) {

    /** 一行 = 一个游戏的存档云同步状态。 */
    data class Row(
        /** 云端 zip 名（不含 .zip）。 */
        val cloudName: String,
        val title: String,
        /** 本地库条目；null = 只有云端（本地没有这个游戏）。 */
        val game: ScanGame?,
        val localSaveCount: Int,
        /** 本地存档最后修改时间（0 = 无）。 */
        val localModified: Long,
        /** 本机记录的上次成功上传时间（0 = 从未）。 */
        val localUploadAt: Long,
        /** 云端 zip 的 Last-Modified（0 = 未知/不存在）。 */
        val cloudModified: Long,
        /** 云端 zip 大小（-1 = 未知）。 */
        val cloudSize: Long,
    ) {
        val hasLocal: Boolean get() = localSaveCount > 0
        val hasCloud: Boolean get() = cloudModified > 0 || cloudSize >= 0
    }

    private val appContext = context.applicationContext
    private val saves = GameSaveManager(appContext)

    /** 该通道是否可用（GitHub 模式暂不支持在此列出/删除，需走 Contents API）。 */
    fun isListable(): Boolean = !OnsSaveCloud.isGithubMode(appContext)

    /**
     * 读取列表（阻塞；请在 IO 线程调用）。
     *
     * @param withCloud 是否顺带读取云端目录（无网络时传 false 只显示本地侧）
     */
    fun list(withCloud: Boolean = true): List<Row> {
        val games = runCatching { GameLibraryFacade.loadGames(appContext) }
            .onFailure { Log.w(TAG, "loadGames failed", it) }
            .getOrDefault(emptyList())

        // 本地：每个游戏的存档数量与最后修改时间（同一云端名只保留一条，
        // 库里存在同标题重复条目时避免两行互相覆盖同一个云端 zip）
        val localRows = games.map { game ->
            val files = runCatching { saves.listSaveFiles(game) }.getOrDefault(emptyList())
            val modified = files.maxOfOrNull { it.lastModified() } ?: 0L
            val cloudName = OnsSaveCloud.cloudNameFor(game.title)
            Local(game, cloudName, files.size, modified, OnsSaveCloud.lastUpload(appContext, cloudName))
        }.sortedByDescending { it.count }
            .distinctBy { it.cloudName }

        val remote = if (withCloud) remoteIndex(localRows.map { it.cloudName }) else emptyMap()

        val rows = ArrayList<Row>(localRows.size)
        for (l in localRows) {
            val cloudModified = remote[l.cloudName] ?: 0L
            rows.add(
                Row(
                    cloudName = l.cloudName,
                    title = l.game.title,
                    game = l.game,
                    localSaveCount = l.count,
                    localModified = l.modified,
                    localUploadAt = l.uploadAt,
                    cloudModified = cloudModified,
                    cloudSize = -1L,
                ),
            )
        }
        return rows.sortedWith(
            compareByDescending<Row> { maxOf(it.cloudModified, it.localModified) }
                .thenBy { it.title.lowercase() },
        )
    }

    /** 上传：本地存档导出 zip → 上传到云端；成功后记录本机上传时间。 */
    fun upload(row: Row, onResult: (Boolean, String) -> Unit) {
        val game = row.game
        if (game == null) {
            onResult(false, "本地没有该游戏，无法上传")
            return
        }
        val zip = File(appContext.cacheDir, "savecloud_${row.cloudName}.zip")
        try {
            saves.exportToZip(game, Uri.fromFile(zip))
        } catch (t: Throwable) {
            Log.w(TAG, "exportToZip failed", t)
            onResult(false, t.message ?: t.toString())
            return
        }
        OnsSaveCloud.upload(appContext, row.cloudName, zip) { ok, message ->
            if (ok) OnsSaveCloud.recordUpload(appContext, row.cloudName, System.currentTimeMillis())
            zip.delete()
            onResult(ok, message)
        }
    }

    /** 下载：云端 zip → 导入本地存档目录。 */
    fun download(row: Row, onResult: (Boolean, String) -> Unit) {
        val game = row.game
        if (game == null) {
            onResult(false, "本地没有该游戏，无法导入")
            return
        }
        val zip = File(appContext.cacheDir, "savecloud_${row.cloudName}.zip")
        OnsSaveCloud.download(appContext, row.cloudName, zip) { ok, message ->
            if (!ok) {
                zip.delete()
                onResult(false, message)
            } else {
                var imported = 0
                try {
                    imported = saves.importFromZip(game, Uri.fromFile(zip))
                } catch (t: Throwable) {
                    Log.w(TAG, "importFromZip failed", t)
                } finally {
                    zip.delete()
                }
                if (imported > 0) {
                    onResult(true, "已导入 $imported 个存档文件")
                } else {
                    onResult(false, "导入失败")
                }
            }
        }
    }

    /**
     * 删除云端存档：先删新布局的 `dir/<标题>/saves.zip`（再顺手删空的游戏子文件夹），
     * 新布局没有则删旧平铺布局的 `dir/<标题>.zip`。
     */
    fun deleteRemote(row: Row, onResult: (Boolean, String) -> Unit) {
        val target = OnsSaveCloud.webdavTargetForHost(appContext)
        val base = target.getOrNull(0).orEmpty()
        if (base.isEmpty()) {
            onResult(false, context.getString(com.tyranor.next.R.string.sync_not_configured_error))
            return
        }
        Thread({
            try {
                val client = WebDavClient(base, target.getOrNull(1).orEmpty(), target.getOrNull(2).orEmpty())
                val fresh = OnsSaveCloud.remoteZipPath(appContext, row.cloudName)
                val legacyPaths = OnsSaveCloud.fallbackRemoteZipPaths(appContext, row.cloudName)
                var ok = client.delete(fresh)
                if (ok) {
                    // 顺带清旧布局遗留（迁移后可能还在），并删空的游戏子文件夹
                    for (legacy in legacyPaths) client.delete(legacy)
                    val gameDir = fresh.substringBeforeLast('/') + "/"
                    client.delete(gameDir)
                } else {
                    for (legacy in legacyPaths) {
                        if (client.delete(legacy)) {
                            ok = true
                            break
                        }
                    }
                }
                onResult(ok, if (ok) "已删除云端存档" else "删除失败")
            } catch (t: Throwable) {
                Log.w(TAG, "deleteRemote failed", t)
                onResult(false, t.message ?: t.toString())
            }
        }, "savecloud-delete").start()
    }

    // ---- 内部 ----

    private data class Local(
        val game: ScanGame,
        val cloudName: String,
        val count: Int,
        val modified: Long,
        val uploadAt: Long,
    )

    /**
     * 云端存档索引：zip 名 → 最后修改时间（0 = 不存在/未知）。
     *
     * 布局：`<dir>/<标题>/saves.zip`（每个游戏一个专属子文件夹）；旧版本是
     * `<dir>/<标题>.zip`，这里两个都查，兼容老上传。
     *
     * 注意：**不使用 PROPFIND 列目录**。Android 自带的 HttpURLConnection（okhttp 实现）
     * 只允许 OPTIONS/GET/HEAD/POST/PUT/DELETE/TRACE/PATCH，`setRequestMethod("PROPFIND")`
     * 直接抛 `ProtocolException`。这里改为对"本地已知的每个游戏"各发一次 HEAD
     * （与存档管理页 `cloudModified` 同一链路），代价是列不出"本地没有、只有云端"的 zip。
     */
    private fun remoteIndex(cloudNames: Collection<String>): Map<String, Long> {
        if (!OnsSaveCloud.isConfigured(appContext)) return emptyMap()
        val target = OnsSaveCloud.webdavTargetForHost(appContext)
        val base = target.getOrNull(0).orEmpty()
        if (base.isEmpty()) return emptyMap()
        val client = try {
            WebDavClient(base, target.getOrNull(1).orEmpty(), target.getOrNull(2).orEmpty())
        } catch (t: Throwable) {
            Log.w(TAG, "client init failed", t)
            return emptyMap()
        }
        val out = HashMap<String, Long>()
        for (name in cloudNames) {
            val modified = try {
                var value = client.getLastModified(OnsSaveCloud.remoteZipPath(appContext, name))
                if (value <= 0) {
                    // 旧布局兼容：旧主目录（TyranorNext/saves）与旧平铺文件
                    for (legacy in OnsSaveCloud.fallbackRemoteZipPaths(appContext, name)) {
                        value = client.getLastModified(legacy)
                        if (value > 0) {
                            Log.i(TAG, "cloud save uses legacy path: $legacy")
                            break
                        }
                    }
                }
                value
            } catch (t: Throwable) {
                Log.w(TAG, "HEAD $name failed", t)
                0L
            }
            if (modified > 0) out[name] = modified
        }
        return out
    }

    private companion object {
        private const val TAG = "SaveCloudCatalog"
    }
}
