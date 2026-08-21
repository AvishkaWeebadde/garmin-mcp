# fitmcp — project context

## What this is

A personal MCP server exposing fitness/activity data to MCP clients. Built twice, deliberately: once in Java (Spring AI, stdio transport), then again in Go (official Go SDK, streamable HTTP with stateless mode).

The point is not the server. The point is learning MCP well enough that the protocol is separable from any one framework's ergonomics, and learning Go properly by solving a problem I've already solved in a language I know.

## Working agreement — read this before responding to anything

I am writing this code. You are not.

- Do not write implementation code unless I explicitly ask. If I describe a problem, respond with the diagnosis and the concept, not a patch. "Your tool handler isn't registering because X" — not thirty lines of corrected Java.
- Do not scaffold ahead of me. If I'm on milestone 2, don't create milestone 4's files because they're obviously needed next.
- Reviewing code I've written is in scope and welcome. Be blunt about design problems. I would rather hear that an abstraction is wrong than be congratulated on it.
- When I'm stuck, prefer a question that unblocks me over an answer that replaces me.
- If you think I'm wrong about something, say so once, with reasoning. Then drop it if I've decided.

## Your other job: keep the tracker current

The Progress section below is yours to maintain. When I finish a milestone, or we make a decision that changes the plan, update it. Don't ask permission to do that — just do it and mention it briefly. Keep it terse; this file is read every session and bloat costs me context.

Log decisions with a one-line reason. Six weeks from now the reason is the part I'll have forgotten.

## Pinned facts — your training data is probably older than these

Verify anything version-specific before advising; do not answer from memory on these points.

- MCP spec 2026-07-28 is final. Largest revision since launch: stateless protocol core, no `initialize` handshake, no protocol-level session, no `Mcp-Session-Id`. Extensions framework (MCP Apps, Tasks). Tool schemas defined against full JSON Schema 2020-12.
- Roots, Sampling, Logging are deprecated (~12-month runway). Legacy HTTP+SSE transport is deprecated. Don't suggest any of them for new code.
- The stateless changes affect the HTTP transport only. stdio is unaffected.
- Spring AI 2.0.0 GA (June 2026). Requires Spring Boot **4.1.0**, not 4.0.x — `spring-ai-starter-mcp-server:2.0.0` depends directly on `spring-boot-starter:4.1.0`. Framework 7. Java 17 baseline, 21 recommended.
- Jackson 3: **databind** moved (`com.fasterxml.jackson` → `tools.jackson`), **annotations did not**. `tools.jackson.core:jackson-databind:3.0.3` still depends on `com.fasterxml.jackson.core:jackson-annotations`, so `@JsonValue` / `@JsonProperty` stay at `com.fasterxml.jackson.annotation`.
- The 2026-07-28 beta SDKs shipped for Python, TypeScript, Go, C#. Java was not among them. Assume the Java side speaks 2025-11-25 and stop suggesting otherwise.
- Go SDK: module `github.com/modelcontextprotocol/go-sdk`, primary package `/mcp`. **2026-07-28 support is GA in `v1.7.0`** (released 2026-07-27), same module path. Stateless via `StreamableHTTPOptions.Stateless = true`.
- **go-sdk v1.7.0 requires Go >= 1.25.0** (its `go.mod` declares `go 1.25.0`). Anything older fails to build. This is the constraint to check first on any new machine.
- Stateless mode is not fully specified yet and is still being refined upstream. Flag this if I hit something that looks like an SDK bug — it may be genuine.

## The tool contract

`contract/tools.md` is the shared spec both implementations satisfy. Tool names, descriptions, input schemas, output shapes. It is written once, before either implementation, and both must conform.

If I'm about to change a tool signature in one language, remind me the contract and the other implementation exist. Divergence between the two is a finding, not a bug to paper over — when the Java and Go servers produce different JSON for the same call, that discrepancy is the most valuable output this project has.

Beyond names and schemas, the contract must pin:

- Units and formats, explicitly (distance, duration, timestamp/timezone). Unstated units are the cheapest divergence and the least interesting one.
- Absent vs `null` vs zero-value, **per field**. Jackson's `@JsonInclude` defaults and Go's `omitempty` disagree by construction. This is the divergence that will actually show up.
- `structuredContent` shape *and* the `content[]` text fallback. Both SDKs will let you emit one and derive the other differently.
- Error semantics: which failures are `isError: true` on a successful result vs a JSON-RPC protocol error. Name each case (unknown id, malformed range, provider unreachable).
- Spec-version-dependent fields, marked as such — Java speaks 2025-11-25, Go speaks 2026-07-28, so some divergence is spec drift, not framework drift. Labelling these up front is what keeps Phase 3 worth writing.
- A version stamp (`contract: v1`) both implementations cite, so a mid-project contract change is visible in the diff rather than silently absorbed.

## Architecture (both languages)

One seam that matters: an `ActivityProvider` abstraction. The tool layer never knows whether data came from a stub, Strava's API, or a parsed `.FIT` file.

```
tool layer  →  ActivityProvider (interface)  →  stub | strava | fit-file
```

Data source reality: Garmin has no usable personal API. The `garth` library that handled Garmin SSO was deprecated in March 2026 after an auth change; new logins don't work. Strava OAuth 2 works today and covers most fields. Garmin stays a swappable adapter behind the interface, not a blocker.

Provider auth lives **outside** the MCP process. The Java server is stdio and has no HTTP listener to receive an OAuth callback, so a one-time out-of-band flow writes a token file that both servers read and refresh. Keeps the OAuth dance out of the tool layer in both languages, and keeps the two implementations comparable.

## Known traps — check these before debugging anything else

**Java / stdio**: stdout is the JSON-RPC wire. Spring's banner and every log line will corrupt it, and the client error will be an unhelpful parse failure. Required:

```properties
spring.main.web-application-type=none
spring.main.banner-mode=off
logging.pattern.console=
logging.file.name=./fitmcp.log
```

Also: no `spring-boot-starter-web`, no `-webmvc` MCP starter. Both drag in a servlet container and push onto HTTP.

**WSL → Claude Desktop**: client runs on Windows, jar runs in WSL. `command` is `wsl.exe`; paths inside `args` are WSL paths, not `/mnt/c/`. Silent failures are usually `JAVA_HOME` — WSL non-login shells don't source `.bashrc`.

**WSL / envman**: `~/.config/envman/load.sh` guards on `ENVMAN_LOAD` and exports it. A login shell spawned from an already-loaded one **silently skips re-sourcing** `PATH.env` and `ENV.env` — you get `java: command not found` with a perfectly good install on disk. Sharper version of the trap above: it isn't only non-login shells. For the Claude Desktop wiring, use the absolute path `~/.local/opt/jdk/bin/java` rather than trusting inherited env.

**WSL `/tmp` is wiped on distro restart.** WSL auto-shuts-down when idle and systemd clears `/tmp` on boot. Don't stage anything there across a gap.

**Both**: debug with `npx @modelcontextprotocol/inspector` before wiring to any real client. Guessing from client-side silence wastes hours.

## Toolchain (verified 2026-08-03)

| | Version | Location |
|---|---|---|
| Go (Windows) | 1.26.5 | `C:\Program Files\Go` (winget `GoLang.Go`) |
| Go (WSL) | 1.26.5 | `~/.local/opt/go` → `go-v1.26.5` |
| JDK (WSL) | Temurin 21.0.12+8 | `~/.local/opt/jdk` → `jdk-v21.0.12`, `JAVA_HOME` set |
| Node (WSL) | v24.18.1 LTS, npm 11.16.0 | `~/.local/opt/node` → `node-v24.18.1` |
| JDK (Windows) | 17.0.10 | Oracle, on PATH |
| Maven (Windows) | 3.8.8 | `C:\Program Files\apache-maven-3.8.8` |

WSL convention is `webi`-style: versioned dir in `~/.local/opt`, symlink without version, PATH via `~/.config/envman`. No sudo (it wants a password). `go-v1.24.1` kept for rollback.

**Open**: no Maven in WSL. Neither `spring-boot-starter-parent` nor `spring-boot-maven-plugin` declares a minimum Maven version in its published pom, so 3.8.8 is unverified rather than known-good. Resolve alongside the build-on-Windows / run-in-WSL question.

## Milestones

### Phase 0 — contract
- [x] `contract/tools.md` written and frozen (v1, 2026-08-05)
- [x] `contract/stub-dataset.json` — normative fixture data both implementations must return

### Phase 1 — Java (Spring AI, stdio)
- [x] Server starts clean, stdout uncorrupted across a full session
- [x] `tools/call` working end to end (`scripts/mcp_probe.py`, raw JSON-RPC)
- [x] Full tool surface per contract — 3 tools, 7/7 happy paths conformant
- [x] Wired into Claude Desktop through WSL — connected, protocol 2025-11-25, 3 tools announced in-app (2026-08-21)
- [ ] Real provider — **`.FIT` file** chosen (Strava API now paid); source-agnostic, filename-id primary + `file_id` fallback. Use the official Garmin FIT SDK for Java.

### Phase 2 — Go (go-sdk, streamable HTTP + stateless)
- [ ] Same contract, stub provider
- [ ] Stateless mode enabled and verified **adversarially** — two independent `tools/call` requests, no shared connection, either order, identical results. The flag compiling is not evidence.
- [ ] Same provider as Java

### Phase 3 — the actual deliverable
- [ ] Identical Inspector calls against both; diff the wire JSON
- [ ] Classify each divergence: spec-revision / SDK-serialization / my own inconsistency
- [ ] Write up where they diverged and why → `notes/divergence.md`

Phase 2 starts within ~3 weeks of Phase 1 finishing. Longer than that and the comparison is worthless — hold me to this.

## Progress

*(maintained by Claude Code)*

- **Current phase**: Phase 1 — Java server working over stdio, conformant on all happy paths; Claude Desktop wiring in progress
- **Last session**: 2026-08-21 — pushed to `github.com/AvishkaWeebadde/garmin-mcp` (source/contract/ADRs/notes only; jar, logs, tokens gitignored). Wiring launch-path decided (WSL). `claude_desktop_config.json` written to `%APPDATA%\Claude\`; the exact `wsl.exe -e <jdk21>/java -jar <jar>` form verified to handshake via `mcp_probe.py` (must run from PowerShell, not Git Bash — MSYS mangles the `/home/...` arg).
- **Prev session**: 2026-08-05 — contract v1 frozen; Java built/verified on JDK 21 via `./mvnw`. 7/7 happy paths conform; 9 findings in `notes/divergence.md`. ADRs 0001–0011.
- **Next**: a real provider (Strava or `.FIT`); then Phase 2 (Go).
- **Wiring notes (packaged Claude Desktop, MSIX)**: this build is the Store/MSIX package. It ignores plain `%APPDATA%\Roaming\Claude`; the real config is sandboxed at `%LOCALAPPDATA%\Packages\Claude_pzs8sxrjxfjjc\LocalCache\Roaming\Claude\claude_desktop_config.json`, and that file **also holds cowork/preferences** — `mcpServers` must be *merged in*, not overwritten. There is no Developer-mode toggle. Live logs are at `%LOCALAPPDATA%\Claude\logs\` (`mcp-server-fitmcp.log`, `main.log`), NOT the sandbox `logs\`.
- **Cold-start trap**: cold WSL launch took ~13s (JVM+WSL spin-up) vs Claude Desktop's 10s `ensureAllConfiguredConnected` budget → tools briefly announce empty, then re-announce on connect. Recovers on its own; a native-Windows JVM would start inside the window. Data point for the WSL-vs-native call, not a blocker.
- **Open**:
  - F-008 — `limit`'s contractual max of 100 is enforced by nobody. Fixing it picks a side on error channel; decide deliberately.
  - F-001 — Spring AI prefixes error text, so contract §1.7 wording is unreachable via annotations. Left unresolved on purpose; do not weaken the contract to hide it.
  - Java-on-WSL vs native Windows is now moot for the build: `./mvnw` on WSL JDK 21 works. Still open for the Claude Desktop wiring.

## Decision log

- Build twice rather than once, to separate protocol knowledge from framework knowledge.
- Java uses stdio: sidesteps the 2026-07-28 transport churn entirely, and the Java SDK doesn't support the new spec yet anyway.
- Go uses streamable HTTP + stateless: covers the other protocol generation, so the two implementations aren't redundant.
- 2026-08-03 — One git repo, both languages inside it. Separate repos make the Phase 3 side-by-side diff ceremony.
- 2026-08-03 — WSL toolchains installed user-local under `~/.local/opt` rather than via apt. `sudo` needs a password, and it matches the `webi` layout already on the machine.
- 2026-08-03 — Provider OAuth kept outside the MCP process (shared token file). The stdio server can't host a callback, so anything else would make the two implementations structurally different for a reason unrelated to MCP.
- 2026-08-21 — **Resolved**: Claude Desktop launches the jar via WSL, not native Windows JDK 21. WSL already has JDK 21 (Windows has only 17), so this needs no new install; accepted the `wsl.exe`/path-translation trap surface in exchange. Config: `command: wsl.exe`, `args: -e /home/avishka/.local/opt/jdk/bin/java -jar /mnt/f/...jar`.
- 2026-08-21 — First real provider is **`.FIT` file, not Strava OAuth**. Strava's API stopped being free on 2026-06-01 ($11.99/mo subscription, per-developer, no free tier); the free bulk export ships `.FIT` files, which feed the `fit-file` seam already in the architecture. Strava-OAuth adapter deferred, not dropped. **Pinned-fact update**: the CLAUDE.md line "Strava OAuth 2 works today" is now cost-gated.
- 2026-08-21 — FIT activity granularity: **one activity per FIT `session`, not per file.** Uniform rule "iterate sessions" — normal files have 1 session (→ bare Strava id, primary scheme untouched); multisport files emit N single-sport activities. `transition` sessions are **dropped** (not activities). Id addendum: multisport legs can't share one filename id → composite `<fileId>-<sessionOrdinal>` (chronological, stable), firing *only* for genuine multisport. Primary source (Strava export) may already split multisport into separate files → check an actual export empirically. **Expected Phase 3 divergence, by design**: a future Strava adapter may represent the same multisport event as 1 activity or as N with Strava's own per-leg ids → structural diff (activity count + ids). Logged as intended, not a bug.
- 2026-08-21 — FIT `sport` → contract `Sport` mapping (shared with the future Strava adapter — both must collapse identically or a Phase 3 diff is self-inflicted). Key on FIT `sport`, not `sub_sport`. `running→run`, `cycling`/`e_biking→ride`, `swimming→swim`, `walking→walk`, **`hiking→walk`** (foot locomotion; deliberate — Strava `Hike` must match). `other` for everything else incl. rowing/paddling/kayaking/SUP, snowshoeing, mountaineering/climbing, `transition`/`multisport`, `fitness_equipment`/`training`, `generic`, and unknown/invalid. **Trap**: FIT enum names (`running`) ≠ wire values (`run`), so an explicit FIT→Sport table is required — routing FIT names through `Sport.fromWire` silently maps everything to `other`. Multisport files map per-`session`, not per-file (activity-granularity TBD separately).
- 2026-08-21 — FIT provider `activityId` scheme: **primary = the Strava export filename's bare activity id** (immutable, edit-proof, unique, traceable to `strava.com/activities/<id>`, and coincides with a future Strava-API provider's ids → clean Phase 3 diff). **Fallback for non-Strava FIT = `file_id.time_created` + `serial_number` composite**; never `session.start_time` (users edit start times → not stable). Bare, no `fit-` prefix, to preserve id-equality with the Strava adapter. The Strava coincidence is a deliberate convenience, **not** a contract guarantee (§1.5 keeps ids opaque/per-provider). Candidate ADR-0012 when the provider is built.
- 2026-08-05 — Claude wrote the Java implementation at explicit request. ADR-0001 records the exception and its cost.
- 2026-08-05 — Contract written and frozen *before* any implementation, including a normative stub dataset. Without shared fixtures every Phase 3 diff would be noise.
- 2026-08-05 — camelCase wire names (ADR-0003); nulls always present (ADR-0004); `bySport` an ordered array not a map (ADR-0005); error channel split by who-got-it-wrong (ADR-0006).
- 2026-08-05 — `Sport` enum constants are lowercase, against Java convention (ADR-0010). Spring AI's schema generator ignores `@JsonValue` while the SDK's mapper honours it; conventional constants made the server contradict itself and fail every call.
- 2026-08-05 — `@Schema(nullable = true)` on every nullable field (ADR-0011). Load-bearing: without it the SDK rejects its own output for any unmeasured value.
