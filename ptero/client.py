"""Small Pterodactyl API client (Application + Client). Reads .env at the repo root.

Safety: every server-scoped call is restricted to servers whose identifier is in ALLOWED_SERVERS
(the project's own server). The client key can see other servers on this shared panel; this guard
makes it impossible to touch them by accident."""
import json, os, time
from pathlib import Path
from urllib.parse import urlparse
import requests

ROOT = Path(__file__).resolve().parent.parent
ALLOWED_SERVERS = {"4036236e"}  # "OSMP | Main" - confirmed by Mart as the Overseer SMP server


def load_env():
    env = dict(os.environ)
    f = ROOT / ".env"
    if f.exists():
        for line in f.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                env.setdefault(k.strip(), v.strip().strip("\"'"))
    return env


class PteroError(Exception):
    pass


class Ptero:
    def __init__(self):
        e = load_env()
        u = urlparse(e["PTERO_URL"])
        self.base = f"{u.scheme}://{u.netloc}"  # PTERO_URL may carry a /server/<id> path; only the origin matters
        self.client_key, self.app_key = e["PTERO_CLIENT_KEY"], e.get("PTERO_APP_KEY", "")
        parts = [p for p in u.path.split("/") if p]
        self.default_server = parts[1] if len(parts) >= 2 and parts[0] == "server" else None
        self.s = requests.Session()

    def _req(self, method, path, key, **kw):
        h = {"Authorization": f"Bearer {key}", "Accept": "Application/vnd.pterodactyl.v1+json"}
        h.update(kw.pop("headers", {}))
        for _ in range(4):
            try:
                r = self.s.request(method, f"{self.base}{path}", headers=h, timeout=60, **kw)
            except requests.ConnectionError:
                time.sleep(3)
                continue
            if r.status_code == 429:
                time.sleep(int(r.headers.get("Retry-After", 2)) + 1)
                continue
            break
        else:
            raise PteroError(f"{method} {path}: network failure after retries")
        if r.status_code >= 400:
            raise PteroError(f"{method} {path} -> {r.status_code}: {r.text[:300]}")
        return r.json() if r.content and "json" in r.headers.get("content-type", "") else r.text

    def app(self, method, path, **kw):
        return self._req(method, f"/api/application{path}", self.app_key, **kw)

    def _guard(self, sid):
        sid = sid or self.default_server
        if sid not in ALLOWED_SERVERS:
            raise PteroError(f"refusing to touch server {sid!r}: not in ALLOWED_SERVERS")
        return sid

    def api(self, method, sid, path="", **kw):
        sid = self._guard(sid)
        return self._req(method, f"/api/client/servers/{sid}{path}", self.client_key, **kw)

    # ---- convenience
    def account(self):
        return self._req("GET", "/api/client/account", self.client_key)["attributes"]

    def servers(self):
        return self._req("GET", "/api/client?per_page=100", self.client_key)["data"]

    def details(self, sid=None): return self.api("GET", sid)["attributes"]
    def resources(self, sid=None): return self.api("GET", sid, "/resources")["attributes"]
    def command(self, cmd, sid=None): return self.api("POST", sid, "/command", json={"command": cmd})
    def power(self, signal, sid=None): return self.api("POST", sid, "/power", json={"signal": signal})
    def list_files(self, d="/", sid=None): return self.api("GET", sid, "/files/list", params={"directory": d})["data"]
    def read_file(self, path, sid=None): return self.api("GET", sid, "/files/contents", params={"file": path})

    def write_file(self, path, data, sid=None):
        body = data if isinstance(data, bytes) else data.encode()
        return self.api("POST", sid, "/files/write", params={"file": path}, data=body, headers={"Content-Type": "text/plain"})

    def delete_files(self, root, names, sid=None): return self.api("POST", sid, "/files/delete", json={"root": root, "files": names})

    def pull(self, url, directory, filename=None, sid=None):
        body = {"url": url, "directory": directory, "use_header": False, "foreground": True}
        if filename:
            body["filename"] = filename
        return self.api("POST", sid, "/files/pull", json=body)

    def upload(self, local, directory="/plugins", sid=None):
        url = self.api("GET", sid, "/files/upload")["attributes"]["url"]
        with open(local, "rb") as f:
            r = requests.post(url, params={"directory": directory}, files={"files": (Path(local).name, f)}, timeout=600)
        if r.status_code >= 400:
            raise PteroError(f"upload failed {r.status_code}: {r.text[:200]}")

    def backups(self, sid=None): return self.api("GET", sid, "/backups")
    def create_backup(self, name=None, sid=None): return self.api("POST", sid, "/backups", json={"name": name} if name else {})
    def variables(self, sid=None): return self.api("GET", sid, "/startup")
    def schedules(self, sid=None): return self.api("GET", sid, "/schedules")

    def wait_state(self, want, sid=None, timeout=120):
        t = time.time()
        while time.time() - t < timeout:
            if self.resources(sid)["current_state"] == want:
                return True
            time.sleep(3)
        return False

    def logs(self, sid=None, seconds=8):
        """Collect console output via the websocket for a few seconds."""
        import websocket
        d = self.api("GET", sid, "/websocket")["data"]
        ws = websocket.create_connection(d["socket"], origin=self.base, timeout=5)
        ws.send(json.dumps({"event": "auth", "args": [d["token"]]}))
        ws.send(json.dumps({"event": "send logs", "args": [None]}))
        out, end = [], time.time() + seconds
        while time.time() < end:
            try:
                m = json.loads(ws.recv())
            except Exception:
                continue
            if m["event"] in ("console output", "install output"):
                out.append(m["args"][0])
        ws.close()
        return out

    def set_var(self, key, value, sid=None):
        return self.api("PUT", sid, "/startup/variable", json={"key": key, "value": value})

    def create_folder(self, root, name, sid=None):
        return self.api("POST", sid, "/files/create-folder", json={"root": root, "name": name})

    def rename(self, root, pairs, sid=None):
        """pairs: [(from, to), ...] relative to root; `to` may include a subfolder path."""
        return self.api("PUT", sid, "/files/rename", json={"root": root, "files": [{"from": a, "to": b} for a, b in pairs]})

    def file_exists(self, directory, name, sid=None):
        return any(f["attributes"]["name"] == name for f in self.list_files(directory, sid))

    def download(self, path, sid=None):
        url = self.api("GET", sid, "/files/download", params={"file": path})["attributes"]["url"]
        r = requests.get(url, timeout=120)
        if r.status_code >= 400:
            raise PteroError(f"download failed {r.status_code}")
        return r.content

    # ---- backup rotation
    BACKUP_KEEP_DEFAULT = 7  # disk guard: the host reports a limit of 999999 but each backup is ~1-6 GB on a 128 GB disk

    def all_backups(self, sid=None):
        out, page = [], 1
        while True:
            r = self.api("GET", sid, "/backups", params={"page": page, "per_page": 50})
            out += [b["attributes"] for b in r["data"]]
            if page >= r["meta"]["pagination"]["total_pages"]:
                return out
            page += 1

    def delete_backup(self, uuid, sid=None):
        return self.api("DELETE", sid, f"/backups/{uuid}")

    def rotate_backups(self, sid=None, keep=None):
        """Make room for one more backup: while count >= min(host limit, keep), delete the oldest UNLOCKED backup.
        Never deletes locked backups; raises if nothing can be deleted. Returns the deleted backup names."""
        host = self.details(sid)["feature_limits"].get("backups") or self.BACKUP_KEEP_DEFAULT
        limit = max(1, min(host, keep or self.BACKUP_KEEP_DEFAULT))
        deleted = []
        backups = self.all_backups(sid)
        while len(backups) >= limit:
            candidates = sorted((b for b in backups if not b["is_locked"]), key=lambda b: b["created_at"])
            if not candidates:
                raise PteroError(f"backup limit {limit} reached and every backup is locked")
            oldest = candidates[0]
            self.delete_backup(oldest["uuid"], sid)
            deleted.append(oldest["name"])
            backups = [b for b in backups if b["uuid"] != oldest["uuid"]]
        return deleted
