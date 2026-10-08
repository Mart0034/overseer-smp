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
| 2026-10-08 | EULA accepted by Mart; server started on 26.2; Geyser moved to UDP 25584 (`auth-type: floodgate`); Bedrock ping from outside answered, Java login from outside confirmed | Single allocation shared over TCP (Java) + UDP (Bedrock) |
| 2026-10-08 | World border 6000 wide overworld / 2000 nether (radius 3000 / 1000), centre 0,0; Chunky square pregen running (~40 chunks/s, TPS 20) | Spec section 5 |
| 2026-10-08 | Panel schedules run in **US Eastern**, not Berlin: backup 22:50 ET, restart sequence 22:55 ET (warnings at 5 min, 1 min, 10 s; restart at +300 s) = 04:50/05:00 Berlin | Panel timezone is not configurable via API. **TODO:** Berlin leaves DST on 2026-10-25, US on 11-01, so move to 23:50/23:55 ET on 10-25 and back to 22:50/22:55 on 11-01 (same dance in March) |
| 2026-10-08 | LuckPerms groups default/acolyte/priest/prophet/admin (weights 10/20/30/100), Mart0034 in admin (+op) | Spec; cosmetic groups empty for now |
| 2026-10-08 | Paid BuiltByBit jars are never committed or put in plugins.lock; Mart downloads, ptero uploads | Public repo + licence terms |
| 2026-10-08 | EssentialsX logs "unsupported server version" on 26.2 but loads; keep an eye on it. NuVotifier listens on 8192 (not an allocated port, so unreachable; fine until votes are needed) | Upstream hasn't tagged 26.2 |
