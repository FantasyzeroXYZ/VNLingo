package com.ies_net.artemis

import android.app.NativeActivity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import com.core.engine.LaunchContract
import com.core.engine.R

open class ArtemisActivity : NativeActivity() {
    companion object {
        init {
            System.loadLibrary("artemis_audio_bridge")
        }

        /**
         * 内核提取观测桥上行（libartemis-clean 的 extract_bridge 发射器调用，
         * 引擎线程）：转发 ArtemisExtractBridge 单例，主线程分发给监听者。
         * 官方 revision 内核从不调用，无兼容影响。
         */
        @JvmStatic
        fun onArtemisExtract(text: String, voiceName: String, voiceCached: String) {
            com.core.ons.ArtemisExtractBridge.dispatch(text, voiceName, voiceCached)
        }

        /**
         * 提取语音缓存目录（宿主在 super.onCreate 前按游戏内路径设置）。
         * 内核 extract_bridge 在 ANativeActivity_onCreate 阶段经
         * [getExtractCacheDir] 反向拉取——dlopen 与 System.load 两份镜像的
         * 全局状态相互独立，JNI 直写会落在引擎读不到的那份镜像上。
         */
        @Volatile
        @JvmStatic
        var extractCacheDirOverride: String = ""

        @JvmStatic
        fun getExtractCacheDir(): String = extractCacheDirOverride

        /**
         * 软件内**游戏音量**（0..100）：只衰减引擎输出，**不动手机系统音量**。
         * clean 内核导出 `nativeSetGameVolume`；官方内核没有该符号 →
         * UnsatisfiedLinkError 静默降级（音量滑块不起作用，但不崩）。
         */
        @JvmStatic
        fun setGameVolume(percent: Int) {
            val p = percent.coerceIn(0, 100)
            try {
                nativeSetGameVolume(p)
            } catch (t: Throwable) {
                // 官方内核无此导出：忽略
            }
        }

        @JvmStatic
        private external fun nativeSetGameVolume(percent: Int)

        /** 宿主 Context（onCreate 里设置），供 [artemisSkipVideo] 读 prefs。 */
        @Volatile
        private var appContext: android.content.Context? = null

        /** 与 App 侧 EngineSettingsStore 同一个 prefs 文件（引擎读、App 写）。 */
        private const val ARTEMIS_PREFS = "tyranor_prefs"
        private const val KEY_SKIP_VIDEO = "artemis_skip_video"

        /**
         * 影片容错开关（内核 `CArtemis::CommandVideo` 钩子每进程查询一次）。
         *
         * 默认 **true**：本端口里影片打不开时，脚本会永久等在白幕上（The Shell Part I 实测：
         * 铺白幕 → wait → 影片命令从未回调 Java PlayVideo），跳过整条影片命令能让剧情继续。
         * 想恢复播放影片可把 `tyranor_prefs` 的 `artemis_skip_video` 置 false（需重启游戏）。
         */
        @JvmStatic
        fun artemisSkipVideo(): Boolean = runCatching {
            appContext?.getSharedPreferences(ARTEMIS_PREFS, android.content.Context.MODE_PRIVATE)
                ?.getBoolean(KEY_SKIP_VIDEO, true) ?: true
        }.getOrDefault(true)

        /**
         * 虚拟鼠标模式保持 native 失焦渲染：全屏按键捕获窗持焦后游戏窗失焦，
         * libartemis 的 native 循环在 APP_CMD_LOST_FOCUS 后停止绘制/响应
         * （表现为游戏暂停）。该标志置位时（虚拟鼠标开启）不向 native 传播
         * 失焦事件——引擎持续绘制；输入经捕获窗（移动/点击注入）与内核触摸
         * 注入继续工作。仅影响失焦传播，不改 onPause 等真实生命周期。
         */
        @Volatile
        @JvmStatic
        var keepNativeFocused: Boolean = false
    }

    /**
     * 注册提取语音缓存目录（clean 内核导出；官方内核无此导出，
     * UnsatisfiedLinkError 由调用方捕获后静默降级为无提取）。
     */
    external fun nativeSetExtractCacheDir(path: String)

    /** 延迟重试提取钩子安装（进程早期 shadowhook_init 可能失败，见 artemis_loader.cpp）。 */
    external fun nativeInstallExtractHook()

    external fun nativePauseAllSound(): Boolean
    external fun nativeResumeAllSound(): Boolean

    fun DownloadExpansionFiles(value: String) {}

    fun DownloadResource(a: String, b: String, c: String) {}

    external fun EmulateKeyEvent(keyCode: Int, action: Int)

    /**
     * 宿主触摸注入（clean 内核 >= pluginVersion 32 导出；官方内核无此符号，
     * UnsatisfiedLinkError 由调用方捕获后降级为无虚拟鼠标点击）。坐标为窗口
     * 像素，与物理触摸同管线入队（key id 1 = 鼠标左键）。
     */
    external fun injectHostTouch(x: Float, y: Float, down: Boolean)

    /**
     * 官方内核虚拟鼠标点击支持（audio_bridge 导出）：读取 CArtemisTouch
     * 钩子捕获的触摸状态，返回 [have, objPtr, ptX, ptY]；have=0 表示本次
     * 会话游戏画面还没被真实触摸过（触摸对象未捕获）。
     */
    external fun artemisTouchState(): DoubleArray?

    /** 在捕获的触摸对象上合成一次点击（OnBegin/OnTouch/OnEnd，引擎坐标）。 */
    external fun artemisTouchClick(objPtr: Long, x: Int, y: Int): Boolean

    external fun ExecuteTag(tag: String)

    fun InAppBilling(a: String, b: String, c: Boolean, d: Boolean) {
        OnFinishPurchase(1, "", "", "", "", 1, "")
    }

    external fun OnFinishPurchase(result: Int, a: String, b: String, c: String, d: String, e: Int, f: String)

    external fun OnFinishVideo()

    external fun OnReadyPlayAssetDelivery(a: Int, b: Int, c: Int)

    fun PlayVideo(path: String, offset: Int, length: Int, volume: Int, skip: Int) {
        // 影片桥（内核 movie_play → Java → IJK）。白屏类问题里"影片没播"是最常见原因，
        // 这里必须留日志：内核是否真的调到这里、参数是什么、启动是否抛异常。
        android.util.Log.i(
            "ArtemisVideo",
            "PlayVideo path=$path offset=$offset length=$length volume=$volume skip=$skip",
        )
        val intent = Intent(applicationContext, VideoViewActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        intent.putExtra("PATH", path)
        intent.putExtra("OFFSET", offset)
        intent.putExtra("LENGTH", length)
        intent.putExtra("VOLUME", volume)
        intent.putExtra("SKIP", skip)
        try {
            startActivityForResult(intent, 1)
            overridePendingTransition(0, 0)
        } catch (t: Throwable) {
            android.util.Log.e("ArtemisVideo", "PlayVideo startActivity failed: $t")
            // 启动失败也必须回调内核，否则脚本会永远等在影片结束上（表现为白屏卡住）
            runCatching { OnFinishVideo() }
        }
    }

    /**
     * 注入一次"点按"（按下 + 抬起）。
     *
     * **不要用 `EmulateKeyEvent(id, 2)`**：clean 内核的 InjectHostKey 把 status 只当
     * 「0 = down、非 0 = up」（见 artemis-compat src/jni/native_activity.cpp），传 2 等于
     * 只发了一个 UP、永远没有 DOWN —— 引擎看不到按下，按键全部失效。实测：左缘 NEXT 用
     * 0/1 两次调用能推进游戏，而走 status=2 的转发（鼠标模式下未消费的键）毫无反应。
     */
    private fun tapEngineKey(engineKey: Int, holdMs: Long = 60) {
        runCatching { EmulateKeyEvent(engineKey, 0) }
        handler.postDelayed({ runCatching { EmulateKeyEvent(engineKey, 1) } }, holdMs)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 手柄按键重映射（手柄→键盘/其他手柄键），先于内核按键映射链路
        val mapped = com.core.engine.GamepadRemap.apply(event)
        val keyCode = mapped.keyCode
        if (mapped.action == 0) {
            when (keyCode) {
                66 -> tapEngineKey(13)
                59, 60 -> tapEngineKey(115)
                113, 114 -> tapEngineKey(140)
                62 -> tapEngineKey(32)
                21 -> tapEngineKey(37)
                19 -> tapEngineKey(38)
                22 -> tapEngineKey(39)
                20 -> tapEngineKey(40)
                29 -> tapEngineKey(143)
                47 -> tapEngineKey(83)
                40 -> tapEngineKey(76)
                50 -> tapEngineKey(86)
                8 -> tapEngineKey(112)
                9 -> tapEngineKey(113)
                10 -> tapEngineKey(114)
                11 -> tapEngineKey(115)
                12 -> tapEngineKey(116)
                13 -> tapEngineKey(117)
                14 -> tapEngineKey(118)
                15 -> tapEngineKey(119)
                23 -> tapEngineKey(13)
                17 -> tapEngineKey(122)
                18 -> tapEngineKey(140)
                98, 100 -> tapEngineKey(1)
                96 -> tapEngineKey(139)
                97 -> tapEngineKey(32)
                99 -> tapEngineKey(123)
                101 -> EmulateKeyEvent(140, 1)
                102 -> tapEngineKey(83)
                103 -> tapEngineKey(76)
                105 -> tapEngineKey(124)
                106 -> tapEngineKey(143)
            }
        } else if (mapped.action == 1 && keyCode == 101) {
            EmulateKeyEvent(140, 0)
        }
        return super.dispatchKeyEvent(mapped)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        if (requestCode == 1) OnFinishVideo()
    }

    override fun onCreate(bundle: Bundle?) {
        appContext = applicationContext
        super.onCreate(bundle)
        window.addFlags(1024)
        // 注意：不要再加 128（FLAG_FORCE_NOT_FULLSCREEN）——它会把状态栏钉在画面上，
        // 与「游戏画面全屏、状态栏自动隐藏」冲突（用户要求）。
        com.core.engine.ImmersiveMode.applyAndReapply(this)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val old = this.intent
        if (old == null || intent == null) return
        val oldPath = old.getStringExtra(LaunchContract.PATH)
        val newPath = intent.getStringExtra(LaunchContract.PATH)
        if (oldPath == null || oldPath == newPath || newPath == null) return
        Toast.makeText(this, getString(R.string.engine_another_game_running), Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        com.core.engine.GamepadRemap.refresh(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        // 全屏：获得焦点时重新压住状态栏/导航栏（部分 ROM 会在焦点变化后放出系统栏）
        if (hasFocus) com.core.engine.ImmersiveMode.apply(this)
        // 虚拟鼠标模式：失焦不传播到 native（native 循环失焦即停绘/停响应）。
        // 获得焦点与真实生命周期（onPause 等）照常传播。
        if (!hasFocus && keepNativeFocused) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = 5894
            return
        }
        super.onWindowFocusChanged(hasFocus)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 5894
    }
}
