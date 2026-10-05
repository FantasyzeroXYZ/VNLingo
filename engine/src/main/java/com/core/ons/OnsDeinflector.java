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

    // ---- 数据模型 ----

    private static final class Rule {
        final String suffix;        // 要匹配的后缀（活用形）
        final String removeSuffix;  // 还原后追加的串
        final String[] conditionsIn;
        final String[] conditionsOut;

        Rule(String suffix, String removeSuffix, String[] conditionsIn, String[] conditionsOut) {
            this.suffix = suffix;
            this.removeSuffix = removeSuffix;
            this.conditionsIn = conditionsIn;
            this.conditionsOut = conditionsOut;
        }
    }

    private static final class DeinflectionResult {
        final String term;
        final int score;

        DeinflectionResult(String term, int score) {
            this.term = term;
            this.score = score;
        }
    }

    private static final Map<String, JSONObject> sConditions = new HashMap<>();
    private static final List<Rule> sRules = new ArrayList<>();
    private static final List<String> sEntryConds = new ArrayList<>();

    private OnsDeinflector() {
    }

    // ---- 初始化 ----

    /** 载入规则数据（幂等；需 Context 读 assets，面板构造时调用一次）。 */
    public static synchronized void init(Context context) {
        if (sLoaded) return;
        try {
            InputStream in = context.getAssets().open("deinflect_ja.json");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            JSONObject root = new JSONObject(bos.toString("UTF-8"));

            JSONObject conds = root.optJSONObject("conditions");
            if (conds != null) {
                java.util.Iterator<String> it = conds.keys();
                while (it.hasNext()) {
                    String key = it.next();
                    sConditions.put(key, conds.optJSONObject(key));
                    sEntryConds.add(key);
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
                        if (r != null) sRules.add(new Rule(
                                r.optString("suffix", ""),
                                r.optString("removeSuffix", ""),
                                toStringArray(r.optJSONArray("conditionsIn")),
                                toStringArray(r.optJSONArray("conditionsOut"))));
                    }
                }
            }
            sLoaded = true;
            Log.i(TAG, "loaded: " + sConditions.size() + " conditions, "
                    + sRules.size() + " rules");
        } catch (Throwable t) {
            Log.w(TAG, "load failed (查词将只按原文精确匹配)", t);
        }
    }

    private static String[] toStringArray(JSONArray arr) {
        if (arr == null) return new String[0];
        String[] out = new String[arr.length()];
        for (int i = 0; i < arr.length(); i++) out[i] = arr.optString(i, "");
        return out;
    }

    // ---- 对外接口（保持既有契约：候选原形列表，按深度升序）----

    /**
     * 词形还原：返回词典原形候选（去重、按还原深度升序）。
     * 未初始化/无结果返回空列表。
     */
    public static List<String> deinflect(String query) {
        if (!sLoaded || query == null || query.isEmpty()) return new ArrayList<>();

        // 预处理管线（ja/preprocessors.ts 同序）生成变体，逐变体还原；
        // 自匹配（term === 变体且深度 0）由精确搜索覆盖，跳过
        Map<String, DeinflectionResult> all = new HashMap<>();
        for (String variant : preprocess(query)) {
            Map<String, DeinflectionResult> seen = new HashMap<>();
            recurse(variant, new HashSet<>(sEntryConds), 0, seen);
            for (DeinflectionResult r : seen.values()) {
                if (r.term.equals(variant) && r.score == 0) continue;
                DeinflectionResult existing = all.get(r.term);
                if (existing == null || existing.score > r.score) {
                    all.put(r.term, r);
                }
            }
        }
        List<String> out = new ArrayList<>(all.keySet());
        java.util.Collections.sort(out);
        return out;
    }

    // ---- 递归还原引擎（transform-engine.ts 对齐）----

    private static void recurse(String text, Set<String> currentConds, int depth,
                                Map<String, DeinflectionResult> seen) {
        if (depth > MAX_DEPTH) return;
        for (Rule rule : sRules) {
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
            for (String cond : expandConditions(java.util.Arrays.asList(rule.conditionsOut))) {
                if (isDictionaryForm(cond)) {
                    String key = candidate + "|" + cond;
                    DeinflectionResult existing = seen.get(key);
                    if (existing == null || existing.score > depth + 1) {
                        seen.put(key, new DeinflectionResult(candidate, depth + 1));
                    }
                }
                recurse(candidate,
                        new HashSet<>(java.util.Collections.singletonList(cond)),
                        depth + 1, seen);
            }
        }
    }

    private static boolean isDictionaryForm(String cond) {
        JSONObject def = sConditions.get(cond);
        return def != null && def.optBoolean("isDictionaryForm", false);
    }

    /** 条件名递归展开子条件（isDictionaryForm 条件不展开）。 */
    private static List<String> expandConditions(List<String> condNames) {
        List<String> result = new ArrayList<>();
        for (String name : condNames) {
            JSONObject def = sConditions.get(name);
            JSONArray sub = def == null ? null : def.optJSONArray("subConditions");
            boolean isDictForm = def != null && def.optBoolean("isDictionaryForm", false);
            if (def != null && sub != null && sub.length() > 0 && !isDictForm) {
                List<String> subList = new ArrayList<>();
                for (int i = 0; i < sub.length(); i++) subList.add(sub.optString(i, ""));
                result.addAll(expandConditions(subList));
            } else {
                result.add(name);
            }
        }
        return result;
    }

    // ---- 预处理管线（japanese-util.ts 对齐）----

    private static List<String> preprocess(String text) {
        Set<String> current = new LinkedHashSet<>();
        current.add(text);

        // 与 ja/preprocessors.ts 同序：
        // normalizeCombining → half→full → fullAlnum→half → kata→hira → collapse
        Set<String> step = new LinkedHashSet<>();
        for (String v : current) {
            String c = normalizeCombiningCharacters(v);
            step.add(v);
            if (!c.equals(v)) step.add(c);
        }
        current = step;
        step = new LinkedHashSet<>();
        for (String v : current) {
            String c = convertHalfWidthKanaToFullWidth(v);
            step.add(v);
            if (!c.equals(v)) step.add(c);
        }
        current = step;
        step = new LinkedHashSet<>();
        for (String v : current) {
            String c = convertFullWidthAlphanumericToNormal(v);
            step.add(v);
            if (!c.equals(v)) step.add(c);
        }
        current = step;
        step = new LinkedHashSet<>();
        for (String v : current) {
            String c = convertKatakanaToHiragana(v);
            step.add(v);
            if (!c.equals(v)) step.add(c);
        }
        current = step;
        step = new LinkedHashSet<>();
        for (String v : current) {
            String c = collapseEmphaticSequences(v);
            step.add(v);
            if (!c.equals(v)) step.add(c);
        }
        return new ArrayList<>(step);
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

    private static final String HW = "ｧｨｩｪｫｬｭｮｯｰｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜｦﾝ";
    private static final String FW = "ァィゥェォャュョッーアイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲン";

    private static String convertHalfWidthKanaToFullWidth(String text) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            Character next = i + 1 < text.length() ? Character.valueOf(text.charAt(i + 1)) : null;

            // 濁点／半濁点処理（ﾞ +1 / ﾟ +2，仅可浊音段有效）
            if (next != null && (next == '\uFF8E' || next == '\uFF9F')) {
                int idx = HW.indexOf(ch);
                if (idx >= 0) {
                    char base = FW.charAt(idx);
                    int baseCp = base;
                    int offset = next == '\uFF8E' ? 1 : 2;
                    if ((baseCp >= 0x30AB && baseCp <= 0x30C8)
                            || (baseCp >= 0x30CF && baseCp <= 0x30DB)) {
                        result.append((char) (baseCp + offset));
                        i++;
                        continue;
                    }
                }
            }
            int idx = HW.indexOf(ch);
            result.append(idx >= 0 ? String.valueOf(FW.charAt(idx)) : String.valueOf(ch));
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

    private static char getProlongedHiragana(char prevChar) {
        switch (prevChar) {
            case 'あ': case 'か': case 'さ': case 'た': case 'な':
            case 'は': case 'ま': case 'や': case 'ら': case 'わ':
                return 'あ';
            case 'い': case 'き': case 'し': case 'ち': case 'に':
            case 'ひ': case 'み': case 'り':
                return 'い';
            case 'う': case 'く': case 'す': case 'つ': case 'ぬ':
            case 'ふ': case 'む': case 'ゆ': case 'る':
                return 'う';
            case 'え': case 'け': case 'せ': case 'て': case 'ね':
            case 'へ': case 'め': case 'れ':
                return 'え';
            case 'お': case 'こ': case 'そ': case 'と': case 'の':
            case 'ほ': case 'も': case 'よ': case 'ろ': case 'を':
                return 'お';
            default:
                return prevChar;
        }
    }

    /** 片仮名 → 平仮名（長音符按前字母音跟随；半角カナ先转全角）。 */
    private static String convertKatakanaToHiragana(String text) {
        StringBuilder result = new StringBuilder();
        final int offset = 0x3041 - 0x30A1;
        for (int i = 0; i < text.length(); i++) {
            int cp = text.codePointAt(i);
            if (cp == 0x30FC && result.length() > 0) {
                // 長音符 → 前の文字の母音に合わせて平仮名化
                result.append(getProlongedHiragana(result.charAt(result.length() - 1)));
            }
            if (cp >= 0x30A1 && cp <= 0x30F6) {
                result.appendCodePoint(cp + offset);
            } else if (cp >= 0xFF66 && cp <= 0xFF9F) {
                // 半角カナ→全角カナ→平仮名
                String full = halfWidthToFullWidthSingle(String.valueOf(text.charAt(i)));
                if (!full.isEmpty() && full.charAt(0) >= 0x30A1 && full.charAt(0) <= 0x30F6) {
                    result.append((char) (full.charAt(0) + offset));
                } else {
                    result.append(full);
                }
            } else {
                result.appendCodePoint(cp);
            }
            if (Character.isHighSurrogate(text.charAt(i))) i++;
        }
        return result.toString();
    }

    private static String halfWidthToFullWidthSingle(String ch) {
        int idx = HW.indexOf(ch);
        return idx >= 0 ? String.valueOf(FW.charAt(idx)) : ch;
    }

    /** 促音・長音符の強調畳み込み。 */
    private static String collapseEmphaticSequences(String text) {
        return text.replace("っっ", "っ").replace("ーー", "ー");
    }
}
