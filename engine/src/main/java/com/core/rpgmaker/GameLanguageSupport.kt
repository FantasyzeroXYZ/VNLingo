package com.core.rpgmaker

import java.io.File

/**
 * Web 游戏语言族与文件名编码损坏还原链（数据驱动，扩展点）：
 * 新增语言支持时只需增加枚举项（signatureRanges 为该语言独占的字符特征区，
 * restoreChains 为「磁盘名 bytes[from] 解码为 [to] 得真名」的还原链列表）。
 */
internal enum class GameLanguage(
    val signatureRanges: List<IntRange>,
    val restoreChains: List<Pair<String, String>>,
) {
    /** 日文：假名为独占特征；文件名常见 SJIS 被中文 Windows GBK 误解。 */
    JAPANESE(
        listOf(0x3040..0x30FF),
        listOf(
            "GBK" to "Shift_JIS",
            "ISO-8859-1" to "Shift_JIS",
        ),
    ),

    /** 俄语：西里尔为独占特征；zip/拷贝链路常见 cp866/cp437 与 cp1251 互损。 */
    RUSSIAN(
        listOf(0x0400..0x04FF),
        listOf(
            "IBM866" to "windows-1251",
            "IBM437" to "windows-1251",
            "GBK" to "windows-1251",
        ),
    ),

    /** 韩语：谚文为独占特征；常见 cp949 损坏链（GBK 误解 ms949 字节）。 */
    KOREAN(
        listOf(0xAC00..0xD7AF),
        listOf(
            "GBK" to "ms949",
        ),
    ),

    /**
     * 中文（简繁共用）：无独占特征（汉字与日文共享），由检测器在无假名/谚文
     * 时按汉字计数判定。繁体游戏文件名可能被 GBK 误解 Big5 字节，链有损时
     * 匹配自然跳过（无损场景如 設定.ini 可命中）。
     */
    CHINESE(
        listOf(0x4E00..0x9FFF),
        listOf(
            "GBK" to "Big5",
        ),
    ),

    /** 西欧/默认：文件名本就是 UTF-8/Latin，无损坏链。 */
    LATIN(emptyList(), emptyList()),
    ;

    val isDetectable: Boolean get() = signatureRanges.isNotEmpty()
}

/**
 * 游戏语言检测：扫描游戏 data 目录的 json 文本构成，按独占特征计数多数决
 * 判定语言族（日文假名 / 俄语西里尔 / 韩语谚文为强特征；仅汉字且量足够时
 * 判中文）。宿主无关，MV/MZ/Tyrano 等本地目录形态的 Web 宿主均可使用。
 */
internal object GameLanguageDetector {

    private const val SCAN_BYTES_PER_FILE = 64 * 1024
    private const val MAX_SCAN_FILES = 6
    private const val CHINESE_HAN_THRESHOLD = 200

    fun detect(gameRoot: File): GameLanguage {
        val dataDir = File(gameRoot, "data")
        val files = dataDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.take(MAX_SCAN_FILES) ?: return GameLanguage.LATIN
        val votes = mutableMapOf<GameLanguage, Int>()
        for (f in files) {
            try {
                f.inputStream().use { input ->
                    val buf = ByteArray(SCAN_BYTES_PER_FILE)
                    val n = input.read(buf)
                    val text = if (n > 0) String(buf, 0, n, Charsets.UTF_8) else ""
                    for (lang in GameLanguage.entries) {
                        if (!lang.isDetectable) continue
                        var count = 0
                        for (c in text) {
                            if (lang.signatureRanges.any { c.code in it }) count++
                        }
                        if (count > 0) votes[lang] = (votes[lang] ?: 0) + count
                    }
                }
            } catch (t: Throwable) {
                // 单文件读取失败跳过
            }
        }
        // 强特征（假名/西里尔/谚文）优先
        val strong = votes.entries
            .filter { it.key != GameLanguage.CHINESE }
            .maxByOrNull { it.value }
        if (strong != null) return strong.key
        // 无强特征：汉字量足够判中文，否则默认西文
        return if ((votes[GameLanguage.CHINESE] ?: 0) >= CHINESE_HAN_THRESHOLD) {
            GameLanguage.CHINESE
        } else {
            GameLanguage.LATIN
        }
    }
}

/**
 * 资源文件名编码回退匹配器：请求名在目录中精确/大小写匹配失败时，按语言
 * 还原链把磁盘损坏名重解释后比对（磁盘名 bytes[from] 解码为 [to] 等于请求名）。
 */
internal object EncodedFileNameResolver {

    /**
     * 在 root 内解析 uri（"/" 分隔的相对路径），编码回退按 language 的还原链。
     * 命中返回规范文件，失败返回 null。
     */
    fun resolve(root: File, uri: String, language: GameLanguage): File? {
        val chains = language.restoreChains
        if (chains.isEmpty() || uri.contains("..")) return null
        val parts = uri.split("/")
        var current = root
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty()) continue
            val exact = File(current, part)
            val isLast = index == parts.size - 1
            if (exact.exists() && (isLast || exact.isDirectory)) {
                current = exact
                continue
            }
            val children = current.listFiles() ?: return null
            var matched: File? = null
            for (child in children) {
                if (child.name.equals(part, ignoreCase = true)) { matched = child; break }
                for ((from, to) in chains) {
                    val restored = runCatching {
                        val bytes = child.name.toByteArray(charset(from))
                        String(bytes, charset(to))
                    }.getOrNull() ?: continue
                    if (restored.equals(part, ignoreCase = true)) { matched = child; break }
                }
                if (matched != null) break
            }
            if (matched == null) return null
            current = matched
        }
        val target = current.canonicalFile
        val rootPath = root.canonicalPath
        val targetPath = target.path
        if (targetPath != rootPath && !targetPath.startsWith(rootPath + File.separator)) {
            return null
        }
        return if (target.isFile) target else null
    }
}
