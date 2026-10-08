package com.core.ons;

import java.util.Set;

/**
 * 日本語ユーティリティ（M5 按需移植 TrackReader
 * {@code yomitan/deinflection/ja/japanese-util.ts}）。
 *
 * <p>计划里点名优先的两个：{@link #isStringEntirelyKana}（读音查询与语言判定用）与
 * {@link #getKanaMoraCount}（读音长度校验用）；其余文字种判定与假名转换一并移植，
 * 便于 {@link OnsDeinflector} 与面板共用同一套 Unicode 范围（避免两处范围漂移）。
 *
 * <p>纯函数、无副作用，与参考实现逐分支对齐（含半角カナ映射与長音符母音化）。
 */
public final class JapaneseUtil {

    private JapaneseUtil() {
    }

    private static final int HIRAGANA_LO = 0x3040, HIRAGANA_HI = 0x309F;
    private static final int KATAKANA_LO = 0x30A0, KATAKANA_HI = 0x30FF;
    private static final int HIRAGANA_CONV_LO = 0x3041, HIRAGANA_CONV_HI = 0x3096;
    private static final int KATAKANA_CONV_LO = 0x30A1, KATAKANA_CONV_HI = 0x30F6;

    /** 小書き仮名（モーラ数で前の文字に吸収される）。 */
    private static final Set<Integer> SMALL_KANA = Set.of(
            0x3041, 0x3043, 0x3045, 0x3047, 0x3049, 0x3083, 0x3085, 0x3087, 0x308E,
            0x30A1, 0x30A3, 0x30A5, 0x30A7, 0x30A9, 0x30E3, 0x30E5, 0x30E7, 0x30EE,
            0x3063, 0x30C3);   // っ / ッ

    /** 半角カナ → 全角カナ（参考 HALFWIDTH_MAP 的等价映射）。 */
    private static char halfWidthToFull(char c) {
        switch (c) {
            case 'ｧ': return 'ァ';
            case 'ｨ': return 'ィ';
            case 'ｩ': return 'ゥ';
            case 'ｪ': return 'ェ';
            case 'ｫ': return 'ォ';
            case 'ｬ': return 'ャ';
            case 'ｭ': return 'ュ';
            case 'ｮ': return 'ョ';
            case 'ｯ': return 'ッ';
            case 'ｰ': return 'ー';
            case 'ｱ': return 'ア';
            case 'ｲ': return 'イ';
            case 'ｳ': return 'ウ';
            case 'ｴ': return 'エ';
            case 'ｵ': return 'オ';
            case 'ｶ': return 'カ';
            case 'ｷ': return 'キ';
            case 'ｸ': return 'ク';
            case 'ｹ': return 'ケ';
            case 'ｺ': return 'コ';
            case 'ｻ': return 'サ';
            case 'ｼ': return 'シ';
            case 'ｽ': return 'ス';
            case 'ｾ': return 'セ';
            case 'ｿ': return 'ソ';
            case 'ﾀ': return 'タ';
            case 'ﾁ': return 'チ';
            case 'ﾂ': return 'ツ';
            case 'ﾃ': return 'テ';
            case 'ﾄ': return 'ト';
            case 'ﾅ': return 'ナ';
            case 'ﾆ': return 'ニ';
            case 'ﾇ': return 'ヌ';
            case 'ﾈ': return 'ネ';
            case 'ﾉ': return 'ノ';
            case 'ﾊ': return 'ハ';
            case 'ﾋ': return 'ヒ';
            case 'ﾌ': return 'フ';
            case 'ﾍ': return 'ヘ';
            case 'ﾎ': return 'ホ';
            case 'ﾏ': return 'マ';
            case 'ﾐ': return 'ミ';
            case 'ﾑ': return 'ム';
            case 'ﾒ': return 'メ';
            case 'ﾓ': return 'モ';
            case 'ﾔ': return 'ヤ';
            case 'ﾕ': return 'ユ';
            case 'ﾖ': return 'ヨ';
            case 'ﾗ': return 'ラ';
            case 'ﾘ': return 'リ';
            case 'ﾙ': return 'ル';
            case 'ﾚ': return 'レ';
            case 'ﾛ': return 'ロ';
            case 'ﾜ': return 'ワ';
            case 'ｦ': return 'ヲ';
            case 'ﾝ': return 'ン';
            case 'ﾞ': return '゛';
            case 'ﾟ': return '゜';
            default: return c;
        }
    }

    // ---- 文字種判定 ----

    public static boolean isCodePointKanji(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x20000 && cp <= 0x2A6DF);
    }

    public static boolean isCodePointKana(int cp) {
        // 严格对齐参考 KANA_RANGES：仅平假名 [3040,309F] 与片假名 [30A0,30FF]。
        // 半角カナ(FF66-FF9F) 与片假名拡張(31F0-31FF) **不在**参考范围内——
        // 语言分派需要它们时用 isCodePointKanaExtended（项目扩展，见下）。
        return (cp >= HIRAGANA_LO && cp <= HIRAGANA_HI)
                || (cp >= KATAKANA_LO && cp <= KATAKANA_HI);
    }

    /**
     * 语言分派用扩展假名判定（项目扩展，非参考语义）：在 {@link #isCodePointKana} 基础上
     * 纳入片假名拡張与半角カナ——面板递减扫描拿到的可能是半角片假名文本（如 ﾂﾂﾞｸ），
     * 必须判成日语才会走日语预处理管线。
     */
    public static boolean isCodePointKanaExtended(int cp) {
        return isCodePointKana(cp)
                || (cp >= 0x31F0 && cp <= 0x31FF)
                || (cp >= 0xFF66 && cp <= 0xFF9F);
    }

    public static boolean isCodePointJapanese(int cp) {
        return isCodePointKana(cp) || isCodePointKanji(cp);
    }

    /** 全部为假名（空串为 false，对齐参考）。 */
    public static boolean isStringEntirelyKana(String str) {
        if (str == null || str.isEmpty()) return false;
        for (int i = 0; i < str.length(); ) {
            int cp = str.codePointAt(i);
            if (!isCodePointKana(cp)) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    public static boolean isStringEntirelyJapanese(String str) {
        if (str == null || str.isEmpty()) return false;
        for (int i = 0; i < str.length(); ) {
            int cp = str.codePointAt(i);
            if (!isCodePointJapanese(cp)) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    // ---- 假名変換 ----

    /** 平仮名 → 片仮名（参考 convertHiraganaToKatakana）。 */
    public static String convertHiraganaToKatakana(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp >= HIRAGANA_CONV_LO && cp <= HIRAGANA_CONV_HI) {
                sb.appendCodePoint(cp + (KATAKANA_CONV_LO - HIRAGANA_CONV_LO));
            } else {
                sb.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    /** 片仮名 → 平仮名（含半角カナ与長音符母音化）。 */
    public static String convertKatakanaToHiragana(String text) {
        if (text == null) return "";
        int offset = HIRAGANA_CONV_LO - KATAKANA_CONV_LO;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp == 0x30FC && sb.length() > 0) {
                String prolonged = prolongedHiragana(sb.charAt(sb.length() - 1));
                if (prolonged != null) {
                    sb.append(prolonged);
                    i += Character.charCount(cp);
                    continue;
                }
            }
            if (cp >= KATAKANA_CONV_LO && cp <= KATAKANA_CONV_HI) {
                sb.appendCodePoint(cp + offset);
            } else if (cp >= 0xFF66 && cp <= 0xFF9F) {
                char full = halfWidthToFull((char) cp);
                int cp2 = full;
                if (cp2 >= KATAKANA_CONV_LO && cp2 <= KATAKANA_CONV_HI) {
                    sb.appendCodePoint(cp2 + offset);
                } else {
                    sb.append(full);
                }
            } else {
                sb.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    /** 長音符の母音（参考 getProlongedHiragana）。 */
    private static String prolongedHiragana(char prev) {
        switch (prev) {
            case 'あ': case 'か': case 'さ': case 'た': case 'な':
            case 'は': case 'ま': case 'や': case 'ら': case 'わ':
                return "あ";
            case 'い': case 'き': case 'し': case 'ち': case 'に':
            case 'ひ': case 'み': case 'り':
                return "い";
            case 'う': case 'く': case 'す': case 'つ': case 'ぬ':
            case 'ふ': case 'む': case 'ゆ': case 'る':
                return "う";
            case 'え': case 'け': case 'せ': case 'て': case 'ね':
            case 'へ': case 'め': case 'れ':
                return "え";
            case 'お': case 'こ': case 'そ': case 'と': case 'の':
            case 'ほ': case 'も': case 'よ': case 'ろ': case 'を':
                return "う";
            default:
                return null;
        }
    }

    /** 半角カナ → 全角カナ（濁点・半濁点の合成付き）。 */
    public static String convertHalfWidthKanaToFullWidth(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (next == 'ﾞ' || next == 'ﾟ') {
                char base = halfWidthToFull(ch);
                if (base != ch) {
                    int baseCp = base;
                    int off = next == 'ﾞ' ? 1 : 2;
                    if ((baseCp >= 0x30AB && baseCp <= 0x30C8)
                            || (baseCp >= 0x30CF && baseCp <= 0x30DB)) {
                        sb.appendCodePoint(baseCp + off);
                        i++;
                        continue;
                    }
                }
            }
            sb.append(halfWidthToFull(ch));
        }
        return sb.toString();
    }

    /** モーラ数（小書き仮名は先行モーラに吸収）。 */
    public static int getKanaMoraCount(String text) {
        if (text == null) return 0;
        int count = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (count == 0 || !SMALL_KANA.contains(cp)) count++;
            i += Character.charCount(cp);
        }
        return count;
    }
}
