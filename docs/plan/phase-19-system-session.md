# Phase 19 — System session refactor: per-lane stateless workers

Status: **landed (2026-10-17)** (phase 22 later added a third lane, `VISUALIZE` / `system-visualize`), all five steps; live manual script partly run (see log). Backend-heavy, one small frontend
touch (queue copy in the ticket dialogs); no schema migration.

## Background

Two problems with the singleton system session, both structural:

1. **"system session is busy" on quick session start.** `SystemSessionService` serializes every
   backend-initiated turn behind one binary semaphore. INTERACTIVE callers (ticket list/import,
   git-assist, handoff, library AI-fill) wait only `INTERACTIVE_LOCK_WAIT` (10 s) and then throw;
   BACKGROUND callers hold the lock for up to their full timeout — **reflection 3 min**, service
   discovery 60 s, auto-title 60 s. The timing is adversarial by design: reflection fires when a
   session closes, auto-title after a new session's first turn — exactly the moments the quick
   dialog tends to open next. The frontend's 50 s abort means "wait longer" cannot ride out a
   reflection inside one HTTP request.
2. **Unbounded context growth.** The system session is one endless conversation; every ticket
   JSON, reflection transcript digest, library fill and title request appends forever. Nothing
   trims it: compaction is user-triggered and the system session is hidden from the dashboard.
   Every turn re-pays input tokens for all prior unrelated tasks, and stale blocks (old ticket
   JSON shapes in particular) bleed into new answers, which `SystemTurnClient.json` then fails
   to parse. The key observation: **every system-turn prompt is self-contained** — no task reads
   anything from a previous turn, so the conversation history is pure cost.

## Confirmed decisions (2026-10-17)

1. **Two system sessions, one per `SystemTurnLane`** (`system-interactive`,
   `system-background`). Reflection/discovery/auto-title can never block ticket picking again.
   System sessions are already exempt from `AGENTIC_UI_MAX_SESSIONS`
   (`SessionService.enforceSessionLimit`), run the cheap tier, and idle-park after 30 min — the
   second one is nearly free. Rejected alternatives: preemption via `interrupt` (more machinery,
   wasted tokens, keeps one shared context) and a single queue with a longer wait (can't fit a
   3-min reflection inside the HTTP/frontend budgets).
2. **Fresh conversation per task.** After every completed (or failed) system turn the worker
   recycles: terminate the sidecar, clear `provider_session_id`, park, re-warm in the
   background. `SidecarManager.buildArgs` only passes `--resume` when `providerSessionId` is
   non-null, so the next wake starts a clean conversation with **zero sidecar changes**. Prompt
   caching still hits the identical system-prompt/tool-schema prefix across conversations.
   Rejected: threshold-triggered compact (pays summarization turns to preserve memory that is
   worthless by construction).
3. **Queue UX instead of an instant error** inside the interactive lane:
   `INTERACTIVE_LOCK_WAIT` 10 s → 20 s (contention is now interactive-vs-interactive only —
   rare and 45 s-capped), the ticket dialogs show honest "another assistant task is
   finishing…" copy while queued, and the frontend safety nets are raised to cover the real
   worst case instead of firing before the backend's own deadlines do.
4. **Direct Linear API fast path** when `AGENTIC_UI_LINEAR_API_KEY` is set: ticket **list** via
   one GraphQL call (~1 s, deterministic, no lock, no JSON-parse flakiness), and ticket
   **import** fetches the issue + comments via GraphQL and inlines them into the Haiku prompt,
   so the import turn needs no MCP tool round trips. OAuth mode (credential lives inside the
   `claude` CLI) keeps the system-turn/MCP path unchanged; any direct-API failure falls back to
   the system-turn path with a WARN.
5. **Both sessions keep `kind = 'system'`**, discriminated by `name`. Every existing
   special case (session-limit exemption, dashboard filter, discovery exclusion, close/park
   rules) keys on `kind` and applies to both untouched. A leftover pre-phase-19 row named
   `system` is retired at startup (see Shape).

## Shape

### Per-lane workers

- `SystemSessionService` grows a small private `SystemWorker` per lane, each owning what is
  today class-level singleton state: the fair binary semaphore, `pendingSystemTurn`,
  `pendingSystemText`, `pendingSystemTurnSessionId`, and its session row (found by
  `findSystemSession(name)` — the existing `kind = 'system'` query plus a `name` predicate).
- `runSystemTurn(prompt, modelOverride, lane, timeout)` picks the worker by `lane`; everything
  downstream (revival budget, `turnExecutor` end-to-end bounding, model-override revert) is
  unchanged in logic, just per-worker. The lane names stay as they are:
  INTERACTIVE = ticket list/import, git-assist, handoff, library AI-fill;
  BACKGROUND = reflection, service discovery, auto-title.
- The sidecar-event hooks (`onAssistantMessage`/`completeTurn`/`failTurn`) already guard by
  session id; they now route to whichever worker's pending turn matches.
- `warmUp()` warms the **interactive** worker (it's what sits in front of the dialogs);
  the background worker warms on first use. `isWarm()` reports the interactive worker.
- `createSystemSession(lane)` is today's method with the per-lane `name`; scratch dirs stay
  under `<worktree-root>/_system/<id>` (one per session, as now). `session.system-provider`,
  the cheap-tier model, Linear MCP pre-approval and the `Task`/`Agent` disallow apply to both.
- **Legacy adoption**: on startup (`SessionHousekeeping`'s existing `ApplicationReadyEvent`
  sweep), any non-terminal `kind = 'system'` row whose name is neither of the two new names is
  retired — orphan sidecar killed (the sweep already does this for LIVE states), state →
  CLOSED, scratch dir removed. No Flyway migration; `name` already exists.

### Recycling (fresh conversation per task)

- In the `turnExecutor` body, after the turn's future settles and the model override is
  reverted but **before releasing the worker's semaphore**: terminate the sidecar, set
  `provider_session_id = NULL` (`SessionRepository.updateProviderSessionId`), transition →
  PARKED. Then release, then fire-and-forget `warmUp()` on that worker (its `tryAcquire`
  no-ops if the next caller already grabbed the lock), so the next task usually finds a warm,
  empty session.
- Doing it before release means no caller can ever start a turn on the stale conversation;
  the added lock-held time is one `terminate` (5 s grace + 2 s force worst case, typically
  instant — the sidecar is idle, its turn just completed).
- Recycle failures are non-fatal: a session left CRASHED is revived by the existing
  `getOrCreateSystemSession` CRASHED → `resume` arm on the next turn (and `--resume` without a
  provider session id is a fresh start anyway).
- `context_usage` on the system sessions becomes a per-task number — nice for debugging, and
  the `ctx` threshold machinery never fires on them (they're not rendered anyway).
- Out of scope but noted: the system sessions' **journal** still grows (append-only, same id
  forever). `memory.retention-days` only prunes CLOSED-and-reflected sessions. Left alone this
  phase; see Out of scope.

### Queue UX (interactive lane)

- `INTERACTIVE_LOCK_WAIT` → 20 s. Post-split, the only thing an interactive caller can wait on
  is another interactive turn (45 s cap), so the "busy" error becomes rare and short-lived;
  when it still fires, the message stays as is.
- `GET /api/import/enabled` (`TicketImportController.EnabledResponse`) gains
  `busy: boolean` — whether the interactive worker's lock is currently held
  (`Semaphore.availablePermits() == 0`). The dialogs already call this endpoint on open; the
  ticket picker shows "finishing another assistant task…" instead of the generic spinner copy
  when `busy` was true, and keeps the existing copy otherwise. No polling loop — the flag just
  picks the honest wording for the wait the request is already making.
- Frontend safety nets in `useTicketImport.ts` (50 s) → **100 s**: they exist to guard a hung
  connection, not as UX timers — the backend always answers by lock-wait (20 s) + turn timeout
  (45 s) + `REVIVAL_BUDGET` (30 s). Today's 50 s can fire *before* a legitimately slow import
  resolves; after the change it can't.

### Direct Linear API (API-key mode only)

- New `integration/LinearApiClient`: `java.net.http.HttpClient` POST to
  `https://api.linear.app/graphql`, `Authorization: <key>` header, 10 s timeout, two queries:
  - **list**: viewer's assigned issues, `updatedAt` desc, first 20, excluding
    `state.type ∈ {completed, canceled}` → `{identifier, title, state { name }}` — exactly the
    `TicketSummary` fields;
  - **issue**: by identifier or URL-parsed id — title, description, state, labels, and comments
    (body + author + createdAt, oldest first).
- `TicketImportService.listMyTickets`: when the API key is set, serve the list from
  `LinearApiClient` (same 15-min `TicketCache`, same `cached` flag, `refresh` just re-queries —
  now cheap). OAuth mode and any client failure (after one WARN) fall back to the existing
  system-turn fetch, so behavior degrades to today's, never below it.
- `TicketImportService.fetchTicketImport`: when the API key is set, fetch the issue + comments
  via the client, inline them into the prompt (capped — description + comments truncated to
  ~20 k chars with a "…truncated" marker), and drop the "You have access to Linear via MCP
  tools. Fetch…" preamble — the turn becomes a single LLM call with no tool use: faster,
  cheaper, and immune to MCP flakiness. OAuth mode keeps today's prompt verbatim. The system
  sessions keep their Linear MCP config regardless (OAuth mode and the fallback path still
  need it).
- The key never leaves the backend process (it already doesn't — env var only, never
  persisted); the client is used by `TicketImportService` only.

### What deliberately does not change

- The NDJSON protocol, both sidecars, `SystemTurnClient`'s parse/fence/truncate pipeline, and
  every caller's prompt and timeout.
- Lane assignments and the BACKGROUND lock-wait semantics (full own-timeout wait).
- The 15-min ticket cache and prefetch-on-dialog-open (phase 12 A2/A3).
- Codex posture: no `allowedTools`/`disallowedTools` on a Codex system session, both lanes.

## Backend structure

- `session/SystemSessionService` — `SystemWorker` extraction, per-lane find-or-create, recycle,
  `warmUp`/`isWarm` on the interactive worker, `busy()` accessor.
- `session/SessionRepository` — `findSystemSession(String name)`; `updateProviderSessionId`
  accepts null (verify — it's a plain parameterized UPDATE).
- `session/SessionHousekeeping` — legacy `system` row retirement in the startup sweep.
- `integration/LinearApiClient` (new), `integration/TicketImportService` — fast paths + fallback.
- `web/TicketImportController` — `busy` field on `EnabledResponse`.
- `frontend/src/hooks/useTicketImport.ts`, `TicketPickerDialog` copy — queue wording, 100 s nets.

## Steps

### Step 1 — Per-lane workers
`SystemWorker` extraction, per-lane sessions/locks/pending state, event-hook routing by session
id, `findSystemSession(name)`, legacy-row retirement in the startup sweep, `warmUp`/`isWarm`
targeting the interactive worker. Tests: background turn in flight does not block an
interactive turn (two fake sidecars); interactive-vs-interactive still queues then errors at
the (new) wait; events route to the right worker; a legacy `system` row is retired at startup.

### Step 2 — Recycling
Recycle in the turn body's finally (terminate → clear provider id → PARK → release → re-warm),
CRASHED-revival unaffected. Tests: after a completed turn the row is PARKED with
`provider_session_id` NULL and the next turn spawns without `--resume` (assert via
`SidecarManager.buildArgs` or a captured spawn); a failed turn still recycles; the re-warm
no-ops when the next caller already holds the lock.

### Step 3 — Queue UX
`INTERACTIVE_LOCK_WAIT` 20 s, `busy` on `EnabledResponse`, dialog copy, 100 s client nets.
Tests: controller returns `busy` from the worker; hook/dialog snapshot for the queued copy.

### Step 4 — Linear API fast path
`LinearApiClient` (fake transport tests: list mapping, state filtering, comment ordering,
truncation cap, 401 vs 5xx), `TicketImportService` wiring: API-key list path, API-key import
prompt with inlined issue, OAuth path byte-identical to today, fallback on client failure.

### Step 5 — Docs
`CLAUDE.md` (system-session paragraph → two stateless per-lane sessions; Linear env-var row
mentions the direct-API fast path), `ARCHITECTURE.md`, decision log, phases table.

## Out of scope

- Preempting background turns (`interrupt`), worker pools beyond one per lane.
- Showing the system sessions in the dashboard.
- Pruning the system sessions' journal (grows slowly; revisit with retention work).
- Direct Linear API in OAuth mode (no usable credential outside the `claude` CLI).
- A second ticket provider (service naming already provider-neutral — unchanged).

## Definition of Done

- [ ] Closing a session (reflection running, up to 3 min) and immediately browsing tickets in
      the quick dialog returns the list — no "busy" error, two live system sidecars.
- [ ] Two concurrent interactive calls: the second waits (queued copy in the dialog) and
      succeeds within its budget; a third pathological 20 s+ collision still errors cleanly.
- [ ] After every system turn the worker's row is PARKED with `provider_session_id` NULL and
      the next spawn carries no `--resume`; each task starts from an empty context.
- [ ] A pre-phase-19 `system` row is retired at startup; the two named sessions are created on
      demand and never counted against `AGENTIC_UI_MAX_SESSIONS`.
- [ ] API-key mode: ticket list needs no system turn; import's system turn makes no MCP tool
      calls; OAuth mode behaves exactly as before; a dead Linear API falls back with a WARN.
- [ ] Codex as `session.system-provider` works on both lanes (no allowedTools, as today).
- [ ] `mvn clean verify`, both sidecars' `npm test` + `tsc`, frontend vitest + build pass;
      docs and the decision log are updated.

## Manual test script

1. Close a session with reflection enabled → within the 3-min reflection window, open the
   quick dialog and Browse tickets → list arrives; `ss`/logs show two system sidecars.
2. In the same window, import a ticket → import succeeds; the Needs-you/queue copy never
   appears unless a second *interactive* call overlaps.
3. Open two dialogs, Browse in both with an empty cache → the second shows "finishing another
   assistant task…" then resolves; no error.
4. `tail -f logs/agentic-ui.log` across two imports → after each turn: terminate + PARKED +
   fresh spawn without `--resume`; `SELECT provider_session_id FROM session WHERE kind='system'`
   is NULL between turns.
5. Start the backend against a DB holding an old `name='system'` row → startup log shows it
   retired (CLOSED), scratch dir gone; the two named rows appear on first use.
6. API-key mode: Browse with an empty cache → list in ~1 s, log shows the GraphQL call and no
   system turn. Import → system turn runs with the issue inlined (no `mcp__linear` tool use in
   the system session's sidecar log). Break the key (one wrong char) → WARN + fallback via the
   system turn; behavior matches today's.
7. OAuth mode (key unset, toggle on): Browse + import → system-turn path, prompts unchanged.
8. Set `session.system-provider` to `codex` → repeat 1–2; both lanes spawn codex sidecars.
9. Let both system sessions idle-park (or lower `AGENTIC_UI_IDLE_PARK_MINUTES`) → next import
   wakes the interactive one fresh; no resume errors.

## Implementation log

### 2026-10-17 — Steps 1–2 (per-lane workers + recycling)

- `SystemSessionService`: private `SystemWorker` per lane (lock, pending-turn fields, name
  `system-interactive` / `system-background`); `workerFor(sessionId)` routes sidecar events;
  `warmUp()`/`isWarm()` target the interactive worker. `SessionRepository.findSystemSession(name)`
  + `findLiveSystemSessions()`.
- Recycle runs in the turn body's `finally`, before the lock is released
  (`SessionService.recycleSystemSession`): **terminate first, outside the session lock**, then under
  it clear `provider_session_id` and PARK — so nothing from the dying sidecar can land after the
  clear (deviation from the spec's literal order, which cleared before terminating).
- **Deviation:** only the interactive worker re-warms after a recycle; the background worker stays
  parked until used (spec said "that worker", which would spawn an idle sidecar after every
  reflection/auto-title).
- **Addition:** `SessionHousekeeping.parkIdleSessions` no longer requires a provider session id for
  `kind='system'` — a re-warmed session has none until its first turn (`system_init`), so it would
  otherwise never idle-park.
- `SessionHousekeeping.retireLegacySystemSessions` (startup): live system rows with neither lane name
  are killed (orphan pid) and closed via the normal system-session close path.
- Tests: `SystemSessionServiceRunTurnTest` — background doesn't block interactive, event routing,
  recycled background row PARKED with null provider id and no handle, failed turn recycles +
  interactive re-warms, legacy row retired. Existing lock test now drives a `ready` event after the
  recycle. Full `mvn verify` tests: 629 run, 0 failures (jar repackage hit a clash with a concurrent
  Maven run; `package -DskipTests` is clean). Live manual script (items 1, 4, 5, 9) **not yet run**.

### 2026-10-17 — Steps 3–5 (queue UX, direct Linear API, docs)

- **Queue UX:** `INTERACTIVE_LOCK_WAIT` 20 s; `SystemSessionService.busy()`; `EnabledResponse.busy`;
  new `frontend/src/ticketCopy.ts` (`ticketWaitCopy(warm, busy)`, unit-tested) drives the
  "finishing another assistant task, then fetching from Linear…" wording in both create dialogs and
  `TicketPickerDialog` (its `warm` prop became `waitCopy`); `useTicketImport` safety nets 50 s → 100 s.
  **Deviation:** `busy` reports a *real turn* in flight (a `turnRunning` flag on the worker), not
  `availablePermits() == 0` — the lock is also held by the fire-and-forget warm-up that this very
  endpoint triggers, which would have shown "finishing another task" for a plain wake. No controller
  test (no ticket-controller test harness exists); the service-level `busyReportsAnInteractiveTurnInFlightOnly`
  covers the accessor.
- **Linear API:** new `integration/LinearApiClient` (JDK `HttpClient`, `Authorization: <key>`, 10 s,
  injectable `Transport`; `LinearApiException` with an `isAuth()` flag for 401/403). `listMyTickets`
  uses it when a key is set (same cache/`cached` flag); `fetchTicketImport` inlines issue + comments
  (cap 20 000 chars, `…truncated`) into a no-MCP prompt and sets the canonical `ticketRef` from the
  fetched identifier itself. Any `LinearApiException` → one WARN and today's system-turn path;
  no client / no key → byte-identical to before. "API-key mode" means the key is set, matching
  `SessionConfigFactory.linearMcpServer()` ("explicit key wins even if OAuth is also enabled").
- **Docs:** `CLAUDE.md` (provider paragraph + Linear env-var row), `docs/ARCHITECTURE.md` box line.
- Suites: backend `mvn test` 640 run / 0 failures; frontend vitest 80 + `tsc --noEmit` + `npm run build`
  green; sidecars untouched (not re-run). Live manual script (1–9) still to be run by the user.

### 2026-10-17 — live verification (API-key mode, real Linear)

- **Script 5 (legacy row):** startup log shows the old `name='system'` rows retired (CLOSED).
- **Script 6 (API-key list/import):** list with `refresh=true` returns in ~0.1 s with no system turn.
  Import first still made `mcp__linear` get_issue/list_comments calls — the inlined prompt told the
  model to "read the comment thread", and it went and fetched it (12–20 s, one 409 where it replied
  with a refusal-style non-JSON). **Fix:** the inlined prompt now opens with "complete Linear issue"
  and says "Do NOT call any tools". After the fix the sidecar log shows no tool-call deltas; imports
  take ~12–20 s of pure model time. A key with one extra char logs the 401 WARN for both list and
  import and falls back to the system-turn path, which still succeeds.
- **Script 3 (concurrent imports):** two simultaneous interactive imports — the second queued
  (~11 s), then ran; both HTTP 200, no "busy" error.
- **Script 4 (recycle):** between turns the interactive row is IDLE (re-warmed) with
  `provider_session_id` NULL; the log shows terminate → wake → fresh spawn after each import.
- **Not yet run:** 1 (close a session during reflection), 2, 7 (OAuth), 8 (Codex), 9 (idle-park).
