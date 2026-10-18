# Demo scenarios (phase 24)

`fixtures/*.json` are real sessions, recorded on 2026-10-18 against the public sample repo
[`eupakhomov/artus-demo-repo`](https://github.com/eupakhomov/artus-demo-repo) (a small Node CLI + HTTP service with an
intentional timezone bug on `main` and PR #1 "Add a rate limiter to the API" carrying two seeded bugs), then scrubbed by
`scripts/capture-demo.mjs`. Everything hand-made about playback lives in `scripts.json`, keyed by fixture file name, so a
re-capture is one command.

## Setup used

- Clone at `~/artus-demo/artus-demo-repo` (repo-local `user.name`/`user.email` = `Artus Demo <demo@example.com>`, so the
  sessions' own commits carry no personal identity); every session's ecosystem path = `~/artus-demo`.
- Backend started with `ARTUS_MAX_SESSIONS=8` (the parent's `spawn_child_session` needs headroom: idle sessions count as
  live). Slack off for these sessions. Capture **before** any backend restart: a restart marks live sessions CRASHED (the
  `endAtSeq`/`state` script keys exist for a session that was captured after one anyway).

## Sessions

| # | Fixture | Type / provider / mode | Prompt (verbatim) |
|---|---|---|---|
| 1 | `01-fix-flaky-date-parsing` | development, claude sonnet, acceptEdits | "The date parser in src/dates.js returns the wrong day for ISO strings with a timezone offset. Fix it and make sure the tests pass." — then Visualize (auto) |
| 2 | `02-review-pr-1-rate-limiter` | review of PR #1, claude opus, acceptEdits | "Review PR #1 "Add a rate limiter to the API". Focus on correctness and edge cases, then submit your findings as a review with inline comments." |
| 3 | `03-add-csv-export` | development, codex (default model), default | "Add a --csv flag to the report command (src/cli.js) that prints the totals as CSV instead of the table. Add a test, and make sure the report tests pass." |
| 4 | `04-release-1-2-prep` | development, claude sonnet, acceptEdits | "Prepare the 1.2.0 release. Spawn a child session on its own branch (chore/changelog-1.2.0) to update CHANGELOG.md from the commits since the 1.1.0 tag, and meanwhile bump the version in package.json here." |
| 5 | `05-artus-demo-repo-chore-changelog-1-2-0` | the child spawned by #4 | (written by the parent) |
| 6 | `06-add-health-endpoint` | development, claude sonnet → commit, push, PR via the Git panel's AI drafts → **closed** | "Add a GET /health endpoint to the HTTP service in src/server.js that returns 200 with {"status":"ok"}, and a test for it. Run the server tests." |
| 7 | `07-document-the-report-helpers` | development, claude haiku → AI-drafted commit → **closed** | "Add JSDoc (params and return types) to the exported functions in src/report.js. No behaviour changes. Be brief." |

Every prompt was approved, except where noted below. GitHub refuses REQUEST_CHANGES on your own PR, so in #2 the agent's
first submission comes back with that reason (since this phase, `GitOpsService` passes GitHub's error body through) and it
resubmits as a COMMENT review — that real retry is part of the recording, and the review is live on PR #1.

## Capture

```bash
node scripts/capture-demo.mjs --url http://localhost:8080 --token "$ARTUS_TOKEN" --out frontend/demo/fixtures \
  --sessions <id1>,<id2>,…,<id7> \
  --path "$HOME/artus-demo/artus-demo-repo=/home/demo/projects/artus-demo-repo" --path "$HOME/artus-demo=/home/demo/projects" \
  --home "$HOME" --name "$USER=demo" --name "$(hostname)=demo-host" --keep "github.com/eupakhomov/artus-demo-repo"
node scripts/capture-demo.mjs --check --token "$ARTUS_TOKEN"
```

Capture all sessions in **one** run: session ids are remapped to stable fake UUIDs per run, and a child's
`parentSessionId` only matches its parent's within the same run. `--keep` protects the public repo URL from the
`--name` rule. The capture also strips the machine's tool/MCP inventory (`system_init` tool lists, other MCP servers in
the context breakdown and in ToolSearch results) and cuts the session row down to the fields the dashboard reads
(`--keep-environment` disables that).

## scripts.json

Per fixture (all keys optional):

- `seedUntilSeq` — events up to this seq are shown instantly; the rest play live.
- `pauseOn` — the permission `requestId`s the viewer decides (`[]` = none). Other prompts play through, answered as
  recorded. Pause points used: #1 `p1` (the test run), #2 `p8` (the final review submission), #4 `p3` (committing the
  version bump — not the child spawn, since denying that would contradict the child that is already in the recording).
- `deny` — per pause point, what plays when the viewer denies; the rest of the recording is dropped. These tails are
  hand-written (a real deny of the same prompt would end the recorded run): `state_changed RUNNING`, a `tool_result`
  "Denied by user" error for the guarded tool call, one assistant message, a `turn_complete`, `state_changed IDLE`. #2's
  wording follows the reply recorded when that submission really was denied in a first attempt.
- `endAtSeq`, `dropSeqs`, `state` — trims: #1 and #3 were captured after a backend restart (crash tail cut, state
  IDLE); #3 also drops two environment-only notices (WSL's missing bubblewrap sandbox, "codex does not support context
  directories").

After a re-capture: check that the seqs referenced here still exist (`npm test` fails if a pause point is missing or has
no deny tail), read every fixture end to end (`--check` is a net, not a proof; add new identifiers to `denylist.txt`), and
run `npm run build:demo`.

## memory.json and library.json

Not recorded: hand-written data for the Memory and Skill-library dialogs (website W0.2), so their screenshots are
reproducible. Ages are relative (`ageDays`, `ageMinutes`), memory links are `[[slug]]` in a note's content, and the
service path is the scrubbed one the fixtures use (`/home/demo/projects/artus-demo-repo`). `node scripts/capture-demo.mjs --check`
scans them with the same denylist. Session ids in proposals/episodes must match the fixtures' ids.
