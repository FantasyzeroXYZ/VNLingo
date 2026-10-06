package com.tyranor.next.core.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.core.engine.EnginePrefs
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.storage.GameLibraryFacade
import com.tyranor.next.core.game.storage.GameOverridesRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

/**
 * WebDAV 同步编排（参考 RinneMobile SyncManager 完整移植，快照内容映射到
 * VNLingo 数据源）：
 *   - 快照序列化编解码（gzip + 大小校验 + 解压放大防护）→ [SyncSnapshotCodec]
 *   - WebDAV 客户端 → [WebDavClient]
 *
 * 同步流（与参考实现同构）：本地快照与云端快照各算 SHA-256，与上次同步哈希
 * 比对得到「本地是否变 / 云端是否变」→ 首次上传 / 新设备首装下载 / 无变化 /
 * 单侧上传 / 单侧下载 / 双侧冲突（取消·用本地·用云端·智能合并）。
 * 合并 = 云端覆盖式并入本地（键级：本地独有保留、同名以本地为准），再重导出。
 *
 * 快照内容（app="VNLingo"）：
 *   - games：游戏库（uri/title/engine/启动目标等文本元数据；不含封面与扫描根，
 *     参考实现同款隐私与跨设备约束）
 *   - overrides：单游戏引擎覆盖设置（prefs 镜像，导入后触发 DB 回灌）
 *   - settings：策展的配置 prefs 整文件（TTS/制卡/手柄重映射/虚拟鼠标绑定）
 *   - play_time：游玩统计（仅本地为空时采纳云端，新设备场景）
 *
 * 不同步：存档 zip（面板存档上传下载独立通道）、词典库（二进制大文件）、
 * 封面图（二进制）、扫描根（SAF URI 跨设备无效）、云同步凭据自身。
 */
class SyncManager(private val context: Context) {

    private val syncPrefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(SYNC_PREFS, Context.MODE_MULTI_PROCESS)

    private var client: WebDavClient? = null

    val isConfigured: Boolean
        get() {
            val c = config
            return c.serverUrl.trim().isNotEmpty() && c.username.trim().isNotEmpty() &&
                c.password.trim().isNotEmpty()
        }

    val config: SyncConfig
        get() = SyncConfig(
            syncPrefs.getString(KEY_SERVER_URL, "") ?: "",
            syncPrefs.getString(KEY_USERNAME, "") ?: "",
            syncPrefs.getString(KEY_PASSWORD, "") ?: "",
            syncPrefs.getBoolean(KEY_AUTO_SYNC, false),
        )

    val isAutoSyncEnabled: Boolean
        get() = syncPrefs.getBoolean(KEY_AUTO_SYNC, false)

    val lastSyncTime: Long
        get() = syncPrefs.getLong(KEY_LAST_SYNC, 0)

    @Synchronized
    fun saveConfig(serverUrl: String?, username: String?, password: String?, autoSync: Boolean) {
        syncPrefs.edit()
            .putString(KEY_SERVER_URL, serverUrl?.trim() ?: "")
            .putString(KEY_USERNAME, username?.trim() ?: "")
            .putString(KEY_PASSWORD, password ?: "")
            .putBoolean(KEY_AUTO_SYNC, autoSync)
            .apply()
        client = null
    }

    @Synchronized
    fun getClient(): WebDavClient? {
        if (client == null && isConfigured) {
            val c = config
            client = WebDavClient(c.serverUrl, c.username, c.password)
        }
        return client
    }

    fun testConnection(): Boolean = try {
        getClient()?.testConnection() ?: false
    } catch (t: Exception) {
        Log.w(TAG, "testConnection failed", t)
        false
    }

    /** 触发一次同步（后台单线程执行；回调已切主线程）。 */
    fun sync(listener: SyncListener?) {
        if (!isConfigured) {
            listener?.onError(context.getString(com.tyranor.next.R.string.sync_not_configured_error))
            return
        }
        executor.execute {
            try {
                listener?.onSyncStart()
                val c = getClient() ?: throw IOException("client init failed")
                val local = buildLocalSnapshot()
                val localText = SyncSnapshotCodec.snapshotToText(
                    local, SyncSnapshotCodec.MAX_REMOTE_SNAPSHOT_BYTES, "本地同步快照")
                val localHash = sha256(localText)
                val lastHash = syncPrefs.getString(KEY_LAST_SYNC_HASH, "")

                var remote: JSONObject? = null
                var remoteText: String? = null
                var remoteHash = ""
                val remoteExists = c.exists(REMOTE_FILE)
                if (remoteExists) {
                    val remoteBytes = c.readFileLimited(
                        REMOTE_FILE, SyncSnapshotCodec.MAX_REMOTE_SNAPSHOT_BYTES.toLong())
                    remoteText = SyncSnapshotCodec.decompressIfGzip(
                        remoteBytes, SyncSnapshotCodec.MAX_REMOTE_SNAPSHOT_BYTES)
                    remote = JSONObject(remoteText)
                    if (APP_TAG != remote.optString("app", "")) {
                        throw IOException(
                            context.getString(com.tyranor.next.R.string.sync_invalid_file))
                    }
                    remoteHash = sha256(remoteText)
                }

                val result = SyncResult()
                result.localBytes = localText.toByteArray(Charsets.UTF_8).size
                result.remoteBytes = if (remoteText == null) 0 else remoteText.toByteArray(Charsets.UTF_8).size

                if (!remoteExists) {
                    c.mkdirs(REMOTE_DIR)
                    c.writeFile(REMOTE_FILE, SyncSnapshotCodec.compressGzip(localText))
                    markSynced(localHash)
                    result.uploaded = true
                    listener?.onProgress(
                        context.getString(com.tyranor.next.R.string.sync_first_upload), true)
                    listener?.onSyncComplete(result)
                    return@execute
                }

                val localChanged = localHash != lastHash
                val remoteChanged = remoteHash != lastHash

                // 新设备首次同步：本地库为空而云端已有数据 → 直接下载，避免空库参与合并
                if (lastHash.isNullOrEmpty() && isSnapshotEmpty(local)) {
                    importSnapshot(remote!!)
                    markSynced(remoteHash)
                    result.downloaded = true
                    listener?.onProgress(
                        context.getString(com.tyranor.next.R.string.sync_first_download), true)
                    listener?.onSyncComplete(result)
                    return@execute
                }

                if (!localChanged && !remoteChanged) {
                    result.noChanges = true
                    markSynced(localHash)
                    listener?.onSyncComplete(result)
                    return@execute
                }
                if (localChanged && !remoteChanged) {
                    c.writeFile(REMOTE_FILE, SyncSnapshotCodec.compressGzip(localText))
                    markSynced(localHash)
                    result.uploaded = true
                    listener?.onProgress(
                        context.getString(com.tyranor.next.R.string.sync_uploading), true)
                    listener?.onSyncComplete(result)
                    return@execute
                }
                if (!localChanged && remoteChanged) {
                    importSnapshot(remote!!)
                    markSynced(remoteHash)
                    result.downloaded = true
                    listener?.onProgress(
                        context.getString(com.tyranor.next.R.string.sync_downloading), true)
                    listener?.onSyncComplete(result)
                    return@execute
                }

                com.core.diag.DiagLog.debug("sync", "conflict local=" + result.localBytes
                        + " remote=" + result.remoteBytes)
                val conflict = Conflict(local, remote!!, result.localBytes, result.remoteBytes)
                val decision = listener?.onConflict(conflict) ?: RESOLVE_MERGE
                if (decision == RESOLVE_CANCEL) {
                    result.cancelled = true
                    listener?.onSyncComplete(result)
                    return@execute
                }
                if (decision == RESOLVE_USE_REMOTE) {
                    importSnapshot(remote)
                    markSynced(remoteHash)
                    result.downloaded = true
                } else if (decision == RESOLVE_USE_LOCAL) {
                    c.writeFile(REMOTE_FILE, SyncSnapshotCodec.compressGzip(localText))
                    markSynced(localHash)
                    result.uploaded = true
                } else {
                    importSnapshot(remote)
                    val merged = buildLocalSnapshot()
                    val mergedText = SyncSnapshotCodec.snapshotToText(
                        merged, SyncSnapshotCodec.MAX_REMOTE_SNAPSHOT_BYTES, "合并后的同步快照")
                    c.writeFile(REMOTE_FILE, SyncSnapshotCodec.compressGzip(mergedText))
                    markSynced(sha256(mergedText))
                    result.merged = true
                }
                listener?.onSyncComplete(result)
            } catch (t: Exception) {
                Log.e(TAG, "sync failed", t)
                com.core.diag.DiagLog.debug("sync", "failed: " + t.message)
                listener?.onError(t.message ?: "sync failed")
            }
        }
    }

    // ------------------------------------------------------------------
    // 本地备份（.vnlbak，gzip 压缩快照；参考 .ykbak 流程）
    // ------------------------------------------------------------------

    /** 导出本地备份为 gzip 压缩字节（附原始 JSON 大小供提示）。 */
    @Throws(Exception::class)
    fun exportLocalBackupAsGzip(): Backup {
        val root = buildLocalSnapshot()
        root.put("created_at", System.currentTimeMillis())
        root.put("backup_type", "local_full")
        val jsonText = SyncSnapshotCodec.snapshotToText(
            root, SyncSnapshotCodec.MAX_LOCAL_BACKUP_BYTES, "本地完整备份")
        val originalSize = jsonText.toByteArray(Charsets.UTF_8).size
        return Backup(SyncSnapshotCodec.compressGzip(jsonText), originalSize)
    }

    /** 从字节导入本地备份（自动识别 gzip/纯 JSON；校验 app 标签）。 */
    @Throws(Exception::class)
    fun importLocalBackupFromBytes(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) throw IOException("backup empty")
        val text = SyncSnapshotCodec.decompressIfGzip(bytes, SyncSnapshotCodec.MAX_LOCAL_BACKUP_BYTES)
        val root = JSONObject(text)
        if (APP_TAG != root.optString("app", "")) {
            throw IOException(context.getString(com.tyranor.next.R.string.sync_invalid_file))
        }
        importSnapshot(root)
    }

    // ------------------------------------------------------------------
    // 快照构建 / 导入
    // ------------------------------------------------------------------

    private fun buildLocalSnapshot(): JSONObject {
        val root = JSONObject()
        root.put("app", APP_TAG)
        root.put("schema", 1)
        root.put("created_at", 0)
        root.put(
            "note",
            "Text metadata only. No game files, save files, cover images, dictionaries or scan roots.",
        )

        root.put("games", exportGamesJson())

        val overrides = JSONObject()
        val overridePrefs = context.getSharedPreferences(
            EnginePrefs.GAME_OVERRIDES_PREFS, Context.MODE_MULTI_PROCESS)
        for ((gameId, raw) in overridePrefs.all) {
            val blob = raw as? String ?: continue
            val obj = runCatching { JSONObject(blob) }.getOrNull() ?: continue
            overrides.put(gameId, obj)
        }
        root.put("overrides", overrides)

        val settings = JSONObject()
        for (prefsName in SYNCED_PREF_FILES) {
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_MULTI_PROCESS)
            val obj = JSONObject()
            for ((key, value) in prefs.all) {
                when (value) {
                    null -> obj.put(key, JSONObject.NULL)
                    is Int -> obj.put(key, value)
                    is Boolean -> obj.put(key, value)
                    is Long -> obj.put(key, value)
                    is Float -> obj.put(key, value.toDouble())
                    is String -> obj.put(key, value)
                    is Set<*> -> obj.put(key, JSONArray(value.filterNotNull()))
                    else -> {}
                }
            }
            settings.put(prefsName, obj)
        }
        root.put("settings", settings)

        val playTime = readPlayTimeFile()
        if (playTime != null) root.put("play_time", playTime)
        return root
    }

    private fun exportGamesJson(): JSONArray {
        val out = JSONArray()
        for (game in GameLibraryFacade.loadGames(context)) {
            out.put(JSONObject().apply {
                put("uri", game.uri)
                put("title", game.title)
                put("engine", game.engine.name)
                put("launch_target", game.launchTarget)
                put("launch_file", game.launchFile ?: "")
                put("vndb_id", game.vndbId ?: "")
                put("metadata_title", game.metadataTitle ?: "")
                put("external_module_alias", game.externalModuleAlias ?: "")
                put("detected_renpy_version", game.detectedRenpyVersion ?: "")
                put("last_opened_at", game.openTime)
            })
        }
        return out
    }

    private fun isSnapshotEmpty(root: JSONObject?): Boolean {
        if (root == null) return true
        val games = root.optJSONArray("games")
        return games == null || games.length() == 0
    }

    /**
     * 导入快照（覆盖式并入：键级合并，本地独有条目保留、同名以导入方为准）。
     * 导入触发引擎侧镜像/缓存刷新：overrides 写 prefs 镜像后由启动同步回灌 DB。
     */
    @Throws(Exception::class)
    fun importSnapshot(root: JSONObject) {
        if (APP_TAG != root.optString("app", "")) {
            throw IOException(context.getString(com.tyranor.next.R.string.sync_invalid_file))
        }
        importGames(root.optJSONArray("games"))
        importOverrides(root.optJSONObject("overrides"))
        importSettings(root.optJSONObject("settings"))
        importPlayTime(root.optJSONObject("play_time"))
    }

    /** 游戏库并入：远端覆盖同 uri 行、新增远端独有行；本地已有行的封面保留。 */
    private fun importGames(games: JSONArray?) {
        if (games == null) return
        val remoteById = LinkedHashMap<String, ScanGame>()
        for (i in 0 until games.length()) {
            val obj = games.optJSONObject(i) ?: continue
            val uri = obj.optString("uri", "")
            if (uri.isEmpty()) continue
            remoteById[uri] = ScanGame(
                title = obj.optString("title", ""),
                uri = uri,
                engine = runCatching {
                    EngineType.valueOf(obj.optString("engine", "UNKNOWN"))
                }.getOrDefault(EngineType.UNKNOWN),
                launchTarget = obj.optString("launch_target", ""),
                coverUri = null,   // 封面为本地/二进制资源，不跨设备同步
                coverSource = null,
                vndbId = obj.optString("vndb_id", "").ifEmpty { null },
                metadataTitle = obj.optString("metadata_title", "").ifEmpty { null },
                externalModuleAlias = obj.optString("external_module_alias", "").ifEmpty { null },
                detectedRenpyVersion = obj.optString("detected_renpy_version", "").ifEmpty { null },
                launchFile = obj.optString("launch_file", "").ifEmpty { null },
                openTime = obj.optLong("last_opened_at", 0),
            )
        }
        if (remoteById.isEmpty()) return
        GameLibraryFacade.updateGames(context) { current ->
            // 远端覆盖同 uri 行（保留本机封面——本地文件引用不随快照走），新增远端独有行
            val out = current.map { local ->
                val remote = remoteById[local.uri]
                if (remote != null) remote.copy(coverUri = local.coverUri, coverSource = local.coverSource)
                else local
            }.toMutableList()
            val existing = out.mapTo(HashSet()) { it.uri }
            for ((uri, game) in remoteById) {
                if (uri !in existing) out.add(game)
            }
            out
        }
    }

    private fun importOverrides(overrides: JSONObject?) {
        if (overrides == null || overrides.length() == 0) return
        val prefs = context.getSharedPreferences(
            EnginePrefs.GAME_OVERRIDES_PREFS, Context.MODE_MULTI_PROCESS)
        val editor = prefs.edit()
        for (key in overrides.keys()) {
            val blob = overrides.optJSONObject(key) ?: continue
            editor.putString(key, blob.toString())
        }
        editor.apply()
        // prefs 镜像已更新：下次读取由启动同步差异回灌 DB（仓库设计的导入路径）
        GameOverridesRepository.invalidateRowCache()
    }

    private fun importSettings(settings: JSONObject?) {
        if (settings == null) return
        for (prefsName in settings.keys()) {
            if (prefsName !in SYNCED_PREF_FILES) continue
            val obj = settings.optJSONObject(prefsName) ?: continue
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_MULTI_PROCESS)
            val editor = prefs.edit()
            for (key in obj.keys()) {
                when (val value = obj.opt(key)) {
                    JSONObject.NULL -> editor.remove(key)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Double -> editor.putFloat(key, value.toFloat())
                    is String -> editor.putString(key, value)
                    is JSONArray -> {
                        val set = HashSet<String>()
                        for (i in 0 until value.length()) set.add(value.optString(i))
                        editor.putStringSet(key, set)
                    }
                    else -> {}
                }
            }
            editor.apply()
        }
    }

    /** 游玩统计：仅本地为空（新设备）时采纳云端，避免互相覆盖统计历史。 */
    private fun importPlayTime(playTime: JSONObject?) {
        if (playTime == null || playTime.length() == 0) return
        val file = playTimeFile()
        if (file.exists() && file.length() > 0) return
        runCatching { file.writeText(playTime.toString()) }
    }

    private fun playTimeFile(): File = File(context.filesDir, PLAY_TIME_FILE)

    private fun readPlayTimeFile(): JSONObject? = runCatching {
        val file = playTimeFile()
        if (!file.exists() || file.length() == 0L) return null
        JSONObject(file.readText())
    }.getOrNull()

    private fun markSynced(hash: String) {
        syncPrefs.edit()
            .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
            .putString(KEY_LAST_SYNC_HASH, hash)
            .apply()
    }

    private fun sha256(text: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in bytes) sb.append(String.format(Locale.ROOT, "%02x", b))
        return sb.toString()
    }

    data class SyncConfig(
        val serverUrl: String,
        val username: String,
        val password: String,
        val autoSync: Boolean,
    )

    data class Conflict(
        val local: JSONObject,
        val remote: JSONObject,
        val localBytes: Int,
        val remoteBytes: Int,
    )

    class SyncResult {
        var uploaded = false
        var downloaded = false
        var merged = false
        var noChanges = false
        var cancelled = false
        var localBytes = 0
        var remoteBytes = 0
        fun hasChanges() = uploaded || downloaded || merged
    }

    class Backup(val bytes: ByteArray, val originalSize: Int)

    interface SyncListener {
        fun onSyncStart()
        fun onProgress(item: String, changed: Boolean)
        /** 双侧都有更改时回调（同步线程阻塞等待决定）。返回 RESOLVE_* 常量。 */
        fun onConflict(conflict: Conflict): Int
        fun onSyncComplete(result: SyncResult)
        fun onError(error: String)
    }

    companion object {
        const val RESOLVE_CANCEL = 0
        const val RESOLVE_USE_LOCAL = 1
        const val RESOLVE_USE_REMOTE = 2
        const val RESOLVE_MERGE = 3

        private const val TAG = "SyncManager"
        private const val APP_TAG = "VNLingo"
        private const val SYNC_PREFS = "vnlingo_sync"
        private const val REMOTE_DIR = "VNLingo"
        private const val REMOTE_FILE = "$REMOTE_DIR/VNLingo_sync.json"
        private const val PLAY_TIME_FILE = "play_time.json"

        private const val KEY_SERVER_URL = "webdav_server"
        private const val KEY_USERNAME = "webdav_username"
        private const val KEY_PASSWORD = "webdav_password"
        private const val KEY_AUTO_SYNC = "auto_sync"
        private const val KEY_LAST_SYNC = "last_sync_time"
        private const val KEY_LAST_SYNC_HASH = "last_sync_hash"

        /** 整文件同步的策展配置 prefs（TTS/制卡/手柄重映射/虚拟鼠标绑定）。 */
        private val SYNCED_PREF_FILES = listOf(
            "ons_extract_tts",
            "anki_card_config",
            "gamepad_remap",
            "virtual_mouse_bindings",
        )

        /** 同步专用单线程执行器（串行化，避免并发同步交错写）。 */
        private val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "vnlingo-sync").apply { isDaemon = true }
        }
    }
}
