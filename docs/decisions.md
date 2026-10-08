# Decision log

| Date | Decision | Why |
|---|---|---|
| 2026-10-08 | Repo initialised; `.env`/jars/clips ignored; secret scan in `.githooks` (pre-commit + pre-push, `scripts/secret_scan.py`) | Public repo; secrets must never be pushed |
| 2026-10-08 | Panel is hosted (Flamegrid); both keys are one non-admin user (`mart`), Application API returns 403 | No server creation, no egg import, no node info available to us |
| 2026-10-08 | Target server is the pre-existing `OSMP | Main` (`4036236e`), confirmed by Mart; `ptero/client.py` hard-refuses any other server id | Account also holds 18 unrelated servers; scope rule |
| 2026-10-08 | No custom egg; use the host's Java-25 Minecraft egg and set variables (Aikar's Flags, MAXIMUM_RAM=85) | Eggs can't be imported without admin; the stock egg already supports it. Plugins are installed by `ptero` after SHA-256 verification against `server-config/plugins.lock` |
| 2026-10-08 | Minecraft **26.2**, Paper build 132 (STABLE) | Newest stable Paper that Geyser 2.11.3 supports (Geyser README: Java 26.2). 26.3 Paper is BETA only. Fallback: 26.1.2 |
| 2026-10-08 | Pin Grim (alpha), EssentialsX 2.22.0 (tagged to 26.1.2), NuVotifier 2.7.3 | Only builds available; verify at first boot. spark unresolved (no stable URL) |
| 2026-10-08 | Bedrock/Geyser to share port 25584 over UDP (Java uses TCP) | Server has a single allocation; Docker publishes both protocols |
