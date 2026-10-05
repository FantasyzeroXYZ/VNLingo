package bridge

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.OsConstants
import android.util.Log
import com.core.engine.LaunchContract
import org.tvp.kirikiri2.KR2Activity
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

object NativeBridge {
    @Volatile
    private var SAF_DOCUMENTS: Map<String, Uri> = emptyMap()
    @Volatile
    private var krkrGameReadyListener: Runnable? = null
    @Volatile
    private var patchOverlayTarget: String? = null
    @Volatile
    private var patchOverlayPath: String? = null
    @Volatile
    private var steamConfigOverlayTarget: String? = null
    @Volatile
    private var steamConfigOverlayPath: String? = null

    @JvmStatic external fun initialize(so: String?): Boolean
    @JvmStatic external fun isLaunchSceneReady(so: String?): Boolean
    @JvmStatic external fun launch(so: String?, path: String?, useMaps: Boolean): Boolean
    @JvmStatic external fun interceptor(prefix: String?): Unit
    @JvmStatic external fun relocate(): Int
    @JvmStatic external fun write(path: String?, data: ByteArray?): Boolean

    @JvmStatic
    private fun onKrkrGameReady() {
        val listener = krkrGameReadyListener
        Log.i("NativeBridge", "KRKR ready callback listener=${listener != null}")
        listener?.run()
    }

    /**
     * [KRKR-EXTRACT] libkrkr_bridge_v2 的 cocos2d Label::setString 钩子上报的文本
     * （已按 CJK/长度启发式过滤）。直接转成 ONS 提取桥的 dialogue 事件，
     * 复用既有面板/Anki 管线。
     */
    /** 控制台行格式：`HH:MM:SS storage : message`。 */
    private val krkrConsoleLine = Regex("^\\d\\d:\\d\\d:\\d\\d (.*?) : (.*)$", RegexOption.DOT_MATCHES_ALL)

    @JvmStatic
    private fun onKrkrText(text: String?) {
        if (text.isNullOrBlank()) return
        // 严格 [TNEXT] 通道：仅 TJS 发射器 v8（或未来带钩内核经此路径）发出的标记行
        // 进入提取；其余 Label 文本（引擎控制台的脚本加载/调试行）一律丢弃，
        // 避免原版内核下面板被噪音刷屏。带钩内核的正常提取走
        // KR2Activity.setExtractListener 独立通道，不经本方法。
        val m = krkrConsoleLine.find(text)
        val message = if (m != null) m.groupValues[2] else text
        // 双通道：[TNEXT]=TJS 发射器标记行；[FTLN]=FT 字符流行（krkr_extract_hook
        // 的 FT_Load_Char 钩子按停顿切行重建，纯运行时 hook、不改内核）
        val dialogue = when {
            message.startsWith("[TNEXT]") -> message.removePrefix("[TNEXT]").trim()
            message.startsWith("[FTLN]") -> message.removePrefix("[FTLN]").trim()
            else -> null
        }?.takeIf { it.isNotEmpty() } ?: return
        Log.i("NativeBridge", "krkr text: $dialogue")
        try {
            val b64 = android.util.Base64.encodeToString(
                dialogue.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val payload = "{\"type\":\"dialogue\",\"payload\":{\"b64\":\"$b64\"}}"
            com.core.ons.OnsExtractBridge.get().onEvent(payload.toByteArray(Charsets.UTF_8))
        } catch (t: Throwable) {
            Log.w("NativeBridge", "onKrkrText failed", t)
        }
    }

    /**
     * [KRKR-EXTRACT] libkrkr_bridge_v2 检测到游戏内提取钩子（patch.tjs）以写模式打开
     * __tn_extract.txt 时上行事件。此处读取文件内容并转成 ONS 提取桥的 dialogue
     * 语义，复用既有面板/Anki 管线。写入方（TJS saveStructure）在打开之后才落盘，
     * 因此延迟一拍再读，避免读到上一句的旧内容。
     */
    @JvmStatic
    private fun onKrkrExtract(path: String?) {
        try {
            val target = KrPathUtils.normalizeFilePath(path) ?: return
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.postDelayed({
                try {
                    val file = File(target)
                    if (!file.isFile) return@postDelayed
                    val text = file.readText(Charsets.UTF_8)
                    if (text.isBlank()) return@postDelayed
                    // 优先解析 TJS saveStructure 单字段结构：%[ "t" => "..." ]
                    val line = extractStructureField(text)
                        ?: text.trimEnd().lineSequence().lastOrNull()?.takeIf { it.isNotBlank() }
                        ?: return@postDelayed
                    val b64 = android.util.Base64.encodeToString(line.toByteArray(Charsets.UTF_8),
                        android.util.Base64.NO_WRAP)
                    val payload = "{\"type\":\"dialogue\",\"payload\":{\"b64\":\"$b64\"}}"
                    com.core.ons.OnsExtractBridge.get().onEvent(payload.toByteArray(Charsets.UTF_8))
                } catch (t: Throwable) {
                    Log.w("NativeBridge", "onKrkrExtract deferred failed path=$path", t)
                }
            }, 120L)
        } catch (t: Throwable) {
            Log.w("NativeBridge", "onKrkrExtract failed path=$path", t)
        }
    }

    /** 提取 TJS saveStructure 输出中 "t" => "..." 的字段值并反转义 TJS 字符串字面量。 */
    private fun extractStructureField(text: String): String? {
        val match = Regex("\"t\"\\s*=>\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(text) ?: return null
        val raw = match.groupValues[1]
        if (raw.isEmpty()) return null
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\') { sb.append(c); i++; continue }
            if (i + 1 >= raw.length) break
            when (val n = raw[i + 1]) {
                'n' -> sb.append(' ')
                'r' -> { }
                't' -> sb.append(' ')
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                'x' -> {
                    val hex = raw.drop(i + 2).take(2)
                    hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                    i += hex.length
                }
                'u' -> {
                    val hex = raw.drop(i + 2).take(4)
                    hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                    i += hex.length
                }
                else -> sb.append(n)
            }
            i += 2
        }
        val out = sb.toString().trim()
        return out.ifEmpty { null }
    }

    @JvmStatic
    fun setKrkrGameReadyListener(listener: Runnable?) {
        krkrGameReadyListener = listener
    }

    @JvmStatic
    fun configureSafMirror(indexPath: String?) {
        SAF_DOCUMENTS = try {
            KrSafMirror.loadIndex(indexPath)
        } catch (t: Throwable) {
            Log.e("NativeBridge", "load SAF mirror index failed path=$indexPath", t)
            emptyMap()
        }
        Log.i("NativeBridge", "SAF mirror index entries=${SAF_DOCUMENTS.size}")
    }

    @JvmStatic
    fun configurePatchOverlay(targetPath: String?, overlayPath: String?) {
        val target = KrPathUtils.canonicalizeKrStoragePath(KrPathUtils.normalizeFilePath(targetPath))
        val overlay = KrPathUtils.normalizeFilePath(overlayPath)
        val overlayFile = overlay?.let { File(it) }
        if (target.isNullOrBlank() || overlay.isNullOrBlank() || overlayFile?.isFile != true) {
            patchOverlayTarget = null
            patchOverlayPath = null
            Log.i("NativeBridge", "KRKR patch overlay disabled target=$targetPath overlay=$overlayPath")
            return
        }
        patchOverlayTarget = target
        patchOverlayPath = overlay
        Log.i("NativeBridge", "KRKR patch overlay configured target=$target overlay=$overlay")
    }

    @JvmStatic
    fun configureSteamConfigOverlay(targetPath: String?, overlayPath: String?) {
        val target = KrPathUtils.canonicalizeKrStoragePath(KrPathUtils.normalizeFilePath(targetPath))
        val overlay = KrPathUtils.normalizeFilePath(overlayPath)
        val overlayFile = overlay?.let { File(it) }
        if (target.isNullOrBlank() || overlay.isNullOrBlank() || overlayFile?.isFile != true) {
            steamConfigOverlayTarget = null
            steamConfigOverlayPath = null
            Log.i("NativeBridge", "KRKR Steam config overlay disabled target=$targetPath overlay=$overlayPath")
            return
        }
        steamConfigOverlayTarget = target
        steamConfigOverlayPath = overlay
        Log.i("NativeBridge", "KRKR Steam config overlay configured target=$target overlay=$overlay")
    }

    @Synchronized
    @JvmStatic
    fun open(path: String?, mode: Int): Int {
        val normalized = KrPathUtils.canonicalizeKrStoragePath(KrPathUtils.normalizeFilePath(path))
        readOnlyOverlayRedirect(normalized)?.takeIf { isReadOnlyOpen(mode) }?.let { overlay ->
            return openDirectFile(path, overlay, mode, diagnosticPrefix = "read-only overlay")
        }
        val redirected = KrPathUtils.redirectScopedSavePath(normalized)
        // The native hook uses the stable storage-volume prefix because KRKR may lowercase
        // the game path. Keep regular asset I/O native; only scoped saves need Java redirection.
        if (redirected == null && !isSafFallbackEnabled()) return -1
        val target = if (redirected != null) redirected else normalized ?: return -1
        val javaMode: String = try {
            toJavaMode(mode)
        } catch (t: Throwable) {
            Log.e("NativeBridge", "bad open mode=$mode path=$path", t)
            return -1
        }
        val mirrorUri = SAF_DOCUMENTS[target.lowercase(Locale.ROOT)]
        val readOnly = (mode and OsConstants.O_ACCMODE) == OsConstants.O_RDONLY
        if (readOnly && mirrorUri != null && File(target).length() == 0L) {
            val mirrorFd = openDocumentUri(mirrorUri, mode)
            if (mirrorFd >= 0) return mirrorFd
        }
        return try {
            openDirectFile(path, target, mode, diagnosticPrefix = if (redirected != null) "redirect" else null)
        } catch (directError: Throwable) {
            if (isSafFallbackEnabled()) {
                val safFd = openViaSaf(target, mode, directError)
                if (safFd >= 0) return safFd
            }
            if (redirected != null) recordOpenDiagnostic(
                "failed path=$path target=$target flags=$mode mode=$javaMode " +
                    "error=${directError.javaClass.simpleName}:${directError.message}",
            )
            Log.e("NativeBridge", "open failed mode=$mode path=$path", directError)
            -1
        }
    }

    @JvmStatic
    fun redirect(path: String?): String? {
        val raw = KrPathUtils.normalizeFilePath(path)
        val normalized = KrPathUtils.canonicalizeKrStoragePath(raw)
        KrPathUtils.redirectScopedSavePath(normalized)?.let { return it }
        if (normalized != null && normalized != path) return normalized
        return null
    }

    @JvmStatic
    fun redirectOpen(path: String?, mode: Int): String? {
        val raw = KrPathUtils.normalizeFilePath(path)
        val normalized = KrPathUtils.canonicalizeKrStoragePath(raw)
        if (isReadOnlyOpen(mode)) {
            readOnlyOverlayRedirect(normalized)?.let { return it }
        }
        KrPathUtils.redirectScopedSavePath(normalized)?.let { return it }
        if (normalized != null && normalized != path) return normalized
        return null
    }

    @JvmStatic
    fun redirectReadMetadata(path: String?): String? {
        val raw = KrPathUtils.normalizeFilePath(path)
        val normalized = KrPathUtils.canonicalizeKrStoragePath(raw)
        readOnlyOverlayRedirect(normalized)?.let { return it }
        KrPathUtils.redirectScopedSavePath(normalized)?.let { return it }
        if (normalized != null && normalized != path) return normalized
        return null
    }

    @JvmStatic
    fun redirectScopedSave(path: String?): String? {
        val normalized = KrPathUtils.canonicalizeKrStoragePath(KrPathUtils.normalizeFilePath(path))
        return KrPathUtils.redirectScopedSavePath(normalized)
    }

    private fun recordOpenDiagnostic(value: String) {
        try {
            KrPathUtils.currentActivity()?.getSharedPreferences("krkr_bridge_diagnostics", 0)
                ?.edit()?.putString("last_open", value)?.commit()
        } catch (_: Throwable) {
        }
    }

    private fun readOnlyOverlayRedirect(path: String?): String? {
        if (path == null || path.isBlank()) return null
        val steamConfigTarget = steamConfigOverlayTarget
        val steamConfigOverlay = steamConfigOverlayPath
        if (
            !steamConfigTarget.isNullOrBlank() &&
            !steamConfigOverlay.isNullOrBlank() &&
            matchesOverlayTarget(path, steamConfigTarget)
        ) {
            return steamConfigOverlay
        }
        val patchTarget = patchOverlayTarget
        val patchOverlay = patchOverlayPath
        if (
            !patchTarget.isNullOrBlank() &&
            !patchOverlay.isNullOrBlank() &&
            matchesOverlayTarget(path, patchTarget)
        ) {
            return patchOverlay
        }
        return null
    }

    private fun matchesOverlayTarget(path: String, target: String): Boolean {
        if (path.equals(target, ignoreCase = true)) return true
        if (path.contains('/')) return false
        return path.equals(File(target).name, ignoreCase = true)
    }

    private fun isReadOnlyOpen(mode: Int): Boolean =
        (mode and OsConstants.O_ACCMODE) == OsConstants.O_RDONLY

    private fun openDirectFile(path: String?, target: String, mode: Int, diagnosticPrefix: String?): Int {
        val javaMode = toJavaMode(mode)
        val raf = RandomAccessFile(File(target), javaMode)
        if ((mode and OsConstants.O_TRUNC) == OsConstants.O_TRUNC) raf.setLength(0)
        if ((mode and OsConstants.O_APPEND) == OsConstants.O_APPEND) raf.seek(raf.length())
        val fd = getFd(raf)
        raf.close()
        if (diagnosticPrefix != null) recordOpenDiagnostic(
            "$diagnosticPrefix ok path=$path target=$target flags=$mode mode=$javaMode fd=$fd",
        )
        Log.i("NativeBridge", "open $fd $javaMode $path -> $target")
        return fd
    }

    private fun isSafFallbackEnabled(): Boolean {
        return try {
            val activity = KrPathUtils.currentActivity()
            val intent = activity?.intent
            intent != null && intent.getBooleanExtra(LaunchContract.SAF_FILE_FALLBACK, false)
        } catch (_: Throwable) {
            false
        }
    }

    private fun openViaSaf(path: String, mode: Int, directError: Throwable): Int {
        return try {
            val uri = storagePathToPersistedDocumentUri(path, mode) ?: return -1
            openDocumentUri(uri, mode)
        } catch (safError: Throwable) {
            Log.w("NativeBridge", "open SAF fallback failed path=$path direct=$directError", safError)
            -1
        }
    }

    private fun openDocumentUri(uri: Uri, mode: Int): Int {
        val activity = KrPathUtils.currentActivity() ?: return -1
        val pfdMode = toPfdMode(mode)
        val pfd = activity.contentResolver.openFileDescriptor(uri, pfdMode) ?: return -1
        val fd = pfd.detachFd()
        Log.i("NativeBridge", "open SAF $fd $pfdMode -> $uri")
        return fd
    }

    @JvmStatic
    fun writeViaSafIfPossible(path: String?, data: ByteArray?): Boolean {
        return try {
            val p = KrPathUtils.canonicalizeKrStoragePath(path) ?: return false
            val uri = storagePathToPersistedDocumentUri(p, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC) ?: return false
            val activity = KrPathUtils.currentActivity() ?: return false
            val out = activity.contentResolver.openOutputStream(uri, "wt") ?: return false
            out.use {
                if (data != null) it.write(data)
                it.flush()
            }
            Log.i("NativeBridge", "write SAF $p -> $uri bytes=${data?.size ?: 0}")
            true
        } catch (t: Throwable) {
            Log.w("NativeBridge", "write SAF failed path=$path", t)
            false
        }
    }

    @JvmStatic
    fun createDirectoryViaSafIfPossible(path: String?): Boolean {
        return try {
            val p = KrPathUtils.canonicalizeKrStoragePath(path) ?: return false
            val uri = storagePathToPersistedDocumentUri(p + "/.tyranor_dir_probe", OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC) ?: return false
            val activity = KrPathUtils.currentActivity()
            if (activity != null) {
                try { DocumentsContract.deleteDocument(activity.contentResolver, uri) } catch (_: Throwable) {}
            }
            Log.i("NativeBridge", "mkdir SAF $p")
            true
        } catch (t: Throwable) {
            Log.w("NativeBridge", "mkdir SAF failed path=$path", t)
            false
        }
    }

    @JvmStatic
    fun deleteViaSafIfPossible(path: String?): Boolean {
        return try {
            val p = KrPathUtils.canonicalizeKrStoragePath(path) ?: return false
            val uri = storagePathToPersistedDocumentUri(p, OsConstants.O_RDONLY) ?: return false
            val activity = KrPathUtils.currentActivity() ?: return false
            val ok = DocumentsContract.deleteDocument(activity.contentResolver, uri)
            Log.i("NativeBridge", "delete SAF $p -> $uri ok=$ok")
            ok
        } catch (t: Throwable) {
            Log.w("NativeBridge", "delete SAF failed path=$path", t)
            false
        }
    }

    @JvmStatic
    fun existsViaSafIfPossible(path: String?): Boolean {
        return try {
            val p = KrPathUtils.canonicalizeKrStoragePath(path) ?: return false
            val uri = storagePathToPersistedDocumentUri(p, OsConstants.O_RDONLY) ?: return false
            val activity = KrPathUtils.currentActivity() ?: return false
            val input = activity.contentResolver.openInputStream(uri)
            input.use { it != null }
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun renameViaSafIfPossible(from: String?, to: String?): Boolean {
        return try {
            val f = KrPathUtils.canonicalizeKrStoragePath(from) ?: return false
            val t = KrPathUtils.canonicalizeKrStoragePath(to) ?: return false
            val src = storagePathToPersistedDocumentUri(f, OsConstants.O_RDONLY) ?: return false
            val activity = KrPathUtils.currentActivity() ?: return false
            val resolver = activity.contentResolver
            val input = resolver.openInputStream(src) ?: return false
            input.use {
                val dst = storagePathToPersistedDocumentUri(t, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC) ?: return false
                val out = resolver.openOutputStream(dst, "wt") ?: return false
                out.use { o ->
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) o.write(buf, 0, n)
                    o.flush()
                }
                try { DocumentsContract.deleteDocument(resolver, src) } catch (_: Throwable) {}
                Log.i("NativeBridge", "rename SAF $f -> $t src=$src")
                true
            }
        } catch (t: Throwable) {
            Log.w("NativeBridge", "rename SAF failed $from -> $to", t)
            false
        }
    }

    @SuppressLint("SdCardPath")
    private fun storagePathToPersistedDocumentUri(path: String?, mode: Int): Uri? {
        if (path == null) return null
        val p = KrPathUtils.normalizeFilePath(path) ?: return null
        if (!p.startsWith("/storage/") && !p.startsWith("/sdcard")) return null
        val volume: String
        val rel: String
        if (p.startsWith("/storage/emulated/0/")) {
            volume = "primary"
            rel = p.substring("/storage/emulated/0/".length)
        } else if ("/storage/emulated/0" == p) {
            volume = "primary"
            rel = ""
        } else if (p.startsWith("/sdcard/")) {
            volume = "primary"
            rel = p.substring("/sdcard/".length)
        } else if ("/sdcard" == p) {
            volume = "primary"
            rel = ""
        } else {
            val rest = p.substring("/storage/".length)
            val slash = rest.indexOf('/')
            if (slash <= 0) return null
            volume = rest.substring(0, slash)
            rel = rest.substring(slash + 1)
        }
        if (volume.isEmpty()) return null
        val activity = KrPathUtils.currentActivity() ?: return null
        val resolver = activity.contentResolver
        val docId = "$volume:$rel"
        Log.i("NativeBridge", "SAF resolve path=$path volume=$volume rel=$rel")
        for (perm in resolver.persistedUriPermissions) {
            val tree = perm.uri ?: continue
            val treeId = try { DocumentsContract.getTreeDocumentId(tree) } catch (_: Throwable) { null } ?: continue
            val decodedTreeId = Uri.decode(treeId)
            if (!decodedTreeId.startsWith("$volume:")) continue
            val treeRel = decodedTreeId.substring("$volume:".length)
            if (treeRel.isNotEmpty()) {
                if (rel != treeRel && !rel.startsWith("$treeRel/")) continue
            }
            val existing = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            if (!needsCreate(mode)) return existing
            val created = ensureDocumentExists(resolver, tree, decodedTreeId, volume, rel)
            return created ?: existing
        }
        return null
    }

    private fun needsCreate(mode: Int): Boolean {
        val accessMode = mode and OsConstants.O_ACCMODE
        return accessMode == OsConstants.O_WRONLY || accessMode == OsConstants.O_RDWR || (mode and OsConstants.O_CREAT) == OsConstants.O_CREAT
    }

    private fun ensureDocumentExists(resolver: ContentResolver, tree: Uri, decodedTreeId: String, volume: String, rel: String): Uri? {
        return try {
            val activity = KrPathUtils.currentActivity() ?: return null
            val dir = DocumentFile.fromTreeUri(activity, tree) ?: return null
            val treePrefix = "$volume:"
            val treeRel = if (decodedTreeId.startsWith(treePrefix)) decodedTreeId.substring(treePrefix.length) else ""
            var localRel = rel
            if (treeRel.isNotEmpty()) {
                if (localRel == treeRel) return DocumentsContract.buildDocumentUriUsingTree(tree, "$volume:$rel")
                if (localRel.startsWith("$treeRel/")) localRel = localRel.substring(treeRel.length + 1)
            }
            val parts = localRel.split("/".toRegex()).toTypedArray()
            var current: DocumentFile? = dir
            for (i in parts.indices) {
                val part = parts[i]
                if (part.isEmpty() || part == ".") continue
                val last = i == parts.size - 1
                var child = findChildDocument(current, part)
                if (last) {
                    if (child == null) child = current!!.createFile(guessMime(part), part)
                    return child?.uri
                }
                if (child == null) child = current!!.createDirectory(part)
                if (child == null || !child.isDirectory) return null
                current = child
            }
            null
        } catch (t: Throwable) {
            Log.w("NativeBridge", "ensure SAF document failed rel=$rel", t)
            null
        }
    }

    private fun guessMime(name: String?): String {
        val lower = name?.lowercase(Locale.ROOT) ?: ""
        if (lower.endsWith(".txt") || lower.endsWith(".tjs") || lower.endsWith(".ks") || lower.endsWith(".xml") || lower.endsWith(".json")) return "text/plain"
        return "application/octet-stream"
    }

    private fun findChildDocument(dir: DocumentFile?, name: String?): DocumentFile? {
        if (dir == null || name == null) return null
        return try {
            dir.findFile(name) ?: dir.listFiles().firstOrNull { it.getName()?.equals(name, ignoreCase = true) == true }
        } catch (_: Throwable) {
            null
        }
    }

    private fun toJavaMode(mode: Int): String {
        val accessMode = mode and OsConstants.O_ACCMODE
        if (accessMode == OsConstants.O_RDONLY) return "r"
        if (accessMode == OsConstants.O_WRONLY || accessMode == OsConstants.O_RDWR) return "rw"
        throw IllegalArgumentException("Bad mode: $mode")
    }

    private fun toPfdMode(mode: Int): String {
        val accessMode = mode and OsConstants.O_ACCMODE
        if (accessMode == OsConstants.O_RDONLY) return "r"
        if ((mode and OsConstants.O_APPEND) == OsConstants.O_APPEND) return "wa"
        if ((mode and OsConstants.O_TRUNC) == OsConstants.O_TRUNC) return "wt"
        if (accessMode == OsConstants.O_WRONLY) return "w"
        if (accessMode == OsConstants.O_RDWR) return "rw"
        throw IllegalArgumentException("Bad mode: $mode")
    }

    private fun getFd(raf: RandomAccessFile): Int {
        val duplicate = ParcelFileDescriptor.dup(raf.fd)
        return duplicate.detachFd()
    }
}
