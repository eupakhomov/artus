# Phase 25 — User feedback: cost accounting, 1M context, live activity, hide from dashboard

Status: **implemented (2026-10-19)** — steps 1 and 3–6 built, unit/DB-tested and checked live in the browser
(manual steps 1, 4, 5, 7, 8; step 6 — spawned children — covered by unit tests only); step 2 parked (premise not
reproduced, see below).
Edit sites verified against the code as of commit `b65458c`.

## Background

First external user feedback (2026-10-19), validated against the code:

| # | Feedback | Verdict | Root cause |
|---|---|---|---|
| 1 | "Context fills very quick, not using Sonnet 1M" | accurate | Claude catalog offers only `haiku`/`sonnet`/`opus` (`ModelCatalog.CLAUDE_MODELS`, `CLAUDE_CAPABILITIES.models`); the create dialog is a fixed dropdown for Claude, no `[1m]` id and no `betas` option is ever passed |
| 2 | "Header cost says 13, step footer says 5" | **bug** | The SDK's `result.total_cost_usd` is a *running total per `query()`* ("read the latest result rather than summing across results" — `sdk.d.ts`), but `sidecar/src/session.ts:337` forwards it as the turn's `costUsd` and everything sums it: header `costToDate` (`store.ts:296`), budget enforcement and usage dashboard (`EventJournal.java:264`/`:289`), Slack (`SlackSessionMirror`), memory digest (`TranscriptDigest`). Running totals 1,2,4,5 → header ≈ 12, true total = last footer 5 |
| 3 | "Stopped, not sure why; model talked about sending something to a development agent" | accurate (two causes) | (a) Claude's own background work — `run_in_background` Bash and background subagents — is invisible: the sidecar drops `task_started`/`task_progress`/`task_notification`/`background_tasks_changed` (`session.ts` `case 'system'` → "unhandled"), so the turn ends, the widget shows IDLE, and work continues unseen; idle parking can even kill it after 30 min. (b) `spawn_child_session` children: `report_result` *does* wake the parent, but a child that stalls (permission prompt, crash, idle without reporting) leaves the parent silently waiting, and the parent widget has no list of its children |
| 4 | "Spent $31 with no visible progress; impossible to follow thinking/executing" | accurate | Tool rows are collapsed `<details>`; a subagent's whole activity is nested *inside* its collapsed Task row, so a long subagent shows one spinner; no live "what is it doing now" line; cost only updates at `turn_complete` (and was inflated by #2) |
| 5 | "Closed chats reappear on reload" | partly | Truly CLOSED sessions are already filtered (`Dashboard.tsx:87`). What reappears: every IDLE/PARKED session (the only way out is a real Close, which forces a worktree decision) and minimized widgets (minimize state is localStorage-only) |
| 6 | "Session name seems to change automatically" | accurate, by design | `AutoTitleService` renames once after the first completed turn while the name still equals the branch; nothing in the UI says so |
| 7 | "Lots of bash calls, not visible what it is doing" | accurate | same as #4 — most likely nested subagent Bash calls inside a collapsed Task row |

## Decisions (agreed 2026-10-19)

1. **Cost: the sidecar emits a per-turn delta.** `costUsd` on `turn_complete` keeps its
   documented meaning ("this turn's cost"); the Claude sidecar computes
   `delta = total_cost_usd − lastTotal` (`lastTotal` reset to 0 at process start; a total
   *lower* than `lastTotal` means the CLI reset it — `/clear`, crash result — so the delta is the
   total itself) and additionally forwards the raw running total as `sessionCostUsd` for
   debugging. No consumer changes needed beyond the footer label. Codex is untouched (its
   estimate is already per-turn).
2. **Historical rows are corrected by a migration**, not left inflated (budgets of live
   sessions are computed from them). `V22__turn_cost_delta.sql` rewrites
   `turn_complete.payload.costUsd` for Claude sessions with a window over `seq`, treating every
   `ready` event as a process boundary (verify `ready` is journaled before writing it — if not,
   fall back to "a total lower than the previous one starts a new run"), and keeps the original
   value as `payload.costUsdRaw` so the rewrite is reversible.
3. **1M context = catalog entries, not a beta flag.** Add `sonnet[1m]` (label "sonnet 1M",
   tier `standard`, listed *after* plain `sonnet` so `byTier("standard")` keeps resolving to the
   200k model for system turns) — and `opus[1m]` if step 2's probe shows the CLI accepts it. The
   `ctx N%` chip already reads the real window from `context_usage`, so nothing else changes.
   The dropdown shows a hint that long context is priced higher past 200k input tokens.
4. **Claude's own subagents stay inline — no separate windows.** A Task subagent runs inside
   the parent's sidecar process, shares its worktree and permission flow, and cannot be
   messaged, interrupted or closed on its own, so a widget for it would be a window you can
   only read. Instead it becomes *visible inline*:
   - a running Task row is **auto-expanded** and its summary line shows the latest nested
     activity (`Explore · Bash: npm test · 14 tools · 2m 10s`); collapses back when done;
   - the row's nested area is capped to the last ~8 items while running, with "show all";
   - a **pop-out** button on a Task row opens its nested transcript in a read-only dialog
     (reuses `Transcript`) for when you want to read a long subagent run in full.
   Only `spawn_child_session` children (real sessions with their own worktree) get widgets,
   as today.
5. **Background tasks get a widget strip.** The sidecar translates the SDK task events into
   one provider-neutral `background_tasks {tasks:[{id, kind, description, toolUseId?}]}`
   level event (replace semantics, from `background_tasks_changed`; reset to `[]` on `ready`)
   plus `task_progress {taskId, toolUseId?, description, lastTool?, toolUses, tokens,
   durationMs}` and `task_done {taskId, toolUseId?, status, summary}`. The widget shows a
   strip under the header while the set is non-empty ("2 running in background: tests ·
   explore auth"), and the status chip reads `IDLE · 2 bg` instead of plain IDLE.
   **Idle parking skips a session with a non-empty background set** (`SessionHousekeeping`),
   so we never kill background work.
6. **Spawned children are visible from the parent.** Spawn journals a `child_spawned
   {childId, childName, service}` event on the parent (rendered as a transcript note with a
   "go to" link); the parent widget gets a **children chip** (`3 children · 1 running`) whose
   popover lists each child's live state/cost with jump-to. `child_reported` (already
   journaled on both sides) is rendered as a note instead of only the raw injected user
   message. A child entering `WAITING_INPUT` or `ERROR` while its parent is waiting adds a
   one-line note on the parent ("child X is waiting for approval") — no auto-message to the
   model, the human decides.
7. **Live activity line + running turn cost.** While RUNNING the widget shows a sticky line
   above the composer: `▶ Bash: npm test · turn 3m 12s · 21 tools · ~$0.84`. Tool/elapsed
   come from the existing tool events in the store. The cost is an **estimate**: the sidecar
   emits `turn_usage {model, input, output, cacheRead, cacheWrite}` after each assistant
   message (the `assistant` SDK message carries `message.usage`), the backend prices it with a
   new settings-editable Claude price table (`claude.pricing`, same JSON shape as
   `codex.pricing`) and fans out `estimatedTurnCostUsd`. Shown with `~`; replaced by the
   authoritative delta at `turn_complete`. The header cost chip shows
   `$12.30 + ~$0.84` while a turn runs.
8. **Hide from dashboard is a per-session server-side flag.** New `session.hidden boolean NOT
   NULL DEFAULT false` (`V23__session_hidden.sql`), `PATCH /api/sessions/{id} {hidden}`.
   Hidden sessions get no widget and are listed in a collapsed **Hidden** sidebar section
   (click = unhide + focus). A hidden session stays fully alive (resumable, Slack, children).
   It **auto-unhides** when it needs the user (permission request / ask-user / error) and
   always shows in "Needs you". Widget header gets a hide button (`EyeOff`-style role icon in
   `icons.ts`, hotkey `h` if free); minimize stays as-is (local, layout-only).
   A Settings → Sessions toggle **"Hide sessions when they park"** (`session.hide-on-park`,
   default off) covers the "don't bring old chats back" case without manual clicks.
9. **Auto-title stays, but is visible and switchable.** `AutoTitleService` already journals
   the rename with `auto: true`; the transcript renders it as a note ("renamed from
   `feat/x` → *Fix login redirect* (auto)") and the name's tooltip says it was auto-generated.
   New setting `session.auto-title` (default on) in Settings → Sessions. A manual rename
   always wins (already the case: the guard is `name == branch`).

## Steps

### Step 1 — Cost accounting (decisions 1–2)
- `sidecar/src/session.ts`: module-level `lastTotalCost`, delta computation in the `result`
  case, `sessionCostUsd` field; `protocol.ts` doc on `TurnComplete.costUsd` (+ sync copy in
  `sidecar-codex/src/protocol.ts`, `check-protocol-sync` must pass).
- `V22__turn_cost_delta.sql` (above). DB test with a fixture journal: two runs, totals
  `1,2,4,5 | 0.5,1.5` → deltas `1,1,2,1 | 0.5,1`.
  *Done:* `ready` is journaled (every sidecar event goes through `record`), so it is the run
  boundary. The migration skips rows that already carry `costUsdRaw`/`sessionCostUsd`, which makes
  it idempotent and lets `EventJournalDbTest` re-run the file on fixture rows. Demo fixture
  `04-release-1-2-prep.json` had the inflated value baked in (two turns in one run, header $0.43
  vs real $0.24) and was patched by hand the same way.
- Footer label: `$0.0123 this turn`; header tooltip unchanged.
- Tests: sidecar vitest for delta/reset; store test that header == sum of footers.

### Step 2 — 1M context models (decision 3) — **parked, premise not reproduced**

Probe (2026-10-19, CLI 2.1.303 bundled with the SDK we ship, direct Anthropic auth, one
`Reply with just: ok` turn through `node sidecar/dist/index.js --model <m>`):

| `--model` | resolved | `context_usage.window` |
|---|---|---|
| `sonnet` | `claude-sonnet-5` | 967,000 |
| `sonnet[1m]` | `claude-sonnet-5[1m]` | 967,000 |
| `opus` | `claude-opus-5` | 1,000,000 |
| `opus[1m]` | `claude-opus-5[1m]` | 1,000,000 |
| `haiku` | `claude-haiku-4-5-20251001` | 200,000 |

So the plain aliases already get the long window; `[1m]` entries would be duplicates. The
reporter's "fills quickly" must come from something else — candidates: a `haiku` session, a
gateway (LiteLLM etc.) mapping `sonnet` to an older 200k model, or an older install. Next: ask
for the `ctx` chip tooltip (window + breakdown) from an affected session before changing code.
Original plan kept below for reference.

- Probe first: start a session with model `sonnet[1m]` (and `opus[1m]`) via
  `npm run drive`, confirm `context_usage.window` reports ~1,000,000 and the turn succeeds.
- `ModelCatalog.CLAUDE_MODELS` + `CLAUDE_CAPABILITIES.models` entries; `ModelSelect` hint.
- Test: `ModelCatalogTest` — `byTier("claude","standard")` is still `sonnet`.

### Step 3 — Inline activity: Task rows, live line, running cost (decisions 4, 7)
- Sidecar: `turn_usage` from `assistant` messages; `task_progress` translation.
- Backend: `ClaudeCostEstimator` (mirror of `CodexCostEstimator`) + `claude.pricing` setting
  and its Settings dialog editor (next to Codex pricing); enrich and fan out.
- Frontend: Task row auto-expand + summary + capped nested list + pop-out dialog; sticky
  activity line; header `+ ~$x` while running. Must not add new hex colours or icons outside
  `icons.ts`.
- Demo: `player.ts`/`ws.demo.ts` pass through the new event types (fixtures without them
  must still play).

*As built:* a live probe (haiku, one Bash call + one Explore subagent) showed the SDK splits
one API response into an `assistant` message **per content block, each repeating the same
partial usage**; only the main loop's `message_delta` stream event carries the final output
count, and subagent calls never stream. So the sidecar's `TurnUsageTracker` keys calls by
message id, keeps the max per field, and emits `turn_usage` only when a count grows (subagent
output is undercounted — fine for a `~` figure). The main loop writes **1 h** cache entries,
so the price table has separate `cacheWrite5mPer1M`/`cacheWrite1hPer1M`. The estimator is
`TokenCostEstimator` (longest key prefix match, `default` fallback), seeded with first-party
list prices; Settings → Claude → "Live cost pricing" edits it (`claude.pricing`). The Task row
is a `ToolRow` component: open while running (`open={running}`), latest nested call + tool
count + duration in the summary, last 8 nested items while running ("show N earlier steps"),
and a portalled read-only `SubagentDialog`. The sticky `LiveTurnLine` sits above the composer
(ticks every second); the header cost chip shows `$x + ~$y` while a turn runs.

### Step 4 — Background tasks and child sessions (decisions 5–6)
- Sidecar: `background_tasks`/`task_done` translation; reset on `ready`.
- Backend: keep the latest background set per session in memory (`SidecarHandle`), expose it
  in the session view; `SessionHousekeeping.parkIdleSessions` skips non-empty sets.
  **Verify live** what the CLI does when a background task finishes while the session is
  IDLE — if it starts a turn on its own, `SessionService` must move IDLE → RUNNING on the
  first stream event instead of only on `user_message`.
- `OrchestrationMcpTools.spawnChildSession` journals `child_spawned` on the parent; a
  child's transition into `WAITING_INPUT`/`ERROR` journals `child_attention` on its parent.
- Frontend: background strip, `IDLE · N bg` chip, children chip + popover, notes for
  `child_spawned`/`child_reported`/`child_attention`.
- `docs/PROTOCOL.md`: all new event types.

*As built:* the probe answered the open question — **the CLI backgrounds Explore subagents by
default** (`is_backgrounded: true`): the turn's `result` arrives while the subagent still runs,
and after its `task_notification` the CLI **opens a new turn by itself**, no `user_message`.
That is the reporter's "stopped, not sure why". `SessionService` now treats a `system_init`
arriving while IDLE as a turn start (→ RUNNING). The background set is kept in memory per session
(`SessionService.backgroundTaskCounts`, cleared on sidecar exit) rather than on `SidecarHandle`;
parking skips a session with a non-empty set. The widget shows a strip above the transcript
("N running in background: …") instead of an extra `IDLE · N bg` chip — one place, readable
text. A settled *background* task adds a transcript note, so the self-started turn after it has
a visible cause. Children: the chip (`3 children · 1 running · 1 waiting`, amber when one waits
or crashed) is computed from the store views of open children, its popover is portalled (the
header clips overflow); the "child of" chip now jumps to the parent.

**Also found and fixed:** the dashboard's session list never refreshed on its own — a session
created by the backend (a spawned child) only appeared after the manual Refresh button or a
reload. `Dashboard` now re-lists every 10 s and on window focus (unchanged lists keep their
identity, so a quiet poll re-renders nothing). This also carries hide/unhide between tabs.

### Step 5 — Hide from dashboard (decision 8)
- `V23__session_hidden.sql`; `SessionEntity.hidden`; PATCH support; auto-unhide in
  `SessionService` on `permission_request`/`ask_user`/`error`; hide-on-park in
  `SessionHousekeeping`.
- Frontend: `visibleIds` excludes hidden; Sidebar **Hidden** section (collapsed, count);
  widget hide button; settings toggle.

*As built:* auto-unhide hooks `SessionService.transition` into `WAITING_INPUT` (covers every
approval prompt and AskUserQuestion) and `CRASHED`; a non-fatal `error` does not unhide.
`updateHidden` does not bump `updated_at` (idle parking reads it). Hide-on-park is
`session.hide-on-park` (default off), applied by `SessionService.onParked` after idle parking,
user sessions only. Icons: `Archive` (hide) / `ArchiveRestore` (Hidden section) — `EyeOff` is
taken by memory exclusion.

### Step 6 — Auto-title transparency (decision 9)
- `session.auto-title` setting checked in `AutoTitleService.maybeAutoTitle`.
- Transcript note for the auto-rename event; name tooltip.

*As built:* `session.auto-title` (default on); the rename event now also carries `from` (the
branch name it replaced), and the note reads `renamed to "…" (was …) — titled automatically
from the first message; switch off under Settings → Sessions`. No separate name tooltip — the
note is in the transcript where the change happened.

## Definition of Done

- [ ] Header cost equals the sum of turn footers for new sessions, and for an existing session
      after V22; cost budget trips at the real spend (unit + DB tests).
- [x] ~~A `sonnet 1M` session reports a ~1M `ctx` window~~ — moot: plain `sonnet` already reports 967k (step 2).
- [ ] A running subagent's latest nested tool call is visible without clicking; pop-out works.
- [ ] Sticky activity line and `~$` running cost show during a turn and disappear after it.
- [ ] A `run_in_background` Bash command shows in the background strip until it finishes;
      the session is not parked while it runs.
- [ ] Parent widget lists spawned children with live state; a stalled child (waiting for
      approval) produces a note on the parent.
- [ ] Hidden sessions stay hidden across reload and across browsers, auto-unhide on a
      permission request, and are reachable from the sidebar.
- [ ] Auto-rename appears as a transcript note; turning the setting off stops renames.
- [ ] Backend unit tests, sidecar vitest (both packages), frontend vitest + `tsc`, demo
      `--check` all pass; both themes checked for every new UI element.

## Manual test script

1. Run three short turns in one Claude session → header `$` = sum of the three footers.
2. Restart the backend mid-session (sidecar restarts) → next turn's footer is that turn's cost,
   not a restarted running total added on top.
3. ~~Create a `sonnet 1M` session~~ — step 2 parked; instead check a `sonnet` session's `ctx` tooltip shows ~967k.
4. Ask for "use an Explore subagent to map the auth flow" → Task row expands by itself, summary
   line tracks the latest tool; pop-out shows the nested transcript.
5. Ask for "run `sleep 120 && echo done` in the background, then stop" → turn ends, strip shows
   the task; after ~2 min a "background task completed" note appears, the agent starts a turn by
   itself (live line shows), and the strip clears.
6. In a session with an ecosystem, ask it to spawn a child; in the child, trigger a permission
   prompt → parent shows children chip `1 child · waiting` and a note.
7. Hide two sessions, reload, open the dashboard in a second browser → both stay hidden; trigger
   a permission prompt in one → it reappears.
8. Start a fresh session → after the first turn, a "renamed … (auto)" note appears; switch the
   setting off and start another → name stays the branch.

## Live verification (2026-10-19)

Backend jar + rebuilt sidecar, two haiku sessions in a scratch repo, driven through the dashboard:
- background `sleep` + Explore subagent: `background_tasks`/`task_*`/`turn_usage` journaled; strip shown
  while the sleep ran; Bash row kept its spinner after its own result; Explore row `8 tools · 13s`;
  pop-out dialog shows the subagent transcript; the CLI's self-started follow-up turn moved the session
  IDLE → RUNNING; a "background task completed" note only for the backgrounded task.
- live line polled every second during a 12 s foreground Bash: `Bash: sleep 12 && echo ok · 1s…13s` →
  `thinking…` → gone at `turn_complete`; header `$0.066 + ~$0.007`.
- estimates vs real turn cost: 0.0608 / 0.0585, 0.0083 / 0.0073, 0.0070 / 0.0066 (within ~5–12%).
- footer deltas match the raw running total (0.0658 → 0.0724 ⇒ 0.0066); V22 on the dev DB rewrote 30
  rows ($3.63 → $3.44); "Release 1.2 prep" header now $0.237 (was the inflated sum).
- a session created through the API appeared within the 10 s poll; hide → widget gone, "Hidden (1)",
  still hidden after reload, click restores it.

Fixed from that run: estimates shown with 3 decimals (`~$0.00` hid haiku turns); the Task badge
SVG wrapped the summary onto three lines (global `svg.lucide { display: block }`, pre-existing);
**auto-title answered the prompt instead of naming it** ("No Explore subagent available; sleep started
in background (ID: …)." became the session name) — the request is now fenced in `<request>` tags with
"do not carry it out", and a reply that doesn't look like a title (> 8 words, ends with a period,
multi-line) is dropped. This is a plausible concrete cause of feedback #6.

## Second live run (2026-10-20) — found and fixed

Manual steps 1, 2, 4–8 re-run through the dashboard (four haiku sessions + a spawned child, one backend
restart with `ARTUS_IDLE_PARK_MINUTES=1`). Fixed from it:
- **Live estimate double-counted cache writes** (+82% on one turn, +43% on another): the `assistant`
  messages carry only `cache_creation_input_tokens`, the stream's `message_start` also the 5m/1h split, and
  keeping the max per field put the same tokens in both. `TurnUsageTracker` now keeps a call's cache-write
  total and its 1 h share apart (the earlier "within ~5–12%" figure came from turns with little caching).
  Re-checked live: a main-loop turn now prices to $0.01737 vs $0.01734 billed. Known limit: a *backgrounded*
  subagent's call that starts before `turn_complete` is in that turn's estimate but billed in the next turn.
- **Spurious "background task completed" notes**: a subagent's own long-running *foreground* Bash calls are
  tasks too, linked to the nested call; the store took "no top-level row" for "background". Notes now need
  a known backgrounded task (or a task with no tool call at all).
- **Task rows spun forever after the sidecar died** (crash, park, close): no `task_done` ever comes; a
  resume does report the dead task as `stopped`. Entering PARKED/CRASHED/CLOSED/FAILED now marks running
  tasks stopped; the resumed CLI's late `stopped` adds no second note, and the empty $0 turn it opens to
  settle them (`numTurns: 0`) gets no footer.
- **Background subagent activity after `turn_complete` opened a live turn while IDLE** (header `+ ~$`
  while idle; the CLI's follow-up turn counted time and tools from that moment). The live turn now opens
  only while running, `system_init` always starts a fresh one, and the header estimate shows only while
  running.
- **A hidden parent digested its child's report unseen** (hide-on-park, then the report woke it):
  `report_result` now un-hides the parent.
- **`child_attention` fired on every prompt**: now once per state per child turn, and not after the child
  has reported.
- Nits: Task-row duration ticks locally between progress events; "1 tool"; while sessions are hidden the
  dashboard re-lists every 3 s (else 10 s) so one needing approval returns quickly; quiet polls keep list
  identity and reload history only when the set of open sessions changed.

Third pass (2026-10-20), the areas the first two runs left out:
- `/compact` on an IDLE session also arrives as `system_init` → RUNNING, and the CLI closes it with a
  `turn_complete` (`numTurns: 0`), so the session returns to IDLE — the new IDLE→RUNNING rule is safe there.
- Retention pruning deletes a session's whole journal, so V22 never meets a run without its `ready` row.
- Hotkey `h` (hide the focused session) was in decision 8 but not built — added (cheatsheet + button title).
- The demo build's `patchSession` ignored `hidden`, so the hide button flickered the widget back — handled.
- A failed hide/unhide PATCH re-syncs the lists instead of leaving an unhandled rejection.
- Auto-title: a reply like `"Fix login."` is now normalized (quotes, one trailing period) before the
  title check instead of being dropped.
- `pricingFor` is cached per provider (one DB read per `turn_usage` event otherwise).
- Settings dialog (Auto-title, Hide when parked, Claude live-cost pricing) and the widget's new elements
  checked in the dark theme. Seed prices match the first-party list (prefix match covers `claude-sonnet-5-5`
  via the `claude-sonnet-5` row; Haiku 5.5's >100k-token tier is not modelled — it only affects the `~` figure).
- Known, left as is: the children chip counts only children that have a widget (a *hidden* child is not
  listed until it un-hides); the live `~$` includes a backgrounded subagent's first call, which the CLI
  bills in the following turn.

## Open questions (answer during implementation, record in README decision log)

- ~~Exact 1M ids accepted by the current CLI~~ — both accepted, and the plain aliases already have the long window (step 2).
- ~~Whether a finished background task triggers a CLI turn on its own while IDLE — step 4.~~
  Yes (live probe) — handled via `system_init` while IDLE.
- ~~Whether `ready` is journaled (V22 boundary detection) — step 1.~~ Yes.
