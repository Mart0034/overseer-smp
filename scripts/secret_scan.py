#!/usr/bin/env python3
"""Fail if any value from .env (or a well-known secret pattern) appears in staged/tracked content.
Used by .githooks/pre-commit and .githooks/pre-push."""
import re, subprocess, sys
from pathlib import Path

root = Path(subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip())
secrets = set()
env = root / ".env"
if env.exists():
    for line in env.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            v = line.split("=", 1)[1].strip().strip("\"'")
            if len(v) >= 8:
                secrets.add(v)
patterns = [re.compile(p) for p in (
    r"ptl[ac]_[A-Za-z0-9]{20,}",            # pterodactyl keys
    r"sk-ant-[A-Za-z0-9_\-]{20,}",          # anthropic keys
    r"gh[pousr]_[A-Za-z0-9]{30,}",          # github tokens
    r"github_pat_[A-Za-z0-9_]{30,}",
    r"discord(?:app)?\.com/api/webhooks/\d+/[\w\-]+",
)]
mode = sys.argv[1] if len(sys.argv) > 1 else "staged"
if mode == "staged":
    files = subprocess.check_output(["git", "diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z"], text=True).split("\0")
    def read(f): return subprocess.run(["git", "show", f":{f}"], capture_output=True).stdout.decode("utf-8", "ignore")
else:  # all tracked files
    files = subprocess.check_output(["git", "ls-files", "-z"], text=True).split("\0")
    def read(f):
        try: return (root / f).read_text(encoding="utf-8", errors="ignore")
        except OSError: return ""
bad = []
for f in filter(None, files):
    if f == ".env" or f.endswith("/.env"):
        bad.append((f, ".env file staged")); continue
    if f == "scripts/secret_scan.py": continue
    t = read(f)
    for s in secrets:
        if s in t: bad.append((f, "contains a value from .env"))
    for p in patterns:
        if p.search(t): bad.append((f, f"matches {p.pattern}"))
if bad:
    print("SECRET SCAN FAILED:")
    for f, why in bad: print(f"  {f}: {why}")
    sys.exit(1)
print("secret scan ok")
