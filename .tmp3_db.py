"""读取设备上的 VNLingo 游戏库 DB，取 Sakura 条目的引擎/内核等属性。"""
import sqlite3, sys, io, glob, os

db = sys.argv[1]
con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
cur = con.cursor()
cur.execute("SELECT name FROM sqlite_master WHERE type='table'")
tables = [r[0] for r in cur.fetchall()]
out = io.StringIO()
out.write(f"tables: {tables}\n")
for t in tables:
    cur.execute(f'PRAGMA table_info("{t}")')
    cols = [r[1] for r in cur.fetchall()]
    out.write(f"\n== {t} cols: {cols}\n")
    if t.lower() in ("games", "gamelibrary", "game"):
        cur.execute(f'SELECT * FROM "{t}"')
        for row in cur.fetchall():
            d = dict(zip(cols, row))
            name = str(d.get("title") or d.get("name") or "")
            path = str(d.get("path") or d.get("uri") or "")
            if "Sakura" in name or "Sakura" in path or "Jirai" in name or "Jirai" in path:
                out.write("HIT:\n")
                for k, v in d.items():
                    sv = str(v)
                    out.write(f"   {k} = {sv[:160]}\n")
with open("db_dump.txt", "w", encoding="utf-8") as f:
    f.write(out.getvalue())
print("ok", len(out.getvalue()))
