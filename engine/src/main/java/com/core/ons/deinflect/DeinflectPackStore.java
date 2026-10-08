package com.core.ons.deinflect;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 词形还原语言包存取（M2：移植 TrackReader yomitan/deinflection/packs/store.ts）。
 *
 * <p>双型包模型：
 * <ul>
 *   <li><b>rules 型</b>（ja/en/ko）：规则表在 APK assets 里，「安装」是既成事实，
 *       可启停、可「卸载」（只打 uninstalled 标记，可零成本恢复），不可真删；</li>
 *   <li><b>table 型</b>：用户导入的反查表（inflected → [lemma...]），可启停、可删除。</li>
 * </ul>
 *
 * <p>存储用独立 SQLite 库 {@code deinflect_packs.db}（不与词典库混用，避免导入/删除
 * 与查词事务互相阻塞）：
 * <pre>
 * pack_meta(lang PK, kind, name, version, enabled, uninstalled, entry_count)
 * pack_entries(lang, inflected, lemmas, PRIMARY KEY(lang, inflected))
 * </pre>
 * 查询按主键单键取（O(log n)），不需要载全表；另有批量 IN 查询供面板逐候选扫描使用。
 *
 * <p>缺省语义（对齐参考）：内置 rules 型缺省启用；未安装/停用/未命中一律返回 null，
 * <b>不抛错</b>——语言包不可用绝不能影响正常查词。
 */
public final class DeinflectPackStore {

    private static final String TAG = "DeinflectPack";
    private static final String DB_NAME = "deinflect_packs.db";

    /** 内置 rules 型语言（对齐参考 packs/builtin.ts：zh/vi 规则表为空故不列）。 */
    public static final String[][] BUILTIN_RULES = {
            {"ja", "日本語（規則活用 439 条）", "1"},
            {"en", "English (regular inflection 40 rules)", "1"},
            {"ko", "한국어（一般语尾 124 条）", "2"},
    };

    public static boolean isBuiltinRulesLang(String lang) {
        if (lang == null) return false;
        for (String[] p : BUILTIN_RULES) {
            if (p[0].equals(lang)) return true;
        }
        return false;
    }

    /** 语言包元数据（UI 列表行）。 */
    public static final class PackInfo {
        public String lang = "";
        public String kind = "rules";
        public String name = "";
        public int version = 1;
        public boolean enabled = true;
        /** rules 型：false = 已卸载（可恢复）；table 型恒 true。 */
        public boolean installed = true;
        public int entryCount;
    }

    public interface Progress {
        void onProgress(String message);
    }

    /** 待导入词条：一形多原形（inflected → [lemma...]）。 */
    public static final class Entry {
        public final String inflected;
        public final List<String> lemmas;

        public Entry(String inflected, List<String> lemmas) {
            this.inflected = inflected;
            this.lemmas = lemmas == null ? new ArrayList<String>() : lemmas;
        }
    }

    private static final DeinflectPackStore INSTANCE = new DeinflectPackStore();

    public static DeinflectPackStore get() {
        return INSTANCE;
    }

    private SQLiteDatabase db;
    private Context appContext;

    private DeinflectPackStore() {
    }

    /** 打开/迁移语言包库（幂等）。面板构造时调用；查不到库时所有查询静默返回空。 */
    public synchronized void init(Context context) {
        if (db != null || context == null) return;
        try {
            appContext = context.getApplicationContext();
            db = appContext.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
            migrate(db);
            Log.i(TAG, "packs db opened process=" + android.os.Process.myPid());
        } catch (Throwable t) {
            Log.w(TAG, "packs db open failed (语言包不可用，不影响查词)", t);
            db = null;
        }
    }

    /**
     * 指定库文件打开（测试/诊断入口，也是 {@link #init(Context)} 的底层）。
     * 供真机 app_process harness 在没有 Context 的情况下验证语言包语义。
     */
    public synchronized void initAt(String path) {
        if (db != null) return;
        try {
            db = SQLiteDatabase.openOrCreateDatabase(new File(path), null);
            migrate(db);
            Log.i(TAG, "packs db opened at " + path);
        } catch (Throwable t) {
            Log.w(TAG, "packs db open failed at " + path, t);
            db = null;
        }
    }

    private static void migrate(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE IF NOT EXISTS pack_meta("
                + "lang TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL DEFAULT 'rules',"
                + "name TEXT NOT NULL DEFAULT '', version INTEGER NOT NULL DEFAULT 1,"
                + "enabled INTEGER NOT NULL DEFAULT 1, uninstalled INTEGER NOT NULL DEFAULT 0,"
                + "entry_count INTEGER NOT NULL DEFAULT 0)");
        database.execSQL("CREATE TABLE IF NOT EXISTS pack_entries("
                + "lang TEXT NOT NULL, inflected TEXT NOT NULL, lemmas TEXT NOT NULL,"
                + "PRIMARY KEY(lang, inflected))");
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_pack_entries_lang ON pack_entries(lang)");
    }

    private synchronized SQLiteDatabase dbOrNull() {
        return db;
    }

    // ---------- 列表与启停 ----------

    /** 全部语言包（内置 rules 型恒在列表里 + 已导入的 table 型），按 lang 排序。 */
    public synchronized List<PackInfo> listPacks() {
        List<PackInfo> out = new ArrayList<>();
        SQLiteDatabase database = dbOrNull();
        if (database == null) return out;
        Map<String, PackInfo> byLang = new LinkedHashMap<>();
        Cursor c = null;
        try {
            c = database.rawQuery(
                    "SELECT lang, kind, name, version, enabled, uninstalled, entry_count FROM pack_meta",
                    null);
            while (c.moveToNext()) {
                PackInfo info = new PackInfo();
                info.lang = c.getString(0);
                info.kind = c.getString(1);
                info.name = c.getString(2);
                info.version = c.getInt(3);
                info.enabled = c.getInt(4) != 0;
                info.installed = c.getInt(5) == 0;
                info.entryCount = c.getInt(6);
                byLang.put(info.lang, info);
            }
        } catch (Throwable t) {
            Log.w(TAG, "listPacks failed", t);
        } finally {
            if (c != null) c.close();
        }
        // 内置 rules 型：数据在 assets，列表恒显示（无 meta 记录 = 缺省启用、已安装）
        for (String[] p : BUILTIN_RULES) {
            PackInfo meta = byLang.get(p[0]);
            PackInfo info = new PackInfo();
            info.lang = p[0];
            info.kind = "rules";
            info.name = p[1];
            info.version = meta == null ? Integer.parseInt(p[2]) : meta.version;
            info.enabled = meta == null || meta.enabled;
            info.installed = meta == null || meta.installed;
            out.add(info);
            byLang.remove(p[0]);
        }
        // 其余（table 型）
        for (PackInfo info : byLang.values()) {
            if ("table".equals(info.kind)) out.add(info);
        }
        return out;
    }

    /** 是否启用（内置 rules 型缺省启用；未安装/停用 table 型 = false）。 */
    public synchronized boolean isPackEnabled(String lang) {
        if (lang == null) return false;
        SQLiteDatabase database = dbOrNull();
        if (database == null) return isBuiltinRulesLang(lang);
        PackInfo meta = meta(database, lang);
        if (isBuiltinRulesLang(lang)) {
            return (meta == null || meta.enabled) && (meta == null || meta.installed);
        }
        return meta != null && "table".equals(meta.kind) && meta.enabled && meta.installed;
    }

    /** 启停（两类包通用）；无 meta 记录时按 rules 型写入（对齐参考）。 */
    public synchronized void setPackEnabled(String lang, boolean enabled) {
        SQLiteDatabase database = dbOrNull();
        if (database == null || lang == null) return;
        PackInfo meta = meta(database, lang);
        ContentValues v = new ContentValues();
        v.put("lang", lang);
        v.put("kind", meta == null ? (isBuiltinRulesLang(lang) ? "rules" : "table") : meta.kind);
        v.put("name", meta == null ? "" : meta.name);
        v.put("version", meta == null ? 1 : meta.version);
        v.put("enabled", enabled ? 1 : 0);
        v.put("uninstalled", meta == null ? 0 : (meta.installed ? 0 : 1));
        v.put("entry_count", meta == null ? 0 : meta.entryCount);
        database.insertWithOnConflict("pack_meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 内置 rules 型「卸载」（只打标记，数据在 assets，可零成本恢复）。 */
    public synchronized void uninstallBuiltinPack(String lang) {
        if (!isBuiltinRulesLang(lang)) {
            Log.w(TAG, "uninstallBuiltinPack: not a builtin rules lang: " + lang);
            return;
        }
        SQLiteDatabase database = dbOrNull();
        if (database == null) return;
        PackInfo meta = meta(database, lang);
        ContentValues v = new ContentValues();
        v.put("lang", lang);
        v.put("kind", "rules");
        v.put("name", meta == null ? "" : meta.name);
        v.put("version", meta == null ? 1 : meta.version);
        v.put("enabled", 0);
        v.put("uninstalled", 1);
        v.put("entry_count", meta == null ? 0 : meta.entryCount);
        database.insertWithOnConflict("pack_meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 内置 rules 型恢复（清除卸载标记即生效）。 */
    public synchronized void restoreBuiltinPack(String lang) {
        if (!isBuiltinRulesLang(lang)) return;
        SQLiteDatabase database = dbOrNull();
        if (database == null) return;
        PackInfo meta = meta(database, lang);
        ContentValues v = new ContentValues();
        v.put("lang", lang);
        v.put("kind", "rules");
        v.put("name", meta == null ? "" : meta.name);
        v.put("version", meta == null ? 1 : meta.version);
        v.put("enabled", 1);
        v.put("uninstalled", 0);
        v.put("entry_count", meta == null ? 0 : meta.entryCount);
        database.insertWithOnConflict("pack_meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 删除包：内置 rules 型退化为「卸载标记」；table 型清数据 + 删元数据。 */
    public synchronized void removePack(String lang) {
        if (lang == null) return;
        if (isBuiltinRulesLang(lang)) {
            uninstallBuiltinPack(lang);
            return;
        }
        SQLiteDatabase database = dbOrNull();
        if (database == null) return;
        database.beginTransaction();
        try {
            database.delete("pack_entries", "lang=?", new String[]{lang});
            database.delete("pack_meta", "lang=?", new String[]{lang});
            database.setTransactionSuccessful();
        } catch (Throwable t) {
            Log.w(TAG, "removePack failed lang=" + lang, t);
        } finally {
            database.endTransaction();
        }
    }

    private static PackInfo meta(SQLiteDatabase database, String lang) {
        Cursor c = null;
        try {
            c = database.rawQuery(
                    "SELECT lang, kind, name, version, enabled, uninstalled, entry_count"
                            + " FROM pack_meta WHERE lang=?", new String[]{lang});
            if (c.moveToFirst()) {
                PackInfo info = new PackInfo();
                info.lang = c.getString(0);
                info.kind = c.getString(1);
                info.name = c.getString(2);
                info.version = c.getInt(3);
                info.enabled = c.getInt(4) != 0;
                info.installed = c.getInt(5) == 0;
                info.entryCount = c.getInt(6);
                return info;
            }
        } catch (Throwable t) {
            Log.w(TAG, "meta read failed lang=" + lang, t);
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    // ---------- 导入 ----------

    /**
     * 导入 table 型语言包（覆盖式：先清同语言旧数据，整包在一个事务里，
     * 失败不产生残库）。返回写入词条数。
     */
    public synchronized int importTablePack(String lang, String name, int version,
                                            List<Entry> entries, Progress progress) {
        SQLiteDatabase database = dbOrNull();
        if (database == null || lang == null || lang.isEmpty() || entries == null) return 0;
        int written = 0;
        database.beginTransaction();
        try {
            database.delete("pack_entries", "lang=?", new String[]{lang});
            for (Entry e : entries) {
                if (e == null || e.inflected == null || e.inflected.isEmpty()) continue;
                JSONArray arr = new JSONArray();
                for (String l : e.lemmas) {
                    if (l != null && !l.isEmpty()) arr.put(l);
                }
                if (arr.length() == 0) continue;
                ContentValues v = new ContentValues();
                v.put("lang", lang);
                v.put("inflected", e.inflected);
                v.put("lemmas", arr.toString());
                database.insertWithOnConflict("pack_entries", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                written++;
                if (progress != null && written % 5000 == 0) {
                    progress.onProgress("已导入 " + written + " 条…");
                }
            }
            ContentValues meta = new ContentValues();
            meta.put("lang", lang);
            meta.put("kind", "table");
            meta.put("name", name == null ? "" : name);
            meta.put("version", version);
            meta.put("enabled", 1);
            meta.put("uninstalled", 0);
            meta.put("entry_count", written);
            database.insertWithOnConflict("pack_meta", null, meta, SQLiteDatabase.CONFLICT_REPLACE);
            database.setTransactionSuccessful();
        } catch (Throwable t) {
            Log.w(TAG, "importTablePack failed lang=" + lang, t);
            written = 0;
        } finally {
            database.endTransaction();
        }
        Log.i(TAG, "importTablePack lang=" + lang + " entries=" + written + " version=" + version);
        return written;
    }

    // ---------- 查表还原 ----------

    /** 查表还原：inflected → [lemma...]；未安装/停用/未命中 → null（不抛错）。 */
    public synchronized List<String> lookupTableLemmas(String lang, String inflected) {
        if (lang == null || inflected == null || inflected.isEmpty()) return null;
        if (!isPackEnabled(lang)) return null;
        SQLiteDatabase database = dbOrNull();
        if (database == null) return null;
        Cursor c = null;
        try {
            c = database.rawQuery(
                    "SELECT lemmas FROM pack_entries WHERE lang=? AND inflected=?",
                    new String[]{lang, inflected});
            if (c.moveToFirst()) return parseLemmas(c.getString(0));
        } catch (Throwable t) {
            Log.w(TAG, "lookupTableLemmas failed lang=" + lang, t);
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /**
     * 批量查表还原（面板逐候选扫描用）：一次 IN 查询取回全部候选的 lemma 表。
     * 未启用/未安装直接返回空表。
     */
    public synchronized Map<String, List<String>> lookupTableLemmasBatch(String lang,
                                                                        Collection<String> inflected) {
        Map<String, List<String>> out = new HashMap<>();
        if (lang == null || inflected == null || inflected.isEmpty()) return out;
        if (!isPackEnabled(lang)) return out;
        SQLiteDatabase database = dbOrNull();
        if (database == null) return out;
        StringBuilder in = new StringBuilder();
        List<String> args = new ArrayList<>();
        args.add(lang);
        int i = 0;
        for (String s : inflected) {
            if (s == null || s.isEmpty()) continue;
            if (i++ > 0) in.append(',');
            in.append('?');
            args.add(s);
        }
        if (i == 0) return out;
        Cursor c = null;
        try {
            c = database.rawQuery(
                    "SELECT inflected, lemmas FROM pack_entries WHERE lang=? AND inflected IN ("
                            + in + ")", args.toArray(new String[0]));
            while (c.moveToNext()) {
                List<String> lemmas = parseLemmas(c.getString(1));
                if (lemmas != null && !lemmas.isEmpty()) out.put(c.getString(0), lemmas);
            }
        } catch (Throwable t) {
            Log.w(TAG, "lookupTableLemmasBatch failed lang=" + lang, t);
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    private static List<String> parseLemmas(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONArray arr = new JSONArray(json);
            List<String> out = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "");
                if (!s.isEmpty()) out.add(s);
            }
            return out.isEmpty() ? null : out;
        } catch (Throwable t) {
            return null;
        }
    }
}
