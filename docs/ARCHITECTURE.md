# Artus — Architecture (as built)

Status: Phases 0–4 complete plus git panel (5.1), PR creation (5.2), long-term memory &
reflection (5.3), desktop notifications (5.14), model switching mid-session (5.5),
per-session service/ecosystem selection (5.7, partial — see §4), transcript export
(5.9), the skill & agent library (Phase 6), the usage dashboard (5.12), dashboard UX &
orchestration (Phase 7), the Codex provider adapter (5.13), and ecosystem service
discovery (Phase 8). This document describes the running system (§§1–3f) and sketches
implementations for what's left of the backlog (§4: 5.4, 5.6–5.8, 5.10–5.11). The dated
decision log in `docs/plan/README.md` remains the authority on *why*; this file covers
*what and how*.

## 1. System overview

```
┌────────────────────────────  Browser (React SPA)  ───────────────────────────┐
│  Dashboard grid (react-grid-layout, layout in localStorage) or focus mode    │
│  Session sidebar: live state · time in state · PR · inline allow/deny       │
│  SessionWidget ×N: transcript ⟵ zustand reducer ⟵ WS envelopes              │
│    permission/plan cards · git panel · queued chips · mode toggle · budget   │
│  TokenGate (bearer, only on 401)    · desktop Notifications (unfocused tab)  │
└───────┬──────────────────────────────────────────────────────────────────────┘
        │ REST /api/** (Authorization: Bearer)          WS /ws/sessions/{id}
        │                                               (token via Sec-WebSocket-Protocol,
        ▼                                                afterSeq replay + live)
┌────────────────────────────  Spring Boot backend  ───────────────────────────┐
│ Controllers: sessions · templates · git ops · meta (services/branches/skills)│
│              maintenance (orphans) · SPA fallback                            │
│ SessionService ── state machine, FIFO queue, budgets (create/close/sidecar   │
│   events); SystemSessionService (per-lane system sessions + runSystemTurn),  │
│   SessionConfigFactory (create-config/MCP defaults), AutoTitleService,       │
│   SessionHousekeeping (parking/orphan sweep) split out — Phase 9 Run E       │
│   │        │                                                                 │
│   │        ├─ GitWorktreeService / GitOpsService (shell git, gh CLI)         │
│   │        ├─ AssetProvisioningService (skills/agents → .claude/, symlinks)  │
│   │        └─ SidecarManager → SidecarHandle ×N (virtual-thread pipes,       │
│   │                            PID files, stderr → logs/sidecar/<id>.log)    │
│   ▼                                                                          │
│ EventJournal (Postgres, per-session seq, delta batching+coalescing)          │
│   └─ SessionEventBus → N WS subscribers (replay-then-live, seq-deduped)      │
└───────┬──────────────────────────────────────────────────────────────────────┘
        │ NDJSON over stdio (provider adapter protocol v1, docs/PROTOCOL.md)
        ▼
┌───────────────────────  Sidecar (Node, one per session)  ────────────────────┐
│ @anthropic-ai/claude-agent-sdk: streaming input, canUseTool bridge,          │
│ summarized thinking + progress, interrupt, resume, permission modes          │
│ cwd = git worktree (one per session, branch-isolated)                        │
└──────────────────────────────────────────────────────────────────────────────┘
```

## 2. Core invariants

- **The journal is the single source of truth.** Every event (adapter output,
  user input echoes, state changes, queue snapshots) gets a per-session monotonic
  `seq` and is persisted before fan-out. Any client can rebuild the full UI state
  from `afterSeq=0`; reconnects resume losslessly from the last seen seq.
- **The adapter contract is provider-neutral.** The backend/UI never reference
  Claude specifics; capabilities announced in `ready` gate which controls render.
  A second provider = a new adapter binary + a `artus.providers.<id>` entry.
- **Sessions outlive processes.** `providerSessionId` (persisted from `system_init`)
  makes sidecars disposable: crash → CRASHED + Resume; idle timeout → PARKED with
  transparent wake; backend restart → sweep marks CRASHED, PID files reap orphans.
- **Worktrees isolate work.** One worktree+branch per session under `worktree-root`;
  provisioned assets and the PID file are kept out of `git status` via the
  per-worktree `info/exclude`; close resolves dirt explicitly (commit/stash/discard).
  In a monorepo (Phase 11), the session's `servicePath` (a `packages/*`-style folder
  detected by `ServiceDetector`) is a subfolder of that same worktree — the sidecar's
  cwd — while the *whole* worktree stays writable (`--writable-root`, checked by
  `readOnlyDenial`/Codex's sandbox `writableRoots` instead of cwd alone) and is what's
  attached as read-only ecosystem context, since it's the session's own fresh checkout
  of the very code being edited. Polyrepo is the `servicePath == repoPath` special case,
  so this is invisible when a service is also its own repo root.

## 3. Key tables (V2 migration)

`session` (config + provider/session ids + capabilities + state) ·
`session_event(session_id, seq, ts, type, payload jsonb)` ·
`session_queue` (FIFO pending messages) · `session_template(config jsonb)`.
Postgres runs the `pgvector/pg17` image; V7 enables the `vector` extension for the
skill library (see below); V9 (§3b) reuses it for long-term memory and adds `pg_trgm`.

## 3a. Skill & agent library (Phase 6)

Curated library on top of per-session skill sources (`de.pamir.agentic.ui.library`):

- **Tables (V7)**: `asset_source` (dir/repo ref, sync flag + last-sync state) ·
  `library_asset` (kind, name/description, managed-copy `location`, `source_path`,
  SHA-256 `content_hash`, ACTIVE/ARCHIVED) · `asset_tag` · `asset_embedding`
  (`vector(1024)`, HNSW cosine) · `source_discovery` (new upstream files).
- **Scan** (`AssetScanService`): convention-first — `SKILL.md` dir = one skill
  (whole-dir tree hash), `.md` under `agent(s)` path = agent, name-contains only a
  low-confidence fallback. Repo sources fetched exclusively via `gh`
  (`RepoCacheService`: `gh repo clone --depth 1` / `gh repo sync`, cache under
  `<skillsRoot>/.repo-cache/<sha256[:16]>`), GitHub-only by design for now.
- **Import** (`LibraryService`): copies into the managed roots (persisted settings
  `library.skills-root` / `library.agents-root`; skills root is also what the
  create-dialog picker and provisioning read), never overwrites different content
  (`-2` suffix + warning), dedupes identical content, writes asset+tags, best-effort
  embeds (`VoyageEmbeddingClient` behind `EmbeddingClient`, key
  `ARTUS_VOYAGE_API_KEY`, model voyage-3.5-lite).
- **AI-fill** (`LibraryAiService`): batches ≤5 file contents per Haiku system-session
  turn (`SystemSessionService.runSystemTurn`, via `SystemTurnClient`), returns name/description/tags per path.
- **Sync** (`LibrarySyncService`): 60s tick, interval as `last_synced_at` cutoff
  (PrCheckPollingService shape). Changed hash → refresh copy + re-embed; vanished →
  ARCHIVED (files kept); reappeared → restored; unimported → `source_discovery`.
  Frontend polls `GET /api/library/sources` for the library badge + desktop notification
  (the journal/WS pipeline is per-session, so a global event has no transport).
- **API**: `/api/library/{scan,import,ai-fill,assets,search,sources}` — see
  `LibraryController`.

## 3b. Long-term memory & reflection (Phase 5.3)

Full design + decisions: `docs/plan/phase-5.3-memory-reflection.md`.

- **Tables (V9)**: `memory_doc` (scope/service_path, name=slug, description, `tags
  TEXT[]`, content, content_hash, status, `vector(1024)` embedding + generated
  `tsvector`) · `memory_link` (wikilink graph, `to_doc_id` nullable = dangling) ·
  `memory_episode` (append-only, per-session summary, its own embedding/tsvector).
  `session` gains `reflection_enabled`/`reflected_seq`.
- **Semantic memory's source of truth is the filesystem, not the DB** — Markdown +
  YAML frontmatter under a managed vault (`memory.root`), laid out
  `ecosystem/*.md` / `services/<slug>/*.md` (`MemoryPaths` resolves the slug,
  collision-suffixed by repo-path hash, each dir marked with a `.repo-path` file).
  The vault is a valid **Obsidian vault** by construction: filenames are slugs,
  `[[wikilinks]]`/`[[slug|alias]]` in the body resolve the same way Obsidian would
  (same-scope-first, then ecosystem, then any service — `MemoryRepository.
  resolveSlug`), dangling links auto-resolve when the target is (re)written.
  `MemoryDocService` is the only writer; `MemorySyncService` (1-minute tick, the
  configured interval applied as a last-run cutoff) re-indexes hand-edited files.
- **Reflection** (`ReflectionService`): one structured system-session turn per
  session close (async — a Spring `ReflectionRequested` event, not a direct
  `SessionService` dependency, avoids a circular bean; the manual "Reflect now"
  widget button calls it synchronously from `SessionController` instead) or manual
  trigger. `TranscriptDigest.render()` (`de.pamir.agentic.ui.journal` — provider-
  neutral, not memory-specific, so it also backs 5.9's transcript export via the
  sibling `renderMarkdown()`) renders the journal into a capped text digest;
  `SystemSessionService.runSystemTurn(prompt, model, lane, timeout)` gained a
  model-override overload so reflection can run on a different model than the
  system session's default haiku (the `lane` param is Phase 9 Run E's S2 — reflection
  runs on the BACKGROUND lane so it never blocks an interactive system-session caller — since phase 19 each lane has its own system session, so that holds structurally, and every session is recycled to an empty conversation after each turn). The model's JSON response (`episode` + up to 10 `semantic` ops)
  is, **by default, held for human approval rather than applied** (`memory.
  reflection-approval-required`, default true): a `memory_proposal` row
  (V10, `PENDING`/`APPROVED`/`DISCARDED`, partial unique index enforcing at most
  one `PENDING` row per session) and a `reflection_proposed` journal event
  instead of a write. The memory dialog's Pending tab approves (optionally editing
  the episode text or any op — mirrors `permission_response`'s `updatedInput`)
  or discards; either way `ReflectionService.applyReflection()` — the same
  method the auto-apply path calls directly when the setting is off — is what
  actually writes and journals `reflection_complete`; a discard journals
  `reflection_discarded` and writes nothing, leaving `reflectedSeq` unset so a
  later reflection isn't blocked by a discarded one.
- **Curation (Phase 21**, `docs/plan/phase-21-memory-curation.md`**)**: the user can mark
  transcript blocks *important* or *excluded* (`block_marked` journal events, folded by
  `TranscriptDigest.marks`; `BlockMarkService` validates and journals them). The digest
  every consumer shares (reflection, skill extraction, `HandoffService`) tags user/assistant
  lines with `[#seq]` anchors, stars important ones and repeats them in full in an appendix
  *after* the head/tail cap (so a mid-transcript mark survives truncation), and replaces
  excluded content with a placeholder. The "nothing to reflect" gate requires a
  digest-relevant event (message, mark, tool call) after `reflectedSeq` — reflection's own
  journal lines don't count — so marking after a reflection re-arms the button; a refresh
  feeds the session's earlier episodes into the prompt and supersedes (discards) a still-pending
  proposal only once the replacement exists. `memory_proposal.kind` (V20) is `memory` /
  `skill` / `remember`, one pending per (session, kind) except `remember`: **skill
  extraction** (`ReflectionService.extractSkill`) always needs approval and, on approve,
  `LibraryService.createSkill` writes `<skills-root>/<slug>/SKILL.md` with no upstream
  source (name collision = 409, proposal stays pending to be renamed); **remember this**
  turns one block into 1-2 memory ops with no episode and without touching `reflectedSeq`.
  Reflection/skill ops cite `evidence` seqs, which `ProposalEvidenceService` resolves into
  quoted snippets on `GET /api/memory/proposals`.
- **Retrieval is hybrid**: pgvector cosine + Postgres FTS (`websearch_to_tsquery`/
  `ts_rank_cd`) + `pg_trgm` similarity (catches exact identifiers embeddings blur),
  fused with Reciprocal Rank Fusion in one SQL query (`MemoryRepository.
  hybridSearch`/`MemoryEpisodeRepository.hybridSearch`). Dense arm skipped when
  Voyage is unconfigured — sparse/trigram still work.
- **Agent-facing tools are in-process, not a spawned process**: three `@McpTool`
  beans (`MemoryMcpTools`) served by `spring-ai-starter-mcp-server-webmvc`
  (Streamable-HTTP) at `/api/mcp/memory`, which — because it's mounted under
  `/api/**` — is already covered by the existing bearer-token `AuthTokenFilter`,
  no new auth code. Every session's `mcpConfig.memory` entry is the same static
  `{type: "http", headers: {Authorization}}` block `SessionConfigFactory.
  memoryMcpServer()` builds (same shape as `linearMcpServer()`, same reused
  `ARTUS_TOKEN` — not a new secret). Since MCP transport context doesn't
  cleanly expose the inbound session identity to a WebMVC tool method, each tool
  takes an explicit `sessionId` argument instead (resolved server-side to that
  session's `repoPath` for the scope filter); the session learns its own id from
  the episodic-window system-prompt block `SessionConfigFactory.
  memorySystemPromptBlock()` adds at every spawn (`SidecarManager.spawn` gained
  an `extraSystemPrompt` parameter, combined with the session's own
  `instructions` into one `--append-system-prompt`).
- **Human-facing**: `MemoryController` (`/api/memory/{search,docs,episodes}`) and
  the topbar memory dialog (`MemoryDialog.tsx`) — hybrid search across scopes,
  browse/edit/archive docs (archive keeps the file, library-style), episode list.
- **Retention**: `MemoryRetentionService` (hourly tick) deletes the raw
  `session_event` rows of a CLOSED session once its `reflected_seq` is set and
  `memory.retention-days` (default 0 = never) has elapsed — the episode/semantic
  memory a reflection wrote is the durable record from that point on.

## 3c. Dashboard UX & orchestration (Phase 7)

Full design + decisions: `docs/plan/phase-7-ux-and-orchestration.md`.

- **7.1 hotkeys**: one document-level `keydown` listener (`useHotkeys`), suppressed
  while typing or a dialog is open. A module-level registry (`widgetRegistry.ts`,
  not React state) lets it reach into a specific `SessionWidget` — focus the
  composer, toggle the git panel, respond to a pending permission — since there's
  no shared component tree between the global listener and per-widget state.
  `y`/`d` special-case `AskUserQuestion` (no safe keystroke "allow"; `d` reuses its
  existing skip message) and `ExitPlanMode` (friendlier deny wording), otherwise
  send the same plain allow/deny `PermissionCard` already does. Every handled key
  calls `preventDefault()` unconditionally (not just where an action fires) — opening
  a dialog whose first field autofocuses can otherwise race the browser's own
  default text-insertion for that same keystroke, landing the letter in the field.
- **7.2 window management**: tiling (react-grid-layout) stays the base; maximize is
  a CSS class (`!important`, since RGL sets `position`/`transform` inline) on the
  same grid-item DOM node — no remount, no WS reconnect. Minimize is
  `visibility:hidden` on that node (phase 16: `display:none` discarded the transcript's
  scroll position), not unmount, so the widget's WS/store stay live (Exposé cards and
  desktop notifications keep working while hidden). Exposé reads straight from the
  Zustand store; zero new connections. (Phase 16 replaced the 7.2 dock strip with the
  sidebar below.)
- **Phase 16 sidebar & focus mode** (frontend only): the left sidebar renders
  `buildSidebarModel()` (`sidebarModel.ts`, pure — Needs-you list oldest first by
  `stateSince`, service groups in creation order, children nested under an open
  parent, System last) from the same store, so it also opens no connections.
  `stateSince` comes from the `state_changed` envelope's `ts`, which replay preserves,
  so time in state survives a reload. PR status (`prUrl`/`prCheckStatus`) lives in the
  store too (seeded from the entity, kept current by `pr_status_changed`) — one source
  for the widget chip and the sidebar. Inline Allow/Deny goes through
  `widgetRegistry.respondPermission` with `permissions.ts`'s `permissionResponseFor`,
  the exact path of the `y`/`d` hotkeys; plans and AskUserQuestion get only **Open**.
  Focus mode hides every grid item but the active one (`focusedId`, falling forward
  past minimized sessions) and pins that one into the main column with the same
  `!important` override trick as maximize — every widget stays mounted, and
  `onLayoutChange` is ignored while in focus mode so the saved grid layout survives
  the round trip. Fold state (`artus.sidebar`) and layout mode
  (`artus.layoutMode`) persist in localStorage.
- **7.3 continuation**: `session.continued_from_id` (V11). `HandoffService`
  (mirrors `GitAssistService`'s digest→system-turn shape) runs one system-session
  turn over `TranscriptDigest.render()` — the same capped digest reflection uses —
  producing a ~1-2 KB Markdown brief (`POST /api/sessions/{id}/handoff-summary`);
  the picker's "full transcript" checkbox instead calls the existing uncapped
  `export.md` (5.9). Either way the text lands unsent in the new session's compose
  box (`CreateSessionDialog`'s `onCreated(id, draftInput)`, the ticket-import
  precedent) — never auto-fired.
- **7.4 orchestration**: `session.parent_session_id` (V11) plus four `@McpTool`
  beans (`OrchestrationMcpTools`) on the *same* in-process MCP server as memory
  (Spring AI autoconfigures exactly one server per app). This forced narrowing
  `allowedTools`' memory grant from the blanket `mcp__memory` server-level entry to
  the three read-only tool names (`mcp__memory__memory_{tags,search,read}`), so
  `spawn_child_session` on that same server flows through the normal
  tool-permission prompt instead of inheriting the pre-approval. Depth 1 (no
  grandchildren) and the per-parent `MAX_CHILDREN` cap are enforced inside the tool
  bodies, not by hiding tools per session — the server's tool list is static and
  application-wide. `report_result` journals `child_reported` on both sessions
  (`SessionEventBus.publish`, same journal-then-fan-out shape as `SessionService`'s
  private `record()`) and delivers the tagged summary into the parent's queue via
  the already-public `SessionService.sendUserMessage` — no bespoke wake logic
  needed, PARKED parents already transparently wake on enqueue. `GitWorktreeService.
  findRepos()`/`defaultBranch()` are shared between `list_services` and
  `MetaController`'s pre-existing (global-root) `/api/repo/services`, parameterized
  by `Path` so the tool can scan a *session's* `ecosystemPath` instead.

## 3d. Ecosystem service discovery (Phase 8)

Full design + decisions: `docs/plan/phase-8-service-discovery.md`.

- **Table (V12, `service_path`/`repo_path` split in V14)**: `service_profile`
  (`service_path` UNIQUE — the identity, a folder inside a git repo; `repo_path` — the
  git root, nullable until a discovery run fills it; name, description, `tags[]`,
  `last_commit_sha`, `vector(1024)` embedding + generated tsvector) — a DB-only cache,
  unlike memory's Markdown vault, since a service description is a disposable derived
  summary rather than curated durable fact.
- **Regeneration is gated on the last commit touching the service's own subtree**
  (`GitWorktreeService.lastCommitTouching`, `git log -1 -- <subtree>`), not `rev-parse
  HEAD` and not a re-read content hash of the digest inputs (`ServiceDiscoveryService.
  discover`) — a monorepo package's profile only regenerates when *that package's*
  files change, so an unrelated commit elsewhere in the same repo just bumps the
  timestamp (decision 6, phase-11-monorepo.md); for a polyrepo service the subtree is
  `.`, equivalent to `rev-parse HEAD`. An unchanged subtree costs one `git log` call,
  nothing more. Triggered at session close (`ServiceDiscoveryRequested` Spring event,
  carrying both `servicePath` and `repoPath`, same async-decoupling shape as 5.3's
  `ReflectionRequested`) when the profile is missing or older than
  `service-discovery.staleness-days` (default 14 days); no scheduled sweep — a "Scan
  ecosystem now" dashboard action and a per-service "Rediscover" button both force
  regeneration regardless of staleness (still skips the LLM call if the SHA is
  unchanged). Both `rediscover`/`updateDescription` and the close-triggered path
  validate a `servicePath` resolves to a repo (`GitWorktreeService.repoRootOf`) and is
  a known service (`isKnownService` — the repo root itself, or one of
  `findServices`'s results) before touching it.
- **The digest fed to the system turn is bounded and non-agentic**
  (`ServiceDigest.render`, `de.pamir.agentic.ui.discovery`): README/CLAUDE.md/AGENTS.md
  (capped per file), a manifest name+description sniff (`package.json`/`pom.xml`), and
  a depth-2 directory listing that skips noise dirs (`node_modules`, `.git`, `target`,
  `dist`, `build`) — same "backend reads a small bounded set of files itself" posture
  as the library's AI-fill, deliberately not an agentic exploration.
  `ServiceDiscoveryService.generate` runs one system-session turn
  (`SystemSessionService.runSystemTurn`, `service-discovery.model` setting, default haiku)
  asking for a JSON `{description, tags[]}`, best-effort embeds it (`EmbeddingClient`,
  same as memory/library), and upserts `service_profile`.
- **Agent-facing tools are on the same shared in-process MCP server** as memory/
  orchestration (`ServiceDiscoveryMcpTools`, decision 6): `service_description` (fetch
  by path), `find_service` (hybrid search by natural-language query, same
  dense+sparse+trigram RRF shape as memory search), `list_discovered_services`
  (overview of everything discovered so far). All three scope results to the calling
  session's own `ecosystemPath` — the same `GitWorktreeService.findServices()`
  visibility (git repos *or* monorepo packages, Phase 11) 7.4's `list_services` already
  uses — and self-gate on `service-discovery.enabled` independently of `memory.enabled`,
  since the two features toggle separately even though they share a server.
- **Human-facing**: `ServiceDiscoveryController` (`/api/service-discovery/services`,
  left-joined against every service under the ecosystem root — `findServices()`, so a
  monorepo's packages list individually — so never-discovered services still show up,
  `stale` computed from the same staleness setting) and a dashboard
  service browser (`ServiceDiscoveryDialog.tsx`) with Rediscover / manual hand-edit
  (writes the description/tags directly, no LLM call, still re-embeds and stamps the
  current commit SHA so auto-discovery treats it as an override until the repo's next
  commit — same "editing is a veto until content actually changes" posture as memory's
  hand-edited files).

Also shipped alongside Phase 8 (not part of its own decision log): **quick session
creation** — `QuickSessionDialog` (`q` hotkey) is a minimal service+ticket dialog; every
other field (model, permission mode, tools, MCP servers, skills, agents, instructions,
ecosystem) is copied from whichever session was created most recently, via the same
`SessionService.lastSessionConfig()` (delegating to `SessionConfigFactory.
configOverridesFrom()`) snapshot logic the
per-session Duplicate button already used. Ticket import reuses the full New Session
dialog's Linear flow (fetch by ref, or browse tickets assigned to the user); a resolved
`recommendedModel` overrides the copied model when it's a valid Claude alias. Falls back
to pointing at the full New Session dialog when ticket import isn't configured, since
skipping the ticket-driven fields is the whole point of the shortcut.

## 3e. Codex CLI provider adapter (5.13)

The proof of provider-agnosticism: `sidecar-codex/` speaks `codex app-server`'s
JSON-RPC-over-stdio protocol (not `codex exec`, which is non-interactive and can't do
the approval round-trip), translated to the same adapter protocol v1 `sidecar/` speaks —
registered under `artus.providers.codex`, with the dashboard needing zero code that
branches on the provider name, only on announced capabilities. As of Phase 10's R1, the
**backend** doesn't either: `SidecarManager.buildArgs`, `SessionConfigFactory.prepare`,
and `SessionService.applyEstimatedCost` all branch on a `ProviderCapabilities` record
(`unsupportedSessionFields`/`contextDirs`/`reportsCostUsd`, alongside the
already-existing `permissionModes` etc.) loaded by `ProviderCatalog` from each adapter
package's own committed, build-generated `capabilities.json` — no `if
("codex".equals(provider))` branch remains anywhere in that path. Reduced capability set
vs. Claude (no plan mode, no `acceptEdits`, no custom agents — confirmed no Codex
equivalent exists for the last one, not just deferred); skills and MCP are supported
(skills via `skills/extraRoots/set` pointing at the same materialized `.claude/skills/`
Claude sessions use; MCP via `thread/start`'s `config.mcp_servers`, bearer tokens passed
as a named env var on the spawned child rather than an inline header). Full capability/
permission-mode mapping, rationale, and live-confirmed protocol quirks:
`docs/plan/phase-5.13-codex-provider.md`; the capability-declaration mechanism itself:
`docs/plan/phase-10-review-followups.md` R1; operational details (build step, cost
estimation, skills/MCP follow-up): CLAUDE.md's "Codex provider adapter" section.

## 3f. Transcript export & usage dashboard

Two small standalone features, no design doc of their own:

- **Transcript export** (5.9): `GET /api/sessions/{id}/export.md` (`SessionController`)
  renders the journal via `TranscriptDigest.renderMarkdown()` — user/assistant turns
  with timestamps, collapsed tool-call summaries, reflection events, a cost+model
  footer — as `text/markdown` with a `Content-Disposition` filename. The dashboard has
  no kebab menu (actions are inline header buttons), so the download is a ⬇ button
  there instead: since a bearer-token API response can't be linked to directly from
  `<a href>`, the frontend fetches the text itself and saves it via a `Blob` + temporary
  anchor, same shape as any other authenticated action.
- **Usage dashboard** (5.12): `GET /api/usage?months=N` (`UsageController`, default 6,
  capped at 24) returns per-turn `{sessionId, sessionName, ts, model, costUsd}` rows
  straight from `EventJournal.usageSince()`; the dashboard (`UsageDashboard.tsx`) buckets
  and renders them as a plain-SVG bar chart — no charting library needed. A bonus beyond
  the original sketch: `GET /api/usage/stale-sessions` surfaces PARKED/CRASHED/FAILED
  sessions whose worktree has sat untouched for 3+ days, for manual cleanup.

## 3g. Code intelligence: Serena / graphify / CodeGraph one-of (Phases 12B, 13, 14)

Three MCP-served "stop grepping, ask the tool" integrations, deliberately **one per
install** (`mcp.code-intel ∈ {none, serena, graphify, codegraph}`, Settings → "MCP
servers"; docs/plan/phase-13-graphify.md decision 1, phase-14-codegraph.md decision 10):
each spawns a process per session and wants a competing "use me first" system-prompt
nudge. Sessions carry one flag (`codeIntelEnabled`, with phase 12's `serenaEnabled` still
accepted as an alias) that `SessionConfigFactory.prepare` resolves to the selected tool
and records as `session.code_intel` (`'serena'`/`'graphify'`/`'codegraph'`/NULL, V17) —
recorded per session because the MCP entry is baked into `mcp_config` at creation, so
flipping the selector later never changes what a live session's chip says.
`withDefaultCodeIntelMcp` layers the matching server with the same merge rule as
Linear/memory (the session's own key wins); `codeIntelSystemPromptBlock` appends Serena's
Claude-Code-only override, or graphify's/CodeGraph's provider-neutral block
(`GraphifyService.SYSTEM_PROMPT_BLOCK` / `CodegraphService.SYSTEM_PROMPT_BLOCK`, both
providers).

- **Serena** (phase 12 Track B): a *live* language-server view — `SerenaService` holds the
  root/uv-path settings + validation; the entry is `uv run --directory <root> serena
  start-mcp-server --context <ProviderCapabilities.serenaContext> --project <cwdPath>`;
  `SidecarManager` sets `MCP_TIMEOUT=300000` for it (cold language-server download).
- **graphify** (phase 13): a *pre-built* structural map — `GraphifyService` owns settings +
  validation (the `--version` probe on save doubles as the first `uv` env sync, 180 s
  budget), the invariant process prefix (`uv run --directory <root> --no-dev --extra mcp
  --extra sql`), and the **build pipeline**: `build()` right after the worktree exists
  (`SessionService.create`, async — the MCP server starts before the graph exists and its
  tools return "graph.json not found" until it appears), `refreshAfterTurn()` from the
  `turn_complete` housekeeping block (single-flight per session + a dirty flag, so turns
  finishing mid-build coalesce into exactly one more run), `ensureBuilt()` on resume/wake
  (READY iff the graph file survived the restart, else a build), `delete()` on both close
  paths and from `MaintenanceController.clean()`'s orphan sweep. Every run is `graphify
  update <cwdPath>` (AST-only by construction — no semantic pass, no API key, nothing
  leaves the machine) with `GRAPHIFY_OUT=<worktree-root>/.graphify/<id>` (outside the
  worktree, dot-prefixed so the orphan scan skips it), `GRAPHIFY_VIZ_NODE_LIMIT=0` (no
  `graph.html` — it loads vis-network from unpkg), stdout/stderr appended to
  `logs/graphify/<id>.log`, a 15 min hard cap, on a 2-thread daemon executor. Status is
  in-memory (`BuildState`) plus a journaled `code_intel_status {tool, status, nodes?,
  edges?, durationMs?, message?}` per transition (docs/PROTOCOL.md) — no column — which
  the store reduces into `SessionView.codeIntelStatus` for the widget chip (`.pulse`
  while BUILDING, `--red` on FAILED) and one transcript line per outcome. A FAILED build
  never fails the session; the next turn retries. Security posture from the pre-phase
  review (never graphify's skill/`install`/hooks/semantic backend): decision 14 in the
  phase doc. Measured: this repo builds in ~11 s from empty and ~7 s on refresh with the
  worktree on ext4 (the ~80 s spike figure was a DrvFS artefact).
- **CodeGraph** (phase 14): a *pre-built, self-refreshing* graph — `CodegraphService`
  follows `SerenaService`'s shape (no build-state machine; a synchronous call, not
  graphify's executor) since codegraph needs no refresh pipeline of its own. `index()`
  runs `node <root>/dist/bin/codegraph.js init <cwdPath> --yes` **synchronously inside
  `SessionService.create()`**, right before `writeMcpConfig` (so the `codegraph` MCP
  entry it writes never points at an unindexed directory), 5 min hard cap, journals
  `code_intel_status` BUILDING/READY/FAILED like graphify (counts parsed from init's `N
  nodes, M edges` output) but only once — never fails session creation, a FAILED chip
  just means "use Read/Grep, the index is unavailable". `ensureIndexed()` (resume/wake)
  re-runs `index()` only when `<cwdPath>/.codegraph/codegraph.db` is missing; otherwise
  it trusts the tool's own file watcher and startup catch-up sync to have kept the graph
  current — no `refreshAfterTurn` equivalent exists. `.codegraph/` is forced **inside**
  the worktree by the tool itself (unlike graphify's `GRAPHIFY_OUT`), so it needs an
  `info/exclude` line the same way `.serena/` does; nothing to clean up on close, the
  worktree removal takes it with it (no `MaintenanceController` orphan sweep, unlike
  graphify's). Posture from the pre-phase security review: only `init`, `serve --mcp`
  and `version` are ever run, from a reviewed local checkout on the backend's own
  `node` (no `uv`), with telemetry/update-check/the shared daemon off on every process
  (`CodegraphService.postureEnv()`); never codegraph's own installer, upgrade, prompt
  hook or git hooks. Full design: docs/plan/phase-14-codegraph.md.

## 3h. Review sessions (Phase 15)

Full design + decisions: `docs/plan/phase-15-review-sessions.md`. `session.session_type`
(V18, `'development'`/`'review'`, default `'development'`) makes the session's role
explicit rather than approximated by hand (checking out a branch and talking the agent
through `gh`).

- **Target picking is PR-first**: `GET /api/repo/prs` (`GitOpsService.listOpenPrs`, `gh pr
  list --json …`) backs a create-dialog picker; picking a PR derives `branch`/`baseBranch`
  from its head/base and attaches `prUrl` at creation. A plain-branch fallback stays
  possible (`MetaController.branches(..., remote=true)` adds remote-tracking branches for
  that picker only — the ordinary dev-flow branch input/datalist is untouched); the PR is
  then resolved lazily (`GitOpsService.resolvePr`, `gh pr view <branch>`) the first time
  `submit_pr_review` needs one, attaching it onto the session at that point.
- **Detached checkout, not a branch checkout**: `GitWorktreeService.createReviewWorktree`
  (`git fetch origin <branch>` best-effort, then `git worktree add --detach <path> <tip>`,
  `origin/<branch>` if the fetch succeeded else the local `<branch>`) — sidesteps `worktree
  add`'s hard failure when the branch is already checked out elsewhere (the likely reviewer
  scenario: a live development session in this app owns it) and makes "the reviewed branch
  ref is never moved by us" structural rather than promised. Both this and `resolvePr`
  validate the branch name (reject a leading `-`/anything outside a normal branch charset)
  and pass `--` ahead of it in the `git`/`gh` argv, since a PR's `headRefName` is
  GitHub-controlled input reaching a subprocess's argv, unlike a locally-typed dev branch.
- **Blocked git-mutating surface, not a read-only worktree**: `GitSessionController`'s
  existing `worktree(id, forWrite)` guard (shared by commit/push/PR) now also rejects a
  review session outright (409, before its RUNNING/WAITING_INPUT check even runs); `close
  (dirtyMode: "commit")` is refused the same way in `SessionService`. The worktree itself
  stays fully writable — scratch notes, running a build/test suite as part of the review —
  the write boundary that changed for phase 11 (`--writable-root`) is orthogonal to this.
- **`submit_pr_review`** (`ReviewMcpTools`, same in-process MCP server as
  memory/orchestration, gated on `session.sessionType == "review"`): one `gh api
  repos/{owner}/{repo}/pulls/{n}/reviews` call, payload (event/body/comments) sent whole
  over stdin (`--input -`) since `-f` flags can't express the comments array — GitHub
  validates every inline comment's path/line against the diff atomically, so a 422 rejects
  the whole review and its body is surfaced as the tool error verbatim. Deliberately **not**
  in `allowedTools`' pre-approval list (unlike the three read-only memory tools) — the
  normal tool-permission prompt is the human gate the whole design relies on instead of any
  sidecar-level enforcement. Journals `pr_review_submitted {event, commentCount, prUrl}`.
- **`sessionType` is identity, not tunable config** — a top-level `CreateOptions` field
  (like name/branch/repo), never emitted by `configOverridesFrom`, so a quick session
  created after a review session doesn't silently inherit `review` via
  `lastSessionConfig`. `duplicate()` and a template's own `sessionType` config key both
  carry it explicitly instead.

## 3i. Slack mirror (Phase 18)

Opt-in per session (`session.slack_enabled`, merged-config key `slackEnabled`, default from the
`slack.default-enabled` setting). Everything lives in `de.pamir.agentic.ui.slack`; with
`artus.slack.enabled=false` (default) none of these beans exist. Setup and operations:
`docs/SLACK.md`; decisions and the live-test log: `docs/plan/phase-18-slack.md`.

```
Slack  <==Socket Mode (JDK WebSocket)==>  SlackSocket -> SlackEnvelopeProcessor -> SlackInbound -> SlackSessionControl -> SessionService
                                                          (ack, dedupe, order)      (allowlist,                              (same ops as
Slack  <==Web API (RestClient)==  SlackWebApi <- SlackOutbox <- SlackSessionMirror <- SessionEventBus (all sessions)        the dashboard)
                                       (rate limit, retry)   (journal events -> posts)
```

- **Outbound.** `SessionEventBus.subscribeAll` feeds `SlackSessionMirror`, which drops everything but ten
  event types and handles the rest on one thread, so a session's posts are enqueued in journal order. The
  parent message is created lazily on a session's first event (retried by the next event after a 30 s
  cooldown if the post failed), its `(channel, ts)` persisted via `SessionRepository.attachSlackThread`,
  and edited in place at most every 10 s. Replies are a curated set, not the transcript. Children post into
  their *parent's* thread and follow the parent's opt-in; the system session never posts. `SlackOutbox` is the
  only thing that talks to Slack: one worker, >= 1.1 s between calls, retry with `Retry-After`, a bounded FIFO
  that sheds the oldest non-critical post first (permission/plan/question prompts are never dropped) and
  per-session coalesced parent updates that render at send time.
- **Inbound.** `SlackSocket` keeps one Socket Mode connection (backoff reconnect, own pings, silence
  watchdog). `SlackEnvelopeProcessor` acks first, de-duplicates on `event_id` / `trigger_id + action_ts` (it outlives
  connections, so a redelivery after a reconnect is still a duplicate) and hands events over one at a time, in order.
  `SlackInbound` applies the allowlist to messages **and** clicks (empty = nobody; WARN with the user ID only),
  requires the configured channel, a plain user reply in a known thread, and a mirrored session, then calls the
  same operations as the dashboard through `SlackSessionControl` (answer/approve/deny, prompt, interrupt).
- **Starting sessions** (`ARTUS_SLACK_START_ENABLED`). `SlackInbound` turns an `app_mention` from an allowlisted
  user in the configured channel into a `SlackStartCommand` (`review <PR>` / `dev <service> <branch> <prompt…>`) and hands
  it to `SlackCommandHandler` on its own virtual thread (creating a session takes seconds; the single inbound thread must
  not wait). The handler decides — which clone a PR belongs to (`origin` slug via `GitOpsService.originSlug`, matched
  case-insensitively against `ServiceCatalog`, the same service list the dashboard uses; in a monorepo the changed files pick
  exactly one package or the command errors), open and not-from-a-fork, service-name matching, branch sanity — and calls
  `SlackSessionStarter` (adapter: `SessionServiceSlackStarter`), which creates the session like the dashboard quick start
  (inherits `lastSessionConfig`, `slackEnabled` forced on, base branch synced) and sends the prompt at once. The mention is
  de-duplicated twice (envelope id, message ts), and a thread reply that mentions the bot is a command, never a session prompt.
  `review-with` / `dev-with <template> …` start the same way from a session template (unique name, exact or unique prefix,
  quoted when it has spaces): the template wins over the inherited last-session settings key by key and brings its own
  skills/agents and base branch (the PR's base for a review), and its kickoff prompt is blanked — exactly the dashboard quick
  start (`SessionServiceSlackStarter.buildOverrides`). A `dev` request without a prompt is remembered in `SlackPendingStarts`
  (15 min, asker only) and the next reply in that thread answers it.
- **Close.** The parent card's **Close** button (with a confirmation) calls `SlackSessionControl.closeClean` = `SessionService.close(id, "fail", null)`:
  it only ever closes a clean worktree (`DirtyWorktreeException` leaves everything untouched and the files are listed in the thread) and never
  commits, stashes or discards. `SlackInbound` refuses first — while a turn runs or waits, or live children exist — and says so privately and
  in the thread; the close itself runs on the command handler's executor because it takes seconds.
- **Who decides first.** `session/PendingPermissions` (in memory, owned by `SessionService`) records each open
  permission/plan/question request *before* it is journaled; `respondPermissionIfPending` delivers a decision only if
  the request is still open, so a Slack click after a dashboard decision (or a second click) gets a private
  "already decided" instead of a second response. The reverse direction is the mirror: the `permission_response`
  event rewrites the Slack prompt ("Allowed on the dashboard" / "Allowed by @user"). `source`/`slackUser` are
  journaled with the response and stripped before it reaches the adapter; prompts and Stop add a `slack_input`
  audit event.
- **Secrets.** `SlackRedactor` masks token shapes, `Authorization`/`Bearer`, URL credentials and `NAME=value`
  pairs in Bash commands and message excerpts; `SlackToolSummary` shows a file edit as its path only and never
  content or `envVars`. `slack.verbose` turns masking off. Tokens never appear in logs, exceptions or `toString`.
- **Failure model.** Slack failures never reach a session: everything is queued and every handler swallows and
  logs. Events missed while disconnected are not replayed (Slack does not replay them); the parent's **Status**
  button is the recovery path.

## 3j. Visualize (Phase 22)

A session's code logic as a Mermaid diagram, on demand. `VisualizeService` builds one prompt from
`ChangesService.unifiedDiff` (merge-base..working tree, untracked files as `+` blocks, binary stubs, source ranked
before lockfiles/generated output, hard char cap with an "N files omitted" trailer) and the last ~20k chars of
`TranscriptDigest.render`, and runs it as one system turn on `SystemTurnLane.VISUALIZE` (model from the
`visualize.model` tier via `ModelCatalog`, 90 s). The reply envelope (`TYPE:` / `WHY:` / a ```` ```mermaid ```` fence)
is parsed leniently and journaled as `diagram_generated {diagramType, mermaid, rationale?, fingerprint, repaired}`;
the latest one wins (a reply whose diagram would overflow the journal payload cap is refused with a readable error
instead of being journaled as a truncation stub). The fingerprint is the SHA-256 of the *complete* diff, so
`GET …/visualize` reports `stale` when the branch moved on; since status is polled (focus + 15 s per open view), the
fingerprint is cached behind `ChangesService.changeSignature` — a cheap HEAD + `git status` + mtime/size hash — and
only recomputed when that signature moves. The backend never parses Mermaid: the frontend renders with `beautiful-mermaid` (lazy chunk) and,
if that throws, asks `POST …/visualize/repair` once before showing the raw source. Lane table:

| Lane | Session name | Lock wait | Warm-up |
|---|---|---|---|
| `INTERACTIVE` | `system-interactive` | 20 s, then "busy" | after each turn + at startup |
| `BACKGROUND` | `system-background` | the caller's own timeout | none |
| `VISUALIZE` | `system-visualize` | 20 s, then "busy" (only vs. another diagram request) | none |

## 3k. Closed-session history (Phase 23)

`session.closed_at` (V21) is set by `SessionRepository.markClosed` from `SessionService.transition(id, CLOSED)`;
`updated_at` can't serve because PR polling, reflection and retention bump it. `GET /api/sessions/history`
reads `findRecentlyClosed` (no full-table scan). The dashboard fetches on mount, on `refresh()` and in
`onClosed`; the sidebar's History section shows `selectHistory(...)` (top 5, minus ids hidden in
`localStorage` `artus.hiddenHistory`) and is absent in the rail fold. Clicking a row opens the read-only
`ClosedSessionDialog` (transcript rebuilt with `transcriptFromEvents`, `Transcript` without `onPermission`);
"Continue from this" mounts `CreateSessionDialog` with `continueFrom`, reusing the phase 7.3 handoff path.

## 4. Backlog implementation sketches (remaining: 5.4, 5.6–5.8, 5.10–5.11)

### 5.4 Templates v2 — remaining gap
Shipped: "duplicate session" action (`POST /api/sessions/{id}/duplicate` —
`SessionService.duplicate` (delegating to `SessionConfigFactory.configOverridesFrom`)
snapshots the source session's config
onto a fresh branch; also backs the quick-session flow, §3d). Template config already
carries every session field. Missing: per-template default base branch + per-service
default template (add a `service_path` column to `session_template`, dialog picks the
matching template automatically).

### 5.6 Mobile / PWA
Single-column stack under 700px (CSS only), sticky input bar, `manifest.json` +
minimal service worker for installability. Notifications already work; consider Web
Push later (needs a push service — non-trivial, defer).

### 5.7 Multi-repo — remaining gaps
Shipped: per-session service picker (repos under `ecosystem-root`) + editable
ecosystem path. Missing: multiple ecosystem roots (make `ecosystem-root` a list;
`/api/repo/services` merges), repos outside any root (free-form path input with
validation — backend already validates any `repoPath`), per-service defaults (see 5.4).

### 5.8 Per-session hooks / settings injection
Extend AssetProvisioningService: template config key `settings` (JSONB) written to
`<worktree>/.claude/settings.local.json` (local layer avoids colliding with a
committed `settings.json`). Needs nothing from the sidecar — the SDK already loads
project settings. Guard: refuse if the repo tracks `settings.local.json`.

### 5.10 Turn checkpoints & rewind
On `turn_complete`, if the worktree is dirty: `git add -A && git commit` onto a
ref `refs/artus/<session>/turn-<n>` (commit on the branch, then update-ref;
or plain branch commits with a tag-like ref). "Rewind" = `git reset --hard <ref>`
(refuse while RUNNING). The transcript's turn footers become rewind anchors
(`checkpoint` event carries the ref). Interacts with close-dirty flow: checkpointed
turns are already committed, so close becomes cleaner too.

### 5.11 Prompt fan-out
`POST /api/sessions/fanout {prompts×1, branches×N, template}` → N create calls
(the create endpoint needs no change). UI: a compare view rendering N widgets
side-by-side with a diff summary per session (reuse `git/diff`). Defer the fancy
diff-compare grid; a "fan out" checkbox in the create dialog + naming convention
(`branch-1..N`) is a good first cut.

## 5. Operational notes

Limits/caps and env vars: see CLAUDE.md "Limits & caps". Logs: `logs/artus.log`,
`logs/error.log`, `logs/sidecar/<sessionId>.log`. Auth: bearer token everywhere
(REST header, WS subprotocol) unless switched off in Settings (read at startup); with it off,
`LocalClientFilter` accepts only trusted client addresses / allowed Host / Origin (phase 17 B).
Metrics: `artus.sessions.active|parked` via `/actuator/metrics` (token-gated).
