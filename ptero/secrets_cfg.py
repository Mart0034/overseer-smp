"""Write secrets from .env into the plugin's config.yml on the server. Values are never printed or logged."""
import json
import re

from .client import load_env

CONFIG = "/plugins/Overseer/config.yml"


def _set(cfg, key, value):
    """Set `key: "<value>"` (a quoted scalar) in the yaml text; returns (new_text, found)."""
    pattern = r'(?m)^(\s*' + re.escape(key) + r':\s*)"[^"\n]*"'
    return re.subn(pattern, lambda m: m.group(1) + json.dumps(value), cfg, count=1)


def set_secrets(c, sid=None):
    env = load_env()
    key = env.get("OVERSEER_API_KEY", "")
    hook = env.get("DISCORD_WEBHOOK_PUBLIC", "")
    if len(key) < 20:
        raise SystemExit("OVERSEER_API_KEY missing in .env")
    cfg = c.read_file(CONFIG, sid)
    cfg, n = _set(cfg, "api-key", key)
    if n != 1:
        raise SystemExit("could not find the api-key line in the server config")
    wrote = []
    if hook:
        cfg, n = _set(cfg, "public-webhook", hook)
        if n != 1:   # older config without the discord section
            cfg = cfg.rstrip("\n") + "\ndiscord:\n  public-webhook: " + json.dumps(hook) + "\n"
        wrote.append("Discord webhook")
    c.write_file(CONFIG, cfg, sid)
    c.command("overseer reload", sid)
    print("Written to the server config and reloaded (values not shown): API key" + "".join(", " + w for w in wrote))
