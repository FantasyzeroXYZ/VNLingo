package com.tyranor.next.core.play

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log

/**
 * 游玩会话生命周期钩子：通过 Application.registerActivityLifecycleCallbacks
 * 自动跟踪游戏 Activity 的前后台切换，零侵入各宿主 Activity。
 *
 * 识别游戏 Activity 的方式：类名包含已知引擎宿主关键词。
 * 会话键 = gameUri 或类名（跨进程引擎由各自进程独立跟踪）。
 */
object PlaySessionLifecycle {

    private val GAME_ACTIVITY_KEYWORDS = listOf(
        "ONScripter", "KR2", "Kirikiroid", "TyranoActivity", "RpgMakerActivity",
        "ArtemisActivity", "FramebufferGame", "RenPy",
    )

    private var activeSessionKey: String? = null
    private var activeTitle: String = ""

    fun install(app: Application) {
        AppContextHolder.set(app)
        PlaySessionTracker.install(app)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                val key = gameKey(activity) ?: return
                if (key == activeSessionKey) return
                finishCurrent()
                activeSessionKey = key
                activeTitle = activity.title?.toString() ?: key
                try {
                    PlaySessionTracker.startSession(activity, key, activeTitle)
                } catch (t: Throwable) {
                    Log.w("PlaySession", "startSession failed", t)
                }
            }

            override fun onActivityPaused(activity: Activity) {
                val key = gameKey(activity) ?: return
                if (key == activeSessionKey) {
                    finishCurrent()
                    activeSessionKey = null
                }
            }

            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private fun finishCurrent() {
        val key = activeSessionKey ?: return
        try {
            PlaySessionTracker.finishSession(appContext(), key)
        } catch (t: Throwable) {
            Log.w("PlaySession", "finishSession failed", t)
        }
    }

    private fun gameKey(activity: Activity): String? {
        val name = activity.javaClass.simpleName
        if (GAME_ACTIVITY_KEYWORDS.none { name.contains(it) }) return null
        // 优先 intent 里的 game path（唯一标识）；否则类名
        return try {
            val intent = activity.intent
            val path = intent?.getStringExtra("path")
                ?: intent?.getStringExtra("gamePath")
                ?: intent?.getStringExtra("gamedir")
            path ?: name
        } catch (ignored: Throwable) {
            name
        }
    }

    private fun appContext(): android.content.Context {
        return AppContextHolder.get()
            ?: throw IllegalStateException("AppContextHolder not initialized")
    }
}

/** 静态 Context 持有（PlaySessionTracker 无 state 需要跨 Activity 传递）。 */
internal object AppContextHolder {
    @Volatile private var ref: android.content.Context? = null
    fun get(): android.content.Context? = ref
    fun set(c: android.content.Context) { ref = c.applicationContext }
}
