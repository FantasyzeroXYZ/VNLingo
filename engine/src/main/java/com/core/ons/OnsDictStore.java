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

    private static final String DB_NAME = "ons_dict.db";
    /** 词条 reading 索引（对齐 TrackReader db.ts 的 reading 索引；B1 补建）。 */
    private static final String IDX_READING = "idx_entries_reading";
    /** 单列 term 索引已被 (dict_id, term) 完全覆盖，实际未被任何查询使用（B1 附带项）。 */
    private static final String IDX_TERM_UNUSED = "idx_entries_term";
    /** 建索引的行数阈值：不超过则同步建（6.7 万条约 0.2s）；超过则后台另开
     *  连接建，避免存量 50 万词条库首次进游戏卡在打开词典库（B4）。 */
    private static final int INDEX_INLINE_MAX_ROWS = 100_000;

    private SQLiteDatabase db;
    private volatile String dictName = "";
    private volatile int entryCount;
    private volatile long currentDictId = -1;
    private volatile Context appContext;
    /** 查询范围缓存（B2）：词典增删/启停/切换当前时失效。 */
    private volatile List<Long> scopeCache;
    private volatile long scopeCacheAt;
    /** 范围缓存有效期：扫描内数百次查询共用一份，跨进程词典变更最多滞后一个 TTL。 */
    private static final long SCOPE_CACHE_TTL_MS = 3000;
    private static volatile boolean readingIndexReady;
    private static volatile boolean readingIndexBuilding;
    private static volatile long readingIndexBuildMs = -1;

    private OnsDictStore() {
    }

    /** reading 索引是否已就绪（词典页用于「正在优化词典库」提示，B4）。 */
    public static boolean isReadingIndexReady() {
        return readingIndexReady;
    }

    /** 是否正在后台补建 reading 索引（词典页提示用，B4）。 */
    public static boolean isReadingIndexBuilding() {
        return readingIndexBuilding;
    }

    /** 最近一次建索引耗时（ms；-1 = 本次进程尚未建过）。 */
    public static long getReadingIndexBuildMs() {
        return readingIndexBuildMs;
    }

    private synchronized SQLiteDatabase db(Context context) {
        if (db == null) {
            if (context == null) return null;
            appContext = context.getApplicationContext();
            db = appContext.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
            // 跨进程争用兜底：词典库被主进程（词典页导入/删除）与游戏进程
            // （面板查词）同时访问，默认忙等 0 会直接 SQLITE_BUSY 报错，
            // 给 5s 忙等窗口让短写事务自然让路。
            // PRAGMA 带结果行，必须 rawQuery（execSQL 会抛 "Queries can be
            // performed using query or rawQuery only"）
            tune(db);
            migrate(db);
            Cursor c = db.rawQuery("SELECT COUNT(*) FROM entries", null);
            if (c.moveToFirst()) entryCount = c.getInt(0);
            c.close();
            // B1/B4：存量库无 reading 索引则补建（小库同步、大库后台）
            ensureReadingIndex(db);
            Log.i(TAG, "db opened: entries=" + entryCount + " process=" + android.os.Process.myPid());
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

    /** 连接参数（PRAGMA 带结果行必须 rawQuery，execSQL 会抛异常）。 */
    private static void tune(SQLiteDatabase database) {
        // B2：默认页缓存约 2MB，真实 6.7 万词条库的索引页就有数 MB，
        // 大词典下索引页/数据页互相换出——提升到 16MB（负值 = KB），
        // 临时排序/临时表放内存（ORDER BY pop 的临时 B 树受益）
        pragma(database, "PRAGMA busy_timeout = 5000");
        pragma(database, "PRAGMA cache_size = -16384");
        pragma(database, "PRAGMA temp_store = MEMORY");
    }

    private static void pragma(SQLiteDatabase database, String sql) {
        Cursor c = null;
        try {
            c = database.rawQuery(sql, null);
            if (c.moveToFirst()) Log.i(TAG, sql.trim() + " -> " + c.getInt(0));
        } catch (Throwable t) {
            Log.w(TAG, "pragma failed: " + sql, t);
        } finally {
            if (c != null) c.close();
        }
    }

    private static boolean hasIndex(SQLiteDatabase database, String name) {
        Cursor c = null;
        try {
            c = database.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='index' AND name=?",
                    new String[]{name});
            return c.moveToFirst();
        } catch (Throwable t) {
            Log.w(TAG, "index probe failed for " + name, t);
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * B1/B4：存量库补建 reading 索引。已有则秒过；小库同步建（6.7 万条约 0.2s）；
     * 大库后台另开一条连接建——建索引期间主连接仍可查询，首次进游戏不卡在打开词典库。
     */
    private void ensureReadingIndex(SQLiteDatabase database) {
        if (readingIndexReady) return;
        if (hasIndex(database, IDX_READING)) {
            readingIndexReady = true;
            return;
        }
        if (readingIndexBuilding) return;
        if (entryCount <= INDEX_INLINE_MAX_ROWS) {
            buildReadingIndex(database, "inline");
            return;
        }
        final Context ctx = appContext;
        if (ctx == null) return;    // 无 context 的只读路径：留给下次带 context 的打开
        readingIndexBuilding = true;
        Thread worker = new Thread(() -> {
            SQLiteDatabase bg = null;
            try {
                bg = ctx.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
                tune(bg);
                buildReadingIndex(bg, "background");
            } catch (Throwable t) {
                Log.w(TAG, "background index build failed", t);
            } finally {
                if (bg != null) bg.close();
                readingIndexBuilding = false;
            }
        }, "ons-dict-index");
        worker.setDaemon(true);
        worker.start();
        Log.i(TAG, "reading index build scheduled (entries=" + entryCount + ")");
    }

    private void buildReadingIndex(SQLiteDatabase database, String how) {
        long start = System.currentTimeMillis();
        try {
            // (reading, dict_id)：reading = ? + dict_id IN (...) 的查询计划从
            // 「按 dict_id 单列扫描 + ORDER BY 临时 B 树」变为索引区间扫描
            database.execSQL("CREATE INDEX IF NOT EXISTS " + IDX_READING
                    + " ON entries(reading, dict_id)");
            readingIndexReady = true;
            readingIndexBuildMs = System.currentTimeMillis() - start;
            Log.i(TAG, "reading index ready (" + how + "): " + readingIndexBuildMs
                    + "ms entries=" + entryCount);
            analyze(database);
        } catch (Throwable t) {
            Log.w(TAG, "reading index build failed (" + how + ")", t);
        }
    }

    /** B1：刷新查询规划统计（sqlite_stat1 为空时规划器「碰巧」选对计划）。 */
    private static void analyze(SQLiteDatabase database) {
        try {
            database.execSQL("ANALYZE");
        } catch (Throwable t) {
            Log.w(TAG, "ANALYZE failed", t);
        }
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
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_dict_term ON entries(dict_id, term)");
        // 单列 term 索引被 (dict_id, term) 覆盖、无查询使用（B1 附带项）：删掉省体积。
        // 先探存在性：无索引时空 DROP 也是 DDL，避免每次启动都写一次 schema。
        if (hasIndex(database, IDX_TERM_UNUSED)) {
            database.execSQL("DROP INDEX IF EXISTS " + IDX_TERM_UNUSED);
            Log.i(TAG, "dropped unused index " + IDX_TERM_UNUSED);
        }
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
        scopeCache = null;
    }

    /** 设为当前词典（查词首选）；写入 kv 供下次启动恢复。 */
    public synchronized void setCurrent(Context context, long id) {
        SQLiteDatabase database = db(context);
        if (database == null) return;
        database.execSQL("INSERT OR REPLACE INTO kv(name, value) VALUES('current_dict', ?)",
                new Object[]{String.valueOf(id)});
        currentDictId = id;
        scopeCache = null;
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
        scopeCache = null;
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
                // 导入期间词典被删除：UPDATE 落空 = 孤儿词条，清掉并报错
                // （catch 分支的 DELETE FROM entries WHERE dict_id=? 兜底同效）
                android.database.sqlite.SQLiteStatement setCount = database
                        .compileStatement("UPDATE dicts SET count=? WHERE id=?");
                setCount.bindLong(1, total);
                setCount.bindLong(2, dictId);
                int updated = setCount.executeUpdateDelete();
                if (updated == 0) {
                    throw new IllegalStateException("dictionary deleted during import");
                }
            } catch (Throwable t) {
                database.execSQL("DELETE FROM entries WHERE dict_id=?", new Object[]{dictId});
                database.execSQL("DELETE FROM dicts WHERE id=?", new Object[]{dictId});
                throw t;
            }
        }
        entryCount = total;
        scopeCache = null;
        // B1：导入后确保 reading 索引存在（导入期间若还没有索引，批量建比逐条维护快）
        // 并跑一次 ANALYZE，让多词典 IN 查询的规划器有统计可依据
        if (!readingIndexReady) {
            if (hasIndex(database, IDX_READING)) {
                readingIndexReady = true;
            } else {
                if (progress != null) progress.onProgress("正在优化词典库索引…");
                buildReadingIndex(database, "import");
            }
        }
        analyze(database);
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
        long total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            // 炸弹包保险丝：单个 term_bank 正常 <20MB，超过 64MB 视为异常包
            // 判导入失败，而不是在 OOM 里拖垮整个进程
            if (total > 64L * 1024 * 1024) {
                throw new IllegalArgumentException("dictionary entry too large (>64MB)");
            }
            out.write(buf, 0, n);
        }
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

    /** 查词命中：matchedTerm = 实际命中的词形（递减前缀或还原原形），
     *  groups 其释义。调用方高亮必须以 matchedTerm 为准——search 内部的
     *  递减/还原意味着命中的词往往短于查询串。 */
    public static final class Match {
        public final String matchedTerm;
        public final List<Group> groups;

        Match(String matchedTerm, List<Group> groups) {
            this.matchedTerm = matchedTerm;
            this.groups = groups;
        }
    }

    /** 查词：对 query 取最长有命中前缀，返回该层的释义组（最多 maxGroups 组）。
     *  范围：当前词典优先，其次其余启用词典（对齐参考的 current-dictionary
     *  模型 + 跨启用词典回退）。 */
    /** 查询：最长前缀 + 词形还原分层（japanese_search.dart tier 思路）。
     *  顶层兜底：词典库异常（损坏/满盘/并发写冲突）降级为空结果并记日志，
     *  查词方（游戏内扫描/词典页）不因库异常崩溃。 */
    public List<Group> search(String query, int maxGroups) {
        Match m = searchMatched(query, maxGroups, true);
        return m == null ? new ArrayList<>() : m.groups;
    }

    /** 查词并返回实际命中的词形。deinflect=false 时只做精确前缀递减。 */
    public Match searchMatched(String query, int maxGroups, boolean deinflect) {
        if (query == null) return null;
        try {
            return searchMatchedInner(query, maxGroups, deinflect);
        } catch (Throwable t) {
            Log.w(TAG, "searchMatched failed for " + query, t);
            return null;
        }
    }

    private Match searchMatchedInner(String query, int maxGroups, boolean deinflect) {
        String q = query.trim();
        if (q.isEmpty()) return null;
        if (q.length() > 20) q = q.substring(0, 20);
        SQLiteDatabase database = db(null);
        if (database == null) return null;
        List<Long> scope = queryScope(database);
        for (int len = q.length(); len >= 1; len--) {
            String prefix = q.substring(0, len);
            List<Group> groups = exactLookup(database, scope, prefix, maxGroups);
            if (!groups.isEmpty()) return new Match(prefix, groups);
            if (!deinflect) continue;
            for (String candidate : OnsDeinflector.deinflect(prefix)) {
                groups = exactLookup(database, scope, candidate, maxGroups);
                if (!groups.isEmpty()) return new Match(candidate, groups);
            }
        }
        return null;
    }

    /** 当前词典优先、其余启用词典兜底的查询范围（TrackReader current-scope 模型）。
     *  B2：一次扫描会发数百次查询，每次现查 dicts 子查询（0.086ms/次）——结果带
     *  短 TTL 缓存；词典增删/启停/切当前（本进程）立即失效，跨进程变更最多滞后
     *  一个 TTL 生效。 */
    private List<Long> buildScope(SQLiteDatabase database) {
        long now = System.currentTimeMillis();
        List<Long> cached = scopeCache;
        if (cached != null && now - scopeCacheAt < SCOPE_CACHE_TTL_MS) return cached;
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
        scopeCache = scope;
        scopeCacheAt = now;
        return scope;
    }

    /** 查询范围；无词典时给 [-1] 快速空路径（返回单例，禁止再改写）。 */
    private List<Long> queryScope(SQLiteDatabase database) {
        List<Long> scope = buildScope(database);
        return scope.isEmpty() ? java.util.Collections.singletonList(-1L) : scope;
    }

    // ---- 精确查询（TrackReader searchByTerm / searchByReading：无递减、无还原——
    //      递减由扫描循环驱动，见 OnsExtractPanel.scanByChar）----

    /** 精确 term 匹配（searchByTerm）。 */
    public List<Group> searchTermExact(String term, int maxGroups) {
        if (term == null || term.isEmpty()) return new ArrayList<>();
        SQLiteDatabase database = db(null);
        if (database == null) return new ArrayList<>();
        return exactLookup(database, queryScope(database), term, maxGroups);
    }

    /** 精确 reading 匹配（searchByReading，仅日语词典有读音索引价值）。 */
    public List<Group> searchReadingExact(String reading, int maxGroups) {
        List<Group> groups = new ArrayList<>();
        if (reading == null || reading.isEmpty()) return groups;
        SQLiteDatabase database = db(null);
        if (database == null) return groups;
        return columnLookup(database, queryScope(database), COL_READING, reading, maxGroups);
    }

    /**
     * B3 批量 term 查询（TrackReader batchSearchTerms 对齐）：一次 IN 查询取回
     * 全部候选（原形 + 全部还原形）的命中行，再在内存里按优先级取用。
     * 逐候选串行时每次都有游标/JNI 往返（约 0.2ms/次 × 数百次），批量化消掉大头。
     *
     * @return 命中键（term）→ 该键的释义组；未命中的键不在表里
     */
    public java.util.Map<String, List<Group>> batchTermLookup(List<String> terms, int maxGroups) {
        if (terms == null || terms.isEmpty()) return new java.util.HashMap<>();
        SQLiteDatabase database = db(null);
        if (database == null) return new java.util.HashMap<>();
        return batchColumnLookup(database, queryScope(database), COL_TERM, terms, maxGroups);
    }

    /** B3 批量 reading 查询（同 batchTermLookup，命中列换成 reading）。 */
    public java.util.Map<String, List<Group>> batchReadingLookup(List<String> readings, int maxGroups) {
        if (readings == null || readings.isEmpty()) return new java.util.HashMap<>();
        SQLiteDatabase database = db(null);
        if (database == null) return new java.util.HashMap<>();
        return batchColumnLookup(database, queryScope(database), COL_READING, readings, maxGroups);
    }

    /** TrackReader searchTermWithDeinflect：①精确 term ②词形还原→term。 */
    public Match searchTermWithDeinflect(String term, int maxGroups, boolean deinflect) {
        if (term == null) return null;
        String q = term.trim();
        if (q.isEmpty()) return null;
        List<Group> g = searchTermExact(q, maxGroups);
        if (!g.isEmpty()) return new Match(q, g);
        if (deinflect) {
            for (String root : OnsDeinflector.deinflect(q)) {
                if (root.equals(q)) continue;
                g = searchTermExact(root, maxGroups);
                if (!g.isEmpty()) return new Match(root, g);
            }
        }
        return null;
    }

    // ---- 词形还原开关（多进程共享；词典页设置，游戏内查词与词典页搜索共用）----

    private static final String DEINFLECT_PREF = "ons_dict_settings";
    private static final String DEINFLECT_KEY = "deinflect_enabled";

    /** 查词词形还原开关（默认开）。MODE_MULTI_PROCESS：词典页（主进程）写、
     *  游戏内查词（引擎进程）读。 */
    public static boolean isDeinflectEnabled(Context context) {
        try {
            return context.getSharedPreferences(DEINFLECT_PREF, Context.MODE_MULTI_PROCESS)
                    .getBoolean(DEINFLECT_KEY, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setDeinflectEnabled(Context context, boolean enabled) {
        try {
            context.getSharedPreferences(DEINFLECT_PREF, Context.MODE_MULTI_PROCESS)
                    .edit().putBoolean(DEINFLECT_KEY, enabled).apply();
        } catch (Throwable ignored) { }
    }

    // ---- 精确查询内核（term / reading 两列共用一套 SQL 与分行逻辑）----

    private static final String COL_TERM = "term";
    private static final String COL_READING = "reading";

    /** 单值精确查询（列表包装给批量实现，避免两套 SQL 走偏）。 */
    private List<Group> exactLookup(SQLiteDatabase database, List<Long> scope,
                                    String term, int maxGroups) {
        return columnLookup(database, scope, COL_TERM, term, maxGroups);
    }

    private List<Group> columnLookup(SQLiteDatabase database, List<Long> scope,
                                     String column, String value, int maxGroups) {
        List<Group> groups = batchColumnLookup(database, scope, column,
                java.util.Collections.singletonList(value), maxGroups).get(value);
        return groups == null ? new ArrayList<>() : groups;
    }

    /**
     * column（term 或 reading，仅内部常量）IN (values) 一次取回，按命中键分组。
     * 行序 pop DESC；每组最多 maxGroups 个 (term, reading) 组，超出后只把
     * 释义并进已收集的组（不再新增组）。
     */
    private java.util.Map<String, List<Group>> batchColumnLookup(
            SQLiteDatabase database, List<Long> scope, String column,
            List<String> values, int maxGroups) {
        java.util.Map<String, List<Group>> out = new java.util.HashMap<>();
        if (values == null || values.isEmpty()) return out;
        boolean byTerm = COL_TERM.equals(column);
        StringBuilder scopeFilter = new StringBuilder();
        StringBuilder valueFilter = new StringBuilder();
        String[] args = new String[scope.size() + values.size() + 1];
        int i = 0;
        for (Long id : scope) {
            if (i > 0) scopeFilter.append(',');
            scopeFilter.append('?');
            args[i++] = String.valueOf(id);
        }
        for (String v : values) {
            if (valueFilter.length() > 0) valueFilter.append(',');
            valueFilter.append('?');
            args[i++] = v;
        }
        args[i] = String.valueOf(maxGroups * 3 * Math.max(1, values.size()));
        Cursor c = null;
        try {
            c = database.rawQuery(
                    "SELECT term, reading, gloss, pop FROM entries WHERE dict_id IN ("
                            + scopeFilter + ") AND " + column + " IN (" + valueFilter + ") "
                            + "ORDER BY pop DESC LIMIT ?",
                    args);
            while (c.moveToNext()) {
                String t = c.getString(0);
                String r = c.getString(1);
                String key = byTerm ? t : r;
                List<Group> groups = out.get(key);
                if (groups == null) {
                    groups = new ArrayList<>();
                    out.put(key, groups);
                }
                Group g = null;
                for (Group existing : groups) {
                    if (existing.term.equals(t) && existing.reading.equals(r)) {
                        g = existing;
                        break;
                    }
                }
                if (g == null) {
                    if (groups.size() >= maxGroups) continue;
                    g = new Group();
                    g.term = t;
                    g.reading = r;
                    groups.add(g);
                }
                String gloss = c.getString(2);
                if (gloss != null && !gloss.isEmpty()) g.glosses.add(gloss);
            }
        } catch (Throwable t) {
            Log.w(TAG, column + " lookup failed for " + values, t);
        } finally {
            if (c != null) c.close();
        }
        return out;
    }
}
