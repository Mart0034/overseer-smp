"""Daily 05:30 Berlin: keep the newest 7 backups, delete the oldest unlocked ones (protects the disk; the panel's own backup is not rotated)."""
import sys

from ops.common import gate, staff_post
from ptero.client import Ptero, PteroError

KEEP = 7


def main():
    gate(5)
    c = Ptero()
    try:
        before = c.all_backups()
        deleted = c.trim_backups(keep=KEEP)
        after = c.all_backups()
    except PteroError as e:
        staff_post(f"⚠️ Backup rotation failed: {str(e)[:300]}")
        print("error:", e)
        return 1
    for name, size in deleted:
        print(f"deleted: {name} ({size / 2**20:.0f} MB)")
    gb = sum(b.get("bytes", 0) for b in after) / 2**30
    print(f"backups: {len(before)} -> {len(after)} (keep {KEEP}); total {gb:.2f} GB")
    if len(after) > KEEP:
        staff_post(f"⚠️ {len(after)} backups remain (> {KEEP}) because the rest are locked.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
