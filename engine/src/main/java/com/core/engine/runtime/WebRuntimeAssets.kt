package com.core.engine.runtime

import android.content.Context
import java.io.File
import java.io.InputStream

/**
 * Web 注入资源解析：外置运行时目录（filesDir/runtimes/web/<id>/，可由下载/
 * 导入写入）优先，APK assets 兜底。使 Web 注入资源（overlay、钩子 js、
 * 修改器等）可拆卸、可按需追加，而无需更新 APK。
 */
internal object WebRuntimeAssets {

    /**
     * 按相对路径打开资源流：外置目录优先，回退 APK assets。
     * caller 负责 close。两处都未命中返回 null。
     */
    fun open(context: Context, runtime: GameRuntime, relativePath: String): InputStream? {
        val external = File(runtime.webOverlayDir(context), relativePath)
        if (external.isFile) return external.inputStream()
        return runCatching { context.assets.open(relativePath) }.getOrNull()
    }

    /** 读取全部字节（外置优先，assets 兜底）；未命中返回 null。 */
    fun readBytes(context: Context, runtime: GameRuntime, relativePath: String): ByteArray? {
        open(context, runtime, relativePath)?.use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16384)
            var n: Int
            while (stream.read(buf).also { n = it } > 0) out.write(buf, 0, n)
            return out.toByteArray()
        }
        return null
    }

    /** 从 zip（下载或导入）安装外置 Web 资源到运行时目录。 */
    fun installFromZip(context: Context, runtime: GameRuntime, zipBytes: ByteArray): Boolean {
        val dest = runtime.webOverlayDir(context)
        val canonicalDest = dest.canonicalFile
        val canonicalDestPath = canonicalDest.path + File.separator
        try {
            dest.deleteRecursively()
            dest.mkdirs()
            val zip = java.util.zip.ZipInputStream(zipBytes.inputStream().buffered())
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(dest, entry.name)
                val canonicalOut = out.canonicalFile
                if (canonicalOut.path != canonicalDest.path &&
                    !canonicalOut.path.startsWith(canonicalDestPath)
                ) {
                    throw SecurityException("Invalid runtime zip entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    canonicalOut.mkdirs()
                } else {
                    canonicalOut.parentFile?.mkdirs()
                    canonicalOut.outputStream().use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
            }
            runtime.markRestored(context)
            return true
        } catch (t: Throwable) {
            dest.deleteRecursively()
            return false
        }
    }
}
