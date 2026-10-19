# Artus — Multi-Session Manager for Claude Code and Codex

Spring Boot backend + React dashboard for running parallel coding-agent sessions
(Claude Code via the Claude Agent SDK, or Codex via `codex app-server`), each in its
own git worktree, driven through per-session Node sidecar processes. Single user, LAN
deployment, PostgreSQL persistence. Renamed from `claude-ui` in 2026-09 — the
`ARTUS_*` env vars still work as fallbacks (see `application.yaml`), everything
else (config prefix, package, DB name, file names) uses the new name only.

## Repository layout

```
├─ src/                    # Spring Boot backend (Java 25, Boot 4.x, package de.pamir.agentic.ui)
│  └─ main/resources/db/migration/   # Flyway migrations — the ONLY way schema changes
├─ sidecar/                # Node + TypeScript session engine (Claude Agent SDK) — Phase 1
├─ sidecar-codex/          # Codex CLI provider adapter (codex app-server JSON-RPC) — Phase 5.13
├─ frontend/               # Vite + React dashboard — Phase 3
├─ docker-compose.yaml     # PostgreSQL 17 (pgvector image)
├─ docs/
│  ├─ plan/                # Phase plans; README.md holds the AUTHORITATIVE decision log
│  ├─ PROTOCOL.md          # Sidecar/WS message contracts
│  ├─ ARCHITECTURE.md      # As-built architecture + backlog implementation sketches
│  ├─ DEPLOY.md            # Deploying on macOS — prereqs, run, update, optional integrations
│  ├─ SLACK.md             # Slack app setup, tokens, env vars, troubleshooting (phase 18)
│  └─ DEMO-MEDIA.md        # Re-shooting the demo screenshots + screencast (phase 24)
└─ CLAUDE.md               # this file
```

Work proceeds phase by phase (`docs/plan/phase-*.md`). Each phase has a Definition of
Done checklist and a manual test script; a phase starts only after the previous one's
DoD fully passes. Check `docs/plan/README.md` (decision log) before questioning any
architectural choice — most have been explicitly decided.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 25 (Temurin) | WSL: installed at `~/.jdks/jdk-25.0.4.1+1`, exported in `~/.bashrc`. IntelliJ manages its own JDK 25 separately |
| Node | ≥ 22 LTS | via nvm (`~/.nvm`); needed by `sidecar/` and `frontend/`, not by the `claude` CLI (native binary) |
| Docker | any recent | Postgres runs in Docker Desktop (Windows). WSL integration may be OFF — see DB section |
| claude CLI | logged in | sidecars use the invoking user's `~/.claude` credentials |
| codex CLI | logged in (optional) | only needed for `provider: codex` sessions; sidecar-codex uses the invoking user's `~/.codex` credentials (`codex login` once, interactively) — same posture as the `claude` CLI row above |

## Build & test (CLI)

**WSL/Windows:**

```bash
# make sure JDK 25 is active (a login shell picks this up from ~/.bashrc)
export JAVA_HOME="$HOME/.jdks/jdk-25.0.4.1+1"; export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -v                     # must report Java 25

./mvnw clean verify           # full build + tests — REQUIRES Postgres running (see below)
./mvnw clean verify -DskipTests   # compile-only, no DB needed
```

Gotcha (bash): `export A=x PATH=$A/bin:$PATH` in ONE statement expands `$A` before the
assignment takes effect — export `JAVA_HOME` and `PATH` as two statements.

**macOS:** JDK 25 and Maven are typically already on `PATH` (e.g. via sdkman/brew), and
`mvnw` loses its executable bit across some git checkouts (`git ls-files -s mvnw` shows
`100644`) — use the system `mvn` instead of `./mvnw`:

```bash
mvn -v                        # must report Java 25

mvn clean verify              # full build + tests — REQUIRES Postgres running (see below)
mvn clean verify -DskipTests  # compile-only, no DB needed
```

**Fast unit tests, no DB needed** (either OS, swap `mvn`/`./mvnw` per above): most backend
tests are plain JUnit against pure logic (no `@SpringBootTest`); the one exception —
`ApplicationTests`, which boots the full context — is tagged `@Tag("integration")` so it
can be excluded:

```bash
mvn -Dskip.installnodenpm -Dskip.npm -DexcludedGroups=integration test
```

CI (`.github/workflows/ci.yml`) runs exactly this, plus `npm test`+`tsc` for both
sidecars (guarded by `scripts/check-protocol-sync.mjs`, which fails the build if
`sidecar-codex/src/protocol.ts`/`stdio.ts` drift from their documented `sidecar/`
originals — see that package's file-header comments) and `npm test` + a full frontend
`npm run build`. It does not run `ApplicationTests` or any other `@Tag("integration")`
test — no Postgres service is wired up in CI yet, so those (including the
`*RepositoryDbTest`/`*DbTest` classes added in phase-9's T3) only run as part of a full
local `mvn`/`mvnw test` or `verify` against the compose DB.

## Database

```bash
docker compose up -d          # if WSL integration is enabled
# Fallback when the docker CLI is unavailable in WSL (integration off):
"/mnt/c/Program Files/Docker/Docker/resources/bin/docker.exe" compose -f 'D:\projects\artus\docker-compose.yaml' up -d
```

- DB `artus`, user `artus`, password `artus` (dev default), port
  `127.0.0.1:5432`. Docker Desktop relays the published port into WSL, so
  `localhost:5432` works from both Windows and WSL.
- A `pgdata` volume initialised before the 2026-09 rename still holds a `claude_ui`
  database/role — `POSTGRES_*` only apply on first init, so the backend fails to connect
  until you `docker compose down -v && docker compose up -d` once (wipes dev data).
- Password override env var is `ARTUS_DB_PASSWORD` — deliberately namespaced:
  the user's `~/.bashrc` exports an unrelated `DB_PASSWORD`; never use that name.
- **Schema changes go through Flyway only** (`src/main/resources/db/migration/V*__*.sql`,
  applied at startup). No manual DDL, no generated schema. Boot 4 note: Flyway needs
  `spring-boot-starter-flyway`; plain `flyway-core` silently does nothing.
- **`docker compose down` keeps the `pgdata` volume** (only `down -v` removes it) — editing
  or renaming an already-applied migration file during dev iteration then hits Flyway's
  checksum-mismatch validation error on next startup, since the old content is still
  recorded in `flyway_schema_history` on that persisted volume. If a migration you're
  actively iterating on hasn't shipped/been committed yet, `docker compose down -v` before
  the next `up -d` is the easy reset; don't do this once a migration is real/committed.

## Run the project

```bash
# 1. Postgres must be up (see Database section), then build the jar.
#    IMPORTANT: stop a running backend first — a live JVM holds the jar and the
#    spring-boot repackage fails half-written.
# WSL/Windows: use ./mvnw. macOS: use mvn (see "Build & test" above for why).
mvnw="./mvnw"; command -v mvn >/dev/null && [ "$(uname)" = "Darwin" ] && mvnw="mvn"
$mvnw package -DskipTests -Dskip.installnodenpm -Dskip.npm   # fast: reuses frontend/dist
$mvnw package -DskipTests                                    # full: rebuilds frontend too
# (run `cd frontend && npm run build` first if frontend sources changed and you use the fast form)

# 2. Generate a token (or reuse ARTUS_TOKEN if already exported — see below),
#    start in background, print the token for the browser login:
TOKEN="${ARTUS_TOKEN:-$(head -c 24 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 20)}"
echo "$TOKEN" > /tmp/artus.token
JAR="$(ls -t target/artus-*.jar | head -1)"   # newest build (the version comes from pom.xml)
ARTUS_TOKEN="$TOKEN" nohup java -jar "$JAR" \
  --server.address=0.0.0.0 > /tmp/artus.log 2>&1 &
echo $! > /tmp/artus.pid
until curl -sf localhost:8080/actuator/health >/dev/null; do sleep 1; done
echo "UI: http://localhost:8080  token: $(cat /tmp/artus.token)"
```

- **WSL gotcha**: bind `0.0.0.0`, not `127.0.0.1` — the Windows→WSL localhost relay
  only forwards wildcard binds, so a loopback-bound server is invisible to the
  Windows browser (ERR_CONNECTION_REFUSED). With the token off the backend therefore checks the
  client, not the bind: only addresses in `artus.auth.trusted-cidrs` (default loopback) with an
  allowed `Host`/`Origin` are served — on WSL add the vEthernet range there (phase 17 B).
- Logs: `tail -f /tmp/artus.log` for raw stdout; structured logs land in `logs/`
  (override dir with env `ARTUS_LOG_DIR`):
  - `logs/artus.log` — everything INFO+, rolled daily/10MB, 14 days kept
  - `logs/error.log` — ERROR only with full stack traces, 30 days kept
  - `logs/sidecar/<sessionId>.log` — each session's sidecar stderr (timestamped)
- Token again: `cat /tmp/artus.token`.
- **Stable token across restarts**: export `ARTUS_TOKEN` (e.g. in `~/.bashrc`)
  before running `start.sh` — it reuses it instead of generating a new
  random token each time, so the browser doesn't need re-pasting it after every
  restart. Leave it unset to keep the old rotate-every-start behavior.

### Stop / kill

```bash
kill "$(cat /tmp/artus.pid)"          # graceful: @PreDestroy shuts sidecars down
# fallback when the pid file is stale — kill whatever listens on 8080:
kill "$(ss -tlnp | grep 8080 | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2)"
# orphaned sidecars, if any survive:
pkill -f "dist/index[.]js --cwd"
```

Note the `[.]` in the pkill pattern: `pkill -f` matches its own shell's command line,
so an unescaped pattern kills the shell that runs it (learned the hard way).

Config lives in `application.yaml` under `artus.*` (default repo path, worktree
root, skills root, max sessions, auth token, provider launch commands), bound by
`de.pamir.agentic.ui.config.AppProperties` and logged at startup (token masked). Service
repo and ecosystem path are selectable **per session** in the create dialog; the config
values are only defaults (the ecosystem root default itself is a persisted setting, not
env-based — see below). Local secrets belong in gitignored `application-local.yaml` or
env vars — never commit them. Provider launch commands (`artus.providers.<id>.command`)
ship two entries out of the box:
```yaml
artus:
  providers:
    claude:
      command: ["node", "sidecar/dist/index.js"]
    codex:
      command: ["node", "sidecar-codex/dist/index.js"]
```
Session/template `provider` (default from the persisted `session.default-provider`
setting, Settings dialog → "Sessions") selects which entry `SidecarManager` spawns. The
system sessions (ticket import, library AI-fill, reflection, service
discovery, commit/PR drafting, handoff briefs) — phase 19 (+22): **three stateless sessions, one per
`SystemTurnLane`** (`system-interactive`, `system-background`, `system-visualize`, all `kind='system'`, discriminated
by `name`; the visualize lane exists so a long diagram turn never makes the quick dialogs report "busy"), each recycled after every turn (terminate sidecar → `provider_session_id = NULL` →
PARKED; the next wake starts a fresh conversation) so a long reflection can never block the quick
dialog and context never accumulates — spawn as `session.system-provider`
(same dialog; empty = follow `session.default-provider`) at a model from
`ModelCatalog`'s "cheap" tier for that provider — a Codex system session gets no MCP
tool pre-approval (Codex rejects `allowedTools` outright), so a backend-initiated turn
needing Linear/memory tools has nobody to answer the resulting approval prompt and
simply times out.

## Sidecar (Phase 1+)

```bash
cd sidecar && npm install && npm run build    # tsc build to dist/
npm test                                      # vitest — permissions.ts, session.ts translation
npm run drive -- --cwd /path/to/dir           # manual REPL driver for the NDJSON protocol
```

One sidecar process per session; stdout is protocol NDJSON only, logs go to stderr.
The NDJSON contract is the provider adapter interface — keep it provider-neutral
(`providerSessionId`, capabilities handshake), Claude specifics stay inside the sidecar.
Once the backend has asked a sidecar to stop (`shutdown` or stdin closed), an error from the agent stream is
only logged to stderr and the sidecar exits 0 (`classifyStreamFailure`): the Claude Agent SDK otherwise throws the
text of the last turn that ended in an error result — an interrupt, max turns — when the process exits.

## Codex provider adapter (Phase 5.13)

```bash
cd sidecar-codex && npm install && npm run build   # tsc build to dist/
npm test                                            # vitest — rpc.ts, mcp.ts, approvals.ts
```

Second adapter implementation, wrapping `codex app-server`'s JSON-RPC-over-stdio
protocol (not `codex exec`, which is non-interactive and can't do the tool-approval
round trip) — translated to the same NDJSON adapter protocol v1 the Claude sidecar
speaks. `sidecar-codex/src/protocol.ts`/`stdio.ts` are synced copies of `sidecar/`'s
shared, provider-neutral types (a plain copy, not a cross-package import, to avoid
coupling the two packages' build order — keep both in sync if protocol v1 changes).
Narrower capability set than Claude's: no plan mode, no `acceptEdits`, no custom
agents (`agentSources`) — Codex has no equivalent to Claude's static subagent files at
all, confirmed permanently infeasible, not just deferred. An explicit `plan`/
`acceptEdits` permission mode or a non-empty `agentSources` on a `codex` session is
rejected at creation time, not silently downgraded. Skills and MCP **are** supported
(as of the 2026-08-30 follow-up): skills via `skills/extraRoots/set` pointing at the
worktree's already-materialized `.claude/skills/` (Codex reads the same `SKILL.md`
format), MCP via `thread/start`'s `config.mcp_servers` (thread-scoped, not the global
`codex mcp add` registration) — `sidecar-codex/src/mcp.ts` translates the same
Claude-shaped `mcpConfig` file the backend already writes, passing any bearer token as
a named env var on the spawned child (Codex's own auth mechanism) rather than an
inline header. Codex reports token counts but no per-turn USD, so `costUsd` is
estimated backend-side (`SessionService.applyCodexCostEstimate`) from a
Settings-editable price table (Settings dialog → "Codex" → Pricing). Full design
rationale, the capability/permission-mode mapping, and
live-confirmed protocol quirks: `docs/plan/phase-5.13-codex-provider.md`.

## Frontend (Phase 3+)

```bash
cd frontend && npm install && npm run dev     # Vite on :5173, proxies /api and /ws to :8080
npm test                                      # vitest — store/store.ts, protocol.ts helpers
npm run dev:demo | build:demo                 # phase 24: backend-less replay build (see "Demo build" below)
```

Production build is wired into `mvn package` (frontend-maven-plugin → `static/`), so
the backend jar serves everything at http://localhost:8080/.

**Build speed**: the FIRST `mvn package` downloads a Node distro into `target/` and
runs `npm install` — slow on /mnt/d (DrvFS), expect ~10+ min. Later builds are fast.
To skip the frontend rebuild entirely (reuses `frontend/dist`):
`./mvnw package -DskipTests -Dskip.installnodenpm -Dskip.npm`

**Vite dev-server watcher gotcha (WSL + DrvFS)**: `npm run dev`'s file watcher (chokidar/
inotify) does not reliably see edits to files on `/mnt/d` — Vite keeps serving its
in-memory transformed copy of a file indefinitely after the *first* request for it,
silently ignoring later on-disk changes (no error, no HMR log). Symptom: an edit that
provably typechecks and is on disk has *zero* effect in the browser, even after a hard
reload. Fix: kill and restart the `npm run dev` process after editing frontend source
while manually verifying in a browser — don't trust HMR here. (`mvn package`'s frontend
build is unaffected; it always reads fresh from disk.)

### Demo build (phase 24)

`npm run build:demo` (`vite build --mode demo`, output `frontend/dist-demo/`, relative base — the site serves it at `/demo/`) compiles the real
dashboard with `src/api/rest.ts` and `src/api/ws.ts` swapped (a `resolveId` plugin in `vite.config.ts`) for
`rest.demo.ts`/`ws.demo.ts`, which replay scrubbed recorded journals from `frontend/demo/fixtures/*.json` — no
backend, no network. The normal build never contains demo code (`main.tsx` lazy-loads the demo bar only when
`MODE === 'demo'`). Rules when touching the frontend:
- A REST method missing from `rest.demo.ts` rejects with a 501 `ApiError` ("Not available in the demo") by design;
  add it there only if the demo should show real content for it.
- A WS command or journal event type the player should honour goes in `demo/player.ts` / `ws.demo.ts`.
- Absolute URLs break the relative demo base: use `import.meta.env.BASE_URL` (`./` in the demo build).
- Fixtures are real sessions recorded on the public `eupakhomov/artus-demo-repo`, captured by `scripts/capture-demo.mjs` (records journal, changes, diagram and the Git panel's status/log/diff; scrubs
  paths/secrets/emails/the Artus token; `--check` + `frontend/demo/denylist.txt` fail on leaks, CI runs it) — workflow in
  `frontend/demo/SCENARIOS.md`. Hand-made playback (seed point, which prompts pause, Deny tails, trims) lives in
  `frontend/demo/scripts.json`, never in the fixture files, so a re-capture is one command. The demo bundle uses a relative base, so it works under any path. Embed params: `?theme=dark|light&speed=1|4&autoplay=0`.

### Visual style (read before touching UI)

IntelliJ-inspired: dense, monochrome, keyboard-first; dark palette is the default and
light is a first-class peer. The rules below are already applied across the dashboard —
follow them rather than inventing a new look for one component.

**Icons — one vocabulary, `frontend/src/icons.ts`.** Every glyph is a `lucide-react`
icon re-exported from that file under a *role* name (`Refresh`, `PullRequest`,
`MemoryDoc`), not a shape name. Components import from `../icons`, never from
`lucide-react` directly, and never add a new icon library.
- Size/stroke/colour are set once by `<LucideProvider size={16} strokeWidth={1.5}>` in
  `main.tsx` — don't pass `size`/`strokeWidth`/`color` per icon. Larger inline contexts
  may override size locally, but the 1.5px stroke stays (lucide's default 2 reads heavy
  against this UI's light type).
- **No emoji, no Unicode symbol glyphs, no CSS-dot pseudo-icons in UI text.** Emoji drag
  in their own palettes (a pink brain beside a grey glyph) and several legacy glyphs
  (`🗖 🗗 🗕 ⑂ ⎇`) are simply absent on macOS — the deployment target — where they render
  as tofu. Emoji are fine in prose/comments, never in the DOM.
- One glyph = one meaning. Before adding an icon, check `icons.ts` for a collision and
  pick a distinguishable pair (e.g. keyboard vs. Exposé, usage vs. library).

**Colour = state, never decoration.** Icons are `currentColor`, so they inherit from the
button/chip around them: `--muted` at rest → `--text` on hover → `--accent` when active.
Saturated colour is reserved for meaning — `--green` ok/success, `--amber`
attention/pending, `--red` error/danger, `--purple` plan mode / merged — and at most one
saturated element per row at rest.
- Never hardcode a hex in a component or a rule; use the tokens on `:root` in
  `styles.css` (`--bg --panel --panel2 --border --text --muted --accent --on-accent`,
  the four state colours, and the tinted backgrounds `--warn-bg --error-bg --perm-bg
  --plan-bg --ask-bg --user-bubble`).
- A new token must be added in **all three** blocks: `:root` (dark default),
  `@media (prefers-color-scheme: light) :root:not([data-theme="dark"])`, and
  `:root[data-theme="light"]`. Check both themes before calling it done.

**Controls.** Reuse the base classes in `styles.css` instead of one-off styling:
`button.icon-btn` (square, centred, icon-only), `button.with-icon` (icon + label),
`button.with-badge` (badge/state dot rides the corner so every toolbar button keeps the
same footprint — an inline badge would make its button wider than its neighbours),
plus `.primary`, `.danger`, `.active`, and `.pulse` for a disabled-while-in-flight
control. Chips are `.chip` (+ a state modifier like `.mode-plan`, `.pr-SUCCESS`).
Every icon-only control carries a `title` describing the action, with its hotkey in
parentheses when it has one: `title="Templates (t)"`.

**Type & geometry.** Font sizes are always `calc(Npx * var(--font-scale))`, never a bare
px — the Settings font-size control scales the whole UI through that variable. Scale:
14 body, 13 controls/inputs, 12.5 code (JetBrains Mono), 11–11.5 chips and footers.
Radius 6px for anything rectangular, 10px for pills/chips; 1px `--border` for every
edge; 5–6px gaps inside a control, and long-running affordances animate via the shared
`pulse`/`spin` keyframes rather than new ones.

**Feedback.** Any action that can take more than ~1s must show it: disable + `.pulse` on
the button that fired it, and journal/render the outcome so the result is visible after
the fact — a silent request that resolves in 45s reads as a broken button.

## Limits & caps

All operational limits are env-tunable (read at backend startup; the sidecar inherits
the backend's environment):

| Env var | Default | What it caps |
|---|---|---|
| `ARTUS_MAX_SESSIONS` | `4` | Concurrent live sidecar processes; create/resume beyond it → 409 (including 7.4's `spawn_child_session`, surfaced to the parent as a tool error). PARKED sessions don't count, so a parent that parks after spawning fans out wider than the raw count suggests — raise this for wide fan-outs |
| `ARTUS_IDLE_PARK_MINUTES` | `30` | Minutes a session may sit IDLE before its sidecar is shut down (PARKED); next message transparently wakes it |
| `ARTUS_TOOL_OUTPUT_LIMIT` | `16384` | Bytes of tool output kept per result (sidecar truncates, `truncated` flag set) |
| `ARTUS_JOURNAL_PAYLOAD_CAP` | `65536` | Max bytes for one journal event payload; larger payloads stored as a truncated preview |
| `ARTUS_WS_BUFFER_LIMIT` | `1048576` | Per-client WS outbound buffer; a slow consumer overflowing it is disconnected (reconnects + replays losslessly) |
| `ARTUS_LOG_DIR` | `logs` | Log directory (backend rolling logs + per-session sidecar stderr) |
| `ARTUS_TOKEN` | — | Dashboard/API auth token. Enforced when set, unless Settings → Access → "Require access token" is switched off (read at startup, applies on restart); with the token off only trusted local clients are served (`artus.auth.*`, see DEPLOY.md). Pre-export a fixed value to keep the same token across restarts — `start.sh` reuses it instead of generating a random one; leave unset to keep the old behavior (a fresh random token printed on every start) |
| `ARTUS_REPO` | `/mnt/d/projects/artus` | Default service repo (per-session selectable in the UI) |
| `ARTUS_WORKTREE_ROOT` | `~/agentic-worktrees` | Where session worktrees live |
| `ARTUS_SKILLS_ROOT` | `~/agentic-skills` | *Default* for the managed skills root, which is now a persisted setting (`library.skills-root`) — the create-dialog picker, provisioning's repo cache, and library imports all read the setting |
| `ARTUS_MEMORY_ROOT` | `~/agentic-memory` | *Default* for the managed semantic-memory root (`memory.root` persisted setting) — an Obsidian-compatible vault of Markdown files with YAML frontmatter; the DB is a rebuildable search index over it, not the source of truth |
| `ARTUS_VOYAGE_API_KEY` | — | Voyage AI API key (a secret — env var only); enables the library's "vectorize" setting + semantic search (`VoyageEmbeddingClient`, voyage-3.5-lite, pgvector) **and** memory's dense-search arm. Unset = those features fall back to sparse-only (Postgres FTS + trigram for memory), everything else works |
| `ARTUS_SLACK_ENABLED` (+ `_BOT_TOKEN`, `_APP_TOKEN`, `_CHANNEL`, `_ALLOWED_USERS`, `_VERBOSE`, `ARTUS_PUBLIC_URL`) | off | Slack mirroring (phase 18, `docs/SLACK.md`). Off = no Slack beans at all. Tokens are secrets (env only); `_CHANNEL` is a channel **ID**; `_ALLOWED_USERS` is a comma-separated allowlist of Slack member IDs, **empty = every inbound message and click is ignored**. `ARTUS_SLACK_START_ENABLED` (default off) additionally lets those users start review/development sessions by mentioning the bot (`@bot review <PR>`, `@bot dev <service> <branch> [prompt]`, and `review-with`/`dev-with <template> …` to start from a session template; the new session inherits the last session's settings incl. permission mode, exactly like the dashboard quick start). Per session it is opt-in (`slackEnabled`; create-dialog checkbox, default from Settings → Slack); a child follows its parent; the system session never posts. Tool inputs are secret-masked unless `_VERBOSE`. |
| `ARTUS_LINEAR_API_KEY` | — | Linear personal API key (a secret — env var only, never persisted); enables "Import ticket" in the create dialog (phase 19: with the key set, the ticket list comes straight from Linear's GraphQL API with no LLM turn and import inlines the issue + comments into a single no-tool Haiku turn — `LinearApiClient`, falling back to the MCP system-turn path with a WARN on any API failure; OAuth mode always uses the MCP path). When configured (or the OAuth toggle is on), the Linear MCP server is also layered by default into every regular session's `mcpConfig` (`SessionService.linearMcpServer()`/`withDefaultLinearMcp()`), restricted to the **Linear allowed tools** list (Settings dialog → "Linear integration", `linear.allowed-tools`, default `get_issue,list_issues,list_comments,get_attachment,extract_images,save_comment`; snapshotted into each session at creation, so edits apply to new sessions only) — unless the session's own `mcpConfig` already declares its own `linear` entry, which wins and is unrestricted. The list is carried as our own `enabledTools` key on the entry: the Claude sidecar strips it and enforces it with a `PreToolUse` hook (the SDK has no allow-only control for MCP tools; the hook fires in every permission mode, bypass included), the Codex sidecar maps it to Codex's per-server `enabled_tools`. The system session's Linear entry is unrestricted. Regular sessions go through the normal permission-approval flow for its tools (the system session always gets it and pre-approves them instead, since backend-initiated turns have nobody to answer a prompt). |

**Per-session limits** (create dialog / template / `PATCH /api/sessions/{id}`, not env):
`costBudgetUsd` (turns are refused once cumulative cost reaches it; in-flight turns
finish; raise via the widget's cost chip), `maxTurns` (agentic turns per prompt),
`thinking` budget and `effort` level, `reflectionEnabled` (opt-in end-of-session memory
retrospective — see "Long-term memory" below; the widget's reflect (brain) button
triggers one manually regardless of this flag).

**Session types** (create dialog/template `sessionType ∈ {development, review}`, default
`development` — see `docs/plan/phase-15-review-sessions.md`): a **review** session targets an
existing PR (picked from `GET /api/repo/prs`) or a plain branch, checked out as a **detached
worktree at the reviewed branch's tip** (`GitWorktreeService.createReviewWorktree` — `git fetch`
then `git worktree add --detach`, never the branch itself, so a live development session already
holding that branch doesn't conflict and the ref can never be moved by us). Commit/push/PR are
blocked both in the UI (Git panel hides the controls) and at the REST layer (`GitSessionController`
returns 409 for `/git/commit`, `/git/push`, `/git/pr`, and `close(dirtyMode: "commit")` is refused
— stash/discard stay available for scratch notes). The agent gets a review-role system-prompt block
(`SessionConfigFactory`) and submits findings via a human-gated `submit_pr_review` MCP tool
(`ReviewMcpTools`, same in-process server as memory/orchestration) — one `gh api …/reviews` call
posting a summary + inline file/line comments atomically, **not** pre-approved in `allowedTools`,
so every submission passes the normal permission prompt. `sessionType` is identity, not tunable
config — never copied via `configOverridesFrom`/`lastSessionConfig`, but `duplicate()` and a
template's own `sessionType` config key both carry it explicitly.

**Permission modes** (create dialog, or click the widget's mode chip to cycle at
runtime): `default` (ask for edits & commands), `acceptEdits`, `plan`, and
`bypassPermissions` — the last skips **every** approval prompt, Bash included, with
no per-session safety net of our own (the worktree-only `readOnlyDenial` check in
`sidecar/src/permissions.ts` lives inside the `canUseTool` callback, which the
underlying CLI does not invoke at all in this mode — `allowDangerouslySkipPermissions`
is its own explicit opt-in, set in `sidecar/src/session.ts` only when this mode is
selected). A Claude session **not** launched in bypass can still be cycled
into it at runtime: the SDK can't switch in, so the sidecar emulates it — SDK stays in `default`,
`canUseTool` auto-allows (readOnlyDenial still applies) and pending prompts are approved. Use only for sessions you already fully trust.

**Persisted settings** (Settings dialog; `app_setting` table, `SettingsService`/
`SettingsController` — `GET`/`PATCH /api/settings`): non-secret, UI-editable, take
effect on the next use with no backend restart.
- **Ecosystem root** (Settings dialog → "Sessions") — default read-only context folder
  + service discovery root (parent of all sibling services); empty = no default wider
  context. Overridable per session in the create dialog (`ecosystemPath`, `null` = no
  wider context for that session). Replaces the old `ARTUS_ECOSYSTEM_ROOT` env var.
  Workspace-manifest detection (one git repo whose `packages/*`/`services/*`/`apps/*`/
  `libs/*`-style folders are the real services, via each folder's own workspace
  manifest — `ecosystem.monorepo-service-globs` next to it a glob-only fallback) is
  **off by default** — `ecosystem.monorepo-detection-enabled` (Settings dialog →
  "Sessions" → "Monorepo detection", a single global toggle covering every repo under
  the root) must be turned on explicitly, so a repo with a `workspaces` manifest used
  only for publishing sub-packages (not an actual monorepo) doesn't get silently split
  into several services. When on, and a folder points *at* a monorepo, the picker
  lists those packages individually; a session on one gets a worktree of the whole
  monorepo with cwd at the package subfolder, the whole worktree writable, and the
  worktree itself (not the original checkout) as its context — see
  `docs/plan/phase-11-monorepo.md`. Polyrepo (a folder of separate repos, or detection
  left off) is unchanged.
- **Access** (Settings dialog → "Access", `auth.token-required`; phase 17 B) — whether the bearer
  token is enforced. Default: on when a token is configured, off otherwise. **Read once at backend
  startup** (`AuthTokenService`), so a change applies on the next restart; the dialog compares the
  stored choice with `GET /api/auth/status` (what the running process enforces). With the token
  off, `LocalClientFilter` (REST, the WS handshake, non-health actuator) accepts only
  `artus.auth.trusted-cidrs` clients (env/yaml, default loopback) with a `Host` in
  `allowed-hosts` and, when sent, an `Origin` on an allowed host or in `allowed-origins`;
  `Sec-Fetch-Site: cross-site` is refused. These lists are security boundaries and are
  deliberately not editable from the dashboard.
- **Changes panel** (phase 17 C; widget header button or `c`) — read-only CodeMirror 6 diff of what the
  branch would carry into a PR (working tree vs merge-base with `baseBranch`; one list with
  `committed`/`staged`/`unstaged`/`untracked` badges). No editing: select lines and "Ask about these
  lines" drops path, line range and the code into the composer. Backend `ChangesService`
  (`GET /api/sessions/{id}/changes[/file?path=]`): only paths in the current change list are
  readable (404 otherwise), never through symlinks or into `.git`, 1 MB cap, UTF-8 only.
- **MCP servers / code intelligence** (Settings dialog → "MCP servers"; feature docs:
  `docs/plan/phase-12-linear-cache-serena-context.md` Track B for Serena,
  `docs/plan/phase-13-graphify.md` for graphify, `docs/plan/phase-14-codegraph.md` for
  CodeGraph, `docs/ARCHITECTURE.md` §3g for all three) — a **Code intelligence** selector
  `mcp.code-intel ∈ {none, serena, graphify, codegraph}` (one tool per install, never more
  than one: each spawns a process per session and wants a competing "use me first" prompt;
  unset reads `serena` when a Serena root exists, else `none`), plus `mcp.serena-root` /
  `mcp.graphify-root` / `mcp.codegraph-root` (paths to local checkouts; empty = that tool
  unavailable) and `mcp.uv-path` (default `uv`, shared by Serena/graphify — CodeGraph runs
  on the backend's own `node`, no `uv` involved). Saving validates a root (directory,
  `pyproject.toml` names `serena-agent` / `graphifyy` for the uv-based tools, `package.json`
  names `@colbymchenry/codegraph` plus a built `dist/bin/codegraph.js` for CodeGraph; a
  version probe runs for each — for graphify `uv run --directory <root> --no-dev --extra mcp
  --extra sql graphify --version`, which is also the first env sync, 180 s budget; for
  CodeGraph `node <root>/dist/bin/codegraph.js version`, 30 s budget, installs nothing) and
  refuses selecting a tool without its root or blanking the selected tool's root ("select
  none first"); failures are 400s with the reason. Sessions/templates opt in with one flag,
  **`codeIntelEnabled`** (default off; phase 12's `serenaEnabled` is accepted as a legacy
  alias), resolved at creation to the selected tool and stored as `session.code_intel`
  (`'serena'`/`'graphify'`/`'codegraph'`/NULL — baked per session, so flipping the selector
  later doesn't change a live session; the widget chip reads the tool name). The create
  dialog's checkbox names the selected tool and is hidden when `none`; the flag with `none`
  selected is a 400, never a silent downgrade.
  - *Serena* (symbolic code tools): a `serena` stdio entry layered into `mcpConfig`
    (unless the session already declares one — same rule as Linear/memory) pointing at
    its own `cwdPath` with `--context` from the provider's `capabilities.json`
    (`claude-code`/`codex`); Claude sessions also get Serena's own system-prompt override
    via `extraSystemPrompt`; the sidecar env gets `MCP_TIMEOUT=300000` (cold language-server
    download).
  - *graphify* (knowledge-graph tools — `query_graph`, `get_neighbors`, `shortest_path`,
    `get_community`, `god_nodes`, `graph_stats`…): a `graphify` stdio entry (`… graphify-mcp
    <graph>`) plus our own provider-neutral prompt block (both providers). The graph is
    **per session, code-only, built in the background** right after the worktree exists
    (`GraphifyService.build`, the session is usable immediately) and **refreshed after
    every completed turn** (single-flight + coalesced — turns finishing mid-build queue
    exactly one more run; ~11 s from empty / ~7 s refresh for this repo on ext4), stored
    at `<worktree-root>/.graphify/<sessionId>/` (never inside the worktree — the Git panel
    stays clean), logged to `logs/graphify/<sessionId>.log`, removed on close and by
    `POST /api/maintenance/orphans/clean`. Status is journaled as `code_intel_status`
    (chip pulses while building, `--red` on failure; a failed build never fails the
    session, the next turn retries). Posture from the pre-phase security review: only the
    `update` CLI (AST-only — no semantic pass, no API key, nothing leaves the machine) and
    the MCP server are used, from a reviewed local checkout via `uv run … --no-dev`; never
    graphify's `/graphify` skill, `graphify install`, git hooks or `graph.html`.
  - *CodeGraph* (`codegraph_explore` — a natural-language/symbol question → verbatim
    line-numbered source, call paths including dynamic dispatch, and a blast-radius
    summary): indexed **synchronously during PROVISIONING**, right after asset provisioning
    and before the MCP config is written (`CodegraphService.index`, `codegraph init
    <cwdPath> --yes`; a few seconds for a repo this size, hard cap 5 min — the session still
    starts, unindexed, with a warning + `--red` chip on failure/timeout, never fails
    creation). A `codegraph` stdio entry (`node <root>/dist/bin/codegraph.js serve --mcp
    --path <cwdPath>`) plus our own provider-neutral prompt block. **No refresh pipeline of
    our own** — unlike graphify, codegraph runs its own file watcher and a startup catch-up
    sync, so the index stays current on its own; `resume`/wake only re-run the index
    (`ensureIndexed`) when `<cwdPath>/.codegraph/codegraph.db` is missing (a prior
    failure/timeout, or a fresh backend). `.codegraph/` lands **inside** the worktree
    (forced by the tool, unlike graphify's out-of-tree dir) so it needs the same
    `info/exclude` treatment as `.serena/` — `SessionService.excludeProvisionedAssets`
    lists it unconditionally; nothing to clean up on close, the worktree removal takes it
    with it. Status reuses the `code_intel_status` journal event, emitted once (BUILDING at
    the start of `init`, READY/FAILED at its end). Posture from the pre-phase security
    review: only `init`, `serve --mcp` and `version` are ever run, from a reviewed local
    checkout on the backend's own `node`, with telemetry/update-check/the shared daemon off
    on every process (`CODEGRAPH_TELEMETRY=0`, `DO_NOT_TRACK=1`,
    `CODEGRAPH_NO_UPDATE_CHECK=1`, `CODEGRAPH_NO_DAEMON=1`); never codegraph's own
    installer, upgrade, prompt hook, git hooks or `codegraph ui`. Checkout build:
    `npm ci --ignore-scripts && npx tsc && npm run copy-assets` (no lifecycle scripts, no
    kernel/UI workspace build); re-review before moving to a newer commit.
- **Context warning threshold** (Settings dialog → "Sessions", `session.context-warn-
  percent`, default 70, floor 30, ceiling 95) — one number, same meaning for every
  session. Every session carries `contextTokens`/`contextWindow` (latest known,
  refreshed after each completed turn and after a compaction — see `context_usage`/
  `context_compacted` in docs/PROTOCOL.md), shown on the widget as a `ctx N%` chip
  beside the cost chip (`--amber` at/above the threshold, `--red` at ≥90%). Crossing
  the threshold once per session (re-armed by a compaction) raises a dismissable
  suggestion card with a **Compact** action (`POST /api/sessions/{id}/compact`,
  refused with 409 unless the session is IDLE or PARKED — a PARKED session is woken
  first and compacted once it reports ready); the button is hidden, chip-only, for a
  provider whose capabilities report `compact: false` (both shipped providers report
  `true`). See docs/plan/phase-12-linear-cache-serena-context.md track C.
- **Visualize** (Settings dialog → "Visualize", `visualize.model` ∈ `cheap|standard|premium`, default `standard`; phase 22,
  `docs/plan/phase-22-visualize.md`) — the widget's Visualize button runs one system turn on its own lane over the branch
  diff (`ChangesService.unifiedDiff`) plus a digest tail and gets a Mermaid diagram (model picks one of flowchart/sequence/
  class/er/state; the dialog can override), rendered in the dashboard by `beautiful-mermaid` (lazy chunk, pinned exact
  version; the renderer is the validator → one repair turn, then raw source). Stored as a `diagram_generated` journal
  event (latest wins); an "outdated" chip shows when the diff's SHA-256 fingerprint differs. Pop-out: `/?diagram=<sessionId>`.
- **Slack** (Settings dialog -> "Slack", `slack.default-enabled`, default off; phase 18, setup and
  operations in `docs/SLACK.md`, as-built design in `docs/ARCHITECTURE.md` section 3i) — mirrors opted-in
  sessions into one private channel and lets allowlisted Slack users Allow/Deny, approve a plan, answer a
  question, reply to prompt the session, or Stop/Status/Close it (Close only when the worktree is clean, no turn is running and no child session is live). The default only seeds the create dialog's
  **Slack** checkbox (shown when `GET /api/slack/status` reports the server has Slack on); the per-session choice is
  the `slackEnabled` override. Server switch, tokens, channel ID, `ALLOWED_USERS` (empty = nobody can act) and
  masking are env-only (see the env table). A child session follows its parent's choice; the system session
  never posts. Decisions are first-wins between the dashboard and Slack (`PendingPermissions`).
- **OAuth toggle** (Settings dialog → "Linear integration") — alternative to
  `ARTUS_LINEAR_API_KEY` for SSO-gated Linear accounts (e.g. Google identity): omits
  the Authorization header, relying on the ambient `claude` CLI's own cached OAuth
  credential for `mcp.linear.app` — run
  `claude mcp add --transport http linear https://mcp.linear.app/mcp` once,
  interactively, on the backend host first. Ignored if `ARTUS_LINEAR_API_KEY` is set.
- **Branch-naming guidance** (Settings dialog → "Linear integration") — free text
  appended to the Haiku prompt that generates a ticket import's `branchName`/`prompt`,
  e.g. "keep the ticket number uppercase" or "format as feat(TICKET)-description /
  fix(TICKET)-description".
- **PR checks** (Settings dialog → "PR checks") — `enabled` (default on) and
  `poll-interval-seconds` (default 180, floor 30) for the background GitHub PR CI
  poller (`PrCheckPollingService`, ticks every 30s and re-reads both settings each
  time, so changes take effect on the next tick). Uses the ambient `gh` CLI auth
  already used for PR creation — no separate token. One PR tracked per session
  (`session.pr_url`/`pr_check_status`/`pr_head_sha`/`pr_checked_at`); polling stops
  once a terminal result (`SUCCESS`/`FAILURE`/`MERGED`/`CLOSED`) is seen and re-arms
  on the next push to that branch. Status changes journal a `pr_status_changed` event
  (see docs/PROTOCOL.md) that drives the Git panel's status pill and a desktop
  notification via the same unfocused-tab `notify()` helper used for turn completion.
- **Skill library** (Settings dialog → "Skill library"; feature docs:
  `docs/plan/phase-6-skill-library.md`, `docs/ARCHITECTURE.md` §3a) —
  `library.skills-root` / `library.agents-root` (managed import destinations;
  skills root defaults to `ARTUS_SKILLS_ROOT`), `library.vectorize` (default
  off; needs the Voyage key), `library.sync-enabled` (default on) and
  `library.sync-interval-minutes` (default 60, floor 5) for the background source
  sync (`LibrarySyncService`, ticks every 60s, interval as cutoff — PR-checks
  pattern). The topbar library dialog scans a local folder or GitHub repo (via `gh`,
  GitHub-only for now), imports skills/agents with metadata + tags (AI-fill via the
  Haiku system session), and synced sources auto-update/archive assets and surface
  new upstream files as badge + desktop notification.
- **Long-term memory** (Settings dialog → "Memory"; feature docs:
  `docs/plan/phase-5.3-memory-reflection.md`, `docs/ARCHITECTURE.md` §3b) —
  `memory.root` (managed vault; defaults to `ARTUS_MEMORY_ROOT`), `memory.enabled`
  (default on — injects the memory MCP tools + episodic window into every session),
  `memory.reflection-default` (default off — per-session `reflectionEnabled` always
  overrides), `memory.reflection-model` (a tier — `cheap`/`standard`/`premium`, default
  `cheap` — resolved to a concrete model via `ModelCatalog` for whatever
  `session.system-provider` is set to; a legacy raw Claude alias from before this was a
  tier is normalized on read), `memory.sync-interval-minutes` (default 5, floor 1) for
  picking up hand-edited vault files
  (`MemorySyncService`), and `memory.retention-days` (default 0 = never prune) for
  pruning a CLOSED-and-reflected session's raw journal (`MemoryRetentionService`,
  hourly tick — the episode/semantic memory a reflection wrote is the durable record
  from that point on), and `memory.reflection-approval-required` (**default on**) —
  a reflection is held as a pending proposal for explicit approve/discard (editable
  first, like a permission prompt's "edit before allow") rather than written
  immediately; turn off to restore straight auto-apply. The topbar memory dialog
  searches (hybrid dense+sparse) and browses/edits/archives memory across services,
  plus a "Pending" tab (topbar badge count) for approving/discarding proposals; the
  widget's reflect button triggers an immediate reflection on that session. **Curation**
  (phase 21, `docs/plan/phase-21-memory-curation.md`; marks and remember-this only while `memory.enabled`, extract-skill always): hover a
  user/assistant block to mark it *important* (pin — starred and kept verbatim in the
  digest even past its 100k cap) or *excluded* (eye-off — its text never reaches the
  reflection model); marks are `block_marked` journal events (`POST /api/sessions/{id}/marks`),
  so they replay and sync across tabs. Marking after a reflection re-arms the reflect button
  (a refresh knows the earlier episodes and supersedes a pending proposal); the widget's
  extract-skill button distils the session's procedure into a pending **skill** proposal
  (always approval-gated, editable; approving writes `SKILL.md` into the managed skills root
  and registers it in the library); an important block's "remember this" action turns just
  that block into a small memory proposal. Proposals show the transcript blocks they cite
  ("based on N blocks").

**Live turn visibility, background work, hiding** (phase 25, `docs/plan/phase-25-feedback-visibility-cost.md`):
`turn_complete.costUsd` is the turn's own cost — the Claude sidecar turns the SDK's per-process running total into a
delta (never sum `total_cost_usd`). While a turn runs the widget shows a sticky activity line (current tool, elapsed,
tool count, `~$` estimate from `turn_usage` priced by Settings → Claude → "Live cost pricing", `claude.pricing`).
The Claude CLI backgrounds Explore subagents by default and opens a turn by itself when a background task settles, so
a `system_init` arriving while IDLE moves the session to RUNNING; live background tasks show as a strip and block idle
parking. Sessions can be hidden from the dashboard (`session.hidden`, widget archive button, sidebar "Hidden"; back by
itself on an approval prompt or crash; Settings → Sessions "Hide when parked"). Auto-title is switchable (Settings →
Sessions) and journals a note when it renames. The dashboard re-lists sessions every 10 s, so backend-created
sessions (spawned children) appear on their own.

**MCP tool search**: every sidecar is spawned with `ENABLE_TOOL_SEARCH=true`
(`SidecarManager.applySidecarEnvironment`) so MCP tool schemas (Linear alone is ~80 tools) are
deferred and loaded on demand instead of filling the first request. A session overrides it via its
`envVars` (`ENABLE_TOOL_SEARCH=auto|false`). Sidecars also **drop an inherited
`CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS`** — a shell that exports it (typical for LiteLLM/gateway
setups) makes the CLI silently ignore `ENABLE_TOOL_SEARCH`, which measured ~37k extra tokens per
session (deferred MCP *and* built-in tool schemas) on Opus 4.6 / Sonnet / Haiku 4.5 through a
LiteLLM proxy; a session's `envVars` can set it back if a gateway rejects the beta headers.
Built-ins `NotebookEdit`, `WebFetch`, `WebSearch` are blocked by default
(`SessionConfigFactory.DEFAULT_DISALLOWED_TOOLS`) for providers that support `disallowedTools`;
a session that sets its own `disallowedTools` (even `[]`) replaces that default. The widget's
`ctx N%` chip tooltip shows the per-category / per-MCP-server breakdown
(`context_usage.breakdown`, Claude only) — use it to check what a session's context is made of.

**Fixed internals** (code constants, for awareness): stream_delta journal batching
50 events / 250 ms with coalescing after each completed turn; crash stderr tail 100
lines; WS send timeout 10 s; git command timeout 60 s; sidecar shutdown grace 5 s + 2 s
before force-kill; auto-title ≤6 words / 60 s timeout; log rotation 10 MB daily,
14 days app / 30 days errors (200 MB / 100 MB total caps); 7.4's `spawn_child_session`
MAX_CHILDREN = 5 (lifetime per parent, not concurrent — children aren't recycled).

## Conventions

- Ports: backend 8080, Vite 5173, Postgres 5432.
- Commit style: imperative summary line; commits/pushes only when asked.
- Errors over REST: RFC 7807 problem+json. All inter-process protocols: NDJSON.
- Per-session event ordering uses a backend-assigned monotonic `seq`; clients resume
  with `afterSeq`.
- The repo lives on `/mnt/d` (Windows drive) under WSL — git prints CRLF warnings;
  `.gitattributes` normalizes endings, this is expected noise. Primary deployment
  target is macOS; keep everything platform-neutral.
