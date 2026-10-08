"""Every 15 min: is the server running, is TPS healthy, is RAM OK? Alerts the staff webhook ONLY when the state changes.

State is kept between runs in health-state.json (the workflow caches it). Worst state wins: down > unresponsive > lowtps > highram > ok.
"""
import json
import re
import sys
import time
from pathlib import Path

from ops.common import now_berlin, staff_post
from ptero.client import Ptero, PteroError

STATE_FILE = Path("health-state.json")
TPS_ALERT = 18.0
RAM_ALERT = 0.95           # fraction of the container limit
TPS_LINE = re.compile(r"TPS from last 1m, 5m, 15m:\s*([*\d.]+)")
SEVERITY = {"ok": 0, "highram": 1, "lowtps": 2, "unresponsive": 3, "down": 4}


def in_restart_window():
    """The nightly restart (panel schedule) makes the server briefly 'down' around 04:55-05:05 Berlin; don't alert for it."""
    t = now_berlin()
    return (t.hour, t.minute) >= (4, 50) and (t.hour, t.minute) <= (5, 10)


def tps_lines(text):
    return TPS_LINE.findall(text)


def measure_tps(c):
    """Send `tps` to the console and read the answer from latest.log. Returns the 1-minute TPS, or None if the console did not answer."""
    before = len(tps_lines(c.read_file("/logs/latest.log")))
    c.command("tps")
    for _ in range(8):
        time.sleep(2)
        found = tps_lines(c.read_file("/logs/latest.log"))
        if len(found) > before:
            return float(found[-1].strip("*"))
    return None


def classify(c):
    """Returns (status, detail)."""
    res = c.resources()
    if res["current_state"] != "running":
        time.sleep(60)                                   # a deploy/restart can be mid-flight: look twice
        res = c.resources()
    if res["current_state"] != "running":
        return "down", f"power state: {res['current_state']}"
    limit_mb = c.details()["limits"]["memory"]
    used_mb = res["resources"]["memory_bytes"] / 2**20
    tps = measure_tps(c)
    if tps is None:
        return "unresponsive", "container is running but the console did not answer `tps` within 16 s"
    if tps < TPS_ALERT:
        return "lowtps", f"TPS {tps:.1f} (1 min average)"
    if used_mb / limit_mb > RAM_ALERT:
        return "highram", f"RAM {used_mb:.0f} / {limit_mb} MB"
    return "ok", f"TPS {tps:.1f}, RAM {used_mb:.0f} / {limit_mb} MB"


MESSAGES = {
    "down": "\U0001f534 **Overseer SMP is DOWN** \u2014 {d}",
    "unresponsive": "\U0001f534 **Overseer SMP is not responding** \u2014 {d}",
    "lowtps": "\U0001f7e0 **Low TPS** \u2014 {d}",
    "highram": "\U0001f7e0 **High memory use** \u2014 {d}",
    "ok": "\U0001f7e2 **Overseer SMP is healthy again** \u2014 {d}",
}


def main():
    prev = "ok"
    if STATE_FILE.exists():
        try:
            prev = json.loads(STATE_FILE.read_text())["status"]
        except (ValueError, KeyError):
            pass
    try:
        status, detail = classify(Ptero())
    except PteroError as e:
        status, detail = "down", f"panel API error: {str(e)[:160]}"
    except Exception as e:                               # network trouble on the runner side must not look like a server outage
        print("health check could not run:", type(e).__name__)
        return 0
    print(f"previous={prev} now={status} ({detail})")
    if status == "down" and in_restart_window():
        print("inside the nightly restart window: not alerting, keeping the previous state")
        status = prev
    elif status != prev:
        staff_post(MESSAGES[status].format(d=detail))
    STATE_FILE.write_text(json.dumps({"status": status, "detail": detail, "checked": now_berlin().isoformat()}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
