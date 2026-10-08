"""Shared helpers for the scripts GitHub Actions runs. Never prints secrets."""
import datetime as dt
import os
import sys
from pathlib import Path
from zoneinfo import ZoneInfo

import requests

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

BERLIN = ZoneInfo("Europe/Berlin")


def now_berlin():
    return dt.datetime.now(BERLIN)


def gate(hour):
    """Scheduled workflows are triggered at two UTC times (summer and winter time); only the one that falls in the
    wanted Berlin hour proceeds. Manual runs (workflow_dispatch) always proceed."""
    if os.environ.get("GITHUB_EVENT_NAME") == "schedule" and now_berlin().hour != hour:
        print(f"Berlin time is {now_berlin():%H:%M}, wanted hour {hour}: nothing to do (the other DST trigger handles it).")
        sys.exit(0)


def staff_post(text):
    """Post to the staff Discord webhook (DISCORD_WEBHOOK_STAFF). Returns True if delivered. The URL is never printed."""
    url = os.environ.get("DISCORD_WEBHOOK_STAFF", "")
    if not url:
        print("DISCORD_WEBHOOK_STAFF not set; alert not sent:", text[:120])
        return False
    try:
        r = requests.post(url, json={"username": "Overseer ops", "content": text[:1900], "allowed_mentions": {"parse": []}}, timeout=15)
        print("staff webhook HTTP", r.status_code)
        return r.status_code < 300
    except requests.RequestException as e:
        print("staff webhook failed:", type(e).__name__)
        return False
