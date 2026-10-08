package com.core.ons;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 词形还原引擎（TrackReader yomitan/deinflection 全量移植：ja 规则 439 条）。
 *
 * 结构（TrackReader packages/domain/src/yomitan/deinflection 对齐）：
 * - conditions：语法条件字典（name / isDictionaryForm / subConditions）
 * - transforms：规则组字典（suffixInflection 规则：suffix/removeSuffix/
 *   conditionsIn/conditionsOut）
 * - 预处理管线：結合文字正規化 → 半角→全角 → 全角英数→半角 →
 *   片仮名→平仮名（含長音符母音跟随）→ 促音畳み込み
 * - 引擎：递归应用规则至收敛，isDictionaryForm 条件收集候选，
 *   term 去重保留最低 score，最大还原深度 10
 *
 * 规则数据来自 assets/deinflect_ja.json（由 TrackReader ja 描述符经
 * esbuild + node 导出，脚本 scripts/emit-deinflect-ja.mjs）。
 * 接口：{@link #deinflect(String)} 返回词典原形候选（按还原深度排序），
 * 供查词扫描逐候选试查。需先 {@link #init(Context)} 载入规则。
 */
public final class OnsDeinflector {

    private static final String TAG = "OnsDeinflector";
    private static final int MAX_DEPTH = 10;

    private static volatile boolean sLoaded = false;

    private static final String[] LANGUAGES = {"ja", "en", "ko", "zh", "vi"};

    // ---- 数据模型 ----

    /** 变换规则（yomitan suffixInflection：suffix 活用尾 → removeSuffix 还原串）。 */
    private static final class Rule {
        final String suffix;
        final String removeSuffix;
        final String[] conditionsIn;
        final String[] conditionsOut;

        Rule(String suffix, String removeSuffix, String[] conditionsIn, String[] conditionsOut) {
            this.suffix = suffix;
            this.removeSuffix = removeSuffix;
            this.conditionsIn = conditionsIn;
            this.conditionsOut = conditionsOut;
        }
    }

    /**
     * 还原结果（M1：对齐参考 transform-engine 的 DeinflectionResult）。
     *
     * <p>相对参考新增 {@link #path}：参考只保留终止条件（`conditions: newConds`），
     * 面板/制卡需要「从输入到该候选的完整条件链」才能显示还原路径（参考侧只有
     * 声明未实现的 `trace` 选项），故在递归时累积条件链；去重键与 score 语义
     * 与参考完全一致，path 只作为附加信息，不参与去重。
     */
    public static final class Result {
        /** 还原后的词典原形候选。 */
        public final String term;
        /** 原始查询串（未预处理）。 */
        public final String rawSource;
        /** 终止条件（对齐参考的 `conditions`，单元素）。 */
        public final List<String> conditions;
        /** 从输入到该候选的条件链（含终止条件；供显示还原路径 A→B→C）。 */
        public final List<String> path;
        /** 还原深度（规则应用次数，越小越可信）。 */
        public final int score;

        Result(String term, String rawSource, List<String> conditions, List<String> path, int score) {
            this.term = term;
            this.rawSource = rawSource;
            this.conditions = conditions;
            this.path = path;
            this.score = score;
        }

        /** 还原路径字符串（如 `-te→v1`），供面板/制卡 `deinflection` 要素使用。 */
        public String pathString() {
            if (path.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < path.size(); i++) {
                if (i > 0) sb.append('→');
                sb.append(path.get(i));
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return term + "(score=" + score + ", path=" + pathString() + ")";
        }
    }

    /** 单语言规则集：conditions 字典 + 变换规则 + 词典形条件集合 + 预处理器名。 */
    private static final class LangRules {
        final String language;
        final Map<String, JSONObject> conditions = new HashMap<>();
        final List<Rule> rules = new ArrayList<>();
        final Set<String> entryConds = new HashSet<>();
        /** 语言预处理管线（JSON 顺序，按名字映射到本类实现）。 */
        final List<String> preprocessors = new ArrayList<>();
        final boolean ja;

        LangRules(String language) {
            this.language = language;
            this.ja = "ja".equals(language);
        }
    }

    private static final Map<String, LangRules> sLangs = new HashMap<>();

    /**
     * 还原结果 LRU 缓存（B2）：游戏内点选同一句时同一候选会被反复还原
     * （递减扫描每层都调 deinflect），词典页输入防抖也常重复。
     * M1 起缓存的是带路径/深度的结果（键含语言，语言设置不同结果不同）；
     * 值列表只读——调用方一律只遍历（见 OnsDictStore / OnsExtractPanel）。
     */
    private static final int CACHE_MAX = 512;
    private static final Map<String, List<Result>> sCache =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, List<Result>>(64, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, List<Result>> eldest) {
                            return size() > CACHE_MAX;
                        }
                    });

    private OnsDeinflector() {
    }

    // ---- 初始化 ----

    /** 载入全部语言规则（幂等；需 Context 读 assets，面板构造时调用一次）。 */
    public static synchronized void init(Context context) {
        if (sLoaded) return;
        Map<String, String> json = new HashMap<>();
        for (String lang : LANGUAGES) {
            String text = readAsset(context, "deinflect_" + lang + ".json");
            if (text != null) json.put(lang, text);
        }
        initFromJson(json);
    }

    /**
     * 从 JSON 文本装载规则（测试/诊断入口，也是 {@link #init(Context)} 的实现）。
     * 键为语言代码，值为 `deinflect_&lt;lang&gt;.json` 的内容。
     */
    public static synchronized void initFromJson(Map<String, String> jsonByLang) {
        sLangs.clear();
        StringBuilder summary = new StringBuilder();
        for (String lang : LANGUAGES) {
            LangRules lr = new LangRules(lang);
            String text = jsonByLang == null ? null : jsonByLang.get(lang);
            if (text != null) parseLanguage(text, lr);
            sLangs.put(lang, lr);
            summary.append(lang).append('=').append(lr.rules.size()).append(' ');
        }
        sLoaded = true;
        sCache.clear();
        Log.i(TAG, "loaded: " + summary);
    }

    private static String readAsset(Context context, String asset) {
        try {
            InputStream in = context.getAssets().open(asset);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toString("UTF-8");
        } catch (Throwable t) {
            Log.w(TAG, "load failed for " + asset + " (该语言将只按原文精确匹配)", t);
            return null;
        }
    }

    private static boolean parseLanguage(String jsonText, LangRules lr) {
        try {
            JSONObject root = new JSONObject(jsonText);

            JSONObject conds = root.optJSONObject("conditions");
            if (conds != null) {
                java.util.Iterator<String> it = conds.keys();
                while (it.hasNext()) {
                    String key = it.next();
                    lr.conditions.put(key, conds.optJSONObject(key));
                    lr.entryConds.add(key);
                }
            }

            JSONObject trans = root.optJSONObject("transforms");
            if (trans != null) {
                java.util.Iterator<String> it = trans.keys();
                while (it.hasNext()) {
                    String key = it.next();
                    JSONObject t = trans.optJSONObject(key);
                    if (t == null) continue;
                    JSONArray rules = t.optJSONArray("rules");
                    if (rules == null) continue;
                    for (int i = 0; i < rules.length(); i++) {
                        JSONObject r = rules.optJSONObject(i);
                        if (r != null) lr.rules.add(new Rule(
                                r.optString("suffix", ""),
                                r.optString("removeSuffix", ""),
                                toStringArray(r.optJSONArray("conditionsIn")),
                                toStringArray(r.optJSONArray("conditionsOut"))));
                    }
                }
            }

            // 预处理管线：JSON 里只有名字，需按名映射到本类实现（M3 全语言）
            JSONArray pre = root.optJSONArray("preprocessors");
            if (pre != null) {
                for (int i = 0; i < pre.length(); i++) {
                    String name = pre.optString(i, "");
                    if (!name.isEmpty()) lr.preprocessors.add(name);
                }
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "parse failed for lang=" + lr.language
                    + " (该语言将只按原文精确匹配)", t);
            return false;
        }
    }

    private static String[] toStringArray(JSONArray arr) {
        if (arr == null) return new String[0];
        String[] out = new String[arr.length()];
        for (int i = 0; i < arr.length(); i++) out[i] = arr.optString(i, "");
        return out;
    }

    // ---- 对外接口 ----

    /**
     * 词形还原：返回词典原形候选（去重、按还原深度升序，同深度按 term 字典序）。
     * 未初始化/无结果返回空列表。语言按文字脚本分派（见类注释）。
     */
    public static List<String> deinflect(String query) {
        List<String> out = new ArrayList<>();
        for (Result r : deinflectDetailed(query)) out.add(r.term);
        return out;
    }

    /** 词形还原（带路径与深度）。语言按文字脚本分派。 */
    public static List<Result> deinflectDetailed(String query) {
        return deinflectDetailed(query, null);
    }

    /**
     * 词形还原（带路径与深度），可指定查询语言（M1/M4）。
     *
     * @param query        查询串
     * @param languageHint 指定语言（ja/en/ko/zh/vi）；null 或未知 → 按文字脚本分派
     * @return 按 score 升序、同分按 term 字典序；未初始化/无规则返回空列表
     */
    public static List<Result> deinflectDetailed(String query, String languageHint) {
        List<Result> empty = java.util.Collections.emptyList();
        if (query == null || query.isEmpty()) return empty;

        LangRules lang = languageHint == null ? null : sLangs.get(languageHint);
        if (lang == null) lang = selectLang(query);

        final String cacheKey = (lang != null ? lang.language : "?") + "|" + query;
        if (sLoaded) {
            List<Result> cached = sCache.get(cacheKey);
            if (cached != null) return cached;
        }
        if (lang == null || lang.rules.isEmpty()) {
            if (sLoaded) sCache.put(cacheKey, empty);
            return empty;
        }

        // deinflectWithPreprocess：先跑该语言的预处理管线，再逐变体还原，
        // 按 term 去重保留最低 score
        Map<String, Result> all = new HashMap<>();
        for (String variant : preprocess(query, lang)) {
            Map<String, Result> seen = new HashMap<>();
            List<String> entryPath = new ArrayList<>();
            recurse(variant, lang.entryConds, 0, variant, seen, entryPath, lang);
            for (Result r : seen.values()) {
                Result existing = all.get(r.term);
                if (existing == null || existing.score > r.score) all.put(r.term, r);
            }
        }
        List<Result> out = new ArrayList<>(all.values());
        java.util.Collections.sort(out, (a, b) -> {
            int c = Integer.compare(a.score, b.score);   // 参考：按 score 升序
            return c != 0 ? c : a.term.compareTo(b.term); // 同分按 term 字典序（稳定）
        });
        if (sLoaded) sCache.put(cacheKey, out);
        return out;
    }

    /** ASCII 判定：英文规则（deinflect_en.json）与其它语言规则的分派依据之一。 */
    private static boolean isAsciiWord(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) return false;
        }
        return true;
    }

    /**
     * 该查询在该语言下的**预处理变体**（不含原文本身）。
     *
     * <p>用途：中文/越南语规则表为空，`deinflectDetailed` 会丢弃 score=0 的自匹配
     * （对齐参考 `r.term === r.rawSource && r.score === 0` 的跳过逻辑），于是
     * 「ＡＢＣ → ABC」「繁体 → 简体」这类规范化变体永远到不了查词层。计划验收要求
     * 「中文模式下全角英数与 NFKC 变体可命中」，因此在查询层补一次**字面变体查表**：
     * 只在前面的 term/reading/查表/规则还原都没命中时兜底，不影响既有命中顺序。
     *
     * @return 变体列表（去重、去原文；无变体时为空表）
     */
    public static List<String> preprocessVariants(String query, String languageHint) {
        List<String> out = new ArrayList<>();
        if (query == null || query.isEmpty()) return out;
        LangRules lang = languageHint == null ? null : sLangs.get(languageHint);
        if (lang == null) lang = selectLang(query);
        if (lang == null) return out;
        for (String variant : preprocess(query, lang)) {
            if (variant == null || variant.isEmpty() || variant.equals(query)) continue;
            if (!out.contains(variant)) out.add(variant);
        }
        return out;
    }

    /** 语言分派（默认汉字归 ja）：假名→ja、谚文→ko、汉字→ja、其余→en。 */
    private static LangRules selectLang(String s) {
        return sLangs.get(detectLanguage(s, "ja"));
    }

    /**
     * 语言判定（M4）：显式「自动」下的分派规则，供面板在查读音/规则选择前调用。
     *
     * <ul>
     *   <li>假名（平/片假名、半角假名）→ ja</li>
     *   <li>谚文 → ko</li>
     *   <li>汉字 → {@code hanPriority}（ja = 默认，与参考 scope 行为一致；zh = 中文优先）</li>
     *   <li>其余（拉丁等）→ en</li>
     * </ul>
     */
    public static String detectLanguage(String s, String hanPriority) {
        final String han = "zh".equals(hanPriority) ? "zh" : "ja";
        if (s == null) return "en";
        for (int i = 0; i < s.length(); i++) {
            int cp = s.codePointAt(i);
            if (JapaneseUtil.isCodePointKanaExtended(cp)) return "ja";
            if ((cp >= 0xAC00 && cp <= 0xD7AF) || (cp >= 0x1100 && cp <= 0x11FF)) {
                return "ko";
            }
            if (JapaneseUtil.isCodePointKanji(cp)) return han;
        }
        return "en";
    }

    // ---- 递归还原引擎（transform-engine.ts 对齐）----

    private static void recurse(String text, Set<String> currentConds, int depth, String rawSource,
                                Map<String, Result> seen, List<String> path, LangRules lang) {
        if (depth > MAX_DEPTH) return;
        for (Rule rule : lang.rules) {
            // 条件检查：conditionsIn 非空时须与当前条件集有交集
            if (rule.conditionsIn.length > 0) {
                boolean ok = false;
                for (String c : rule.conditionsIn) {
                    if (currentConds.contains(c)) { ok = true; break; }
                }
                if (!ok) continue;
            }
            // 后缀匹配（整词即后缀且无还原串 → 拒绝，避免产出空词）
            final int suffixLen = rule.suffix.length();
            if (suffixLen > 0) {
                if (!text.endsWith(rule.suffix)) continue;
                if (text.equals(rule.suffix) && rule.removeSuffix.isEmpty()) continue;
            }
            String stem = suffixLen > 0
                    ? text.substring(0, text.length() - suffixLen) : text;
            String candidate = stem + rule.removeSuffix;
            if (candidate.equals(text)) continue;

            // 出口条件展开（子条件递归；isDictionaryForm 条件不展开）
            for (String cond : expandConditions(java.util.Arrays.asList(rule.conditionsOut), lang)) {
                List<String> newPath = new ArrayList<>(path);
                newPath.add(cond);
                if (isDictionaryForm(cond, lang)) {
                    String key = candidate + "|" + cond;
                    Result existing = seen.get(key);
                    if (existing == null || existing.score > depth + 1) {
                        List<String> conds = new ArrayList<>();
                        conds.add(cond);
                        seen.put(key, new Result(candidate, rawSource, conds, newPath, depth + 1));
                    }
                }
                Set<String> next = new HashSet<>();
                next.add(cond);
                recurse(candidate, next, depth + 1, rawSource, seen, newPath, lang);
            }
        }
    }

    private static boolean isDictionaryForm(String cond, LangRules lang) {
        JSONObject def = lang.conditions.get(cond);
        return def != null && def.optBoolean("isDictionaryForm", false);
    }

    /** 条件名递归展开子条件（isDictionaryForm 条件不展开）。 */
    private static List<String> expandConditions(List<String> condNames, LangRules lang) {
        List<String> result = new ArrayList<>();
        for (String name : condNames) {
            JSONObject def = lang.conditions.get(name);
            JSONArray sub = def == null ? null : def.optJSONArray("subConditions");
            boolean isDictForm = def != null && def.optBoolean("isDictionaryForm", false);
            if (def != null && sub != null && sub.length() > 0 && !isDictForm) {
                List<String> subList = new ArrayList<>();
                for (int i = 0; i < sub.length(); i++) subList.add(sub.optString(i, ""));
                result.addAll(expandConditions(subList, lang));
            } else {
                result.add(name);
            }
        }
        return result;
    }


    // ---- 预处理管线（ja/preprocessors.ts、zh/transforms.ts 对齐）----

    /**
     * 运行该语言的预处理管线（按 JSON 里的名字映射到实现，与参考 `preprocess` 同序）。
     * 未知名/未实现的处理器**原样保留变体**（对齐参考的 try/catch 兜底），
     * 因此新增语言只加名字不会破坏行为。
     */
    private static List<String> preprocess(String text, LangRules lang) {
        Set<String> current = new LinkedHashSet<>();
        current.add(text);
        for (String name : lang.preprocessors) {
            Set<String> next = new LinkedHashSet<>();
            for (String v : current) {
                String converted = applyPreprocessor(name, v);
                next.add(v);                       // 参考：返回 [str, result]（保留原文）
                if (converted != null && !converted.equals(v)) next.add(converted);
            }
            current = next;
        }
        return new ArrayList<>(current);
    }

    /** 预处理器名字 → 实现。名字取自 assets/deinflect_*.json 的 `preprocessors`。 */
    private static String applyPreprocessor(String name, String text) {
        switch (name) {
            case "結合文字の正規化":
                return normalizeCombiningCharacters(text);
            case "半角→全角変換":
                // 委托 JapaneseUtil（M5 移植）：本地旧实现把半角浊点判成 \uFF8E，
                // 真正的「ﾞ」是 \uFF9E，导致 ﾂﾞ 这类永远合不成 ヅ（已修）
                return JapaneseUtil.convertHalfWidthKanaToFullWidth(text);
            case "全角英数→半角英数":
                return convertFullWidthAlphanumericToNormal(text);
            case "カタカナ→ひらがな":
                return JapaneseUtil.convertKatakanaToHiragana(text);
            case "強調音の畳み込み":
                return collapseEmphaticSequences(text);
            case "繁体→简体":
                // 参考实现：NFKC 规范化（注释名是繁体→简体，实际只做 NFKC）
                try {
                    return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC);
                } catch (Throwable t) {
                    return text;
                }
            default:
                return text;   // 未知处理器：保留原文（对齐参考兜底）
        }
    }

    private static String normalizeCombiningCharacters(String text) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ((ch == '\u3099' || ch == '\u309A') && result.length() > 0) {
                char prev = result.charAt(result.length() - 1);
                int prevCp = prev;
                int offset = ch == '\u3099' ? 1 : 2;
                if ((prevCp >= 0x3041 && prevCp <= 0x3068)
                        || (prevCp >= 0x306F && prevCp <= 0x307B)
                        || (prevCp >= 0x30AB && prevCp <= 0x30C8)
                        || (prevCp >= 0x30CF && prevCp <= 0x30DB)) {
                    result.setCharAt(result.length() - 1, (char) (prevCp + offset));
                    continue;
                }
            }
            result.append(ch);
        }
        return result.toString();
    }

    private static String convertFullWidthAlphanumericToNormal(String text) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0xFF10 && c <= 0xFF19) result.append((char) (c - 0xFF10 + '0'));
            else if (c >= 0xFF21 && c <= 0xFF3A) result.append((char) (c - 0xFF21 + 'A'));
            else if (c >= 0xFF41 && c <= 0xFF5A) result.append((char) (c - 0xFF41 + 'a'));
            else result.append(c);
        }
        return result.toString();
    }

    /** 促音・長音符の強調畳み込み。 */
    private static String collapseEmphaticSequences(String text) {
        return text.replace("っっ", "っ").replace("ーー", "ー");
    }
}
