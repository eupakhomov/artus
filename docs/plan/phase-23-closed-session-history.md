# Phase 23 — Closed-session history in the sidebar

Status: **implemented (2026-10-18)** — steps 1-4 done; backend unit + DB tests and frontend vitest/tsc pass. Manual test script not yet run in a browser.
Edit sites verified against the code as of commit `d75ebc7`.

## Background

Closing a session removes it from the dashboard (`Dashboard.onClosed` drops the id and its
view) and, short of the create dialog's "Continue from…" picker, there is no way back to it:
the user has to remember it existed. The sidebar (phase 16/17) only lists *open* sessions.

This phase adds a **History** section at the **bottom of the sidebar** listing the **five most
recently closed** sessions, so a just-closed session can be found, read, and continued without
leaving the dashboard.

Today the sidebar has only two kinds of section: **Needs you** (pending permissions) and one
group per service of open sessions (e.g. "artus"; plus "System"). There is no history section —
it is created here as a third kind, at the bottom of the same sidebar column.

## Decisions (agreed 2026-10-18)

1. **Where**: a `sb-section` after the session groups in `Sidebar.tsx`, heading "History"
   (icon `ContinuedFrom`, already in `icons.ts` as lucide `History`). Pinned to the bottom
   of the scrolling column (`margin-top: auto` inside a flex column) so it stays at the
   foot when there are few open sessions. Hidden in `rail` fold and when the list is empty.
2. **Which**: `state = 'CLOSED'`, `kind <> 'system'` (system sessions are recycled, not
   closed by the user), top 5 by **close time**. Review sessions included, with the `review`
   chip like open rows.
3. **Close time needs a real column.** `updated_at` is bumped by PR polling, reflection and
   retention, so it is not "closed at". New migration `V21__session_closed_at.sql`:
   `ALTER TABLE session ADD COLUMN closed_at timestamptz;` plus a backfill
   `UPDATE session SET closed_at = updated_at WHERE state = 'CLOSED'`. Set it where
   `SessionService` performs the final `transition(id, CLOSED)` (lines ~409 and ~440) via a
   new `SessionRepository.markClosed` (state + `closed_at = now()` in one statement).
4. **Endpoint**: `GET /api/sessions/history?limit=5` (limit clamped 1–20) returning the
   existing `SessionSummary` shape plus `closedAt`, using a dedicated repository query
   (`WHERE state='CLOSED' AND kind<>'system' ORDER BY closed_at DESC LIMIT ?`) — not
   `findAll()` + client filtering, which loads every session and journal stat. Route must be
   declared before `/{id}`.
5. **Freshness**: the frontend fetches on mount and re-fetches when a session closes
   (`Dashboard.onClosed`), so the just-closed one appears immediately; no polling. Closes from
   another tab/Slack are picked up on the next `refresh()`/focus.
6. **Row**: dot-less, muted: name, service, relative close age (`shortAge`), cost
   (`$0.00`), PR icon if `prUrl` was set. Same `.sb-row` geometry; no hotkey hints, not in
   `model.order` (j/k/1–9 stay open-sessions-only).
7. **Click action — read-only first.** The worktree is gone after close, so reviving the live
   session is impossible. Click opens a small **Closed session** dialog: header facts
   (name, branch, model, cost, closed time) over the read-only transcript from
   `GET /api/sessions/{id}/events` (reuse `Transcript` in a read-only mode), with two actions:
   **Continue from this** (opens the create dialog pre-set to the existing continuation flow,
   `continuedFromId` + handoff brief — phase 7.3) and **Export** (`export.md`, already
   there). A pruned journal (`lastSeq === 0`, phase 5.3 retention) shows "journal pruned"
   and disables both, matching `ContinuationPickerDialog`.
8. **Dismiss**: per-row "x" hides it from History in this browser only (localStorage list of
   hidden ids, `prefs.ts`); it does not delete anything server-side.

## Steps

### Step 1 — Backend
- `V21__session_closed_at.sql` (above); `SessionEntity` gains `closedAt` (nullable `Instant`),
  `rowMapper` reads it.
- `SessionRepository.markClosed(id)` and `findRecentlyClosed(int limit)`.
- Switch the two `transition(id, CLOSED)` sites to set `closed_at`.
- `SessionController.history(limit)`; `SessionSummary` gets `closedAt` (null for open ones).
- Tests: repository DB test (`*RepositoryDbTest`, integration-tagged) for ordering, the
  system-kind exclusion and the limit clamp; a controller unit test for the clamp.

### Step 2 — Frontend model + sidebar
- `protocol.ts`: `SessionSummary.closedAt`. `rest.ts`: `sessionHistory(limit)`.
- `sidebarModel.ts`: pure `selectHistory(summaries, hiddenIds, limit)` (sort by `closedAt`
  desc, drop hidden, cap) with a vitest.
- `Sidebar.tsx`: `HistorySection` fed by a `history` prop from `Dashboard`; styles in
  `styles.css` reusing `.sb-heading/.sb-row` tokens only (both themes checked).
- `Dashboard.tsx`: state + fetch on mount and in `onClosed`.

### Step 3 — Closed-session dialog
- `ClosedSessionDialog.tsx` (read-only transcript, Continue, Export), hooking Continue into
  `CreateSessionDialog`'s existing continuation props rather than a second path.
- `Transcript` read-only prop: no composer, no block marks, no permission cards.

### Step 4 — Docs
- `docs/plan/README.md` row, `docs/PROTOCOL.md` (REST `history` endpoint),
  `docs/ARCHITECTURE.md` note, CLAUDE.md "Persisted settings"-style blurb only if a setting is
  added (none planned).

## Out of scope
- Reviving a closed session or recreating its worktree.
- Search/browse of all history (the continuation picker covers older sessions).
- Server-side delete of history entries.

## Definition of Done
- [ ] `V21` applies on a populated DB; existing CLOSED rows backfilled.
- [ ] Closing a session adds it to the top of History without reload; list never exceeds 5.
- [ ] System sessions never appear; review sessions show the chip.
- [ ] Rail fold hides History; empty history renders nothing.
- [ ] Dialog shows the transcript read-only; Continue starts a session with the handoff
      brief; pruned journals disable both actions.
- [ ] Hide ("x") persists across reload and doesn't touch the server.
- [ ] Both themes checked; no hex literals; sizes via `--font-scale`.
- [ ] Backend unit + DB tests, frontend vitest pass; `npm run build` clean.

## Manual test script
1. Open 6 sessions, close them one by one; History shows the last five, newest first.
2. Reload — same list. Close a seventh from Slack; refresh picks it up.
3. Click a row: transcript renders, no composer. Continue from this: new session's compose
   box holds the handoff brief.
4. Hide a row, reload: stays hidden. Toggle sidebar to rail: section gone.
