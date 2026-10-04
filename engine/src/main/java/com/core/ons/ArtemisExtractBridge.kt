package com.core.ons

import android.os.Handler
import android.os.Looper

/**
 * Artemis 内核提取观测桥的 Java 侧接点：libartemis-clean 的 extract_bridge
 * 发射器经 ArtemisActivity.onArtemisExtract（引擎线程）调进 dispatch，
 * 这里切主线程后分发给监听者（ArtemisExtractFacade）。
 *
 * 事件语义与 ONS-BRIDGE 对齐：text = 消息层累积文本（打字机分段到达，内容
 * 去重由面板负责）；voiceName = 最近一次 voplay 的脚本内路径；voiceCached =
 * 内核落盘的语音副本绝对路径（空 = 未缓存）。
 */
object ArtemisExtractBridge {

    fun interface Listener {
        fun onExtract(text: String, voiceName: String, voiceCached: String)
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var listenerInternal: Listener? = null

    @JvmStatic
    fun setListener(l: Listener?) {
        listenerInternal = l
    }

    @JvmStatic
    fun dispatch(text: String, voiceName: String, voiceCached: String) {
        val l = listenerInternal ?: return
        main.post { l.onExtract(text, voiceName, voiceCached) }
    }
}
