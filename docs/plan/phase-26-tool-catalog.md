# Phase 26 — Tool catalog: pick MCP servers, plugins and built-in tools per session and template

Status: **implemented (2026-10-20)**; see "As built" at the end. Feasibility checked against the code as of commit `a758480` and a
probe of the installed Codex CLI 0.151.0 (`codex plugin`, `codex app-server generate-json-schema`).

## Background

Sessions start with a deliberately small tool set (phase 25 / `DEFAULT_DISALLOWED_TOOLS`, `ENABLE_TOOL_SEARCH`). The
cost is that tools a user relies on elsewhere — the Datadog plugin, a BigQuery tool — never reach a session, and
there is no way to ask for them:

| Finding | Where |
|---|---|
| User-level MCP servers (`~/.claude.json`) and enabled plugins (`~/.claude/settings.json`) are never loaded: the Claude sidecar runs with `settingSources: ['project']` and takes MCP servers only from the per-session `mcpConfig` file | `sidecar/src/session.ts:626`, `SessionService.mcpConfigPath` |
| The SDK's `plugins: [{type:'local', path}]` option exists, the sidecar never sets it | `sdk.d.ts` `SdkPluginConfig` |
| No UI for tools. The create dialog has only a "Raw overrides" JSON box; templates have an "MCP servers (JSON)" textarea; nothing sets `allowedTools`/`disallowedTools` | `CreateSessionDialog.tsx`, `TemplateManager.tsx` |
| A session that sets its own `disallowedTools` loses the default `NotebookEdit/WebFetch/WebSearch` block (and the reverse: there is no way to *enable* one of them) | `SessionConfigFactory:151` |
| Layering of managed MCP entries (Linear, memory, code-intel) into `mcpConfig` exists and has one merge rule: a session's own entry of the same name wins | `SessionConfigFactory.withDefault*Mcp` |
| Codex: MCP works through `thread/start config.mcp_servers`, translated from the same Claude-shaped file. **`enabledTools` is only mapped for `url` entries** — a stdio server (typical for BigQuery) loses its allow-list on Codex | `sidecar-codex/src/mcp.ts` |
| Codex has a first-party **plugin** system (`codex plugin add PLUGIN@MARKETPLACE`, app-server `plugin/*` methods, `plugins` feature stable). Plugins are installed **globally** into `~/.codex`; the marketplace snapshot already lists `datadog`, `google-drive`, `data-analytics`, … | `codex plugin list` |
| A plugin on either side is, in practice, a bundle of `.mcp.json` (+ skills/apps): Codex's `datadog` plugin is `{"datadog": {"type": "http", "url": "https://mcp.datadoghq.com/v1/mcp"}}`, Claude's Playwright plugin `{"playwright": {"command": "npx", ...}}` | `.mcp.json` in both plugin caches |

## Decisions

1. **One catalog, entries of two kinds, both resolved to the existing `mcpConfig` path.**
   - `mcp` — a named MCP server: `{name, description, server: {command,args,env | url,headers}, enabledTools?,
     preApprove?}`. Goes into the session's `mcpConfig` under its name (provider-neutral, Claude-shaped, translated
     for Codex as today).
   - `plugin` — a reference to an installed plugin directory (`{name, source: 'claude'|'codex', path}`). Its
     `.mcp.json` servers are expanded into `mcp` entries at *resolution time*; for Claude the plugin directory is
     also passed to the SDK's `plugins` option so its skills/commands come along. Plugins are not a separate runtime
     concept for Codex: its plugin's MCP servers are carried as `config.mcp_servers` like any other (thread-scoped,
     nothing installed or toggled in the user's `~/.codex`). A native Codex plugin *toggle* per thread is a **spike**
     (step 0) — adopt it only if `thread/start` accepts it without touching global config.
2. **Catalog lives in Settings** (`tools.catalog`, JSON, persisted `app_setting`, edited in a new Settings → "Tools"
   section) — non-secret only. Secrets are referenced as `${ENV_VAR}` in `env`/`headers` and expanded by the
   backend from its own environment at session creation (same posture as `ARTUS_LINEAR_API_KEY`); a missing variable
   is a 400 at creation naming the entry, never a silently broken server.
3. **Import instead of retyping.** "Import…" in the Tools settings scans, read-only: `~/.claude.json` `mcpServers`,
   Claude plugin dirs from `~/.claude/plugins/installed_plugins.json`, and Codex plugin dirs from
   `~/.codex/.tmp/plugins` / installed plugins. Shows what it found with the servers each contains; the user ticks
   what to add. Nothing is written outside Artus's own settings. Plugin entries keep a *path*, not a copy, so a plugin
   update is picked up by the next session.
4. **Per-session selection is a list of catalog names**, stored as `session.tools` (`text[]`/jsonb, new migration) and
   as a `tools` key in template config. Resolved **at creation** and baked into `mcpConfig` — editing the catalog
   later does not change a live session (same snapshot rule as Linear's allowed-tools list). `duplicate()` and
   `lastSessionConfig` carry the selection; the quick-session dialog inherits it like the other settings.
5. **Built-in tools become checkboxes**, backed by `disallowedTools`: "Web search", "Web fetch", "Notebook edit"
   (provider-capability-gated: hidden when `disallowedTools` is unsupported). The default stays blocked; ticking one
   removes just that name from the effective list, so enabling `WebSearch` no longer drops the other defaults. A
   session's explicit `disallowedTools` (raw overrides) still replaces the default wholesale, as documented.
6. **Permissions unchanged by default.** Catalog tools go through the normal approval flow. `preApprove` (per catalog
   entry, off by default) adds the server's tools to `allowedTools` — Claude only (Codex rejects `allowedTools`, so
   there it is a documented no-op and the UI says so). `enabledTools` (allow-list) is enforced as for Linear: a
   `PreToolUse` hook on Claude, `enabled_tools` on Codex — **now for stdio entries too** (fix in `mcp.ts`).
7. **No conflicts with managed entries.** Catalog names may not be `memory`, `linear`, `serena`, `graphify`,
   `codegraph` (rejected when saving the catalog); the existing merge rule (a session's own entry wins) stays.
8. **Cost is visible.** The picker shows a server's tool count when known (from a one-off "Test" call that starts
   the server, lists tools and stops it — 30 s budget, same pattern as the code-intel root validation) and the
   existing `ctx N%` tooltip already breaks context down per MCP server.

## Steps

### Step 0 — Spikes (½ day, before any code)
- Codex: does `thread/start` accept a thread-scoped plugin toggle (e.g. `config: {"plugins": {"datadog@openai-curated":
  {"enabled": true}}}`)? Probe in a throwaway `CODEX_HOME` so the user's `~/.codex` is never modified. Outcome decides
  whether decision 1's "native toggle" is built; the MCP-carrying path is the fallback and is enough for Datadog.
- Claude: pass `plugins: [{type:'local', path}]` for the Playwright plugin dir from `npm run drive`; confirm its MCP
  server and skills are visible and that `settingSources: ['project']` does not suppress them.
- Datadog's MCP auth headless: confirm the API-key/application-key header form works without OAuth (the plugin's
  default is OAuth in a browser, which a headless sidecar cannot do — same limit as Linear OAuth today).

### Step 1 — Catalog model and Settings API
- `ToolCatalog` (record + validation: unique names, reserved names, exactly one of `command`/`url`, `${VAR}`
  syntax) in `config/`; `tools.catalog` setting in `SettingsService`/`SettingsPatch`/`Settings`; REST in
  `SettingsController` (`GET/PATCH /api/settings` as today) plus `POST /api/tools/test {name}` and
  `GET /api/tools/import` (the read-only scan of decision 3).
- Unit tests: validation, `${VAR}` expansion (missing var → named error), import parsing of both plugin layouts and
  `~/.claude.json` fixtures.

### Step 2 — Resolution into the session
- `SessionConfigFactory.withCatalogMcp(mcpConfig, selectedNames)` using the shared merge rule; `preApprove` →
  `allowedTools` (Claude); `enabledTools` carried as our own key; plugin entries → expanded servers + a `plugins`
  list written next to the MCP file.
- `session.tools` migration (`V24__session_tools.sql`), `SessionEntity`/`SessionRepository`, `CreateOptions`,
  `configOverridesFrom`, `duplicate()`. Unknown/removed catalog name at creation → 400; at resume of an old session
  the baked `mcpConfig` is used as is (no re-resolution).
- Sidecar: `--plugin-dir <path>` (repeatable) → SDK `plugins`; `SidecarManager` passes it. `sidecar-codex/src/mcp.ts`:
  map `enabledTools` for stdio entries as well; protocol-sync check unaffected (adapter flags are not protocol).
- Tests: factory resolution (both providers, reserved names, own-entry-wins), `mcp.ts` stdio allow-list, sidecar
  argument parsing.

### Step 3 — UI
- **Settings → Tools**: catalog table (add/edit/remove, JSON for the server block with a form for the common
  fields), Import dialog, per-entry Test (tool count + error text), `preApprove` and `enabledTools` fields.
- **Create dialog** and **Template editor**: a "Tools" group — catalog checkboxes (with tool count when tested,
  "needs `${DD_API_KEY}`" hint when the variable is unset) and the three built-in toggles; replaces the need for
  Raw overrides. Template config gains `tools` and `disallowedTools` keys; quick-session and duplicate carry them.
- Widget: an info chip/tooltip lists the session's catalog tools (read-only).
- Demo build: `rest.demo.ts` returns an empty catalog; the new settings keys must not break fixtures.
- Frontend tests: store/protocol types, the pure "effective disallowed list" helper.

### Step 4 — Docs and live verification
- `docs/ARCHITECTURE.md` (catalog + resolution), `docs/DEPLOY.md` (`${VAR}` secrets, headless auth caveat),
  `CLAUDE.md` (persisted-settings entry), README decision log, PROTOCOL.md only if a sidecar flag is documented there.
- Live: a Claude and a Codex session each with the Playwright MCP (stdio) and Datadog (http, key headers) from the
  catalog; a template carrying both; `enabledTools` restricting a stdio server on both providers; `WebSearch`
  enabled via the checkbox while `WebFetch` stays blocked; catalog edit leaves a live session unchanged.

## Definition of Done

- [ ] Spikes recorded in this doc (Codex per-thread plugin toggle yes/no; Claude `plugins` option works under
      `settingSources: ['project']`; Datadog headless auth route).
- [ ] A catalog entry can be added by hand and by import (`~/.claude.json`, a Claude plugin dir, a Codex plugin dir).
- [ ] Create dialog and template editor can pick catalog tools and the three built-in tools; the choice reaches a new
      session's `mcpConfig`/`disallowedTools`, and survives duplicate, quick session and `lastSessionConfig`.
- [ ] Works on Claude **and** Codex sessions: a catalog server's tools are callable; `enabledTools` is enforced for
      stdio and http entries on both.
- [ ] Secrets only via `${ENV_VAR}`; a missing variable fails creation with a message naming the entry; no secret
      appears in the DB, logs or the journal.
- [ ] Reserved names rejected; a catalog edit never changes a live session.
- [ ] Backend unit/DB tests, both sidecar suites, frontend vitest + `tsc`, demo `--check`, protocol-sync pass; both
      themes checked for every new UI element.

## Manual test script

1. Settings → Tools → Import: both Claude (`playwright@claude-plugins-official`) and Codex (`datadog`) plugins are
   listed with their servers; add both; Test shows a tool count for Playwright.
2. New session (Claude, default permissions) with Playwright ticked → `ctx` tooltip lists the server, a tool call
   goes through the approval prompt. Same on a Codex session.
3. Template with Datadog + `WebSearch` enabled → create from it → the session has both; `WebFetch` still blocked.
4. Set `enabledTools: ["search_datadog_logs"]` on a stdio test server → only that tool is callable on Claude and Codex.
5. Unset `DD_API_KEY`, create a session with Datadog → 400 naming the entry; set it → works.
6. Edit the catalog entry while a session runs → the running session keeps the old config; a new one gets the new.

## Open questions

- Codex per-thread plugin toggle (step 0) — decides whether a native-plugin path exists beyond carrying its MCP servers.
- Whether the Claude SDK loads a plugin's `skills/` through `plugins` when `settingSources` excludes `user` (step 0);
  if not, skills are materialised the way library skills already are (`.claude/skills/` in the worktree).
- OAuth-only servers (Datadog's default, Google Drive): unsupported headless on both providers; the catalog entry
  should say so rather than start a session that cannot authenticate.

## As built (2026-10-20)

Deviations from the plan, and what was actually verified.

- **Catalog API** is its own endpoint (`GET`/`PUT /api/tools/catalog`, `POST /api/tools/test`, `GET /api/tools/import`),
  not a key of `/api/settings` — the `Settings` record has ten chained legacy constructors and the catalog is a list,
  like the pricing tables that already live outside it. Stored in `app_setting` key `tools.catalog`.
- **Secrets stay out of the DB**: `session.mcp_config` keeps `${VAR}`; `SessionConfigFactory.expandSecrets` substitutes
  at the moment the per-session MCP file is written (every spawn). Creation fails with a 400 naming entry and variable.
  (Pre-existing: the Linear API key *is* baked into `mcp_config`; untouched here.)
- **Plugins**: a Claude session gets the plugin dir via the SDK `plugins` option (`--plugin-dir`, new
  `session.plugin_dirs`); the SDK loads the plugin's servers itself. Providers listing `pluginDirs` in
  `unsupportedSessionFields` (Codex) get the plugin's `.mcp.json` servers carried as ordinary entries
  (`${CLAUDE_PLUGIN_ROOT}` filled in). `session.tools` holds the picked names (V24).
- **Built-in tools**: backend unchanged; the UI helper (`toolsModel.ts`) sends the *remaining* defaults as
  `disallowedTools`, and omits the key when nothing is ticked. A template's own non-built-in list is left alone.
  `/api/providers` capabilities gained `toolPresets` to hide the toggles for Codex.
- Duplicate / quick session carry `tools`; the baked servers win over a re-resolution (a catalog entry deleted since
  makes duplicate fail with a 400 naming it).

### Spikes
- **Claude `plugins` under `settingSources: ['project']`**: works — a haiku session with the Playwright plugin listed
  all `mcp__plugin_playwright_playwright__browser_*` tools. Plugin *skills* were not checked.
- **Codex per-thread plugin toggle**: not probed; the MCP-carrying path was built instead (sufficient for Datadog).
- **Datadog headless auth**: the unauthenticated endpoint answers 401 (Test surfaces it as "needs authentication");
  the API-key header form could not be verified without keys.

### Live verification (second backend instance on :8091, shared DB)
- Import scan listed Claude (`playwright`, …) and Codex (`datadog`, …) plugins with their servers; adding, Test
  (25 Playwright tools listed through the stdio probe), reserved name `linear` → 400, unset `DD_API_KEY` → 400 naming
  `dd`, both at Test and at session creation.
- Claude session with the catalog plugin: tools callable-listed (above). Codex session: the baked file contained the
  expanded `playwright` server, but Codex reported no MCP tools at all — **also for a session with no catalog tools**
  (memory/linear), so this is an environment/Codex-side limitation to look into separately, not caused by this phase.
- Not exercised live: `enabledTools` on stdio for both providers (unit-tested in `mcp.test.ts`/`ToolCatalogTest`),
  the dashboard UI in a browser (typechecked and built, both themes not eyeballed).
