package com.core.ons;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 本地词典库（查词数据源）：Yomichan 词典 zip（term_bank_*.json）或
 * MDX 转换产物 jsonl（{"key","html"} 每行）导入到本地 SQLite，无网络依赖。
 *
 * 参考实现 D:\Desktop\test-flutter\anki（lib/core/dictionary/*）：同样以
 * Yomichan bank 格式为源、SQLite 承载、查词走「最长前缀 + 词形还原」分层；
 * 支持多词典（dicts 表管理 名称/词条数/启用，查词用「当前词典」，对齐
 * 参考的 current-dictionary 模型），导入为追加而非整体替换。
 *
 * 搜索算法（对齐 japanese_search.dart 的 tier 思路）：
 * 对查询串按长度递减取前缀，每层先精确命中 term，未命中再经 OnsDeinflector
 * 还原后查；首个有结果的层即为最长匹配词，返回其全部释义组。
 */
public final class OnsDictStore {

    private static final String TAG = "OnsDict";

    /** 一组同 term+reading 的释义。 */
    public static class Group {
        public String term = "";
        public String reading = "";
        public final List<String> glosses = new ArrayList<>();
    }

    /** 词典元信息（管理列表行）。 */
    public static class DictInfo {
        public long id;
        public String name = "";
        public int count;
        public boolean enabled;
        public boolean current;
    }

    public interface Progress {
        void onProgress(String message);
    }

    private static final OnsDictStore INSTANCE = new OnsDictStore();

    public static OnsDictStore get() {
        return INSTANCE;
    }

    private SQLiteDatabase db;
    private volatile String dictName = "";
    private volatile int entryCount;
    private volatile long currentDictId = -1;

    private OnsDictStore() {
    }

    private synchronized SQLiteDatabase db(Context context) {
        if (db == null) {
            if (context == null) return null;
            db = context.getApplicationContext()
                    .openOrCreateDatabase("ons_dict.db", Context.MODE_PRIVATE, null);
            migrate(db);
            Cursor c = db.rawQuery("SELECT COUNT(*) FROM entries", null);
            if (c.moveToFirst()) entryCount = c.getInt(0);
            c.close();
            c = db.rawQuery("SELECT value FROM kv WHERE name='dict_name'", null);
            if (c.moveToFirst()) dictName = c.getString(0);
            c.close();
            c = db.rawQuery("SELECT value FROM kv WHERE name='current_dict'", null);
            if (c.moveToFirst()) {
                try {
                    currentDictId = Long.parseLong(c.getString(0));
                } catch (NumberFormatException ignored) {
                }
            }
            c.close();
        }
        return db;
    }

    /** v1（单表 + kv 名字）→ v2（dicts 表 + entries.dict_id）一次性迁移。 */
    private static void migrate(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE IF NOT EXISTS entries("
                + "term TEXT NOT NULL, reading TEXT NOT NULL DEFAULT '',"
                + "gloss TEXT NOT NULL DEFAULT '', pop INTEGER NOT NULL DEFAULT 0,"
                + "dict_id INTEGER NOT NULL DEFAULT 1)");
        database.execSQL("CREATE TABLE IF NOT EXISTS kv(name TEXT PRIMARY KEY NOT NULL, value TEXT)");
        database.execSQL("CREATE TABLE IF NOT EXISTS dicts("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL DEFAULT '',"
                + "enabled INTEGER NOT NULL DEFAULT 1, count INTEGER NOT NULL DEFAULT 0)");
        // v1 表没有 dict_id 列：必须先补列，(dict_id, term) 索引才能建
        Cursor cols = database.rawQuery("PRAGMA table_info(entries)", null);
        boolean hasDictId = false;
        while (cols.moveToNext()) {
            if ("dict_id".equalsIgnoreCase(cols.getString(1))) hasDictId = true;
        }
        cols.close();
        if (!hasDictId) {
            database.execSQL("ALTER TABLE entries ADD COLUMN dict_id INTEGER NOT NULL DEFAULT 1");
        }
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_term ON entries(term)");
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_dict_term ON entries(dict_id, term)");
        Cursor v = database.rawQuery(
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='entries'", null);
        boolean hasEntries = v.moveToFirst() && v.getInt(0) > 0;
        v.close();
        if (!hasEntries) return;
        Cursor dataCur = database.rawQuery("SELECT COUNT(*) FROM entries", null);
        boolean hasData = dataCur.moveToFirst() && dataCur.getInt(0) > 0;
        dataCur.close();
        if (!hasData) return;
        Cursor migrated = database.rawQuery(
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='dicts'"
                        + " AND (SELECT count(*) FROM dicts) > 0", null);
        boolean alreadyMigrated = migrated.moveToFirst() && migrated.getInt(0) > 0;
        migrated.close();
        if (alreadyMigrated) return;
        // 存量数据整体升级为第 1 号词典并设为当前
        database.beginTransaction();
        try {
            Cursor nameCur = database.rawQuery(
                    "SELECT value FROM kv WHERE name='dict_name'", null);
            String name = nameCur.moveToFirst() ? nameCur.getString(0) : "";
            nameCur.close();
            Cursor cntCur = database.rawQuery("SELECT COUNT(*) FROM entries", null);
            int count = cntCur.moveToFirst() ? cntCur.getInt(0) : 0;
            cntCur.close();
            Cursor rowCur = database.rawQuery(
                    "SELECT COALESCE(MAX(id),0)+1 FROM dicts", null);
            long nextId = rowCur.moveToFirst() ? rowCur.getLong(0) : 1;
            rowCur.close();
            database.execSQL("UPDATE entries SET dict_id=? WHERE dict_id<>?",
                    new Object[]{nextId, nextId});
            database.execSQL("INSERT INTO dicts(id, name, enabled, count) VALUES(?, ?, 1, ?)",
                    new Object[]{nextId, name, count});
            database.execSQL("INSERT OR REPLACE INTO kv(name, value) VALUES('current_dict', ?)",
                    new Object[]{String.valueOf(nextId)});
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    /** 提前打开数据库（面板构造时调用，保证后续 search 无需 context）。 */
    public synchronized void init(Context context) {
        db(context);
    }

    public boolean hasDictionary() {
        return entryCount > 0;
    }

    public String getDictName() {
        return dictName;
    }

    public int getEntryCount() {
        return entryCount;
    }

    // ------------------------------------------------------------------
    // 词典管理（多词典：列表/启停/删除/设为当前）
    // ------------------------------------------------------------------

    /** 全部词典（按 id 升序）；状态同步内存字段。 */
    public synchronized List<DictInfo> listDicts(Context context) {
        List<DictInfo> out = new ArrayList<>();
        SQLiteDatabase database = db(context);
        if (database == null) return out;
        Cursor c = database.rawQuery(
                "SELECT d.id, d.name, d.enabled, d.count,"
                        + " (SELECT value FROM kv WHERE name='current_dict') = CAST(d.id AS TEXT)"
                        + " FROM dicts d ORDER BY d.id", null);
        while (c.moveToNext()) {
            DictInfo info = new DictInfo();
            info.id = c.getLong(0);
            info.name = c.getString(1);
            info.enabled = c.getInt(2) != 0;
            info.count = c.getInt(3);
            info.current = !c.isNull(4) && c.getInt(4) != 0;
            out.add(info);
        }
        c.close();
        return out;
    }

    /** 启用/停用；停用的词典不参与查词。 */
    public synchronized void setEnabled(Context context, long id, boolean enabled) {
        SQLiteDatabase database = db(context);
        if (database == null) return;
        database.execSQL("UPDATE dicts SET enabled=? WHERE id=?",
                new Object[]{enabled ? 1 : 0, id});
    }

    /** 设为当前词典（查词首选）；写入 kv 供下次启动恢复。 */
    public synchronized void setCurrent(Context context, long id) {
        SQLiteDatabase database = db(context);
        if (database == null) return;
        database.execSQL("INSERT OR REPLACE INTO kv(name, value) VALUES('current_dict', ?)",
                new Object[]{String.valueOf(id)});
        currentDictId = id;
    }

    /** 删除词典（词条与元信息一并清除）；返回是否删除了当前词典。 */
    public synchronized boolean deleteDict(Context context, long id) {
        SQLiteDatabase database = db(context);
        if (database == null) return false;
        database.beginTransaction();
        boolean wasCurrent;
        try {
            database.execSQL("DELETE FROM entries WHERE dict_id=?", new Object[]{id});
            database.execSQL("DELETE FROM dicts WHERE id=?", new Object[]{id});
            wasCurrent = currentDictId == id;
            if (wasCurrent) {
                database.execSQL("DELETE FROM kv WHERE name='current_dict'");
                currentDictId = -1;
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        Cursor c = database.rawQuery("SELECT COUNT(*) FROM entries", null);
        if (c.moveToFirst()) entryCount = c.getInt(0);
        c.close();
        return wasCurrent;
    }

    // ------------------------------------------------------------------
    // 导入（后台线程执行；追加为新词典，不动旧词典）
    // ------------------------------------------------------------------

    /** 导入 Yomichan zip 或 MDX jsonl，返回词条数；失败抛异常（失败不产生残库）。 */
    public synchronized int importFromFile(Context context, Uri uri, Progress progress) throws Exception {
        SQLiteDatabase database = db(context);
        Context app = context.getApplicationContext();
        String fileName = uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
        int total;
        long dictId;
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalArgumentException("openInputStream null");
            byte[] head = new byte[2];
            readFully(in, head);
            boolean isZip = head[0] == 'P' && head[1] == 'K';
            // 先占位一行 dicts，导入事务内填充，失败即删除
            database.execSQL("INSERT INTO dicts(name, enabled, count) VALUES(?, 1, 0)",
                    new Object[]{fileName});
            Cursor c = database.rawQuery("SELECT last_insert_rowid()", null);
            c.moveToFirst();
            dictId = c.getLong(0);
            c.close();
            try {
                try (InputStream in2 = app.getContentResolver().openInputStream(uri)) {
                    if (in2 == null) throw new IllegalArgumentException("openInputStream null(reopen)");
                    total = isZip
                            ? importYomichanZip(in2, database, dictId, progress)
                            : importJsonl(in2, fileName, database, dictId, progress);
                }
                database.execSQL("UPDATE dicts SET count=? WHERE id=?", new Object[]{total, dictId});
            } catch (Throwable t) {
                database.execSQL("DELETE FROM entries WHERE dict_id=?", new Object[]{dictId});
                database.execSQL("DELETE FROM dicts WHERE id=?", new Object[]{dictId});
                throw t;
            }
        }
        entryCount = total;
        return total;
    }

    private static void readFully(InputStream in, byte[] buf) throws Exception {
        int read = 0;
        while (read < buf.length) {
            int n = in.read(buf, read, buf.length - read);
            if (n < 0) throw new IllegalArgumentException("file too small");
            read += n;
        }
    }

    private int importYomichanZip(InputStream in, SQLiteDatabase database, long dictId,
                                  Progress progress) throws Exception {
        String name = "";
        database.beginTransaction();
        int total = 0;
        try {
            ZipInputStream zip = new ZipInputStream(in);
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (entryName.endsWith("index.json")) {
                    JSONObject idx = new JSONObject(readText(zip));
                    name = idx.optString("title", "");
                } else if (entryName.contains("term_bank") && entryName.endsWith(".json")) {
                    total += importTermBank(readText(zip), database, dictId);
                    if (progress != null) {
                        progress.onProgress(entryName + ": " + total);
                    }
                }
                zip.closeEntry();
            }
            if (total == 0) {
                throw new IllegalArgumentException("no term_bank entries found");
            }
            database.execSQL("UPDATE dicts SET name=? WHERE id=?", new Object[]{name, dictId});
            database.execSQL("INSERT OR REPLACE INTO kv(name, value) VALUES('dict_name', ?)",
                    new Object[]{name});
            database.setTransactionSuccessful();
            // 内存名在事务成功后更新，失败回滚时不同步污染
            dictName = name;
            return total;
        } finally {
            database.endTransaction();
        }
    }

    private int importTermBank(String json, SQLiteDatabase database, long dictId) throws Exception {
        JSONArray rows = new JSONArray(json);
        int count = 0;
        // Yomichan term_bank 行：[term, reading, defTags, rules, score, defs...]
        for (int i = 0; i < rows.length(); i++) {
            JSONArray row = rows.optJSONArray(i);
            if (row == null || row.length() < 6) continue;
            String term = row.optString(0, "");
            String reading = row.optString(1, "");
            if (term.isEmpty()) continue;
            if (reading.equals(term)) reading = "";
            StringBuilder gloss = new StringBuilder();
            JSONArray defs = row.optJSONArray(5);
            if (defs != null) {
                for (int j = 0; j < defs.length(); j++) {
                    Object item = defs.get(j);
                    String text;
                    if (item instanceof JSONObject) {
                        text = structuredToText(item);
                    } else if (item instanceof String) {
                        String s = ((String) item).trim();
                        // 结构化内容 JSON（{"tag":...} / [{...}]）转纯文本
                        if (s.startsWith("[") || s.startsWith("{")) {
                            text = structuredToText(parseLenient(s));
                        } else {
                            text = s;
                        }
                    } else {
                        text = String.valueOf(item);
                    }
                    if (text.isEmpty()) continue;
                    if (gloss.length() > 0) gloss.append('\n');
                    gloss.append(text);
                }
            }
            database.execSQL("INSERT INTO entries(term, reading, gloss, pop, dict_id) VALUES(?,?,?,?,?)",
                    new Object[]{term, reading, gloss.toString(), row.optInt(4, 0), dictId});
            count++;
        }
        return count;
    }

    /** 递归展开 Yomichan structured-content 树：拼接纯文本，li 项换行。 */
    private static String structuredToText(Object node) {
        StringBuilder sb = new StringBuilder();
        walkStructured(node, sb);
        return sb.toString().replaceAll(" ?\\n ?", "\n").trim();
    }

    private static void walkStructured(Object node, StringBuilder sb) {
        if (node instanceof String) {
            String s = ((String) node).trim();
            if (s.isEmpty()) return;
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append(' ');
            sb.append(s);
        } else if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if ("li".equals(o.optString("tag", "")) && sb.length() > 0) sb.append('\n');
            walkStructured(o.opt("content"), sb);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) walkStructured(a.opt(i), sb);
        }
    }

    private static Object parseLenient(String s) {
        try {
            s = s.trim();
            return s.startsWith("[") ? new JSONArray(s) : new JSONObject(s);
        } catch (Throwable t) {
            return s;
        }
    }

    /** 导入 MDX 转换 jsonl（每行 {"key","html"}）；首行格式校验失败抛异常，不产生残库。 */
    private int importJsonl(InputStream in, String fallbackName, SQLiteDatabase database,
                            long dictId, Progress progress) throws Exception {
        database.beginTransaction();
        int total = 0;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line = nextNonBlankLine(reader);
            // 非词典 jsonl（如误选普通文本/其他 JSON）直接拒绝
            if (line == null || !isValidJsonlRow(line)) {
                throw new IllegalArgumentException("not a dictionary jsonl file");
            }
            while (line != null) {
                try {
                    JSONObject obj = new JSONObject(line);
                    String term = obj.optString("key", "");
                    String html = obj.optString("html", obj.optString("content", ""));
                    if (!term.isEmpty()) {
                        database.execSQL(
                                "INSERT INTO entries(term, reading, gloss, pop, dict_id) VALUES(?,?,?,0,?)",
                                new Object[]{term, "", stripHtml(html), dictId});
                        total++;
                        if (progress != null && total % 20000 == 0) {
                            progress.onProgress("jsonl: " + total);
                        }
                    }
                } catch (org.json.JSONException t) {
                    // 单行坏数据跳过
                }
                line = reader.readLine();
                if (line != null) line = line.trim();
            }
            if (total == 0) {
                throw new IllegalArgumentException("no valid jsonl rows");
            }
            database.execSQL("UPDATE dicts SET name=? WHERE id=?", new Object[]{fallbackName, dictId});
            database.execSQL("INSERT OR REPLACE INTO kv(name, value) VALUES('dict_name', ?)",
                    new Object[]{fallbackName});
            database.setTransactionSuccessful();
            dictName = fallbackName;
            return total;
        } finally {
            database.endTransaction();
        }
    }

    private static String nextNonBlankLine(BufferedReader reader) throws Exception {
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (!line.isEmpty()) return line;
        }
        return null;
    }

    private static boolean isValidJsonlRow(String line) {
        try {
            JSONObject obj = new JSONObject(line);
            return !obj.optString("key", "").isEmpty();
        } catch (org.json.JSONException t) {
            return false;
        }
    }

    private static String readText(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String stripHtml(String html) {
        if (html == null || html.isEmpty()) return "";
        String text = html
                .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(div|p|li|dd|dt)>", "\n")
                .replaceAll("<[^>]+>", " ");
        text = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
        return text.replaceAll("[ \\t]+", " ").replaceAll(" ?\\n ?", "\n").trim();
    }

    // ------------------------------------------------------------------
    // 查询：最长前缀 + 词形还原分层（japanese_search.dart tier 思路）
    // ------------------------------------------------------------------

    /** 查词：对 query 取最长有命中前缀，返回该层的释义组（最多 maxGroups 组）。
     *  范围：当前词典优先，其次其余启用词典（对齐参考的 current-dictionary
     *  模型 + 跨启用词典回退）。 */
    public List<Group> search(String query, int maxGroups) {
        List<Group> empty = new ArrayList<>();
        if (query == null) return empty;
        String q = query.trim();
        if (q.isEmpty()) return empty;
        if (q.length() > 20) q = q.substring(0, 20);
        SQLiteDatabase database = db(null);
        if (database == null) return empty;
        List<Long> scope = new ArrayList<>();
        if (currentDictId > 0) scope.add(currentDictId);
        Cursor c = null;
        try {
            c = database.rawQuery(
                    "SELECT id FROM dicts WHERE enabled=1"
                            + (currentDictId > 0 ? " AND id<>?" : "") + " ORDER BY id",
                    currentDictId > 0 ? new String[]{String.valueOf(currentDictId)} : null);
            while (c.moveToNext()) scope.add(c.getLong(0));
        } catch (Throwable t) {
            Log.w(TAG, "scope query failed", t);
        } finally {
            if (c != null) c.close();
        }
        if (scope.isEmpty()) scope.add(-1L); // 无词典：快速空路径
        for (int len = q.length(); len >= 1; len--) {
            String prefix = q.substring(0, len);
            List<Group> groups = exactLookup(database, scope, prefix, maxGroups);
            if (groups.isEmpty()) {
                for (String candidate : OnsDeinflector.deinflect(prefix)) {
                    groups = exactLookup(database, scope, candidate, maxGroups);
                    if (!groups.isEmpty()) break;
                }
            }
            if (!groups.isEmpty()) return groups;
        }
        return empty;
    }

    private List<Group> exactLookup(SQLiteDatabase database, List<Long> scope,
                                    String term, int maxGroups) {
        List<Group> groups = new ArrayList<>();
        StringBuilder filter = new StringBuilder();
        String[] args = new String[scope.size() + 2];
        for (int i = 0; i < scope.size(); i++) {
            if (i > 0) filter.append(",");
            filter.append("?");
            args[i] = String.valueOf(scope.get(i));
        }
        args[scope.size()] = term;
        args[scope.size() + 1] = String.valueOf(maxGroups * 3);
        Cursor c = null;
        try {
            c = database.rawQuery(
                    "SELECT term, reading, gloss, pop FROM entries WHERE dict_id IN ("
                            + filter + ") AND term = ? "
                            + "ORDER BY pop DESC LIMIT ?",
                    args);
            while (c.moveToNext() && groups.size() < maxGroups) {
                String t = c.getString(0);
                String r = c.getString(1);
                Group g = null;
                for (Group existing : groups) {
                    if (existing.term.equals(t) && existing.reading.equals(r)) {
                        g = existing;
                        break;
                    }
                }
                if (g == null) {
                    g = new Group();
                    g.term = t;
                    g.reading = r;
                    groups.add(g);
                }
                String gloss = c.getString(2);
                if (gloss != null && !gloss.isEmpty()) g.glosses.add(gloss);
            }
        } catch (Throwable t) {
            Log.w(TAG, "exactLookup failed for " + term, t);
        } finally {
            if (c != null) c.close();
        }
        return groups;
    }
}
