# Phase 21 — Memory curation (marks, reflection refresh, skill extraction)

Status: **implemented and verified (2026-10-18)** — see "Manual verification" at the end;
nine code-review follow-ups (F1–F9, two of them agreed product-scope changes) are listed
in "Code-review follow-ups" and implemented. Design settled in a planning discussion with the user;
decisions 1–15 below record its conclusions, with the deviations found while building noted
inline (7, 8, 10, 14). Edit sites verified against the code as of commit `c333904`.

## Background

Reflection today is all-or-nothing: `ReflectionService` distills the *whole* capped
transcript digest (`TranscriptDigest.render`, 100k chars, head+tail — the **middle of a
long session is silently dropped**) into one episode + semantic ops, once, gated by
`reflectedSeq`. The user has no way to say "this exchange is the part worth remembering",
no way to re-run a reflection after realising that, no way to keep a block *out* of
memory, and no path from a session to a reusable **skill** — even though the session
often just demonstrated a repeatable procedure and the skill library (phase 6) already
manages exactly that asset type.

This phase adds a curation layer on top of the existing machinery, changing none of its
defaults — an unmarked session reflects exactly as today:

- **Marks**: any user/assistant block in the transcript can be marked **important**
  (weighted in reflection, exempt from the digest cap) or **excluded** (never shown to
  reflection — a noise/privacy valve). One click, journal-native, provider-neutral.
- **Refresh**: the existing reflect (brain) button learns to *re-run* — marking blocks
  after a session did its job, then refreshing, produces a new proposal that supersedes
  a pending one and is told what the previous reflection already recorded.
- **Skill extraction**: a parallel action distilling the *procedure* a session followed
  into a SKILL.md, through the same pending-proposal approval flow, landing in the
  managed skill library like any imported skill. Deliberately **not** a "save as" choice
  on the reflection output — declarative memory and procedural skills need different
  prompts and shapes.
- **Evidence citations**: reflection ops cite the transcript blocks (seqs) they derive
  from, so approving a proposal is an audit, not an act of faith.
- **Remember this**: a single marked block can become a one-off memory proposal via one
  cheap system turn — no full reflection needed for "the user just stated a durable
  preference".
- **Handoff briefs** get mark-awareness for free (decision 4).

No sidecar/protocol-v1 change anywhere: marks are backend journal events, every LLM call
rides the existing system-session lanes, and nothing new is injected into live sessions.

## Decisions (agreed 2026-10-18)

### Marks

1. **A mark is a journal event, not a table.** `block_marked
   { targetSeq, mark: "important" | "excluded" | null }` (null clears). The journal
   already gives per-session monotonic seq, Postgres persistence, and lossless WS replay
   via `afterSeq` — every open client converges on the mark set with zero new sync
   machinery, toggling is appending, and no migration is needed. The current mark set is
   a trivial fold over the event list (last `block_marked` per `targetSeq` wins).
   Consequences accepted: marks are pruned with the journal (`MemoryRetentionService`),
   and a mark event bumps `lastSeq` — which is a *feature*, see decision 7.
2. **Only `user_message` and `assistant_message` events are markable** in this phase.
   Tool calls/results are truncated to ~200 chars in the digest; marking them would need
   a separate "expand this tool output into the digest" behavior — out of scope.
3. **Marking goes through REST** (journal appends are backend-only):
   `POST /api/sessions/{id}/marks` body `{ "targetSeq": N, "mark": "important" |
   "excluded" | null }`. The backend validates the target seq exists in that session's
   journal and is a markable type (404/400 problem+json otherwise), then journals
   `block_marked` via `JournalPublisher` — broadcast and replay follow automatically.
   No live sidecar required: marking works on IDLE, PARKED, and CLOSED (unpruned)
   sessions alike.
4. **`TranscriptDigest.render` becomes mark-aware** — and since `block_marked` events
   ride the same `readAfter(sessionId, 0)` list, the signature doesn't change and
   **every digest consumer inherits the behavior**: reflection, skill extraction, and
   `HandoffService` (marks feed handoff briefs for free — one click, three consumers).
   Concretely, `render`:
   - first pass collects the mark set and assigns each user/assistant block its seq
     anchor;
   - user/assistant lines are prefixed `[#<seq>]` (the anchor evidence citations point
     at, decision 12) and marked-important lines additionally `★ MARKED`;
   - **excluded** blocks render as `User/Assistant: [excluded from memory by the user]`
     — the placeholder keeps the narrative coherent, the content never reaches the
     model;
   - after the (unchanged) head/tail cap, a `## Marked as important by the user`
     appendix lists every important-marked block **in full** (per-block cap 4k chars) —
     cap-exemption without threading mark-awareness through the cap logic, and the
     emphasis signal in one place. No marks + no exclusions ⇒ byte-identical output to
     today except the `[#seq]` anchors.
   - `renderMarkdown` (the human export) annotates marks (`★` / `excluded`) but hides
     nothing — exclusion is an LLM-digest concern, not redaction of the user's own
     record.
5. **Frontend**: `TranscriptItem` variants `user` and `text` gain `seq` (threaded from
   the envelope through `reduce`/`applyBranchEvent`; a streaming `text` item gets its
   seq when the finalizing `assistant_message` lands — until then it isn't markable,
   which is correct: it has no journaled identity yet). `SessionView` gains
   `marks: Record<number, 'important' | 'excluded'>`, reduced from `block_marked`.
   `Transcript.tsx` shows two hover icon-buttons on user/text blocks — mark-important
   (pin) and exclude (eye-off), mutually exclusive, `--accent` / `--muted` when active,
   re-click clears — role-named in `icons.ts` (`MarkImportant`, `ExcludeFromMemory`;
   check for glyph collisions first). A marked block gets a subtle left-edge accent
   (important) or reduced opacity + badge (excluded). The affordance renders only when
   memory is enabled (`GET /api/settings` → `memory.enabled`, already fetched by the
   dashboard for other defaults) — except the widget's extract-skill button, which is a
   library feature and always shows (follow-up F5). Marks are binary-ish and category-free by design —
   the signal's whole value is being one keystroke.

### Reflection refresh

6. **Refresh is the existing brain button / `POST /{id}/reflect`, not a new endpoint.**
   What changes is the gating and the prompt, not the surface.
7. **The "nothing to reflect" gate is rewritten** (found while implementing: the original
   `reflectedSeq >= lastSeq` check was effectively dead — reflection journals its own
   `reflection_proposed`/`reflection_complete` events *after* capturing `lastSeq`, so
   `lastSeq > reflectedSeq` held forever after the first reflection and repeated clicks
   produced duplicate episodes). The gate now requires a **digest-relevant** event after
   `reflectedSeq` — `user_message`, `assistant_message`, `block_marked` or `tool_started`
   (`EventJournal.hasEventsAfter`). Marking a block after an applied reflection therefore
   genuinely re-arms the button, and reflecting twice with nothing new is refused.
8. **A pending proposal is superseded, not a 409 — but only once the replacement is
   ready.** `runReflection` discards the pending proposal (`DISCARDED` + a
   `reflection_discarded` journal event with `reason: "superseded by refresh"`)
   immediately before inserting the new one, so a failed or unparseable system turn never
   costs the user the proposal they already had. The up-front `findPendingForSession`
   fail-fast check is removed. Single-user posture: no confirmation dialog, the journal is
   the audit trail.
9. **A re-reflection is told what came before.** When the session already has episodes
   (new `MemoryEpisodeRepository.findBySession(sessionId)` finder), the prompt gains:
   "Earlier reflections of this session already recorded: <episode summaries>. Do not
   restate them — focus on what the marked blocks and newer turns add, and prefer
   `update` ops over `create`." Episodes stay append-only (a decided phase-5.3
   invariant) — a refresh adds an episode rather than mutating one; if duplicate
   episodes prove annoying in practice, a `superseded_by` column is a follow-up, not
   this phase. Retention guard: a pruned journal means `lastSeq == 0` and the existing
   "nothing to reflect" error already answers readably — refresh is only possible while
   the journal lives, documented, not specially handled.

### Skill extraction

10. **V20 migration — proposals gain a kind.** `ALTER TABLE memory_proposal ADD COLUMN
    kind TEXT NOT NULL DEFAULT 'memory' CHECK (kind IN ('memory', 'skill', 'remember'))`
    and `reflected_seq` becomes nullable; the one-pending-per-session partial unique index
    is dropped and recreated over `(session_id, kind) WHERE status = 'PENDING' AND kind <>
    'remember'` — a pending skill proposal must not block a reflection or vice versa, and
    several "remember" proposals may wait at once. Column reuse for `kind = 'skill'`: `episode` holds the full proposed SKILL.md
    **body** (what the approval card edits), `ops` holds the metadata object
    `{ "name": "kebab-slug", "description": "...", "tags": [...] }`. `reflected_seq` is
    stored for parity but **never applied** to the session — extracting a skill doesn't
    make a session "reflected".
11. **Extraction lives in `ReflectionService`** (`extractSkill(sessionId)`, endpoint
    `POST /api/sessions/{id}/extract-skill`) — it shares the digest, `SystemTurnClient`
    (BACKGROUND lane, 3 min, same cheap-tier model resolution), the `InFlightGuard`,
    proposal insert, and journaling; only the prompt differs: distill the *procedure*
    the session demonstrated (marked blocks weighted — this is where marks matter most:
    "mark the exchange where the approach finally worked") into SKILL.md frontmatter
    (`name`, `description`) + step-wise instructions, generalized beyond this one
    session's filenames, or return a structured "nothing skill-shaped here" refusal that
    surfaces as a warning. Skill proposals always go through approval **regardless of
    `memory.reflection-approval-required`** — a skill extracted from one session is
    usually overfit and needs the human generalization pass; auto-applying one into
    every future session's asset picker is a different risk class than a memory note.
12. **Approve writes through the library, not past it.** A new
    `LibraryService.createSkill(name, description, tags, content)` writes
    `<skills-root>/<slug>/SKILL.md` (settings-resolved root), registers the asset
    exactly like an import (same repo insert + `maybeEmbed`), and returns the asset —
    so the skill is immediately searchable, taggable, and pickable in the create
    dialog. A slug collision with an existing library asset or an existing directory
    under the root is a 409 with a readable message — the user edits the name in the
    proposal card and approves again (no silent overwrite, no auto-suffix). Applying
    journals `skill_extracted` (payload: name, assetId) on the session.
13. **Evidence citations** (both kinds): the reflection/extraction prompts require each
    semantic op (and the skill proposal as a whole) to carry
    `"evidence": [<seq>, ...]` referencing the digest's `[#seq]` anchors.
    `applyOp` ignores the key (verified: it reads only op/scope/name/description/tags/
    content, and `docService.write` takes explicit fields — evidence never lands in a
    vault file). The proposal **listing** (`GET /api/memory/proposals`) resolves each
    cited seq server-side into a short quoted snippet (journal lookup, 300 chars), so
    the pending card can show expandable "based on:" quotes with no cross-navigation —
    approving becomes auditing. Invalid/unknown seqs resolve to a `(block not found)`
    snippet, never an error.

### Remember this (single block)

14. **One block → one proposal, no episode, no reflectedSeq.**
    `POST /api/sessions/{id}/remember` body `{ "targetSeq": N }`: validates like
    decision 3, runs one INTERACTIVE-lane system turn (45 s, cheap tier) over just that
    block's text + the memory index ("produce at most 2 semantic ops capturing the
    durable fact — or an empty array if there is none"), and inserts a `kind='remember'`
    proposal (not `memory`: it must not collide with a pending full reflection) with an
    **empty episode**. `applyReflection` is parameterized accordingly:
    a blank episode skips the episode insert, and a null `reflectedSeq` skips
    `updateReflectedSeq` — remembering one block must not mark the whole session
    reflected. Honors `memory.reflection-approval-required` like a full reflection
    (off ⇒ auto-apply). Surfaced as a third hover action on marked-important blocks
    (and in v1 only there — the pin is the natural first click anyway).
15. **Interactions checked deliberately**: Codex-provider sessions are fully covered
    (everything is backend-side; the system session is provider-neutral already);
    review sessions mark/reflect/extract like any other; compaction is provider-side
    and unaffected by marks; `TranscriptDigest`'s new anchors slightly change handoff
    and reflection prompts (harmless, verified in tests); Slack mirroring ignores
    `block_marked` (not a message event); the usage dashboard and auto-title read
    nothing mark-related.

## Steps

### Step 1 — Marks: backend + digest

Edit sites: `journal/EventJournal.java` (nothing — `block_marked` is an ordinary
non-stream event; verify flush-ordering holds), new mark validation + append in
`session/SessionService.java` (or a small `journal/BlockMarkService` if SessionService
is getting crowded), `web/SessionController.java` (`POST /{id}/marks`, decision 3),
`journal/TranscriptDigest.java` (decision 4: two-pass mark fold, `[#seq]` anchors, ★
prefix, excluded placeholder, important-appendix after the cap; `renderMarkdown`
annotations), `docs/PROTOCOL.md` (`block_marked` journal event).

- Tests: `TranscriptDigestTest` — mark fold last-wins; excluded block's text absent
  from `render()` output but placeholder present; important block inside the capped-out
  middle of an oversized transcript still appears verbatim in the appendix; no
  marks ⇒ output unchanged apart from anchors; `renderMarkdown` shows everything.
  Controller/service-level: mark on an unknown seq → 404; on a `tool_started` seq →
  400; clear (null) journals and the fold returns to none.

DoD: marking via curl journals a `block_marked` event visible in a WS replay; a
reflection prompt (log it at DEBUG or assert via test) shows anchors, ★ lines, the
appendix, and no excluded content.

### Step 2 — Marks: frontend

Edit sites: `store/store.ts` (`seq` on `user`/`text` items — thread the envelope seq
into `applyBranchEvent`; `marks` record on `SessionView`, reduced from `block_marked`),
`protocol.ts` (event type), `api/rest.ts` (`setMark(id, targetSeq, mark)`),
`icons.ts` (two role names, decision 5), `components/Transcript.tsx` (hover actions,
visual mark states; it needs the `marks` record and a `memoryEnabled` prop passed down
from `SessionWidget`/`Dashboard`), `styles.css` (mark affordance classes, all three
theme blocks if any new token — prefer existing tokens).

- Tests: store vitest — seq lands on user/text items (streaming text gets it at
  finalize); `block_marked` replay rebuilds the mark record; clearing removes the
  entry.

DoD: hover-marking a block updates instantly, survives a reload (replay), and a second
browser tab converges without one; excluded and important states are distinguishable in
both themes; the affordance is absent when memory is disabled.

### Step 3 — Reflection refresh

Edit sites: `memory/ReflectionService.java` (supersede instead of fail-fast, decision
8; prior-episode prompt block, decision 9), `memory/MemoryEpisodeRepository.java`
(`findBySession`), `web/SessionController.java` (nothing — same endpoint).

- Tests: `ReflectionServiceTest` (extend whatever seam it uses for `SystemTurnClient`) —
  pending proposal superseded + journaled; prompt contains prior episode summaries on a
  second run and not on the first; mark-after-reflect re-arms (lastSeq bump) while
  reflect-twice-with-nothing-new still errors.

DoD: mark a block on an already-reflected session → the brain button works again and
the new proposal's prompt carried the earlier episode; a pending proposal silently
yields to the refresh with an audit line in the transcript.

### Step 4 — Skill extraction

Edit sites: new `V20__memory_proposal_kind.sql` (decision 10),
`memory/MemoryProposalRepository.java` (kind column through insert/select/mapRow; the
pending-check becomes per-kind), `memory/ReflectionService.java` (`extractSkill`,
decision 11; `applyReflection` parameterization shared with Step 5; approve path
branches on kind → `LibraryService.createSkill`), `library/LibraryService.java`
(`createSkill`, decision 12), `web/SessionController.java` (`POST /{id}/extract-skill`),
`web/MemoryController.java` (proposal listing carries kind + evidence snippets, decision
13; approve request for skills carries edited content/name/description/tags),
`components/MemoryDialog.tsx` (kind badge on the Pending tab, skill-shaped edit form),
`components/SessionWidget.tsx` (an extract-skill button beside reflect — distinct icon
role, same pulse-while-running pattern, title per the visual-style rules),
`docs/PROTOCOL.md` (`skill_extracted`; `reflection_proposed` payload gains `kind`).

- Tests: repository — one pending per (session, kind), memory+skill coexist;
  `ReflectionServiceTest` — skill proposal always pending even with approval-required
  off; approve writes via a faked `LibraryService` and journals `skill_extracted`;
  slug collision surfaces as IllegalState (409). `LibraryService` — `createSkill`
  writes SKILL.md with frontmatter, registers, embeds-when-configured, refuses
  collisions.

DoD: "extract a skill" on a finished session produces a pending skill proposal; its
card shows the SKILL.md body editable; approving lands the file under the skills root,
the asset appears in the library dialog and the create dialog's picker, and a
`skill_extracted` line lands in the transcript. A deliberately duplicate name → 409
with a message, edit, approve succeeds.

### Step 5 — Evidence citations + remember-this

Edit sites: `memory/ReflectionService.java` (evidence in both prompts; `remember`
turn, decision 14), `web/MemoryController.java` (snippet resolution on the proposal
listing — needs `EventJournal` access), `web/SessionController.java`
(`POST /{id}/remember`), `components/MemoryDialog.tsx` (evidence quotes on proposal
cards), `components/Transcript.tsx` (remember action on important-marked blocks),
`api/rest.ts`.

- Tests: snippet resolution — valid seq quotes the block, bogus seq yields the
  placeholder, no evidence key yields none; remember-this — proposal has empty episode,
  approve inserts no episode row and leaves `reflectedSeq` untouched; approval-required
  off auto-applies.

DoD: a proposal's ops each show "based on:" quotes matching real transcript blocks;
"remember this" on a marked block yields a 1–2-op proposal in under a minute, and
approving it writes the memory doc(s) with no episode and no reflectedSeq change.

### Step 6 — Docs + decision log

`CLAUDE.md` (marks/refresh/extract-skill/remember in the memory bullet + per-session
limits table untouched), `docs/ARCHITECTURE.md` (§3b additions), `docs/PROTOCOL.md`
(checked in Steps 1/4), `docs/plan/README.md` (decision-log rows per landed step, phase
table row).

## Out of scope for this phase

- **Marking tool calls/results** (decision 2) and expanding tool output into digests.
- **Mark categories** beyond important/excluded — the one-keystroke signal is the point.
- **Hotkey marking** ("m marks the last block") — revisit once hover-marking proves
  used.
- **Episode supersession** (`superseded_by`) — only if refresh-created duplicate
  episodes annoy in practice (decision 9).
- **Agent-definition extraction** — same machinery could emit library *agents*; ship
  skills first and judge extraction quality.
- **Evidence-chip → transcript scroll/flash navigation** from the MemoryDialog — the
  server-resolved quotes (decision 13) cover the audit need without cross-dialog
  navigation plumbing.
- **Compaction awareness of marks** — compaction is provider-side; nothing we can pin.

## Definition of Done (whole phase)

- Every step's DoD above holds.
- An unmarked, untouched session reflects byte-identically to phase 20 behavior modulo
  digest `[#seq]` anchors; all existing reflection/handoff/proposal tests pass
  unchanged or with mechanically updated expectations.
- The full loop works end to end on both providers: run a session → mark two blocks
  important, one excluded → refresh reflection → the proposal cites evidence, omits the
  excluded content, and supersedes a pending predecessor → approve → extract a skill →
  approve → the skill is attachable to a new session from the create dialog.
- Marks survive backend restart and WS reconnect (journal replay), and a
  CLOSED-then-pruned session degrades to readable errors, never 500s.
- Unit tests green (`./mvnw -Dskip.installnodenpm -Dskip.npm
  -DexcludedGroups=integration test`), frontend `npm test` + `tsc --noEmit` +
  `npm run build` green; no sidecar package touched (`check-protocol-sync.mjs`
  trivially green); one new migration (V20).

## Manual test script

1. Memory enabled, approval-required on. Run a short session (2–3 turns, one dead end,
   one working approach). Hover a user block → pin and eye-off appear; mark the working
   exchange **important**, mark a block containing a fake secret **excluded**. Reload
   the page → marks persist; open the same session in a second tab → marks match.
2. Click reflect (brain). In the Pending tab: the proposal's ops show "based on:"
   quotes pointing at the marked exchange; the excluded block's content appears nowhere
   in the proposal. Approve → memory doc written, `reflection_complete` in transcript.
3. Mark one more block important (session now already reflected) → the brain button
   works again (no "nothing to reflect"); the new proposal doesn't restate the first
   episode. While it is pending, reflect once more → the transcript shows the
   supersede discard + a fresh proposal.
4. Click extract-skill → pending **skill** proposal with editable SKILL.md; verify a
   memory proposal can be pending at the same time. Rename its slug to an existing
   library asset's → approve → readable 409; fix the name → approve → SKILL.md exists
   under the skills root, asset visible in the library dialog and attachable in the
   create dialog; `skill_extracted` in the transcript.
5. On an important-marked block containing a stated preference, click remember →
   within ~1 min a small proposal appears; approve → doc written, no new episode row
   (`memory_episode` count unchanged), `reflected_seq` unchanged.
6. Turn approval-required **off**: remember-this auto-applies; extract-skill still
   produces a pending proposal (decision 11).
7. Repeat 1–2 on a **Codex**-provider session → identical behavior.
8. Export the transcript (download button) → marked blocks annotated, excluded block's
   text still present (export ≠ redaction).
9. Disable memory in Settings → mark affordances gone from transcripts, the extract-skill
   button stays; re-enable → marks back, previous marks intact.
10. Long-session check: a session whose digest exceeds 100k chars with an important
    mark in the middle → the reflection proposal demonstrably uses the marked content
    (it appears in an op's evidence quote). Light + dark theme pass over mark states,
    kind badges, and evidence quotes.

## Manual verification (2026-10-18)

Run against a throwaway Postgres 17 (port 55432), the built jar on :8081 with scratch memory/skills/
worktree roots, and the real dashboard driven through Playwright; sessions used haiku. Backend suite
670/670 (integration included), frontend 87/87.

Passed live: steps 1 (marks: hover actions, important/excluded states, reload replay, 404/400 on bad
input, annotated export that still shows excluded text), 2 (reflect: the model saw the mark, the
secret never reached the proposal or the vault), 3 (refresh: 409 with nothing new, 204 after a new
mark; pending proposal superseded with a `reflection_discarded` audit line), 4 (extract-skill: a pending
skill proposal coexisting with a pending reflection; slug collision keeps it pending and now shows the
409 message; rename + approve wrote `SKILL.md`, registered a source-less library asset, journaled
`skill_extracted`), 5 (remember-this: pulsing button, `remember` proposal with an empty episode and integer
evidence that resolved to a quote; approving left the episode count and `reflected_seq` unchanged), 6
(memory off hides marks and extract-skill; the handoff brief carried the mark and not the secret), 9,
and a dark-theme pass.

Not run: the Codex provider (step 7), a second browser tab (convergence is the same journal replay
the reload check exercised), and the >100k-char digest (unit-tested only: an important block survives the cap
in the appendix). Quality note: a refresh's episode still partly restates the earlier one despite the
prompt asking it not to — the instruction is advisory.

## Code-review follow-ups (2026-10-18, agreed with the user; implemented)

A /code-review pass over the uncommitted diff produced ten findings; nine survived
verification (the tenth — per-icon `size={13}` — matches `Transcript.tsx`'s pre-existing
inline-status idiom and was dismissed). Two were product calls, both resolved with the
user and folded into the fix list below.

### Fixes (all implemented; per-fix notes mark where the build chose a specific shape)

F1. **Bound the important-blocks appendix.** `TranscriptDigest` appends the appendix
    *after* `cap()` with no total limit — 50 marked blocks add ~200k chars to a prompt
    the 100k cap exists to bound. Add one total appendix budget (~20k chars; keep the
    4k per-block cap) and note the truncation in the appendix itself ("… N more marked
    block(s) omitted"). Unit test: many marked blocks → digest length bounded.
    *Done:* appendix budget `MARKED_APPENDIX_CHARS` = 20k with the per-block 4k cap kept; overflow ends in
    "… N more marked block(s) omitted (appendix budget)". Tests: 50 × 3k-char marked blocks stay bounded; a few are all kept.

F2. **Refuse a blank skill body/name at the approval card.** Clearing the SKILL.md
    textarea and approving currently publishes the *original* body (the blank-means-
    no-edit idiom inherited from the memory card is wrong for a skill: it ships content
    the user explicitly deleted). Frontend fix in `MemoryDialog.tsx`'s
    `SkillProposalCard`: Approve disabled while the name or body is blank.
    *Done* in the card only: Approve is disabled (with a title) while the name or body is blank. The backend's
    blank-falls-back-to-original behaviour is unchanged for direct API callers.

F3. **Explain the digest markers to the handoff model.** `HandoffService`'s prompt
    embeds `[#N]` anchors, `★ MARKED` lines, exclusion placeholders and the appendix
    with no explanation. Add one line: anchors identify blocks, starred blocks were
    flagged important by the user, excluded placeholders were withheld on purpose.
    *Done:* one sentence added to `HandoffService`'s prompt covering anchors, starred blocks and exclusion placeholders.

F4. **Tooltip scope wording** *(product call, resolved)*: exclusion applies to every
    digest consumer (reflection, skill extraction, handoff) by design — decision 4 —
    but the tooltips say "exclude from memory reflection", under-promising the scope.
    Reword in `Transcript.tsx` to "exclude from memory & handoff" (and the un-exclude
    title to match). The behavior stays as decided.
    *Done:* both mark tooltips now say "memory & handoff".

F5. **Un-gate extract-skill from `memory.enabled`** *(product call, resolved)*: skill
    extraction writes to the skill library, not the memory vault, and the backend
    endpoint has no memory check — hiding the button for memory-off users removes a
    working feature for the wrong setting. `SessionWidget.tsx`: show the extract-skill
    button for every non-system session regardless of `memoryEnabled`. Marks and
    remember-this stay memory-gated (they are memory curation); the Pending tab in the
    memory dialog already opens regardless of the setting, so approval still works.
    Amends decision 5's "renders only when memory is enabled" for this one button, and
    the manual-script step 9 expectation ("marks and extract-skill gone") becomes
    "marks gone, extract-skill stays".
    *Done:* the extract-skill button shows for every non-system session; marks and remember-this stay behind `memoryEnabled`.

F6. **Stop the evidence N+1 on the badge poll.** The dashboard polls
    `GET /api/memory/proposals?status=PENDING` every 60 s just for the topbar badge
    count, and `listProposals` resolves every citation with per-seq `findBySeq` point
    SELECTs (plus a journal flush each). Two parts: a `GET /api/memory/proposals/count`
    (or `countPending`, which already exists on the repository) for the badge poll, and
    a batched `WHERE seq IN (…)` per session in `ProposalEvidenceService.resolve` for
    the full listing.
    *Done:* `GET /api/memory/proposals/pending-count` (`{count}`) feeds the badge poll (the notification text now says
    "proposal(s)", since skill/remember proposals count too); `EventJournal.findBySeqs` resolves a proposal's citations in one query.

F7. **Truncate the discard journal payload.** `reflection_discarded` carries the full
    proposal episode — for a skill, the entire SKILL.md body — which no UI renders for
    discards. Journal a short prefix (~200 chars) instead, in both `discardProposal`
    and `supersedePending`.
    *Done:* `ReflectionService.discardNote` — a skill proposal journals `skill proposal: <name>`, a memory episode is cut at 200 chars.

F8. **Share the skill-slug validator.** `runSkillExtraction`'s inline
    `[a-z0-9][a-z0-9-]{0,62}` duplicates `LibraryService.SKILL_NAME`; expose an
    `isValidSkillName` (or the pattern) on `LibraryService` and use it from both sites
    so they cannot drift.
    *Done:* `LibraryService.isValidSkillName`, used by `createSkill` and by skill extraction.

F9. **Honest fallback for unresolved evidence.** `ProposalEvidenceService.resolve`
    caps at 30 seqs per proposal, so citations past the cap render "(block not found)"
    — a false claim. Either raise the cap comfortably above `MAX_OPS × typical
    citations` and resolve per op, or (minimum) have the card's fallback read
    "(not resolved)" so it never asserts absence it didn't check.
    *Done:* the cap is now per op (30) with a separate 200-per-proposal resolve limit; an unresolved citation renders
    "(not resolved)" in the card, while "(block not found)" is kept for a seq that was actually looked up and is gone.

### Noted, deliberately not fixed
 
- **Refresh-while-editing discards a proposal someone has open** (edits lost after the
  3-minute turn, Approve → 409). This is decision 8's supersede-without-confirmation,
  chosen for the single-user posture with the journal as audit trail. Cheap later
  mitigation if it bites in practice: the Pending tab refreshing itself on a
  `reflection_discarded` event instead of only on its own actions.
