# Phase 24 — Demo mode (replay build of the dashboard)

Status: **implemented (2026-10-18)** — steps 1-5 and 7 done; **real fixtures recorded** on the public
`eupakhomov/artus-demo-repo` (7 sessions: dev, review of PR #1, Codex, parent + child, two closed; 83 KB gzipped) and
scrubbed, playback scripted in `frontend/demo/scripts.json` (workflow: `frontend/demo/SCENARIOS.md`). `vitest`, `tsc`,
`npm run build`/`build:demo` pass and the demo build was driven in a browser. **Not done:** step 6 media (screenshots,
screencast). Edit sites verified against the code as of commit `97832c3`.

### Deviations from the plan as written
- `rest.demo.ts` exposes the client through a `Proxy` whose default is the 501 `ApiError`, instead of `satisfies typeof api`: a new real
  method needs no demo edit and can never reach a network (decision 7's intent); `tsc` still checks the implemented methods' signatures.
- **Restart = page reload** (store, in-memory REST state and players reset together).
- `?session=` was dropped from the embed contract (not needed by the site plan); `?theme/speed/autoplay` work.
- `base` is relative (`./`), so the bundle works under any path, not only `/demo/` (verified served at `/see-it-work/`).
- Narrow embeds (< 760 px) start in focus layout with the sidebar folded **for that load only** (`Dashboard`'s `NARROW_DEMO`, never
  written to storage); phone width is still cramped, so the site should show the poster/screencast there rather than the iframe.
- `storageMigration` is a no-op in demo builds (decision 10).
- Fixtures also record the **Git panel reads** (`git/status`, `git/log`, `git/diff` → `fixture.git`), served through `api.raw`;
  every git write (commit, push, PR, AI drafts) is a 501.
- Leak check: `frontend/demo/denylist.txt` (regex per line; the maintainer handle is allowed only as a `github.com/` owner) is applied
  by capture and `--check` (incl. CI); the Artus token (`--token`/`$ARTUS_TOKEN`) is redacted on capture and denied on check;
  `git@host:` SSH remotes are not treated as emails.
- Hand-made playback lives in `frontend/demo/scripts.json` (keyed by fixture file name), not in the fixtures, so a re-capture
  is one command: `seedUntilSeq`, `pauseOn` (only these prompts wait; the rest play through answered as recorded, in 0.4 s),
  `deny` tails, and trims (`endAtSeq`, `dropSeqs`, `state`). A Deny ends the recording (the timeline diverged) rather than
  skipping one turn. While a pause point waits, what was recorded during the real wait (WAITING_INPUT, a parallel tool call)
  still plays. Deny tails are hand-written — a recording can't contain both branches of one prompt.
- The sample generator (`build-sample-fixtures.mjs`) is gone; the capture strips the machine's tool/MCP inventory and keeps
  only the session fields the dashboard reads (`stripEnvironment`), and `lin_api_` keys are a redacted secret shape.
- Found while recording, fixed in the backend: `GitOpsService.submitPrReview` dropped GitHub's error body (the agent only saw
  "HTTP 422", not "Can not request changes on your own pull request") and sent a `start_line` equal to `line`, which GitHub
  rejects; both fixed with tests.
- Post-review fix: any event the player invents (viewer echo, deny tail, rename/mark, warning) now shifts the rest of the recording
  past its seq — previously a recorded event with the same seq was silently dropped by the WS dedupe (could hang a session).
- The dashboard's `beforeunload` guard is skipped in demo mode.
- Sidebar History needs no code: it reads `sessionHistory` (phase 23), which the fixtures answer.

Source: `artus-web/artus_website_plan.md` §4 and §8 steps 1–3. The website itself (Astro, Cloudflare Pages,
copy) is a separate task in the `artus-web` repo; this phase only delivers what that site consumes.

## Background

The launch site needs a "See it work" section. The decision (website plan §0) is **replay mode**: the real
dashboard, compiled with no backend, playing back recorded session journals in the browser. A screencast is
the fallback and is needed anyway for the HN post and the OG image.

Why this is cheap here (verified in the frontend):
- `store/store.ts` is a pure reducer over journal `Envelope`s; no network code.
- `WsSession` (`api/ws.ts`) is only constructed in `SessionWidget.tsx:145`; its surface is `(sessionId, onEvent,
  onStatus)`, `send(cmd)`, `stop()`.
- All REST goes through the single `api` object in `api/rest.ts` (plus `ApiError`, `setToken`, `token`;
  `api.raw` is used by `GitPanel`/`PrDialog` for `/git/*` calls).
- `GET /api/sessions/{id}/events` already returns `Envelope[]`, which is the fixture format.

## Decisions

1. **Build mode, not a runtime switch.** `vite build --mode demo` swaps `api/rest` and `api/ws` for demo
   implementations at bundle time, so the production bundle contains no demo code and a real deployment can't
   end up in demo mode. Output `frontend/dist-demo/`, `base: '/demo/'`. `mvn package` and `npm run build` are
   unchanged; `dist-demo/` is gitignored.
2. **Swap mechanism: a small Vite plugin with `resolveId`** that redirects any import resolving to
   `src/api/rest.ts` / `src/api/ws.ts` to `rest.demo.ts` / `ws.demo.ts` (a string `alias` would miss the
   `./api/rest` vs `../api/rest` spellings). The demo modules must export the same names (`api`, `ApiError`,
   `setToken`, `token`; `WsSession`, `WsStatus`). `tsc --noEmit` checks the demo files against the real types
   via `satisfies typeof realApi`, so a new REST method in the real client breaks the build rather than the demo.
3. **Fixtures are real journals, captured then scrubbed** (website plan §9.4: fresh sessions on a small public
   sample repo, not past sessions — nothing private to leak, reproducible). Staged scenario, four live sessions
   plus closed history:
   a. development session → Bash **permission prompt** (pause point), then edits + tests pass;
   b. review session on a PR of the sample repo → `submit_pr_review` approval card (pause point);
   c. Codex session (shows the provider chip, token-estimated cost);
   d. parent with a child fan-out (`spawn_child_session`);
   plus 2–3 closed sessions for the History section (phase 23) and one diagram (`diagram_generated`) so
   Visualize opens with content.
4. **Capture script lives in the artus repo** (`scripts/capture-demo.mjs`): given a running backend + token +
   session ids, writes `frontend/demo/<id>.json` = `{ summary, detail, events, changes? }` (`GET /api/sessions`,
   `/{id}`, `/{id}/events`, `/{id}/changes[/file]`) after scrubbing. Scrubbing is a pure, unit-tested module
   (`frontend/demo/scrub.mjs`): home/repo/worktree paths → `/home/demo/...`, hostnames, usernames, email, any
   token-shaped string (`ghp_`, `sk-`, the Artus token), session UUIDs remapped deterministically, `costUsd`
   rounded, timestamps rebased to a fixed epoch. A `--check` mode greps the output for the denylist
   (real home path, `eupakhomov`, `/mnt/d`, token prefixes) and fails if any hit remains. Fixtures are
   committed (gzipped total target < 300 KB; tool outputs are already capped at 16 KB each).
5. **Playback model** (`ws.demo.ts`): emits the session's envelopes in `seq` order, paced by `ts` deltas
   scaled by a speed factor (1×/4×) and capped (`min(delta/speed, 1.5 s)`); `stream_delta` runs are replayed at
   a fixed readable cadence rather than their recorded micro-timings. Each fixture is split by a
   `demo.script` marker list into **seeded history** (emitted instantly, then `replay_complete`, mirroring the
   real WS so notification gating and the store's replay logic behave) and the **live part**.
6. **Pause-on-decision.** On a `permission_request` / plan-approval / `ask_user_question` envelope the player
   stops and waits for `send({type:'permission_response', ...})`. Allow → continue the recorded tail; Deny →
   a short recorded "denied" tail stored beside the fixture (`branches.deny`: one error `tool_result` +
   assistant text), then `turn_complete`. The decision works from the `PermissionCard` and the sidebar's inline
   buttons because both go through the same `send`. `user_message` is accepted and answered with a fixed
   assistant note ("This is a recording — install Artus to talk to an agent"); `interrupt`,
   `set_permission_mode`, `set_model` update in-memory state only.
7. **`rest.demo.ts`**: same `api` shape, backed by the fixtures held in module state so UI mutations feel real
   (`patchSession`, rename, budget, mark blocks → append a synthetic `block_marked` envelope). Static answers for
   `getSettings` (token off, default theme), `authStatus` (open), `providers`, `slackStatus` (off),
   `memoryPendingCount` (0), `librarySources` (empty), `sessionHistory` (the closed fixtures), `staleSessions`
   (empty), `visualizeStatus` (from the fixture). **Everything else throws
   `new ApiError(501, 'Not available in the demo')`** — the UI already renders `ApiError` inline, and this is
   the safe default for any method added later. No `fetch` is ever issued; a vitest asserts the demo bundle
   source has no `fetch(`/`WebSocket` call (guards against a leak to a real backend).
8. **Demo bar**: one demo-only component (`src/demo/DemoBar.tsx`, mounted by `main.tsx` only when
   `import.meta.env.MODE === 'demo'`, so it's dead-code-eliminated from the real build): "Recording" badge,
   play/pause, speed 1×/4×, restart scenario, link "Get Artus" (GitHub). Dashboard tokens only (no hex), sizes via
   `--font-scale`, icons from `icons.ts` (new role names if needed, e.g. `Play`, `Pause`, `Restart`).
9. **Embedding contract for the website**: `/demo/` is a self-contained static site (relative `base`, no
   server rewrites). Query params: `?theme=dark|light` sets `data-theme` before first paint (the dashboard
   already honours `data-theme`; the param just seeds the stored pref), `?speed=`, `?autoplay=0`. No `X-Frame-Options`/frame-ancestors restriction is added by us (hosting side owns
   headers). The Visualize pop-out (`DiagramDialog.tsx:8`, `window.open('/?diagram=…')`) is rewritten to
   `import.meta.env.BASE_URL` so it works under `/demo/`; `main.tsx`'s `?diagram=` branch stays.
10. **Storage isolation**: the demo runs on the website's origin, so its `localStorage` keys must not collide
    with anything else; `prefs.ts` already namespaces with `artus.*`. Demo mode additionally skips
    `storageMigration.ts` and never stores a token.
11. **Not in scope**: the Astro site, Cloudflare setup, the `fetch-demo.sh` pipeline (site repo, next task),
    and live recording UI. Screenshots/screencast are produced from this build but their *placement* in the site
    belongs to the site task.

## Steps

### Step 1 — Demo build plumbing (≈0.5 day)
- `vite.config.ts`: `defineConfig(({ mode }) => …)`; for `mode === 'demo'` add the `resolveId` plugin, `base:
  '/demo/'`, `build.outDir: 'dist-demo'`. Default (non-demo) config byte-for-byte equivalent.
- `package.json`: `"build:demo": "tsc --noEmit && vite build --mode demo"`, `"dev:demo": "vite --mode demo"`.
- `.gitignore`: `frontend/dist-demo/`.
- `vite-env.d.ts`: nothing new (`MODE` is built in).
- Smoke: `npm run build:demo` produces a bundle that loads with an empty fixture set.

### Step 2 — `rest.demo.ts` + fixture loading (≈0.5 day)
- `src/demo/fixtures.ts`: loads `frontend/demo/*.json` through `import.meta.glob('../../demo/*.json', { eager: true })`
  (bundled, no runtime fetch), builds the in-memory session table.
- `src/api/rest.demo.ts`: per decision 7, typed `satisfies typeof import('./rest').api`.
- Unit tests (vitest): `listSessions`/`sessionEvents` shape, mutation reflected in the next read, unsupported
  call → `ApiError` 501.

### Step 3 — `ws.demo.ts` player + pause-on-decision (≈1 day)
- `src/demo/player.ts`: pure scheduler (no timers inside; takes a clock/`setTimeout` injectable) — cursor over
  envelopes, speed, pause states, branch selection. This is the part worth unit-testing hard:
  ordering, seeded-vs-live split, `replay_complete` placement, pause/resume, Deny branch, restart, speed change
  mid-wait.
- `src/api/ws.demo.ts`: `WsSession` wrapper over one player per session (shared registry so DemoBar controls all),
  reporting `onStatus('open')` immediately. Same-`seq` guarantees as the real class (drops `seq <= lastSeq`).
- Commands handled per decision 6.

### Step 4 — Demo bar + embedding contract (≈0.5 day)
- `src/demo/DemoBar.tsx` + styles in `styles.css` under a `.demo-bar` block (tokens only; both themes).
- `?theme/speed/autoplay` parsing in `src/demo/params.ts` (pure, tested).
- Pop-out URL via `BASE_URL`; skip `storageMigration` in demo mode.
- Manual check inside an `<iframe>` at 1280×800 and phone width (the dashboard must stay usable — if the
  grid layout is unusable at narrow widths, the demo forces focus layout mode there; verify before deciding).

### Step 5 — Fixture capture + scrub (≈0.5–1 day, partly manual)
- `frontend/demo/scrub.mjs` + vitest; `scripts/capture-demo.mjs` (Node ≥ 22, no deps; `--url --token --out
  --sessions id,id,…`, `--check` re-verifies committed fixtures).
- Create the sample repo (small public GitHub repo, a few files + a failing test + one open PR with a seeded bug for
  the review session) — **name/location is the user's call**; plan assumes `eupakhomov/artus-demo-repo`.
- Run the four scenarios on a real backend (scripted prompts, written down in `frontend/demo/SCENARIOS.md` so a
  re-capture is reproducible), capture, scrub, hand-trim (cut dead time, long thinking), add `demo.script` markers and
  the Deny branches.
- Review pass over the scrubbed JSON by a human before committing (grep is a net, not a proof).

### Step 6 — Media (≈0.5 day)
- `docs/images/`: dashboard screenshots at 2× in both themes from the demo build (deterministic, so re-shootable):
  full dashboard, permission card, review approval card, Git panel, History + sidebar. Update `dashboard.png`.
- 60–90 s screencast of the four-session scenario from the demo build (mp4 + webm), stored outside git (release
  asset) with a poster frame in the repo. A `scripts/shoot-demo.mjs` Playwright script produces the screenshots from
  `vite preview --mode demo` (Playwright is not a project dependency — run via `npx`, no `package.json` change).

### Step 7 — Docs
- `docs/plan/README.md` row for phase 24; `docs/ARCHITECTURE.md` short "Demo build" note (what is swapped, what
  throws); `CLAUDE.md` Frontend section: `npm run dev:demo` / `build:demo` and the "when you add an `api`
  method, `tsc` will tell you to add it to `rest.demo.ts`" rule; `README.md` gets the demo link once the site is live.
- CI (`ci.yml`): add `npm run build:demo` after the frontend build so the demo can't silently rot.

## Risks / open items

- **Fixture drift.** Protocol/UI changes can desync old envelopes. Mitigation: the typed `satisfies` check, the CI
  `build:demo`, and a vitest that runs every fixture through the real store reducer (`apply` + `transcriptFromEvents`)
  without throwing. Re-capture is scripted.
- **Scrub misses.** Mitigated by `--check` and the human review; the sample repo means there is little to leak in
  the first place. Keep the denylist in one file.
- **Narrow viewports.** The embedded demo may be small on the landing page; Step 4 decides whether to force focus mode.
- **Bundle size.** CodeMirror and `beautiful-mermaid` are already lazy/large; measure the demo's initial JS and fixture
  weight in Step 1/5 and report (target < 300 KB gzipped for fixtures; initial JS no worse than the real build).
- **Clock-dependent UI** ("time in state", relative ages, PR check pills) reads `Date.now()` against recorded timestamps.
  Rebasing timestamps to "now − offset" at load time (in `fixtures.ts`) keeps ages plausible; check each.
- **Tag** `v1.0.0` (website plan §9.5) is a launch-checklist item, not part of this phase.

## Definition of Done
- [ ] `npm run build` output is unchanged by this phase (no demo code, no `dist-demo` references); `mvn package` unaffected.
- [ ] `npm run build:demo` yields `dist-demo/` that works served from `/demo/` on a static server with no backend and
      issues zero network requests besides its own assets.
- [ ] All four staged sessions play; Allow and Deny both work at the permission and review-approval pause points,
      from both the card and the sidebar buttons.
- [ ] Composer message gets the fixed "recording" reply; unsupported actions show an inline "Not available in the demo".
- [ ] History, Visualize, Changes panel and Git panel show fixture content; no console errors.
- [ ] Demo bar: pause/play, 1×/4×, restart; `?theme`, `?speed`, `?autoplay` honoured.
- [ ] Works inside an iframe on a different origin at desktop and phone width; both themes checked.
- [ ] Fixtures pass `capture-demo.mjs --check` and a manual read-through; total < 300 KB gzipped.
- [ ] Vitest: player, scrub, params, rest.demo, every fixture through the real reducer; `tsc --noEmit` green;
      CI runs `build:demo`.
- [ ] Screenshots (both themes) and the screencast produced and noted for the site task.
- [ ] Docs updated (README row, ARCHITECTURE, CLAUDE.md, PROTOCOL untouched unless the fixture format is documented there).

## Manual test script
1. `cd frontend && npm run build:demo && npx vite preview --mode demo --outDir dist-demo` (or any static server on
   `/demo/`); open it with the backend **stopped**. Dashboard renders, four sessions, History with closed ones.
2. Watch session (a) reach the permission prompt; the player stops. Click Allow in the card → tests run, turn
   completes. Restart, repeat with Deny → short denied tail.
3. Repeat from the sidebar's inline buttons. Same result.
4. Session (b): approval card for the review; Allow shows the "review posted" tail.
5. Type a message in any composer → fixed recording note. Click Commit in the Git panel → inline "Not available".
6. DevTools Network: only static assets; no `/api`, no WebSocket.
7. Embed in a throwaway HTML page on another origin with `?theme=light&speed=4`; resize to 400 px width.
8. Visualize pop-out opens under `/demo/`.
9. `node scripts/capture-demo.mjs --check` passes; plant a fake `/mnt/d/` string in a fixture, it fails.
