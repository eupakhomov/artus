# Deploying Artus on macOS

How to get the dashboard running on a Mac: install the prerequisites, clone, configure
a few paths, build, run. Optionally reach it from other devices on your LAN and set up
the integrations in §8.

## 1. Prerequisites

| What | Version | Install | Why |
|---|---|---|---|
| JDK | 25 | `brew install --cask temurin` (or SDKMAN: `sdk install java 25-tem`) | backend targets Java 25 |
| Maven | 3.9+ | `brew install maven` (or SDKMAN: `sdk install maven`) | build |
| Node.js | ≥ 22 LTS | `brew install node` | **runtime** for the session sidecars — the backend spawns `node` from `PATH` |
| Docker Desktop | any recent | docker.com | Postgres via `docker compose` (alternative: native `postgresql@17` + pgvector, then point the datasource at it) |
| git | ≥ 2.40 | ships with Xcode CLT | worktrees, all git ops |
| Claude Code CLI | latest | `curl -fsSL https://claude.ai/install.sh \| bash` (or `npm i -g @anthropic-ai/claude-code`) | **run `claude` once and log in** — sessions authenticate via `~/.claude` |
| Codex CLI | latest | follow Codex CLI's own install instructions, then `codex login` once | optional — only for `provider: codex` sessions |
| gh CLI | latest | `brew install gh`, then `gh auth login` | optional — PR creation from the Git panel, PR-check polling, review sessions, GitHub imports in the skill library |

Verify:

```bash
java --version        # 25.x
mvn --version         # Java 25 listed
node --version        # v22+
docker compose version
claude --version      # and `claude` opens logged-in
```

## 2. Get the code

```bash
git clone https://github.com/eupakhomov/artus.git
cd artus
```

## 3. Configure

Add these to `~/.zshrc` (then open a new terminal):

```bash
# REQUIRED — the repo shown as the default service when creating a session.
# The built-in default points at a path that doesn't exist on your machine.
# Any git checkout works; you can pick a different repo per session in the UI.
export ARTUS_REPO="$HOME/projects/<some-repo>"

# Optional — defaults shown; the folders are created on demand.
export ARTUS_WORKTREE_ROOT="$HOME/agentic-worktrees"   # one git worktree per session
export ARTUS_SKILLS_ROOT="$HOME/agentic-skills"        # managed skill library
export ARTUS_MEMORY_ROOT="$HOME/agentic-memory"        # long-term memory vault (Markdown)

# Optional — a fixed dashboard token. Unset = a new random token on every start.
export ARTUS_TOKEN="<pick-a-long-random-string>"
```

`ARTUS_SKILLS_ROOT` and `ARTUS_MEMORY_ROOT` only seed the first boot — after
that both are editable in the Settings dialog ("Skill library" / "Memory").

The **ecosystem root** (the parent folder of your repos, used for the service picker and
read-only cross-repo context) is not an env var — set it in Settings → "Sessions" after
first login (§7).

The full list of env vars is in CLAUDE.md "Limits & caps"; none of the others need
changing for a default install.

## 4. Database

```bash
docker compose up -d          # pgvector/pg17; DB/user/password artus, port 127.0.0.1:5432
```

Data persists in the `artus_pgdata` Docker volume. To use a non-default DB
password, set `ARTUS_DB_PASSWORD` in `~/.zshrc` — both compose and the backend
read it.

## 5. Build

`./install.sh` builds everything: both sidecars (`sidecar/`, and `sidecar-codex/`, which is
only used by `provider: codex` sessions but is cheap to build), the frontend and the
backend jar. It uses `npm ci`, which installs exactly what the lockfile pins (and wipes
`node_modules` first), so a stale or half-written install can't survive an update. It is
run in the next step.

## 6. Run

```bash
./install.sh
./start.sh
```

`install.sh` builds both sidecars and the jar (frontend included — the first build
takes a few minutes while it downloads Node and runs `npm install`). `start.sh` starts
Postgres if needed, starts the backend in the background, waits until it's healthy, and prints the URL and token:

```
UI:    http://localhost:8080
Token: <token>
```

Day to day:

| | |
|---|---|
| `./start.sh` | stop the running backend if any, start it from the existing jar |
| `./install.sh` | build sidecars, frontend and backend (stop the backend first) |
| `./install.sh --backend-only` | rebuild the backend jar only (fast) |
| `./update.sh` | `git pull --ff-only` + `./install.sh` |
| `./stop.sh` | stop (graceful; shuts sessions down, force-kills after 10 s) |
| `cat /tmp/artus.token` | show the current token |

Logs: `/tmp/artus.log` (raw stdout), `logs/artus.log` (application),
`logs/error.log`, `logs/sidecar/<session-id>.log` (one per session).

If `./mvnw` complains "permission denied", ignore it — the scripts use the system `mvn`
on macOS; `./mvnw` sometimes loses its executable bit in git checkouts.

**Token**: with `ARTUS_TOKEN` exported (§3) the same token is reused on every
restart, so the browser stays logged in. Unset, a fresh one is generated each start.
The in-process `memory` MCP server authenticates with the same token; its entry in a
session's `mcpConfig` is re-stamped with the live token whenever the config is written
(create, resume, wake), so a restart no longer leaves a session with a dead `memory` server.

**LAN access** (phone, tablet, another laptop): the scripts already bind all interfaces,
so open `http://<mac-name>.local:8080` from the other device and enter the token. macOS
will ask once whether `java` may accept incoming connections — allow it. For anything
beyond a trusted home network put TLS in front (e.g. Tailscale, or a Caddy reverse proxy
in front of a loopback-only backend).

**Token on or off** (Settings → Access → "Require access token"): the choice is stored in the
database but **read once at startup, so it applies on the next restart** (the dialog shows
what the running process actually enforces). The default is on when `ARTUS_TOKEN` is
set, off otherwise. With the token **off**, the backend accepts only trusted local clients:
the client address must be in `artus.auth.trusted-cidrs` (default: loopback), the `Host`
header in `artus.auth.allowed-hosts` (default: `localhost`, `127.0.0.1`, `[::1]`; any
port), and a browser `Origin` must belong to an allowed host or be listed in
`artus.auth.allowed-origins`. Anything else gets a 403, which also stops a web page you
visit from driving the backend on localhost. This works whatever address the socket is bound to:
- **WSL**: the backend must bind `0.0.0.0` for the Windows browser to reach it, so add the WSL
  virtual-network range to `trusted-cidrs` (or use mirrored networking, where Windows clients
  arrive from loopback).
- **Remote access with the token off**: an SSH tunnel (`ssh -L 8080:localhost:8080`), Tailscale, or
  an authenticating reverse proxy (Caddy, oauth2-proxy) on loopback with its public hostname added
  to `allowed-hosts`. The proxy is a loopback client, so `trusted-cidrs` needs no change.

### Start at login (optional)

Create a `launchd` agent (`~/Library/LaunchAgents/de.pamir.artus.plist`) whose
program is `<repo>/start.sh`, with `RunAtLoad` and your env vars from §3 in
`EnvironmentVariables` — or simply add `start.sh` to Login Items. Set Docker Desktop to
start at login too, so Postgres is up first (the backend fails fast without it and
starts cleanly once the DB is there).

## 7. First use checklist

1. Open the URL, enter the token (remembered by the browser afterwards).
2. Click the bell in the top bar to enable desktop notifications (finished / needs
   input / crashed).
3. Open **Settings** (gear icon, or `,`). None of these need a restart:
   - **Sessions** → *Ecosystem root*: the parent folder of your repos. Enables the
     service picker in the create dialog and read-only cross-repo context. Leave
     *Monorepo detection* off unless a folder underneath really is a monorepo.
   - **Linear integration** → ticket import (§8).
   - **PR checks** → background CI polling for open PRs (on by default; needs `gh`).
   - **Skill library** → skills/agents roots, optional semantic search (§8a).
   - **Memory** → long-term-memory vault, reflection defaults, optional semantic
     search (§8a).
   - **MCP servers** → code-intelligence tools (§8b–§8d).
4. **+ New Session** → pick a service (from the ecosystem root), branch, model,
   permissions — go. `q` opens the quick-session shortcut (service + ticket only,
   everything else copied from your last session) once Linear import is set up.
5. Each session widget has a git panel (`g`): status / diff / commit / push / PR. The
   PR button needs `gh auth login`.

## 8. Optional: Linear ticket import

Lets the "New Session" dialog fetch a Linear ticket and prefill the branch name and
initial prompt. Pick one auth mode:

**Personal API key** — simplest; works unless your Linear account is SSO-only:

```bash
export ARTUS_LINEAR_API_KEY="lin_api_..."   # Linear → Settings → Security & Access
```

With a key set, the ticket list is fetched straight from Linear's GraphQL API (about a second,
no AI turn) and an import hands the ticket plus its comments to the model in one call. If the
API call fails, a WARN is logged and the slower MCP-based path is used instead.

**SSO-gated Linear account** (e.g. Google identity) — the API key path won't work, so
authorize once interactively:

1. On the Mac running the backend, in a real terminal:
   `claude mcp add --transport http linear https://mcp.linear.app/mcp`
   (the `--scope` flag doesn't matter — only the OAuth consent it records is used).
2. Complete the browser OAuth flow through your org's SSO login.
3. In the dashboard: **Settings → Linear integration**, turn on "use the ambient
   `claude` CLI's cached OAuth credential". Leave `ARTUS_LINEAR_API_KEY` unset —
   an explicit key always wins over OAuth.
4. Try an import from the create-session dialog. No Linear token is stored in
   Artus; it reuses the `claude` CLI's cached credential.

**Branch-naming guidance** (either mode): the same Settings panel has a free-text field
that steers generated branch names, e.g. "keep the ticket number uppercase" or "format
as feat(TICKET)-description / fix(TICKET)-description".

## 8a. Optional: semantic search (Voyage embeddings)

One key adds semantic (embedding-based) search to the skill library, long-term memory,
and service discovery. Without it all three still work with Postgres full-text search:

```bash
export ARTUS_VOYAGE_API_KEY="pa-..."   # Voyage AI dashboard → API keys
```

- **Skill library**: also turn on "vectorize" in Settings → "Skill library" (off by
  default).
- **Memory** and **service discovery**: no toggle — semantic search is used
  automatically once the key is set.

## 8b. Optional: Serena (symbolic code tools)

Gives sessions `find_symbol`/references/etc. via an MCP server, opt-in per session.
Prerequisites:

```bash
curl -LsSf https://astral.sh/uv/install.sh | sh          # uv
git clone https://github.com/oraios/serena.git ~/serena   # or wherever you keep checkouts
```

Then in Settings → "MCP servers": set **Serena root** to the checkout path (saving
validates it and checks that `uv --version` runs) and, only if `uv` isn't on the
backend's `PATH`, set **uv path** to its full path. Pick `Serena` in the **Code
intelligence** selector. The create dialog now shows a "Code intelligence" checkbox —
off by default, since each enabled session runs its own Python process plus a language
server.

## 8c. Optional: graphify (knowledge-graph code tools)

A per-session knowledge graph of the session's code, queried via MCP tools
(`query_graph`, `get_neighbors`, `shortest_path`, …). The **Code intelligence** selector
allows one tool per install — Serena, graphify or CodeGraph, never more than one.
Prerequisites: `uv` (as in §8b) plus a checkout at the reviewed commit:

```bash
git clone https://github.com/Graphify-Labs/graphify.git ~/graphify
git -C ~/graphify checkout c7ec108        # v0.9.62
```

Then in Settings → "MCP servers": set **Graphify root** to the checkout path and pick
`Graphify` in the selector. The first save also installs graphify's Python environment
(~110 packages on a cold cache; the field pulses meanwhile). If that times out, run the
command the error message shows once by hand, then save again.

Do **not** run graphify's own `graphify install`, `hook install` or the `/graphify`
skill on this Mac — they rewrite `~/.claude/settings.json` and git hooks. Artus
only uses the checkout's CLI and MCP server; graphs live under
`<worktree-root>/.graphify/<session-id>/`, never inside a worktree.

## 8d. Optional: CodeGraph (code graph, self-refreshing)

A per-session code graph (symbols, calls, imports, inheritance) with one MCP tool,
`codegraph_explore`, that answers a question with line-numbered source, call paths and
a blast-radius summary. It keeps its own index current (file watcher), so nothing to
refresh. Prerequisites: Node ≥ 22.5 (already installed, §1 — no `uv`) plus a checkout
at the reviewed commit, built once:

```bash
git clone https://github.com/colbymchenry/codegraph.git ~/codegraph
git -C ~/codegraph checkout 1f0cbbd      # v1.6.0
cd ~/codegraph && npm ci --ignore-scripts && npx tsc && npm run copy-assets
```

Then in Settings → "MCP servers": set **CodeGraph root** to the checkout path (saving
runs a version probe; nothing is installed) and pick `CodeGraph` in the selector. A
session created with the checkbox on gets its index built while it's provisioning
(a few seconds for a typical repo; the session still starts, with a warning, if that
fails).

Do **not** run codegraph's own `codegraph install`, `upgrade`, `codegraph ui` or its
git hooks on this Mac — they rewrite `~/.claude/settings.json`, `~/.claude.json` and
`~/.claude/CLAUDE.md`. Artus only runs `init`, `serve --mcp` and `version` from the
checkout, with telemetry and update checks off. The index (`.codegraph/`) lives inside
the session's worktree, git-excluded, and disappears with it.

## 8e. Optional: Slack (mirror sessions, approve from your phone)

Each session you opt in becomes a thread in one private Slack channel — permission, plan and
question prompts with buttons, turn summaries, errors, PR changes — and the people on an allowlist
can **Allow/Deny, approve a plan, answer a question, reply to prompt the session, Stop, Status or Close**
it from Slack. Behind a separate switch, they can also **start review and development sessions** (optionally
from a template) by mentioning the bot. It uses Socket Mode, so there is no public URL and no inbound port.

**Read first:** a Slack account on the allowlist can approve commands on this Mac. Use a private
channel with only you in it, an allowlist of only you, and two-factor/SSO on that Slack account.
The full setup, the security posture, token rotation, the emergency stop and troubleshooting are in
**[SLACK.md](SLACK.md)**; this section is the short path.

1. **Create the app** — api.slack.com/apps → *Create New App* → *From a manifest* → your workspace →
   JSON, paste (least privilege for a **private** channel; for a public one swap
   `groups:history`/`message.groups` for `channels:history`/`message.channels`):

   ```json
   {
     "display_information": { "name": "Artus" },
     "features": { "bot_user": { "display_name": "Artus", "always_online": false } },
     "oauth_config": { "scopes": { "bot": ["chat:write", "groups:history", "reactions:write", "files:write", "app_mentions:read"] } },
     "settings": {
       "event_subscriptions": { "bot_events": ["message.groups", "app_mention"] },
       "interactivity": { "is_enabled": true },
       "socket_mode_enabled": true,
       "org_deploy_enabled": false,
       "token_rotation_enabled": false
     }
   }
   ```

2. **Tokens** — *Basic Information → App-Level Tokens → Generate* with the `connections:write` scope
   (`xapp-…`), and *OAuth & Permissions → Install to Workspace* for the bot token (`xoxb-…`).
3. **Channel** — create a private channel, `/invite @Artus`, and note its channel **ID** (channel
   details → About, starts with `C`) and your member ID (profile → ⋯ → Copy member ID, `U…`).
4. **Configure** — add to `~/.zshrc` (the macOS default shell; for bash use `~/.bashrc`, see SLACK.md
   for the launchd and non-interactive cases), then open a new terminal:

   ```bash
   export ARTUS_SLACK_ENABLED=true
   export ARTUS_SLACK_BOT_TOKEN='xoxb-…'      # prefer the Keychain, see SLACK.md
   export ARTUS_SLACK_APP_TOKEN='xapp-…'
   export ARTUS_SLACK_CHANNEL=C0123456789     # the channel ID, not its name
   export ARTUS_SLACK_ALLOWED_USERS=U0123456789   # comma-separated; empty = nobody can act
   # export ARTUS_SLACK_START_ENABLED=true     # optional: start review/dev sessions by mentioning the bot (SLACK.md)
   # export ARTUS_PUBLIC_URL=https://mac.tailnet.ts.net:8080   # optional: the "Open" button
   ```

5. **Restart** (`./start.sh`) and check the log for `slack: bot '…' in workspace '…'` then
   `slack socket: ready`. Tick **Slack** in the new-session dialog (default for new sessions:
   Settings → Slack). The first start also applies Flyway migration `V19` (three nullable/defaulted
   columns on `session`, plus an index).

Slack is off unless `ARTUS_SLACK_ENABLED=true`; with it off no Slack code runs. One backend per
Slack app (two on the same app steal each other's events). Emergency stop: delete the app-level
token in the Slack app (cuts inbound immediately), then unset the variable and restart.

## 8f. Optional: extra MCP servers and plugins (tool catalog)

Sessions start with a small tool set; anything else (a Datadog or BigQuery MCP server, the Playwright plugin, …)
is added through **Settings → Tools** and then ticked per session or template. **Import…** lists what the host
already has — `~/.claude.json` servers, installed Claude plugins, Codex plugins — so nothing has to be retyped.

- **Secrets never go into the catalog.** Write them as `${DD_API_KEY}`-style references in the server's `env` or
  `headers`; the import replaces literal values with such placeholders. The variable must be set in the environment
  of the process running Artus (e.g. exported before `start.sh`, or in the launchd plist) — a session picking an
  entry whose variable is unset fails with a 400 that names the entry.
- **Headless only.** The backend runs no browser, so a server that only offers OAuth login (Datadog's default,
  Google Drive, …) cannot authenticate; "Test" reports it as `HTTP 401 — the server needs authentication`. Use the
  server's API-key/header form instead.
- **Codex sessions** get the same servers (a plugin's `.mcp.json` is carried as thread-scoped `mcp_servers`, nothing
  is installed into `~/.codex`), but no pre-approval — every call goes through the approval prompt.

## 9. Updating

```bash
./stop.sh   # a live JVM holds the jar; install.sh refuses to run over it
./update.sh                         # git pull --ff-only, then install.sh (sidecars, frontend, backend)
./start.sh                          # DB migrates on boot
```

## 10. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| 403 "auth disabled; only trusted local clients are accepted (…)" | The token is off and the request failed the local-client check; the parenthesis names which one (client address, Host, Origin, cross-site). Add the client's range to `artus.auth.trusted-cidrs` or the hostname to `allowed-hosts`, or turn the token on (Settings → Access, then restart) |
| Session stuck in STARTING, then CRASHED | `node` not on the backend's `PATH`, sidecar not built (§5), or `claude` never logged in — check `logs/sidecar/<id>.log` |
| Same, only for `provider: codex` sessions | `sidecar-codex` not built (§5), or `codex login` never done — check `logs/sidecar/<id>.log` |
| Sidecar build fails with a `TS1010`/syntax error inside `node_modules/@types/...` | A truncated/corrupt `node_modules` (interrupted install, bad npm cache, synced folder) — `rm -rf node_modules && npm cache verify && npm ci` in that package |
| Create fails 409 "already used by worktree" | That branch is checked out by another (possibly orphaned) session worktree — see `GET /api/maintenance/orphans`, clean via `POST …/clean` |
| Health DOWN / boot fails on datasource | Postgres not up yet — `docker compose up -d`, wait for healthy |
| PR button → 409 | `gh` missing or not authenticated, or repo has no GitHub remote — the message says which |
| Widgets empty after update | Hard-refresh the browser (cached JS) |
| Ticket import: "needs auth" / "cannot run the OAuth flow" | OAuth mode only: the `claude mcp add` step in §8 wasn't done on this Mac — check `logs/sidecar/<system-session-id>.log` |
