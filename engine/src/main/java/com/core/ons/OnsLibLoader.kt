package com.core.ons

import android.annotation.SuppressLint
import android.content.Context
import android.system.Os
import android.util.Log
import com.core.nativeplugin.NativeLibraryLoader
import com.core.nativeplugin.NativePluginConstants
import com.core.nativeplugin.NativePluginManager
import java.io.File
import java.io.FileOutputStream

object OnsLibLoader {
    private const val TAG = "OnsLibLoader"
    private var loaded = false

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    @Synchronized
    @JvmStatic
    fun load(context: Context) {
        if (loaded) return
        val app = context.applicationContext
        copyAssetFile(app, "DroidSansFallback.ttf", File(app.filesDir, "DroidSansFallback.ttf"))
        if (NativeLibraryLoader.loadOns(app) == null) {
            throw IllegalStateException("ONS plugin missing")
        }
        try {
            // libONSPatch hooks a fixed code offset inside
            // ONScripter::runEventLoop (+0x4d8).  The offset is synchronized
            // with the shipped libonsyuri.so; rebuild/patch both together.
            System.loadLibrary("ONSPatch")
        } catch (t: Throwable) {
            Log.w(TAG, "load ONSPatch failed, continue", t)
        }
        loaded = true
    }

    @JvmStatic
    fun getMainSharedObject(context: Context): File {
        val libPath = NativePluginManager.onsLibPath(context, NativePluginConstants.LIB_ONSYURI)
        if (libPath == null) {
            throw IllegalStateException("ONS plugin missing libonsyuri.so")
        }
        val main = File(libPath)
        if (!main.isFile) {
            throw IllegalStateException("ONS plugin missing libonsyuri.so")
        }
        return main
    }

    private fun copyAssetFile(context: Context, asset: String, out: File): File {
        try {
            val parent = out.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Log.w(TAG, "mkdir failed: $parent")
            }
            if (!out.exists() || out.length() <= 0) {
                context.assets.open(asset).use { input ->
                    FileOutputStream(out).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        var n: Int
                        while (input.read(buf).also { n = it } > 0) {
                            fos.write(buf, 0, n)
                        }
                    }
                }
            }
            Os.chmod(out.absolutePath, 384)
        } catch (t: Throwable) {
            throw RuntimeException("copy asset failed: $asset", t)
        }
        return out
    }
}
