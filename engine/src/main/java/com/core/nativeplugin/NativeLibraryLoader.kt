package com.core.nativeplugin

import android.content.Context

/**
 * Loads Kirikiroid2 native libraries from the installed zip plugin directory.
 *
 * Return values are absolute `libgame*.so` paths and must be passed unchanged to
 * NativeBridge so the C++ bridge can dlopen the same file.
 */
object NativeLibraryLoader {
    private val loadedPaths = LinkedHashSet<String>()

    @JvmStatic
    fun loadKirikiroid139(context: Context): String? {
        val sdl2 = NativePluginManager.kirikiroid2LibPath(context, NativePluginConstants.LIB_SDL2) ?: return null
        val ffmpeg = NativePluginManager.kirikiroid2LibPath(context, NativePluginConstants.LIB_FFMPEG) ?: return null
        val game = NativePluginManager.kirikiroid2LibPath(context, NativePluginConstants.LIB_GAME_139) ?: return null
        // krkr2-main 带钩内核 DT_NEEDED libomp.so（OpenMP）与 libc++_shared.so
        // （NDK C++ 运行时）：插件目录不在动态链接器搜索路径，必须先于 libgame.so
        // 显式加载（旧内核无此依赖，缺失时跳过）。
        NativePluginManager.kirikiroid2LibPath(context, NativePluginConstants.LIB_CPP_SHARED)
            ?.let { loadPath(it) }
        val omp = NativePluginManager.kirikiroid2LibPath(context, NativePluginConstants.LIB_OMP)
        if (omp != null) loadPath(omp)
        loadPath(sdl2)
        loadPath(ffmpeg)
        loadPath(game)
        return game
    }

    @JvmStatic
    fun loadKirikiroid134(context: Context): String? {
        val ffmpeg = NativePluginManager.kirikiroid2LibPath(context, NativePluginConstants.LIB_FFMPEG) ?: return null
        val game = NativePluginManager.kirikiroid2LibPath(context, NativePluginConstants.LIB_GAME_134) ?: return null
        loadPath(ffmpeg)
        loadPath(game)
        return game
    }

    @JvmStatic
    fun loadKirikiroid126(context: Context): String? {
        val ffmpeg = NativePluginManager.kirikiroid2LibPath(context, NativePluginConstants.LIB_FFMPEG) ?: return null
        val game = NativePluginManager.kirikiroid2LibPath(context, NativePluginConstants.LIB_GAME_126) ?: return null
        loadPath(ffmpeg)
        loadPath(game)
        return game
    }

    /**
     * krkr2-main 带钩内核（libkrkr2.so，追加内核）：依赖 = NDK libc++_shared +
     * libomp（两内核共用插件目录内的 NDK 构建，不再加载 1.3.9 的 SDL2/ffmpeg —
     * 新内核自带静态 ffmpeg/miniaudio）。
     */
    @JvmStatic
    fun loadKrkr2Hooked(context: Context): String? {
        val cxx = NativePluginManager.kirikiroid2LibPath(context, NativePluginConstants.LIB_CPP_SHARED) ?: return null
        val omp = NativePluginManager.kirikiroid2LibPath(context, NativePluginConstants.LIB_OMP) ?: return null
        val game = NativePluginManager.kirikiroid2LibPath(context, NativePluginConstants.LIB_KRKR2) ?: return null
        loadPath(cxx)
        loadPath(omp)
        loadPath(game)
        return game
    }

    @JvmStatic
    fun loadOns(context: Context): String? {
        // 先一次性预检全部 .so，任一缺失即整体失败，避免加载到一半无法回滚。
        val paths = NativePluginConstants.ONS_REQUIRED_LIBS.map { lib ->
            NativePluginManager.onsLibPath(context, lib) ?: return null
        }
        paths.forEach { loadPath(it) }
        // ONS_REQUIRED_LIBS 末尾即 libonsyuri.so（主入口）。
        return paths.last()
    }

    @Synchronized
    private fun loadPath(path: String) {
        if (path !in loadedPaths) {
            android.util.Log.i("NativeLibraryLoader", "loading $path")
            System.load(path)
            android.util.Log.i("NativeLibraryLoader", "loaded $path")
            loadedPaths.add(path)
        }
    }
}
