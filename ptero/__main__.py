"""CLI: python -m ptero <command> [args]   (server defaults to the one in PTERO_URL)"""
import sys
for _s in (sys.stdout, sys.stderr): _s.reconfigure(encoding="utf-8", errors="replace")
import argparse, json, subprocess, time
from pathlib import Path
from .client import ALLOWED_SERVERS, Ptero, PteroError, ROOT


def main():
    ap = argparse.ArgumentParser(prog="ptero")
    ap.add_argument("--server", help="server identifier (must be in ALLOWED_SERVERS)")
    sub = ap.add_subparsers(dest="c", required=True)
    for n in ("whoami", "list", "status", "resources", "backup", "backups", "files", "schedules", "startup"):
        sub.add_parser(n)
    p = sub.add_parser("cmd"); p.add_argument("command", nargs="+")
    p = sub.add_parser("power"); p.add_argument("signal", choices=["start", "stop", "restart", "kill"])
    p = sub.add_parser("upload"); p.add_argument("local"); p.add_argument("directory", nargs="?", default="/plugins")
    p = sub.add_parser("pull"); p.add_argument("url"); p.add_argument("directory"); p.add_argument("--filename")
    p = sub.add_parser("logs"); p.add_argument("--seconds", type=int, default=8)
    p = sub.add_parser("cat"); p.add_argument("path")
    p = sub.add_parser("put"); p.add_argument("local"); p.add_argument("remote")
    p = sub.add_parser("deploy"); p.add_argument("jar")
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
            b = c.create_backup(f"manual-{int(time.time())}", sid)
            print("backup started:", b["attributes"]["uuid"])
        elif a.c == "deploy":  # backup -> upload -> restart -> health check (90 s)
            jar = Path(a.jar)
            assert jar.exists(), "jar not found"
            subprocess.run([sys.executable, "-I", str(ROOT / "scripts" / "secret_scan.py"), "all"], check=True)
            uuid = c.create_backup(f"pre-deploy-{int(time.time())}", sid)["attributes"]["uuid"]
            for _ in range(60):
                if any(x["attributes"]["uuid"] == uuid and x["attributes"]["is_successful"] for x in c.backups(sid)["data"]):
                    break
                time.sleep(5)
            else:
                sys.exit("backup did not complete; aborting deploy")
            c.upload(str(jar), "/plugins", sid)
            c.power("restart", sid)
            time.sleep(10)
            if c.wait_state("running", sid, 90):
                print("deploy ok; backup", uuid)
            else:
                sys.exit(f"UNHEALTHY after 90 s: restore backup {uuid}")
    except PteroError as e:
        sys.exit(f"error: {e}")


main()
