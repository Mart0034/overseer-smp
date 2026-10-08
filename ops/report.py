"""Daily 05:15 Berlin: write reports/YYYY-MM-DD.md from what the client API can read (SQLite log, decree.json, server logs, resources).

The repo is PUBLIC: the report contains aggregates only. No player names, no prayer text, no IPs, no secrets.
"""
import collections
import datetime as dt
import gzip
import json
import re
import sqlite3
import sys
import tempfile
from pathlib import Path

from ops.common import BERLIN, ROOT, gate, now_berlin
from ptero.client import Ptero, PteroError

JOIN = re.compile(r"\]: ([\w.]{3,17}) joined the game")
LEAVE = re.compile(r"\]: ([\w.]{3,17}) left the game")
STAMP = re.compile(r"^\[(\d\d):(\d\d):(\d\d)\] \[([^\]/]+)/(INFO|WARN|ERROR)\]: (.*)$")
IPV4 = re.compile(r"\b\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?\b")
UUID = re.compile(r"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b")
NOISE = ("Chunky", "Essentials] You are running an unsupported", "Could not save persona.md", "Could not save decree.md")


def fetch_db(c, workdir):
    for suffix in ("", "-wal", "-shm"):
        try:
            (workdir / f"overseer.db{suffix}").write_bytes(c.download(f"/plugins/Overseer/overseer.db{suffix}"))
        except PteroError:
            pass
    return sqlite3.connect(str(workdir / "overseer.db"))


def fetch_logs(c, since_utc):
    """[(datetime_utc, thread, level, message)] for log lines since `since_utc`."""
    out = []
    files = c.list_files("/logs")
    today = dt.datetime.now(dt.timezone.utc).date()
    for f in files:
        a = f["attributes"]
        name = a["name"]
        try:
            if name == "latest.log":
                raw = c.download("/logs/latest.log").decode("utf-8", "replace")
                day = today
            elif name.endswith(".log.gz") and name[:10] >= since_utc.strftime("%Y-%m-%d"):
                raw = gzip.decompress(c.download(f"/logs/{name}")).decode("utf-8", "replace")
                day = dt.date.fromisoformat(name[:10])
            else:
                continue
        except (PteroError, OSError):
            continue
        last = None
        for line in raw.splitlines():
            m = STAMP.match(line)
            if not m:
                continue
            h, mi, s = int(m[1]), int(m[2]), int(m[3])
            t = dt.datetime.combine(day, dt.time(h, mi, s), tzinfo=dt.timezone.utc)
            if name == "latest.log" and t > dt.datetime.now(dt.timezone.utc) + dt.timedelta(minutes=5):
                t -= dt.timedelta(days=1)                  # the file started yesterday evening
            if last and name == "latest.log" and t < last - dt.timedelta(hours=1):
                t += dt.timedelta(days=1)
            last = t
            if t >= since_utc:
                out.append((t, m[4], m[5], m[6]))
    out.sort(key=lambda x: x[0])
    return out


def scrub(text, names):
    text = IPV4.sub("<ip>", text)
    text = UUID.sub("<uuid>", text)
    for n in names:
        if len(n) >= 3:
            text = re.sub(re.escape(n), "<player>", text, flags=re.IGNORECASE)
    return text


def normalise(msg):
    m = re.sub(r"\d+(\.\d+)?", "N", msg)
    return m[:160]


def main():
    gate(5)
    c = Ptero()
    now = dt.datetime.now(dt.timezone.utc)
    since = now - dt.timedelta(hours=24)
    since_ms = int(since.timestamp() * 1000)
    day = now_berlin().date()
    lines = []
    add = lines.append

    with tempfile.TemporaryDirectory() as d:
        con = fetch_db(c, Path(d))
        q = lambda sql, *a: con.execute(sql, a).fetchall()
        names = {r[0] for r in q("SELECT name FROM players") if r[0]}

        # ---- players (from the join/leave log lines of the last 24 h) ----
        logs = fetch_logs(c, since)
        events = []
        for t, _, _, msg in logs:
            if (m := JOIN.search("]: " + msg)):
                events.append((t, +1, m[1]))
            elif (m := LEAVE.search("]: " + msg)):
                events.append((t, -1, m[1]))
        online, peak = set(), 0
        joined_at, sessions = {}, []
        for t, kind, who in events:
            if kind > 0:
                online.add(who); joined_at[who] = t; peak = max(peak, len(online))
            else:
                online.discard(who)
                if who in joined_at:
                    sessions.append((t - joined_at.pop(who)).total_seconds())
        unique = len({w for _, k, w in events if k > 0})
        new = q("SELECT COUNT(*) FROM players WHERE first_seen>=?", since_ms)[0][0]
        avg_min = (sum(sessions) / len(sessions) / 60) if sessions else 0

        # ---- retention (approximate: players table has first/last seen only; sessions table arrives with v0.3) ----
        def retention(days):
            lo = int((now - dt.timedelta(days=days + 1)).timestamp() * 1000)
            hi = int((now - dt.timedelta(days=days)).timestamp() * 1000)
            rows = q("SELECT first_seen,last_seen FROM players WHERE first_seen>=? AND first_seen<?", lo, hi)
            back = sum(1 for f, l in rows if l - f >= (days * 24 - 4) * 3600 * 1000)
            return back, len(rows)

        ret = {1: retention(1), 7: retention(7)}

        # ---- prayers / API ----
        pr = q("SELECT status, action, input_tokens, output_tokens, uuid, counted FROM prayers WHERE ts>=?", since_ms)
        by_status = collections.Counter(s.split(":")[0] for s, *_ in pr)
        answered = [r for r in pr if r[0] == "ok"]
        actions = collections.Counter(r[1] for r in answered)
        praying = len({r[4] for r in pr})
        usage = q("SELECT COUNT(*), COALESCE(SUM(input_tokens),0), COALESCE(SUM(output_tokens),0), COALESCE(SUM(cost_usd),0) FROM api_usage WHERE ts>=?", since_ms)[0]
        month_start = int(now_berlin().replace(day=1, hour=0, minute=0, second=0, microsecond=0).timestamp() * 1000)
        month_cost = q("SELECT COALESCE(SUM(cost_usd),0) FROM api_usage WHERE ts>=?", month_start)[0][0]
        try:
            cfg = c.read_file("/plugins/Overseer/config.yml")
            cap = float(re.search(r"monthly-cap-usd:\s*([\d.]+)", cfg)[1])
        except (PteroError, TypeError, ValueError):
            cap = None

        # ---- decree ----
        dec = q("SELECT ts, modifiers, source FROM decrees WHERE ts>=? ORDER BY ts DESC", since_ms)
        try:
            active = json.loads(c.read_file("/plugins/Overseer/decree.json")).get("active")
        except (PteroError, ValueError):
            active = None

        # ---- log problems ----
        problems = collections.Counter()
        for t, thread, level, msg in logs:
            if level in ("WARN", "ERROR") and not any(n in msg for n in NOISE):
                problems[(level, normalise(scrub(msg, names)))] += 1
        con.close()

    res = c.resources()
    backups = c.all_backups()

    add(f"# Daily report — {day.isoformat()}")
    add("")
    add(f"_Generated {now_berlin():%Y-%m-%d %H:%M} Europe/Berlin by `ops/report.py`; window: the last 24 hours. Aggregates only (the repo is public)._")
    add("")
    add("## Players")
    add(f"- Unique players who joined: **{unique}** (new in the player table: {new}; returning: {max(0, unique - new)})")
    add(f"- Peak concurrent players (from join/leave log lines): **{peak}**; average session: {avg_min:.0f} min ({len(sessions)} sessions)")
    for days in (1, 7):
        back, n = ret[days]
        add(f"- D{days} retention (approx., from first/last seen; cohort of {n}): " + (f"{back / n:.0%}" if n else "n/a (no cohort yet)"))
    add("")
    add("## Prayers and the API")
    add(f"- Prayer attempts: **{len(pr)}** from {praying} players — answered {by_status.get('ok', 0)}, filtered {by_status.get('filtered', 0)}, "
        f"limited {by_status.get('limited', 0)}, errors {by_status.get('error', 0)}, rejected replies {by_status.get('invalid', 0)}")
    if answered:
        add("- Outcomes of answered prayers: " + ", ".join(f"{k} {v}" for k, v in actions.most_common()))
    add(f"- API calls: {usage[0]}; tokens {usage[1]} in / {usage[2]} out; estimated cost today **${usage[3]:.4f}**; this month **${month_cost:.4f}**"
        + (f" of the ${cap:.2f} local cap" if cap else ""))
    add("")
    add("## Decree")
    if dec:
        for ts, mods, source in dec:
            ids = ", ".join(m["id"] for m in json.loads(mods)) if mods else "?"
            add(f"- {dt.datetime.fromtimestamp(ts / 1000, BERLIN):%H:%M}: {ids} ({source})")
    else:
        add("- No decree was issued in this window.")
    if active:
        add(f"- Active now: {', '.join(s['id'] for s in active['selected'])}, expires {dt.datetime.fromtimestamp(active['expiresAt'] / 1000, BERLIN):%Y-%m-%d %H:%M}")
    add("")
    add("## Server")
    r = res["resources"]
    add(f"- State: {res['current_state']}; RAM {r['memory_bytes'] / 2**20:.0f} MB; disk {r['disk_bytes'] / 2**30:.2f} GB; uptime {res.get('resources', {}).get('uptime', 0) / 3600000:.1f} h")
    add(f"- Backups: {len(backups)} ({sum(b.get('bytes', 0) for b in backups) / 2**30:.2f} GB total)")
    add("")
    add("## Warnings and errors in the logs (last 24 h)")
    if problems:
        for (level, msg), n in problems.most_common(12):
            add(f"- {n}× `{level}` {msg}")
    else:
        add("- None.")
    add("")
    add("## Revenue")
    add("- Not connected yet (no store).")
    add("")

    path = ROOT / "reports" / f"{day.isoformat()}.md"
    path.parent.mkdir(exist_ok=True)
    # never overwrite a hand-written handoff for the same day: write the generated one next to it
    if path.exists() and "Generated" not in path.read_text(encoding="utf-8")[:600]:
        path = ROOT / "reports" / f"{day.isoformat()}-auto.md"
    path.write_text("\n".join(lines), encoding="utf-8", newline="\n")
    print("wrote", path.relative_to(ROOT))
    return 0


if __name__ == "__main__":
    sys.exit(main())
