package com.core.ons

import android.os.Handler
import android.os.Looper
import android.util.Base64

/**
 * KRKR 带钩内核（libkrkr2.so）提取事件接点：
 *
 * 内核对白（tTJSNI_BaseLayer::DrawText）/语音（WaveSoundBuffer::Open）事件经
 * KR2Activity.onKrkr2Extract(type, payload) 上行（引擎线程），本桥负责：
 * - dialogue：CJK 启发过滤 + src=krkr 标记转 OnsExtractBridge 既有 JSON 协议
 *   （复用连续页累积/点击翻页/候选机制），同时以 (text,"","") 通知监听者
 *   （KrkrExtractFacade 据此做语音配对状态机）；
 * - voice：以 ("", displayName, cachedPath) 通知监听者（缓存副本路径进待配队列）；
 * - kernelBridgeActive：内核桥武装成功标志，NativeBridge 据此跳过旧 FT 钩子
 *   通道转发（防双通道双份文本）。
 *
 * 事件语义与 ArtemisExtractBridge 对齐（text 空 = 纯语音事件）；旧内核
 * （无 nativeSetExtractSink）从不调用本桥，行为与原版一致。
 */
object KrkrExtractBridge {

    /** 监听契约与 ArtemisExtractBridge 一致：text 空 = 纯语音事件。 */
    fun interface Listener {
        fun onExtract(text: String?, voiceName: String?, voiceCachedPath: String?)
    }

    private val main = Handler(Looper.getMainLooper())

    /** 内核桥已武装（libkrkr2.so 导出 nativeSetExtractSink 且注册成功）。 */
    @Volatile
    var kernelBridgeActive: Boolean = false
        private set

    private var listener: Listener? = null

    @JvmStatic
    fun setListener(l: Listener?) {
        listener = l
    }

    /** KR2Activity.setExtractListener 注册成功后调用（仅带钩内核会走到）。 */
    @JvmStatic
    fun markKernelBridgeActive() {
        kernelBridgeActive = true
    }

    /** 内核事件统一入口（KR2Activity 上行；引擎线程）。 */
    @JvmStatic
    fun dispatch(type: String?, payload: String?) {
        if (com.core.diag.DiagLog.isEnabled()) {
            android.util.Log.i("KrkrExtractBridge", "dispatch type=$type len=${payload?.length ?: -1}")
        }
        if (type.isNullOrEmpty()) return
        when (type) {
            "dialogue" -> dispatchDialogue(payload)
            "voice" -> dispatchVoice(payload)
        }
    }

    /**
     * 对白上行：CJK 启发过滤（菜单/ASCII 装饰文本丢弃，与 NativeBridge.hasCjk
     * 同款）后转 OnsExtractBridge 既有 JSON 协议（src=krkr 启用缝合语义），
     * 并通知监听者做语音配对。
     */
    private fun dispatchDialogue(payload: String?) {
        val text = payload?.trim() ?: return
        if (text.isEmpty() || !hasCjk(text)) {
            if (com.core.diag.DiagLog.isEnabled()) {
                android.util.Log.i("KrkrExtractBridge", "dialogue filtered: len=${text.length}")
            }
            return
        }
        if (com.core.diag.DiagLog.isEnabled()) {
            android.util.Log.i("KrkrExtractBridge", "dialogue -> OnsExtractBridge: ${text.take(24)}")
        }
        try {
            val b64 = Base64.encodeToString(
                text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val json = "{\"type\":\"dialogue\",\"src\":\"krkr\",\"payload\":{\"b64\":\"$b64\"}}"
            OnsExtractBridge.get().onEvent(json.toByteArray(Charsets.UTF_8))
            val l = listener
            if (l != null) main.post { l.onExtract(text, "", "") }
        } catch (_: Throwable) {
        }
    }

    /** 内核 voice 事件（payload = 缓存副本绝对路径；未配缓存目录时为存储名）。 */
    private fun dispatchVoice(payload: String?) {
        if (payload.isNullOrEmpty()) return
        val name = displayName(payload)
        // BGM/SE 名兜底过滤（内核 /vo|voice 启发之外的第二道防线）。
        val lower = name.lowercase()
        if (lower.contains("bgm") || lower.contains("music")) return
        val l = listener
        if (l != null) main.post { l.onExtract("", name, payload) }
    }

    /** 缓存副本路径 → 展示名（_tn_voice_<sanitized> → sanitized）。 */
    fun displayName(pathOrName: String): String {
        if (pathOrName.isEmpty()) return ""
        val file = pathOrName.substringAfterLast('/')
        return if (file.startsWith("_tn_voice_")) file.removePrefix("_tn_voice_") else file
    }

    /** CJK 启发（与 NativeBridge.hasCjk 同款：含任一 CJK 字符即通过）。 */
    private fun hasCjk(s: String): Boolean {
        for (c in s) {
            if (c in '\u2E80'..'\u9FFF' || c in '\uF900'..'\uFAFF' ||
                c in '\uFF66'..'\uFF9D' || c in '\u3040'..'\u30FF'
            ) return true
        }
        return false
    }
}
