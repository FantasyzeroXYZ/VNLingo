package com.core.ons.deinflect;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 词形还原语言包导入（M2：移植 TrackReader yomitan/deinflection/packs/parse.ts）。
 *
 * <p>接受两种形态：
 * <ol>
 *   <li><b>语言包 zip</b>：`lemma_pack_v1.json`
 *       （`{lang, version, entries: [[inflected, [lemma...]], ...]}`）+
 *       可选 `index.json`（`{lang, version, title}`，lang 不一致即报错）；</li>
 *   <li><b>裸 JSON / JSONL</b>：`{lang, version, entries: [...]}`，或对象映射
 *       `{"inflected": ["lemma", ...]}`，或 JSONL（每行 `["inflected", ["lemma"...]]`）。</li>
 * </ol>
 * 解析只做结构与类型清洗（与参考一致：丢非字符串/inflected 为空的项、lemma 去空），
 * 空包视为失败。
 */
public final class DeinflectPackImporter {

    private static final String TAG = "DeinflectPack";
    private static final int MAX_BYTES = 256 * 1024 * 1024;  // 256MB 保险丝

    /** 解析结果：语言、版本、标题（可空）与词条。 */
    public static final class Parsed {
        public String lang = "";
        public String title = "";
        public int version = 1;
        public final List<DeinflectPackStore.Entry> entries = new ArrayList<>();
    }

    private DeinflectPackImporter() {
    }

    /** 从 SAF Uri 解析语言包（自动识别 zip / JSON / JSONL）。失败抛异常，调用方转 toast。 */
    public static Parsed parse(Context context, Uri uri) throws Exception {
        byte[] data;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("无法打开文件");
            data = readAll(in, MAX_BYTES);
        }
        String name = uri.getLastPathSegment();
        if (isZip(data)) {
            Parsed p = parseZip(data);
            p.title = p.title.isEmpty() && name != null ? name : p.title;
            return p;
        }
        Parsed p = parseJsonText(new String(data, "UTF-8"));
        if (p.title.isEmpty() && name != null) p.title = name;
        return p;
    }

    private static byte[] readAll(InputStream in, int maxBytes) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > maxBytes) throw new Exception("文件过大（超过 " + (maxBytes / 1024 / 1024) + "MB）");
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static boolean isZip(byte[] data) {
        return data.length > 4 && data[0] == 'P' && data[1] == 'K'
                && (data[2] == 3 || data[2] == 5 || data[2] == 7);
    }

    /** zip：找 lemma_pack_v1.json（可选校验 index.json 的 lang）。 */
    static Parsed parseZip(byte[] data) throws Exception {
        String packJson = null;
        String indexJson = null;
        try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String entryName = e.getName();
                if (entryName == null) continue;
                String base = entryName.substring(entryName.lastIndexOf('/') + 1);
                if ("lemma_pack_v1.json".equals(base)) {
                    packJson = new String(readAll(zin, MAX_BYTES), "UTF-8");
                } else if ("index.json".equals(base)) {
                    indexJson = new String(readAll(zin, MAX_BYTES), "UTF-8");
                }
            }
        } catch (Throwable t) {
            throw new Exception("zip 解析失败：" + t.getMessage());
        }
        if (packJson == null) throw new Exception("ZIP 中未找到 lemma_pack_v1.json");

        Parsed p = parseJsonText(packJson);
        if (indexJson != null) {
            try {
                JSONObject idx = new JSONObject(indexJson);
                String idxLang = idx.optString("lang", "");
                if (!idxLang.isEmpty() && !idxLang.equals(p.lang)) {
                    throw new Exception("语言不一致：index=" + idxLang + " pack=" + p.lang);
                }
                if (p.version <= 1 && idx.optInt("version", 0) > 0) p.version = idx.optInt("version");
                if (p.title.isEmpty()) p.title = idx.optString("title", "");
            } catch (Exception ex) {
                if (ex.getMessage() != null && ex.getMessage().startsWith("语言不一致")) throw ex;
                // index.json 解析失败不致命（对齐参考）
            }
        }
        return p;
    }

    /**
     * 裸 JSON / JSONL：支持 `{lang, version, entries}`、对象映射与 JSONL 行式数组。
     * 没有 lang 时抛错（无法确定挂到哪个语言下）。
     */
    static Parsed parseJsonText(String text) throws Exception {
        Parsed p = new Parsed();
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) throw new Exception("语言包内容为空");

        if (trimmed.startsWith("{")) {
            JSONObject root = null;
            try {
                root = new JSONObject(trimmed);
            } catch (Throwable ignored) {
                // 多行（JSONL）→ 走下面的逐行解析
            }
            if (root != null) {
                p.lang = root.optString("lang", "");
                p.version = root.optInt("version", 1);
                p.title = root.optString("title", "");
                JSONArray entries = root.optJSONArray("entries");
                JSONObject entriesObj = entries == null ? root.optJSONObject("entries") : null;
                if (entries != null) {
                    addEntriesFromArray(entries, p);
                } else if (entriesObj != null) {
                    // {"lang":"fr","entries":{"inflected":["lemma",...]}}
                    java.util.Iterator<String> keys = entriesObj.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        JSONArray arr = entriesObj.optJSONArray(k);
                        if (arr != null) addEntry(p, k, arr);
                    }
                } else {
                    // 顶层对象映射：{"lang":"fr","inflected":["lemma", ...]}
                    java.util.Iterator<String> keys = root.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        if ("lang".equals(k) || "version".equals(k) || "title".equals(k)) continue;
                        JSONArray arr = root.optJSONArray(k);
                        if (arr != null) addEntry(p, k, arr);
                    }
                }
            }
        }
        if (p.entries.isEmpty()) {
            // JSONL：逐行 ["inflected", ["lemma"...]] 或 {lang, inflected, lemmas}
            for (String line : trimmed.split("\r?\n")) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("//")) continue;
                try {
                    if (s.startsWith("[")) {
                        JSONArray row = new JSONArray(s);
                        if (row.length() >= 2 && row.opt(1) instanceof JSONArray) {
                            addEntry(p, row.optString(0, ""), row.optJSONArray(1));
                        }
                    } else if (s.startsWith("{")) {
                        JSONObject row = new JSONObject(s);
                        if (p.lang.isEmpty()) {
                            p.lang = row.optString("lang", "");
                            p.version = row.optInt("version", p.version);
                        }
                        String inflected = row.optString("inflected",
                                row.optString("i", row.optString("term", "")));
                        JSONArray arr = row.optJSONArray("lemmas");
                        if (arr == null) arr = row.optJSONArray("l");
                        if (inflected.isEmpty()) arr = null;
                        if (arr != null) addEntry(p, inflected, arr);
                    }
                } catch (Throwable ignored) {
                    // 单行不合法：跳过（JSONL 容错）
                }
            }
        }

        if (p.lang.isEmpty()) throw new Exception("语言包缺少 lang 字段");
        if (p.entries.isEmpty()) throw new Exception("语言包不含任何词条");
        Log.i(TAG, "parsed pack lang=" + p.lang + " version=" + p.version
                + " entries=" + p.entries.size());
        return p;
    }

    private static void addEntriesFromArray(JSONArray entries, Parsed p) {
        for (int i = 0; i < entries.length(); i++) {
            JSONArray row = entries.optJSONArray(i);
            if (row == null || row.length() < 2) continue;
            addEntry(p, row.optString(0, ""), row.optJSONArray(1));
        }
    }

    /** 加一条（inflected 为空 / lemma 清洗后为空则跳过）。 */
    private static void addEntry(Parsed p, String inflected, JSONArray lemmas) {
        DeinflectPackStore.Entry e = toEntry(inflected, lemmas);
        if (e.inflected == null || e.inflected.isEmpty() || e.lemmas.isEmpty()) return;
        p.entries.add(e);
    }

    /** 单条：[inflected, [lemma...]]；清洗非字符串、去重、去空（对齐参考 parse）。 */
    private static DeinflectPackStore.Entry toEntry(String inflected, JSONArray lemmas) {
        List<String> out = new ArrayList<>();
        if (lemmas != null) {
            for (int i = 0; i < lemmas.length(); i++) {
                Object o = lemmas.opt(i);
                if (!(o instanceof String)) continue;
                String s = (String) o;
                if (s.isEmpty() || out.contains(s)) continue;
                out.add(s);
            }
        }
        return new DeinflectPackStore.Entry(inflected == null ? "" : inflected, out);
    }

    /** 诊断：把解析结果折叠成 lang→条目数（harness 打印用）。 */
    public static Map<String, Integer> summarize(Parsed p) {
        Map<String, Integer> m = new HashMap<>();
        m.put(p.lang, p.entries.size());
        return m;
    }
}
