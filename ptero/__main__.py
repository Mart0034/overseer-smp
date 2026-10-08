"""CLI: python -m ptero <command> [args]   (server defaults to the one in PTERO_URL)"""
import sys
for _s in (sys.stdout, sys.stderr): _s.reconfigure(encoding="utf-8", errors="replace")
import argparse, json, re, shutil, subprocess, tempfile, time
from pathlib import Path
from .client import ALLOWED_SERVERS, Ptero, PteroError, ROOT, load_env


PLUGIN_JAR = "Overseer.jar"
BACKUP_DIR = "deploy-backup"


def wait_backup(c, uuid, sid, timeout=300):
    t = time.time()
    while time.time() - t < timeout:
        for x in c.backups(sid)["data"]:
            if x["attributes"]["uuid"] == uuid and x["attributes"]["is_successful"]:
                return True
        time.sleep(5)
    return False


def healthy(c, version, sid, timeout=90):
    """Server running AND the log shows this plugin version enabled. Returns (ok, reason)."""
    t = time.time()
    while time.time() - t < timeout:
        time.sleep(4)
        try:
            log = c.read_file("/logs/latest.log", sid)
        except PteroError:
            continue
        if "Error occurred while enabling Overseer" in log or "Could not load 'plugins/Overseer" in log:
            return False, "plugin failed to load/enable"
        if f"Enabling Overseer v{version}" in log and "[Overseer] Enabled" in log and "Done (" in log:
            return c.resources(sid)["current_state"] == "running", "enabled"
    return False, "no healthy log line within %d s" % timeout


def deploy(c, jar, sid):
    """backup -> keep old jar -> upload -> restart -> health check -> rollback if unhealthy."""
    assert jar.exists(), "jar not found"
    m = re.match(r"Overseer-(.+)\.jar$", jar.name)
    version = m.group(1) if m else "?"
    subprocess.run([sys.executable, "-I", str(ROOT / "scripts" / "secret_scan.py"), "all"], check=True)
    print("backup...")
    for n in c.rotate_backups(sid):
        print("rotated out oldest unlocked backup:", n)
    uuid = c.create_backup(f"pre-deploy-{version}-{int(time.time())}", sid)["attributes"]["uuid"]
    if not wait_backup(c, uuid, sid):
        print("backup did not complete; aborting deploy"); return 1
    had_old = c.file_exists("/plugins", PLUGIN_JAR, sid)
    if had_old:
        try: c.create_folder("/", BACKUP_DIR, sid)
        except PteroError: pass
        c.rename("/plugins", [(PLUGIN_JAR, f"../{BACKUP_DIR}/{PLUGIN_JAR}")], sid)
    with tempfile.TemporaryDirectory() as d:
        tmp = Path(d) / PLUGIN_JAR
        shutil.copy(jar, tmp)
        c.upload(str(tmp), "/plugins", sid)
    print("restart...")
    c.power("restart", sid)
    ok, why = healthy(c, version, sid)
    if ok:
        print(f"deploy ok: Overseer {version} ({why}); backup {uuid}")
        return 0
    print(f"UNHEALTHY ({why}); rolling back")
    c.delete_files("/plugins", [PLUGIN_JAR], sid)
    if had_old:
        c.rename(f"/{BACKUP_DIR}", [(PLUGIN_JAR, f"../plugins/{PLUGIN_JAR}")], sid)
    c.power("restart", sid)
    print("rolled back; full backup is", uuid)
    return 2


def set_key(c, sid):
    from .secrets_cfg import set_secrets
    set_secrets(c, sid)


def show_prayers(c, n, sid):
    """Download the plugin's SQLite log (db + WAL) to a temp dir and print the latest rows."""
    import sqlite3
    with tempfile.TemporaryDirectory() as d:
        for suffix in ("", "-wal", "-shm"):
            try:
                (Path(d) / f"overseer.db{suffix}").write_bytes(c.download(f"/plugins/Overseer/overseer.db{suffix}", sid))
            except PteroError:
                pass
        con = sqlite3.connect(str(Path(d) / "overseer.db"))
        rows = con.execute("SELECT datetime(ts/1000,'unixepoch'), name, prayer, status, action, effect, favor_delta, input_tokens, output_tokens, latency_ms, reply, counted FROM prayers ORDER BY id DESC LIMIT ?", (n,)).fetchall()
        for r in reversed(rows):
            print(f"{r[0]} {r[1]}: {r[2]!r}")
            print(f"   status={r[3]} action={r[4]} effect={r[5]} favor={r[6]} tokens={r[7]}/{r[8]} {r[9]}ms counted={r[11]}")
            print(f"   reply={r[10]!r}")
        print(f"({len(rows)} rows)")
        con.close()


def main():
    ap = argparse.ArgumentParser(prog="ptero")
    ap.add_argument("--server", help="server identifier (must be in ALLOWED_SERVERS)")
    sub = ap.add_subparsers(dest="c", required=True)
    for n in ("whoami", "list", "status", "resources", "backups", "files", "schedules", "startup"):
        sub.add_parser(n)
    p = sub.add_parser("cmd"); p.add_argument("command", nargs="+")
    p = sub.add_parser("power"); p.add_argument("signal", choices=["start", "stop", "restart", "kill"])
    p = sub.add_parser("upload"); p.add_argument("local"); p.add_argument("directory", nargs="?", default="/plugins")
    p = sub.add_parser("pull"); p.add_argument("url"); p.add_argument("directory"); p.add_argument("--filename")
    p = sub.add_parser("logs"); p.add_argument("--seconds", type=int, default=8)
    p = sub.add_parser("cat"); p.add_argument("path")
    p = sub.add_parser("put"); p.add_argument("local"); p.add_argument("remote")
    p = sub.add_parser("deploy"); p.add_argument("jar")
    p = sub.add_parser("backup"); p.add_argument("--keep", type=int, help="max backups to keep (default 7, never above the host limit)")
    sub.add_parser("set-key")
    p = sub.add_parser("prayers"); p.add_argument("n", nargs="?", type=int, default=10)
    a = ap.parse_args()
    c, sid = Ptero(), a.server
    try:
        if a.c == "whoami":
            print(json.dumps(c.account(), indent=1))
        elif a.c == "list":  # other servers on the shared panel are counted, never listed
            allv = c.servers()
            print(f"{len(allv)} servers visible to this key; project servers:")
            for s in allv:
                s = s["attributes"]
                if s["identifier"] in ALLOWED_SERVERS:
                    print(f" {s['identifier']}  {s['name']}  node={s['node']}  limits={s['limits']}")
        elif a.c == "status":
            d, r = c.details(sid), c.resources(sid)
            res = r["resources"]
            print(d["name"], "|", r["current_state"], "| cpu %.1f%% mem %d/%d MB disk %d MB" % (
                res["cpu_absolute"], res["memory_bytes"] / 2**20, d["limits"]["memory"], res["disk_bytes"] / 2**20))
            print("allocations:", [(x["attributes"]["ip_alias"] or x["attributes"]["ip"], x["attributes"]["port"], x["attributes"]["is_default"])
                                   for x in d["relationships"]["allocations"]["data"]])
        elif a.c == "resources": print(json.dumps(c.resources(sid), indent=1))
        elif a.c == "startup": print(json.dumps(c.variables(sid), indent=1))
        elif a.c == "cmd": c.command(" ".join(a.command), sid); print("sent")
        elif a.c == "power": c.power(a.signal, sid); print("sent", a.signal)
        elif a.c == "upload": c.upload(a.local, a.directory, sid); print("uploaded")
        elif a.c == "pull": c.pull(a.url, a.directory, a.filename, sid); print("pulled")
        elif a.c == "logs": print("\n".join(c.logs(sid, a.seconds)))
        elif a.c == "cat": print(c.read_file(a.path, sid))
        elif a.c == "put": c.write_file(a.remote, Path(a.local).read_bytes(), sid); print("written")
        elif a.c == "files": print(json.dumps([(f["attributes"]["name"], f["attributes"]["size"]) for f in c.list_files("/", sid)]))
        elif a.c == "schedules": print(json.dumps(c.schedules(sid), indent=1))
        elif a.c == "backups": print(json.dumps(c.backups(sid), indent=1))
        elif a.c == "backup":
            for n in c.rotate_backups(sid, a.keep):
                print("rotated out oldest unlocked backup:", n)
            b = c.create_backup(f"manual-{int(time.time())}", sid)
            print("backup started:", b["attributes"]["uuid"])
        elif a.c == "deploy":
            sys.exit(deploy(c, Path(a.jar), sid))
        elif a.c == "prayers":
            show_prayers(c, a.n, sid)
        elif a.c == "set-key":
            set_key(c, sid)
    except PteroError as e:
        sys.exit(f"error: {e}")


main()
