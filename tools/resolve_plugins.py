#!/usr/bin/env python3
"""Resolve plugin downloads for a Minecraft version from official sources and write server-config/plugins.lock.
Usage: python tools/resolve_plugins.py 26.2   (downloads each jar to compute SHA-256; nothing is committed)"""
import hashlib, json, sys, urllib.request, urllib.parse
UA = {"User-Agent": "OverseerSMP/1.0 (public repo overseer-smp)"}
MC = sys.argv[1] if len(sys.argv) > 1 else "26.2"
PAPER_BUILD = sys.argv[2] if len(sys.argv) > 2 else None

def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120) as r: return r.read()
def sha(b): return hashlib.sha256(b).hexdigest()

out = []
# --- Geyser / Floodgate from GeyserMC's own download API
for proj in ("geyser", "floodgate"):
    vers = json.loads(get(f"https://download.geysermc.org/v2/projects/{proj}"))["versions"]
    d = json.loads(get(f"https://download.geysermc.org/v2/projects/{proj}/versions/{vers[-1]}/builds"))
    b = d["builds"][-1]; plat = "spigot"; f = b["downloads"][plat]
    url = f"https://download.geysermc.org/v2/projects/{proj}/versions/{vers[-1]}/builds/{b['build']}/downloads/{plat}"
    out.append(dict(name=proj, version=f"{vers[-1]}-b{b['build']}", file=f["name"], url=url, sha256=f["sha256"], source="download.geysermc.org"))

# --- Modrinth projects (official distribution for each)
MODRINTH = {"luckperms": "luckperms", "coreprotect": "coreprotect", "chunky": "chunky",
            "grimac": "grimac", "decentholograms": "decentholograms"}
for name, slug in MODRINTH.items():
    q = urllib.parse.urlencode({"game_versions": json.dumps([MC]), "loaders": json.dumps(["paper"])})
    try: vs = json.loads(get(f"https://api.modrinth.com/v2/project/{slug}/version?{q}"))
    except Exception as e: vs = []
    if not vs:
        q = urllib.parse.urlencode({"game_versions": json.dumps([MC])})
        try: vs = json.loads(get(f"https://api.modrinth.com/v2/project/{slug}/version?{q}"))
        except Exception: vs = []
    vs = [v for v in vs if v["version_type"] == "release"] or vs
    if not vs: print(f"!! {name}: no build for {MC} on Modrinth"); continue
    v = vs[0]; f = next((x for x in v["files"] if x["primary"]), v["files"][0])
    out.append(dict(name=name, version=v["version_number"], file=f["filename"], url=f["url"], sha256=sha(get(f["url"])),
                    source=f"modrinth.com/plugin/{slug}", channel=v["version_type"], loaders=v["loaders"]))
    print(name, v["version_number"], v["version_type"], v["loaders"])

# --- GitHub releases (official repos)
GH = {"essentialsx": ("EssentialsX/Essentials", "EssentialsX-"), "essentialsx-chat": ("EssentialsX/Essentials", "EssentialsXChat-"),
      "essentialsx-spawn": ("EssentialsX/Essentials", "EssentialsXSpawn-"), "nuvotifier": ("NuVotifier/NuVotifier", "nuvotifier.jar")}
for name, (repo, prefix) in GH.items():
    r = json.loads(get(f"https://api.github.com/repos/{repo}/releases/latest"))
    a = next(x for x in r["assets"] if x["name"].startswith(prefix))
    out.append(dict(name=name, version=r["tag_name"], file=a["name"], url=a["browser_download_url"],
                    sha256=sha(get(a["browser_download_url"])), source=f"github.com/{repo}"))
    print(name, r["tag_name"])

json.dump(dict(minecraft=MC, plugins=out), open("server-config/plugins.lock.json", "w"), indent=2)
print("wrote", len(out), "plugins")
