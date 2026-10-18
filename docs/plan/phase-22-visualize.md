# Phase 22 — Visualize (diagram of a session's code logic)

Status: **landed (2026-10-18)** — Steps 1-4 built; live manual script pending. Design settled in a planning discussion with the user;
decisions below record its conclusions. Edit sites verified against the code as of commit
`89d883d`.

## Background

Once a development or review session has done its work, the user wants a picture of the
*logic* the agent touched: how the new code flows, which components talk to each other, what
states something moves through. Reading a diff or a chat transcript is slow; a diagram is a
one-glance sanity check and a good review aid.

The feature is a **Visualize** button on the session widget: one system-session turn (own lane, tier-configurable)
over the branch's changes produces Mermaid source, the dashboard renders it with
[`beautiful-mermaid`](https://github.com/lukilabs/beautiful-mermaid) in a dialog, and the
user can switch diagram type (re-render) or pop it out into its own window.

No sidecar / protocol-v1 change: the LLM call rides the existing system-session lanes
(`HandoffService` is the direct precedent — same `SystemTurnClient.text`, same digest, same
"Markdown out" shape), the result is a journal event (phase 21's block-mark precedent), and
the renderer is frontend-only.

## Decisions (agreed 2026-10-18)

1. **Input = the branch diff + a short digest tail, single-shot, no tools.** The transcript
   digest alone describes what was *discussed*; the diff is what the branch actually
   carries, so it grounds the diagram. Reviews work identically (the detached worktree's
   diff against `baseBranch` is the PR). Agentic read-only exploration of the worktree is a
   possible later upgrade, deliberately not in this phase. A session with an empty diff
   (pure Q&A) is still allowed — the prompt is told "no code changes" and the digest tail
   carries it — but the button's tooltip warns the result is conversation-derived.
2. **The model chooses the diagram type from a fixed subset; the user can override.**
   Subset = exactly what `beautiful-mermaid` renders and what suits code logic:
   `flowchart`, `sequence`, `class`, `er`, `state` (its XY-chart type is dropped — not a
   logic diagram). Default request is `auto` (model picks and states its pick in the
   response); the dialog's type selector re-requests with an explicit type, which
   **re-generates** (a new LLM turn over the same input — a diagram of another type can't
   be mechanically derived from the first).
3. **Renderer = `beautiful-mermaid`** (MIT, v1.1.3, deps `elkjs` + `entities`, ~2 MB
   unpacked). Chosen over stock `mermaid`: synchronous `renderMermaidSVG`, **no DOM
   dependency**, 6 diagram types matching decision 2, and colours as CSS custom properties
   (`bg`/`fg`/`accent`/`line`/`muted`/`surface`/`border` accept `var(--…)`), so our `--bg`/
   `--text`/`--accent` tokens drive it and a dark↔light toggle needs **no re-render**. Risks
   accepted and mitigated: last release 2026-02-26 (pin the exact version, no caret);
   only 6 diagram types (the prompt restricts the model to the supported subset); heavy
   (lazy `import()`, own chunk, never in the main bundle).
4. **The renderer is the validator.** `renderMermaidSVG` throws on unparseable source, so
   validation lives in the frontend: on a throw the dialog makes **one automatic repair
   request** (`POST …/visualize/repair` with the broken source + error message → one more
   system turn), and if that also fails it shows the raw source plus the error — never a
   blank window. The backend does not parse Mermaid (no Node-in-the-backend dependency for
   this).
5. **Surface = a dialog in the dashboard** (like the Changes panel) with an "Open in new
   window" escape hatch. The pop-out is a same-origin page opened with `window.open` that
   loads the stored diagram by session id from the backend (so it survives reload and shares
   the dashboard's token/localStorage). Pan/zoom, copy Mermaid source, export SVG (PNG only
   if it falls out cheaply from the SVG — otherwise out of scope).
6. **Persisted as a journal event, latest wins.** `diagram_generated { diagramType,
   mermaid, rationale?, fingerprint, repaired }` — replayed over WS and synced across tabs
   like `block_marked`, pruned with the journal (retention). Reopening costs no LLM call.
   History beyond "latest" is not kept (older events remain in the journal until pruned but
   no UI lists them).
7. **Staleness = fingerprint, refresh is manual.** `fingerprint` = SHA of the `git diff`
   output the diagram was generated from (so uncommitted edits, new commits and a rebase all
   register). The status endpoint returns `{ latest, currentFingerprint, stale }`; the
   widget button and dialog show an **outdated** chip when they differ and offer
   "Regenerate". Never auto-regenerates (it costs a turn).
8. **Provider-neutral; own model-tier setting.** Provider is `session.system-provider` (empty
   = follow `session.default-provider`), as for every system feature; Codex system sessions
   work too (no MCP tools needed). The model is a new persisted setting
   **`visualize.model`** ∈ `cheap|standard|premium` (Settings dialog → new "Visualize"
   section, a `tierField` exactly like `service-discovery.model`), resolved per call through
   `ModelCatalog.byTier(settings.systemProvider(), …)`. **Default `standard`**, not `cheap`
   (a deviation from the earlier "cheap tier" sketch, agreed to revisit): reading a diff and
   choosing a faithful structure is a reasoning task, and now that the tier is user-tunable
   the default can favour quality; flip the default if the first live runs say `cheap` is
   good enough. Timeout 90 s (bigger output than a handoff).
11. **Its own system lane: `SystemTurnLane.VISUALIZE`** (a third stateless system session,
   `name = "system-visualize"`, `kind = 'system'`, recycled after every turn like the other
   two — phase 19). Why not `INTERACTIVE`: a diagram turn is the longest system task a human
   waits on (90 s, a `standard`-tier model, a large prompt), and on the shared interactive
   worker it would make the ticket-import / commit-message / handoff dialogs fail with "busy"
   for its whole duration, and vice versa. Why not `BACKGROUND`: a human is waiting, so a
   second Visualize click while one runs must fail fast, not queue silently. Semantics:
   **fail-fast lock wait like INTERACTIVE** (the 20 s `INTERACTIVE_LOCK_WAIT`, applying only
   to this lane's own lock, so it conflicts only with another diagram request), **no
   post-turn warm-up and no startup warm-up** (BACKGROUND's cold-on-first-use behaviour — one
   idle sidecar fewer; the button's `.pulse` covers the cold spawn). System sessions are
   exempt from `ARTUS_MAX_SESSIONS` (`enforceSessionLimit`), so the third worker costs no user
   slot. Repair turns (decision 4) use the same lane.
9. **Prompt rules that make LLM Mermaid parseable**: only the five supported types; quote
   every node label (`A["…"]`); no reserved words (`end`, `graph` as ids); ASCII ids; ≤ 25
   nodes (collapse detail into subgraphs or summary nodes); label edges sparingly; output
   ONLY a JSON-free envelope — first line `TYPE: <type>`, second line `WHY: <one sentence>`,
   then a single ```` ```mermaid ```` fence — parsed by the service (fence-less output =
   treat the whole body as source).
10. **Eligibility**: button enabled when the session is IDLE, PARKED, or CLOSED-with-
    worktree-or-journal (CLOSED sessions can't have a worktree; they fall back to the
    digest only, tooltip says so). Disabled while a turn runs (the diff is in flux). The
    REST layer enforces the same (409). Review sessions allowed (read-only, no git
    mutation involved).

## Steps

### Step 1 — Backend: diff + generation

Edit sites (lane + setting first, they are prerequisites): 
`session/SystemTurnLane.java` (add `VISUALIZE`, extend the javadoc: who sits on it and why it
is separate), `session/SystemSessionService.java` (`VISUALIZE_NAME = "system-visualize"`, a
third `SystemWorker` in the `EnumMap`; `lockWait` becomes `lane == BACKGROUND ? timeout :
INTERACTIVE_LOCK_WAIT`; the post-turn `warmUp` stays INTERACTIVE-only; `busy()`/`isWarm()`
stay about the interactive worker — add `busy(SystemTurnLane)` only if the dialog needs it),
`session/SessionHousekeeping.java` (`retireLegacySystemSessions` skips by a *set* of lane
names, not two hard-coded ones — otherwise the new row is retired as "legacy" at every
startup; derive the set from the workers), `config/Settings.java` + `SettingsPatch.java` +
`SettingsService.java` (`visualizeModel`, key `visualize.model`, `tierField`, default
`standard`; mirror every place `serviceDiscoveryModel` appears), `frontend/src/protocol.ts` +
`api/rest.ts` (settings type / patch pick-list) + `components/SettingsDialog.tsx` ("Visualize"
section with the tier `<select>`, optimistic save like `saveServiceDiscoveryModel`).
Then: `git/GitCommandRunner` (reuse) + `git/ChangesService.java` (add
`unifiedDiff(Path worktree, String baseBranch, int maxChars)` — merge-base..working tree,
untracked files appended as `+` blocks, binary files as a one-line stub, hard cap with a
`[diff truncated: N files omitted]` trailer, files ranked so source beats lockfiles/generated
output); new `session/VisualizeService.java` (modelled on `HandoffService`: builds the
prompt from the diff + the last ~20k chars of `TranscriptDigest.render` for intent,
calls `systemTurnClient.text(prompt, modelOverride, SystemTurnLane.VISUALIZE, TIMEOUT)` (model = `visualize.model` tier via `ModelCatalog`), parses the `TYPE:`/`WHY:`/fence envelope, validates the type is
in the subset, appends `diagram_generated` to the journal, returns it); fingerprint helper;
`web/SessionController.java` (or a small `VisualizeController`):
`POST /api/sessions/{id}/visualize` body `{ "type": "auto|flowchart|sequence|class|er|state" }`,
`POST /api/sessions/{id}/visualize/repair` body `{ "mermaid": "…", "error": "…" }`,
`GET /api/sessions/{id}/visualize` → `{ latest, currentFingerprint, stale }`;
`docs/PROTOCOL.md` (`diagram_generated` journal event).

- Tests: `SystemSessionService` — a held INTERACTIVE or BACKGROUND lock does not delay a
  VISUALIZE turn and vice versa; a second concurrent VISUALIZE call fails fast with the
  "busy" error; `retireLegacySystemSessions` leaves a `system-visualize` row alone;
  `SettingsService` round-trips `visualize.model` (default `standard`, invalid tier
  normalised like the others); envelope parser (typed reply, fence-less reply, unknown type → fall back to
  `flowchart` if the body parses as one else 502-style readable error); `unifiedDiff` cap +
  untracked + binary; fingerprint stable across no-op and changes on edit; controller 409
  while RUNNING, 404 unknown session, readable error on empty journal + empty diff.

### Step 2 — Frontend: renderer + dialog

Edit sites: `frontend/package.json` (`"beautiful-mermaid": "1.1.3"`, exact), new
`components/DiagramDialog.tsx` + a small `components/MermaidView.tsx` (lazy `import()` of the
renderer; `useMemo` keyed on source; colours passed as our CSS variables so theme toggle is
free; SVG injected via `dangerouslySetInnerHTML` — the source is model output derived from
untrusted code, so the SVG is sanitised/strict-checked: verify the library escapes labels via
`entities`, and strip `<script>`/event-handler attributes defensively), store handling for
`diagram_generated` in `store/store.ts`, `icons.ts` (new role name `Diagram`, check
collisions), `SessionWidget.tsx` (header button, `title="Visualize"` + hotkey if a free key
exists; `with-badge` dot when stale; `.pulse` while generating per the Feedback rule),
`styles.css` (reuse existing dialog/chip classes; no new colours — `--amber` for outdated).
Dialog: type selector (auto/five types) → regenerate, Copy source, Export SVG, Regenerate
(stale), "Open in new window", the model's one-line rationale, the auto-repair + raw-source
fallback of decision 4.

- Tests: store reducer for `diagram_generated` (latest wins, stale flag), a pure
  `parseEnvelope`-free renderer wrapper test (throws → repair path invoked once, then raw
  fallback).

### Step 3 — Pop-out window

New same-origin page (check at build time how the frontend routes — if there is no router,
a `?diagram=<sessionId>` branch in `main.tsx` is enough) that fetches
`GET …/visualize`, renders with the same `MermaidView`, offers the same pan/zoom and export,
and keeps itself current via the status endpoint on window focus. Must work with the token
on (same localStorage) and off.

### Step 4 — Docs + decision log

`docs/plan/README.md` (decision-log row + phase table row), `docs/PROTOCOL.md`,
`docs/ARCHITECTURE.md` (a short section next to the handoff/reflection description),
`CLAUDE.md` (the "two stateless sessions, one per `SystemTurnLane`" passage in the provider-
commands paragraph becomes three, and the Persisted-settings list gains **Visualize** with
`visualize.model`), `docs/ARCHITECTURE.md` (lane table), `docs/plan/phase-19-system-session.md`
(one-line cross-reference that the lane set grew).

## Out of scope for this phase

- Agentic exploration (letting the model read the worktree read-only before drawing).
- Diagram history UI, per-type caching, diffing two diagrams.
- Server-side Mermaid parsing / PNG rendering.
- Types the renderer doesn't support (gantt, pie, journey, mindmap, XY).
- Auto-regeneration after each turn.
- A separate provider setting for Visualize (it follows `session.system-provider`).

## Definition of Done (whole phase)

- Visualize on a development session with a non-trivial diff yields a rendered diagram in the
  dashboard, with the model's chosen type and rationale shown.
- Switching type regenerates and re-renders; theme toggle recolours the SVG without a new
  request.
- A deliberately broken source (test hook) triggers exactly one repair turn, then the raw-
  source fallback — no blank dialog.
- Editing the worktree after generation flips the outdated chip within one poll/focus; 
  Regenerate clears it.
- Works on a review session, a PARKED session (does not wake it unnecessarily — diff is read
  from the worktree), a Codex-system-provider setup, and a CLOSED session with journal only.
- Pop-out window works with token on and off, survives reload.
- Heavy Visualize generation no longer makes other system dialogs (ticket import, git-assist,
  handoff, library AI-fill) report "busy": start a Visualize and, while it runs, open a ticket
  import — it answers normally. Changing Settings → Visualize tier changes the model of the
  next turn without a restart; `system-visualize` survives a backend restart (not retired).
- Unit tests green (`./mvnw -Dskip.installnodenpm -Dskip.npm -DexcludedGroups=integration
  test`), frontend `npm test` + `tsc --noEmit` + `npm run build` green (renderer in its own
  lazy chunk); no sidecar package touched; no migration (journal-only).

## Manual test script

1. Run a development session that adds a small feature across 2–3 files; let it finish.
2. Click Visualize → spinner/`.pulse`, then a diagram + "chosen: flowchart — …" line.
3. Switch type to `sequence` → regenerates; switch theme → recolours instantly.
4. Edit a file in the worktree → outdated chip appears; Regenerate → clears.
5. Open in new window → same diagram; reload it → still there; close the dashboard tab, it
   still renders.
6. Repeat on a review session (PR) and on a CLOSED session.
7. Force a parse failure (temporarily stub the system turn) → one repair attempt, then raw
   source + error.
