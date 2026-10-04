package com.core.ons

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * ONS 脚本编码自动探测（encoding = "auto" 时由启动参数构造调用）。
 *
 * 依据：
 * - nscript.dat 未加密时首字节必为 '0'..'9'（脚本版本号）；否则视为 NScripter
 *   加密（密钥派生自游戏 exe，离线不可判读），跳过转而探测目录下 .txt
 *   （日文游戏几乎都带日文 readme）。
 * - UTF-8 严格解码成功且含 CJK/假名 → utf8（强信号）。
 * - SJIS 严格解码成功且含假名（平假名/片假名）→ sjis：假名是日文专属码位，
 *   SJIS 字节被 GBK 误读只会产出错字汉字，不会出现假名。
 * - GBK 严格解码成功且含 CJK 且无假名 → gbk。
 * - 全部不确定 → null（调用方回退全局默认编码）。
 */
object OnsEncodingDetect {

    private val UTF8 = Charset.forName("UTF-8")
    private val SJIS = Charset.forName("Shift_JIS")
    private val GBK = Charset.forName("GBK")
    private const val SAMPLE_BYTES = 256 * 1024

    /** 返回 "utf8" / "sjis" / "gbk"，无法判定时返回 null。 */
    @JvmStatic
    fun detect(gameDir: String?): String? {
        val root = gameDir?.let { File(it) } ?: return null
        if (!root.isDirectory) return null
        val candidates = mutableListOf<File>()
        File(root, "nscript.dat").takeIf { it.isFile }?.let { candidates.add(it) }
        root.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".txt") }
            ?.sortedByDescending { it.length() }
            ?.take(3)
            ?.let { candidates.addAll(it) }
        for (file in candidates) {
            try {
                detectFile(file)?.let { return it }
            } catch (_: Throwable) {
                // 单文件失败（IO/解码）不影响其他候选
            }
        }
        return null
    }

    private fun detectFile(file: File): String? {
        val bytes = file.readBytes().let {
            if (it.size > SAMPLE_BYTES) it.copyOf(SAMPLE_BYTES) else it
        }
        if (bytes.size < 16) return null

        if (file.name.equals("nscript.dat", ignoreCase = true)) {
            val first = bytes.firstOrNull {
                it != ' '.code.toByte() && it != '\r'.code.toByte() &&
                    it != '\n'.code.toByte() && it != 0.toByte() &&
                    it != '\t'.code.toByte()
            } ?: return null
            val isDigit = first >= '0'.code.toByte() && first <= '9'.code.toByte()
            if (!isDigit) {
                // NScripter pro 加密（密钥派生自游戏 exe）：该加密法只见于日文
                // 原版（中文 ONS 移植均解密重打包为明文），直接判 sjis。
                return "sjis"
            }
        }

        // UTF-8 严格解码：合法且含 CJK → utf8
        val utf8Text = tryDecode(bytes, UTF8)
        if (utf8Text != null) {
            if (containsCjk(utf8Text)) return "utf8"
            // 合法 UTF-8 且含非 ASCII 但无 CJK：判别价值低，交给其他候选
            if (utf8Text.any { it.code >= 0x80 }) return null
        }

        // SJIS：假名是决定性信号
        val sjisText = tryDecode(bytes, SJIS)
        if (sjisText != null && containsKana(sjisText)) return "sjis"

        // GBK：含 CJK 且无假名
        val gbkText = tryDecode(bytes, GBK)
        if (gbkText != null && containsCjk(gbkText) && !containsKana(gbkText)) return "gbk"

        return null
    }

    /** 严格模式解码，失败返回 null。 */
    private fun tryDecode(bytes: ByteArray, charset: Charset): String? = try {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Throwable) {
        null
    }

    private fun containsKana(text: String): Boolean = text.any {
        (it.code in 0x3041..0x309F) || (it.code in 0x30A1..0x30FF)
    }

    private fun containsCjk(text: String): Boolean = text.any {
        (it.code in 0x3400..0x4DBF) || (it.code in 0x4E00..0x9FFF) ||
            (it.code in 0x3041..0x30FF)
    }
}
