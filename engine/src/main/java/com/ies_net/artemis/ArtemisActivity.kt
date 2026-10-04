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
    }

    /**
     * 注册提取语音缓存目录（clean 内核导出；官方内核无此导出，
     * UnsatisfiedLinkError 由调用方捕获后静默降级为无提取）。
     */
    external fun nativeSetExtractCacheDir(path: String)

    external fun nativePauseAllSound(): Boolean
    external fun nativeResumeAllSound(): Boolean

    fun DownloadExpansionFiles(value: String) {}

    fun DownloadResource(a: String, b: String, c: String) {}

    external fun EmulateKeyEvent(keyCode: Int, action: Int)

    external fun ExecuteTag(tag: String)

    fun InAppBilling(a: String, b: String, c: Boolean, d: Boolean) {
        OnFinishPurchase(1, "", "", "", "", 1, "")
    }

    external fun OnFinishPurchase(result: Int, a: String, b: String, c: String, d: String, e: Int, f: String)

    external fun OnFinishVideo()

    external fun OnReadyPlayAssetDelivery(a: Int, b: Int, c: Int)

    fun PlayVideo(path: String, offset: Int, length: Int, volume: Int, skip: Int) {
        val intent = Intent(applicationContext, VideoViewActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        intent.putExtra("PATH", path)
        intent.putExtra("OFFSET", offset)
        intent.putExtra("LENGTH", length)
        intent.putExtra("VOLUME", volume)
        intent.putExtra("SKIP", skip)
        startActivityForResult(intent, 1)
        overridePendingTransition(0, 0)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (event.action == 0) {
            when (keyCode) {
                66 -> EmulateKeyEvent(13, 2)
                59, 60 -> EmulateKeyEvent(115, 2)
                113, 114 -> EmulateKeyEvent(140, 2)
                62 -> EmulateKeyEvent(32, 2)
                21 -> EmulateKeyEvent(37, 2)
                19 -> EmulateKeyEvent(38, 2)
                22 -> EmulateKeyEvent(39, 2)
                20 -> EmulateKeyEvent(40, 2)
                29 -> EmulateKeyEvent(143, 2)
                47 -> EmulateKeyEvent(83, 2)
                40 -> EmulateKeyEvent(76, 2)
                50 -> EmulateKeyEvent(86, 2)
                8 -> EmulateKeyEvent(112, 2)
                9 -> EmulateKeyEvent(113, 2)
                10 -> EmulateKeyEvent(114, 2)
                11 -> EmulateKeyEvent(115, 2)
                12 -> EmulateKeyEvent(116, 2)
                13 -> EmulateKeyEvent(117, 2)
                14 -> EmulateKeyEvent(118, 2)
                15 -> EmulateKeyEvent(119, 2)
                23 -> EmulateKeyEvent(13, 2)
                17 -> EmulateKeyEvent(122, 2)
                18 -> EmulateKeyEvent(140, 2)
                98, 100 -> EmulateKeyEvent(1, 2)
                96 -> EmulateKeyEvent(139, 2)
                97 -> EmulateKeyEvent(32, 2)
                99 -> EmulateKeyEvent(123, 2)
                101 -> EmulateKeyEvent(140, 1)
                102 -> EmulateKeyEvent(83, 2)
                103 -> EmulateKeyEvent(76, 2)
                105 -> EmulateKeyEvent(124, 2)
                106 -> EmulateKeyEvent(143, 2)
            }
        } else if (event.action == 1 && keyCode == 101) {
            EmulateKeyEvent(140, 0)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        if (requestCode == 1) OnFinishVideo()
    }

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        window.addFlags(1024)
        window.addFlags(128)
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 5894
    }
}
