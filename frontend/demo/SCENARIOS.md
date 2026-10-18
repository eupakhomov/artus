# Demo scenarios (phase 24)

`fixtures/*.json` currently hold the **hand-scripted sample set** (`node build-sample-fixtures.mjs`): good enough to
exercise the whole demo build, but not recordings. For the launch, replace them with real sessions run on the public
sample repo (assumed `eupakhomov/artus-demo-repo`: a few JS files, one failing date-parsing test, one open PR
"Add a rate limiter" with a seeded bug).

## Sessions to run (fresh backend, throwaway worktree root)

| # | Type / provider | Prompt (verbatim) | Must show |
|---|---|---|---|
| 1 | development, claude (default permissions) | "The date parser in src/dates.js returns the wrong day for ISO strings with a timezone offset. Fix it and make sure the tests pass." | Read, Edit, then a **Bash `npm test` permission prompt**; run Visualize once; leave an uncommitted diff |
| 2 | review, claude | Review PR #7, "focus on correctness and edge cases, then submit your findings as a review" | the human-gated **`submit_pr_review` approval card** (do not actually approve against a real repo: capture up to the prompt, then add the allow tail by hand or use a throwaway fork) |
| 3 | development, codex | "Add a `--csv` flag to the report command that prints the totals as CSV." | provider chip, no per-turn USD |
| 4 | parent + child | Parent: "Prepare the 1.2.0 release: the changelog needs updating in its own branch, and bump the version here." | `spawn_child_session`, child row under parent |
| 5-6 | any, then **Close** | two short tasks, closed after a PR | History section |

## Capture

```bash
node scripts/capture-demo.mjs --url http://localhost:8080 --token "$ARTUS_TOKEN" --out frontend/demo/fixtures \
  --sessions <id1>,<id2>,… --home "$HOME" --path /real/repo/path=/home/demo/projects/artus-demo-repo \
  --name "$(hostname)=demo-host" --deny "$USER"
node scripts/capture-demo.mjs --check --deny "$USER"
```

Then, by hand, per live fixture:
1. Add `"script": {"seedUntilSeq": N, "deny": {"<requestId>": [ …events ]}}` — seed the prompt and first tool calls,
   play the rest live. The deny tail replaces everything up to and including that turn's `turn_complete`; it should be
   `state_changed RUNNING`, a short assistant text, and a `turn_complete` (see the sample set for the shape).
2. Trim dead time and long thinking blocks (delete events; seqs only need to stay increasing).
3. Read every fixture end to end. `--check` is a net, not a proof; add any new identifier to `denylist.txt`.
   Closed sessions have no worktree, so they carry no `git` block (the capture skips it).
4. `cd frontend && npm test` (every fixture is replayed through the real store reducer) and `npm run build:demo`.
