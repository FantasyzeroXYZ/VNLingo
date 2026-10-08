package com.core.anki

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.ichi2.anki.api.AddContentApi
import java.io.File

/**
 * AnkiDroid 制卡助手：经官方 AddContentApi（ContentProvider）添加笔记与媒体。
 *
 * 参照 Tyranor 提取面板的制卡需求与 jidoujisho/参考实现的成熟套路：
 * - deck/model 名 → ID 经 SharedPreferences 缓存（重复制卡跳过全表扫描；
 *   用户在 AnkiDroid 里改名后仍可经缓存 ID 命中）。
 * - 媒体（语音/截图）经 addMediaFromUri 交给 AnkiDroid 进程自己拷贝进
 *   collection.media，返回 [sound:xx] / <img src=xx> 格式化串；失败时回退
 *   直拷 collection.media 两个已知路径（scoped / legacy）。
 * - 卡片模型：Front=当前句，Back=整页文本 + 截图 + 语音（无则省略）。
 */
class AnkiDroidHelper(private val context: Context) {

    companion object {
        const val ANKIDROID_PACKAGE = "com.ichi2.anki"
        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

        // 不预设任何牌组/模型名：牌组与模板一律从 AnkiDroid 现有内容里选，
        // 本项目不自动创建同名牌组/模板（用户明确要求，避免「凭空多出 TyranorNext」）。

        private const val DECK_REF_DB = "com.ichi2.anki.api.decks"
        private const val MODEL_REF_DB = "com.ichi2.anki.api.models"
        private const val REQUEST_PERMISSION_CODE = 22001

        private const val TAG = "AnkiDroid"
    }

    private val api: AddContentApi? by lazy {
        if (isAnkiDroidInstalled()) {
            try {
                AddContentApi(context)
            } catch (e: Exception) {
                Log.w(TAG, "AddContentApi init failed", e)
                null
            }
        } else null
    }

    // ─── 可用性与权限 ───────────────────────────────────────────────────

    fun isAnkiDroidInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(ANKIDROID_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    /** API 就绪（已安装 + 已授权 + 提供方可用）。 */
    fun isApiAvailable(): Boolean =
        isAnkiDroidInstalled() && hasPermission() &&
            AddContentApi.getAnkiDroidPackageName(context) != null

    /**
     * 发起 READ_WRITE_DATABASE 运行时权限申请（AnkiDroid 定义，系统路由到
     * AnkiDroid 授权页）。无 NEW_TASK 无法从 Activity 以外的上下文启动。
     * 授权窗必须主线程发起：制卡流程在后台线程跑，直接调用时系统授权框
     * 实测不会弹出（静默无框，用户以为点了没反应）。
     */
    fun requestPermission(activity: android.app.Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    activity.requestPermissions(arrayOf(PERMISSION), REQUEST_PERMISSION_CODE)
                } catch (t: Throwable) {
                    Log.w(TAG, "requestPermissions failed", t)
                }
            }
        }
    }

    /** 拉起 AnkiDroid 主页（未安装或需要用户先完成初始化时兜底）。 */
    fun launchAnkiDroidApp(): Boolean = try {
        val intent = context.packageManager.getLaunchIntentForPackage(ANKIDROID_PACKAGE)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } else false
    } catch (t: Throwable) {
        false
    }

    // ─── Deck / Model 解析（名字优先，缓存 ID 兜底） ────────────────────

    private fun queryIdByDisplayName(uri: Uri, displayName: String): Long? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val cols = c.columnNames
            val nameCol = cols.indexOfFirst { it.equals("name", true) }
                .let { if (it >= 0) it else cols.indexOfFirst { n -> n.contains("name", true) } }
            var idCol = cols.indexOfFirst { it.equals("_id", true) }
            if (idCol < 0) idCol = cols.indexOfFirst {
                it.equals("id", true) || it.contains("id", true)
            }
            if (nameCol >= 0 && idCol >= 0) {
                while (c.moveToNext()) {
                    if (c.getString(nameCol) == displayName) return c.getLong(idCol)
                }
            }
            null
        }
    } catch (t: Throwable) {
        Log.w(TAG, "queryIdByDisplayName failed: $uri", t)
        null
    }

    fun findDeckId(deckName: String): Long? {
        queryIdByDisplayName(
            Uri.parse("content://com.ichi2.anki.flashcards/decks"), deckName
        )?.let { return it }
        // 名字未命中（可能已被改名）→ 缓存 ID 仍存在则沿用
        val cached = context.getSharedPreferences(DECK_REF_DB, Context.MODE_PRIVATE)
            .getLong(deckName, -1L)
        return if (cached != -1L && api?.getDeckName(cached) != null) cached else null
    }

    fun findModelId(modelName: String): Long? {
        val cached = context.getSharedPreferences(MODEL_REF_DB, Context.MODE_PRIVATE)
            .getLong(modelName, -1L)
        if (cached != -1L && api?.getModelName(cached) != null) return cached
        return queryIdByDisplayName(
            Uri.parse("content://com.ichi2.anki.flashcards/models"), modelName
        )
    }

    /** 模型字段名列表（制卡设置 UI 与字段映射装配用）；null = 查询失败。 */
    fun getModelFieldNames(modelId: Long): Array<String>? = try {
        api?.getFieldList(modelId)
    } catch (t: Throwable) {
        Log.w(TAG, "getModelFieldNames failed: $modelId", t)
        null
    }

    /** 全部牌组名（设置页选择用；空 = 未就绪/查询失败）。 */
    fun getDeckNames(): List<String> = try {
        api?.getDeckList()?.values?.toList() ?: emptyList()
    } catch (t: Throwable) {
        Log.w(TAG, "getDeckList failed", t)
        emptyList()
    }

    /** 全部模型（note type）名。 */
    fun getModelNames(): List<String> = try {
        api?.getModelList()?.values?.toList() ?: emptyList()
    } catch (t: Throwable) {
        Log.w(TAG, "getModelList failed", t)
        emptyList()
    }

    /**
     * 取牌组（**不自动创建**）：牌组必须由用户从制卡设置的下拉里选择。
     *
     * 保留方法名里的 `OrCreate` 会诱使后来者重新引入「凭空建牌组」，因此这里只做查找；
     * 需要新建牌组时请由用户在 AnkiDroid 里建好再选。
     */
    fun findDeck(deckName: String): Long? {
        if (deckName.isBlank()) {
            Log.w(TAG, "findDeck: 未配置牌组名")
            return null
        }
        val id = findDeckId(deckName)
        if (id == null) Log.w(TAG, "findDeck: AnkiDroid 中不存在牌组 $deckName")
        return id
    }

    /**
     * 取模板（**不自动创建**）：模板必须由用户从制卡设置的下拉里选择。
     */
    fun findModel(modelName: String): Long? {
        if (modelName.isBlank()) {
            Log.w(TAG, "findModel: 未配置模板名")
            return null
        }
        val id = findModelId(modelName)
        if (id == null) Log.w(TAG, "findModel: AnkiDroid 中不存在模板 $modelName")
        return id
    }

    /**
     * 取词卡模型（查词制卡用）：Word / Reading / Meaning / Sentence / Image / Audio 六字段。
     *
     * **不自动创建**：模板必须由用户在制卡设置里从 AnkiDroid 现有模板中选择
     * （用户明确要求，避免凭空生成 "TyranorNext Word" 之类的自定义模板）。
     */
    fun getWordModel(modelName: String): Long? {
        if (modelName.isBlank()) {
            Log.w(TAG, "getWordModel: 未配置模型名")
            return null
        }
        val id = findModelId(modelName)
        if (id == null) Log.w(TAG, "getWordModel: AnkiDroid 中不存在模型 $modelName")
        return id
    }

    // ─── 媒体导入 ───────────────────────────────────────────────────────

    /**
     * 导入本地文件到 collection.media，返回 Anki 字段标记（[sound:x] / <img src=x>）。
     *
     * 三步降级（每一步都记日志，制卡缺图/缺音时能在 logcat 定位到是哪一步断的）：
     * 1. **content:// + 显式授权**（`FileProvider` → `grantUriPermission(com.ichi2.anki)`）：
     *    Android 11+ 起 AnkiDroid 进程读不到我们私有目录的 `file://`，这是唯一可靠路径；
     * 2. `file://`（老 AnkiDroid / 老 Android 可用）；
     * 3. 直拷 collection.media（Android 11+ 基本不可写，仅作老系统兜底）。
     */
    fun addMedia(file: File, preferredName: String, mimeType: String): String? {
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "addMedia: 文件不存在或为空 ${file.absolutePath}")
            return null
        }
        // API 约定 preferredName 不带扩展名（扩展名由提供方按媒体类型决定）
        val baseName = preferredName.substringBeforeLast('.')

        // 1) content:// + 授权（Android 11+ 的唯一可靠路径）
        val shared = fileProviderUri(file)
        if (shared != null) {
            try {
                context.grantUriPermission(
                    ANKIDROID_PACKAGE, shared, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (t: Throwable) {
                Log.w(TAG, "grantUriPermission 失败（AnkiDroid 可能读不到该 Uri）", t)
            }
            try {
                val raw = api?.addMediaFromUri(shared, baseName, mimeType)
                if (!raw.isNullOrEmpty()) {
                    Log.i(TAG, "addMedia(content) ok: $raw")
                    return raw
                }
                Log.w(TAG, "addMedia(content) 返回空：$baseName（$mimeType）")
            } catch (t: Throwable) {
                Log.w(TAG, "addMediaFromUri(content) 异常：$baseName", t)
            }
        } else {
            Log.w(TAG, "addMedia: 无法为该文件生成 content:// Uri（FileProvider 未配置？）")
        }

        // 2) 旧 file:// 路径（老 AnkiDroid / 老 Android）
        try {
            val raw = api?.addMediaFromUri(Uri.fromFile(file), preferredName, mimeType)
            if (!raw.isNullOrEmpty()) {
                Log.i(TAG, "addMedia(file) ok: $raw")
                return raw
            }
            Log.w(TAG, "addMedia(file) 返回空：$preferredName")
        } catch (t: Throwable) {
            Log.w(TAG, "addMediaFromUri(file) 异常：$preferredName", t)
        }

        // 不再做「直拷 collection.media」兜底：Android 11+ 下第三方应用写不进 AnkiDroid 的
        // media 目录（scoped storage），而旧路径 /sdcard/AnkiDroid/collection.media 从
        // AnkiDroid 2.16 起就不再是它的媒体目录——直拷会「看起来成功」（返回 <img> 标记）
        // 但 AnkiDroid 找不到文件，卡片显示缺图且无从排查。宁可失败并明确提示。
        Log.w(TAG, "addMedia 失败（未写入 AnkiDroid 媒体库）：$preferredName")
        return null
    }

    /**
     * 本应用 FileProvider 的 content:// Uri。
     *
     * 宿主 App 的清单里已声明 `${applicationId}.fileprovider`（@xml/file_paths），
     * 其 paths 覆盖了 `external-files-path extract/`（制卡截图落在这里）。
     */
    private fun fileProviderUri(file: File): Uri? = try {
        androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
    } catch (t: Throwable) {
        Log.w(TAG, "FileProvider.getUriForFile 失败：${file.absolutePath}", t)
        null
    }

    // 已删除「直拷 collection.media」回退：Android 11+ 第三方应用写不进 AnkiDroid 的媒体
    // 目录，且旧路径 /sdcard/AnkiDroid/collection.media 自 AnkiDroid 2.16 起不再是它的
    // 媒体目录 —— 直拷返回成功标记但卡片缺图，属于「假装成功」，比失败更难排查
    // （用户实测：图片落在旧目录、AnkiDroid 完全看不到）。

    // ─── 笔记 ───────────────────────────────────────────────────────────

    /** 添加单条笔记，返回 noteId（负值/异常视为失败）。null = API 无果（可能字段
     *  与既有模型不符），与异常分开记日志便于定位。 */
    fun addNote(modelId: Long, deckId: Long, fields: Array<String>, tags: Set<String>?): Long? = try {
        val id = api?.addNote(modelId, deckId, fields, tags)?.takeIf { it > 0 }
        if (id == null) Log.w(TAG, "addNote returned null (modelId=$modelId deckId=$deckId)")
        id
    } catch (t: Throwable) {
        Log.w(TAG, "addNote failed", t)
        null
    }
}
