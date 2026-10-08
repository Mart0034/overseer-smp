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
| 2026-10-08 | Overseer v0.1 shipped as release `v0.1.1` (CI: test -> build -> GitHub release, only for pushes touching `plugin/**`; version = `0.1.<run number>`). Deployed with `ptero deploy` (backup -> keep old jar in `/deploy-backup` -> upload as `Overseer.jar` -> restart -> log health check -> rollback) | Spec section 6; path filter avoids a release for every docs commit |
| 2026-10-08 | Model call: `claude-haiku-5-5`, `thinking: disabled`, `effort: low`, `max_tokens 150`, JSON-schema structured output, no sampling params. Plugin re-validates everything (strict JSON parse, whitelist, clamps) | Haiku 5.5 thinks by default and would eat the 150-token cap; the schema is a helper, not the trust boundary |
| 2026-10-08 | Cost: $0.10 / $0.50 per Mtok in config; local monthly cap $8 (console limit $10 stays the hard cap). Counted from `api_usage` and restored at startup | Spec rule 5 |
| 2026-10-08 | Per-prayer log in `plugins/Overseer/overseer.db` (`prayers`: filtered/limited/error/ok with `counted` flag). Read via `python -m ptero prayers [n]` | User asked to inspect the SQLite log after each test prayer |
| 2026-10-08 | Local Gradle builds need ASCII temp/home dirs (`GRADLE_USER_HOME=C:/gradle-ovr/home`, `-Djava.io.tmpdir=C:/gradle-ovr/tmp`) because the Windows user name contains a non-ASCII character | Java's Windows pipe implementation fails ("Unable to establish loopback connection") otherwise |
| 2026-10-08 | Secret scan stays strict; test fixtures build fake key strings by concatenation so they don't match key patterns | Don't weaken the scanner |
| 2026-10-08 | Persona tuned after Mart's review: stay inside the fiction (no "menu/list" talk); blessings are earned (plain greetings -> none; target mix none 50 / bless 30 / curse 15 / smite 5). Length limits unchanged (prayer 200, reply 220). Dry run of 20 prayers: 8 none / 8 bless / 4 curse, avg reply 111 chars | Mart: punchier, less generous. Pending: vary curses, avoid openers that name the prayer |
| 2026-10-08 | Chunky pregeneration was interrupted by the deploy restart (does not auto-resume); resumed with `chunky continue` and set `continue-on-restart: true`, `update-interval: 120` | The 05:00 nightly restart would otherwise interrupt it again |
| 2026-10-08 | Daily prayer cap was temporarily raised to 6 for testing and restored to 3 | My miscount: the first test prayer also consumed a slot |
| 2026-10-08 | Handoff written to `reports/2026-10-08.md` (full state, gotchas, TODOs incl. DST schedule shifts on 2026-10-25 and 2026-11-01) | Mart may not return to this chat; the repo is the source of truth |
