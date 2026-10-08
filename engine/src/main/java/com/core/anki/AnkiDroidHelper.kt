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
     * 导入本地文件到 collection.media，返回 Anki 字段标记（[sound:x] /
     * <img src=x>）。mimeType 只接受 API 约定的 "audio" / "image"。
     * 优先走 AnkiDroid 进程自拷（无权限问题），失败回退直拷。
     */
    fun addMedia(file: File, preferredName: String, mimeType: String): String? {
        if (!file.exists() || file.length() == 0L) return null
        try {
            val raw = api?.addMediaFromUri(
                Uri.fromFile(file), preferredName, mimeType
            )
            if (!raw.isNullOrEmpty()) return raw
        } catch (t: Throwable) {
            Log.w(TAG, "addMediaFromUri failed for $preferredName", t)
        }
        return storeFileDirectly(file, preferredName)?.let { name ->
            if (mimeType == "audio") "[sound:$name]" else "<img src=\"$name\" />"
        }
    }

    /** 直拷回退：scoped 路径优先，legacy 路径兜底（两处都写兼容面最大）。 */
    private fun storeFileDirectly(file: File, targetName: String): String? = try {
        val candidates = listOf(
            File("/storage/emulated/0/Android/data/com.ichi2.anki/files/AnkiDroid/collection.media"),
            File("/storage/emulated/0/AnkiDroid/collection.media")
        )
        var ok = false
        for (dir in candidates) {
            try {
                dir.mkdirs()
                val out = File(dir, targetName)
                file.copyTo(out, overwrite = true)
                if (out.exists() && out.length() > 0) ok = true
            } catch (t: Throwable) {
                Log.d(TAG, "direct copy to ${dir.path} failed: ${t.message}")
            }
        }
        if (ok) targetName else null
    } catch (t: Throwable) {
        Log.w(TAG, "storeFileDirectly failed", t)
        null
    }

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
