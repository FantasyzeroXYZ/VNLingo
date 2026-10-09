"""导出 engine_detection_cache 与 game_overrides 中 Sakura 相关行（判断内核版本判定结果）。"""
import sqlite3, sys, io, urllib.parse

db = sys.argv[1]
con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
cur = con.cursor()
out = io.StringIO()

def dec(uri):
    try:
        return urllib.parse.unquote(uri)
    except Exception:
        return uri

cur.execute("SELECT uri, title, engine, launch_target, launch_file, last_opened_at FROM games")
games = {r[0]: r for r in cur.fetchall()}

cur.execute("SELECT * FROM engine_detection_cache")
cols = [d[0] for d in cur.description]
out.write("=== engine_detection_cache ===\n")
for row in cur.fetchall():
    d = dict(zip(cols, row))
    u = d.get("game_uri") or ""
    if "Sakura" in dec(u) or "Jirai" in dec(u):
        out.write(f"--- {dec(u)}\n")
        for k, v in d.items():
            out.write(f"    {k} = {str(v)[:200]}\n")

out.write("\n=== game_overrides ===\n")
cur.execute("SELECT * FROM game_overrides")
cols = [d[0] for d in cur.description]
for row in cur.fetchall():
    d = dict(zip(cols, row))
    u = d.get("game_uri") or ""
    if "Sakura" in dec(u) or "Jirai" in dec(u):
        out.write(f"--- {dec(u)}\n")
        for k, v in d.items():
            out.write(f"    {k} = {str(v)[:300]}\n")

out.write("\n=== 全部 ARTEMIS 游戏的检测缓存（对照）===\n")
for row in cur.execute("SELECT * FROM engine_detection_cache"):
    d = dict(zip([x[0] for x in cur.description], row))
    if d.get("engine") == "ARTEMIS":
        out.write(f"    {dec(d.get('game_uri') or '')[:120]} | artemis_version={d.get('artemis_version')} success={d.get('artemis_success_version')} conf={d.get('confidence')} reason={str(d.get('reason'))[:60]}\n")

with open("db_dump2.txt", "w", encoding="utf-8") as f:
    f.write(out.getvalue())
print("ok", len(out.getvalue()))
