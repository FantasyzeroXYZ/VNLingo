package com.core.ons;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 日语词形还原（紧凑版）：查词精确命中失败时生成原形候选。
 *
 * 参考实现 D:\Desktop\test-flutter\anki 用 Yomichan deinflect.json 全量规则表
 * （~950 行）；此处先内置最常见的活用规则（敬体/て形/た形含促音便与い音便/
 * 否定/い形容词/です），覆盖对话文本高频形态；后续可再移植全量规则表。
 */
public final class OnsDeinflector {

    private OnsDeinflector() {
    }

    /** 五段动词连用形（i 段）→ 终止形（u 段）映射，如 飲み→飲む。 */
    private static final String I_ROW = "いきぎしじちにびみり";
    private static final String U_ROW = "うくぐすずつぶむる";
    /** 未然形（a 段）→ 终止形（u 段）映射，如 行か→行く、買わ→買う。 */
    private static final String A_ROW = "かがさざただなばまらわ";
    private static final String A_U_ROW = "くぐすずつづぬぶむるう";

    /** 生成 query 的原形候选（含 query 本身不必再加，调用方先查原词）。 */
    public static List<String> deinflect(String query) {
        Set<String> out = new LinkedHashSet<>();
        if (query == null || query.length() < 2) return new ArrayList<>();
        String q = query;

        // です/ます 系敬体：strip 后 i 段→u 段
        for (String suf : new String[]{"ませんでした", "ました", "ません", "ます"}) {
            if (q.endsWith(suf)) {
                String stem = q.substring(0, q.length() - suf.length());
                if (stem.length() > 0) {
                    out.add(stem + "る"); // 一段动词
                    out.add(convertLast(stem, I_ROW, U_ROW)); // 五段动词
                }
            }
        }
        // て/で 形（含促音便与い音便：書いて→書く、泳いで→泳ぐ）
        if (q.endsWith("って")) {
            String stem = q.substring(0, q.length() - 2);
            out.add(stem + "う");
            out.add(stem + "る");
            out.add(stem + "く");
        } else if (q.endsWith("て") || q.endsWith("で")) {
            boolean voiced = q.endsWith("で");
            String stem = q.substring(0, q.length() - 1);
            out.add(stem + "る"); // 一段动词
            addRenyoukeiVariants(out, stem, voiced);
        }
        // た/だ 形（含促音便与い音便）
        if (q.endsWith("った")) {
            String stem = q.substring(0, q.length() - 2);
            out.add(stem + "う");
            out.add(stem + "る");
            out.add(stem + "く");
        } else if (q.endsWith("た") || q.endsWith("だ")) {
            boolean voiced = q.endsWith("だ");
            String stem = q.substring(0, q.length() - 1);
            out.add(stem + "る"); // 一段动词
            addRenyoukeiVariants(out, stem, voiced);
        }
        // 否定形：strip ない/なかった 后 a 段→u 段（五段）或直接 +る（一段）
        for (String suf : new String[]{"なかった", "なくて", "ない"}) {
            if (q.endsWith(suf)) {
                String stem = q.substring(0, q.length() - suf.length());
                if (stem.length() > 0) {
                    out.add(stem + "る");
                    out.add(convertLast(stem, A_ROW, A_U_ROW));
                }
            }
        }
        // い形容词
        if (q.endsWith("くて") || q.endsWith("かった") || q.endsWith("くない")
                || q.endsWith("くなかった") || q.endsWith("ければ") || q.endsWith("く")) {
            String stem = stripLongest(q, new String[]{"くなかった", "かった", "くて", "くない", "ければ", "く"});
            if (stem.length() > 0) out.add(stem + "い");
        }
        // 名词/敬体断定
        for (String suf : new String[]{"でした", "です", "だ"}) {
            if (q.endsWith(suf)) {
                out.add(q.substring(0, q.length() - suf.length()));
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * 连用形词干（て/た 形 strip 后）→ 五段终止形候选。
     * 促音便：買っ→買う/買る/買く；い音便：書い→書く（清音）、泳ぎ→泳ぐ（浊音）。
     */
    private static void addRenyoukeiVariants(Set<String> out, String stem, boolean voiced) {
        char last = stem.charAt(stem.length() - 1);
        String s = stem.substring(0, stem.length() - 1);
        if (last == 'っ') {
            out.add(s + "う");
            out.add(s + "る");
            out.add(s + "く");
        } else if (last == 'ん') {
            out.add(s + "む");
            out.add(s + "ぶ");
            out.add(s + "ぬ");
        } else if (last == 'い') {
            out.add(s + (voiced ? "ぐ" : "く"));
        } else {
            int i = I_ROW.indexOf(last);
            if (i >= 0) out.add(s + U_ROW.charAt(i));
        }
    }

    /** stem 末字符在 from 行内时替换为 to 行同位置字符。 */
    private static String convertLast(String stem, String from, String to) {
        char last = stem.charAt(stem.length() - 1);
        int i = from.indexOf(last);
        if (i < 0 || i >= to.length()) return stem;
        return stem.substring(0, stem.length() - 1) + to.charAt(i);
    }

    private static String stripLongest(String q, String[] suffixes) {
        for (String suf : suffixes) {
            if (q.endsWith(suf)) return q.substring(0, q.length() - suf.length());
        }
        return q;
    }
}
