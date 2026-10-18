# Shooting the demo media (screenshots + screencast)

Everything is produced from the **demo build** (phase 24), which replays recorded sessions with no backend, so the
output is deterministic and re-shootable at any time. Dark theme only. Script: `scripts/shoot-demo.mjs`.

## Prerequisites

| Tool | Notes |
|---|---|
| Node ≥ 22 | as for the rest of the repo |
| Playwright + Chromium | **not** a project dependency. `npx -y -p playwright node scripts/shoot-demo.mjs` fetches it; first run needs `npx playwright install chromium` |
| `ffmpeg` on `PATH` | only for `--video` (webm → mp4) |

If `import('playwright')` does not resolve (`npx -p` does not put it on the ESM resolution path), point the script at any
installed copy: `PLAYWRIGHT_DIR=/path/to/node_modules/playwright`.

## Steps

```bash
# 1. Build and serve the demo (separate terminal, leave running)
cd frontend && npm run build:demo && npx vite preview --mode demo --port 4173

# 2. From the repo root: screenshots only
node scripts/shoot-demo.mjs

# 3. Screenshots + the ~75 s screencast
node scripts/shoot-demo.mjs --video
```

Run step 1 again whenever the frontend or the fixtures change — the shots come from `frontend/dist-demo/`, not the source.

## Output

| File | Where | Content |
|---|---|---|
| `dashboard.png` | `docs/images/` (committed) | full 4-session grid; also the screencast poster |
| `demo-permission-card.png` | `docs/images/` | a widget with a pending Bash permission prompt |
| `demo-review-card.png` | `docs/images/` | the review session's `submit_pr_review` approval |
| `demo-git-panel.png` | `docs/images/` | Git panel open on the child session |
| `demo-history-sidebar.png` | `docs/images/` | sidebar with Needs-you, sessions and History |
| `artus-demo.mp4` / `.webm` | `demo-media/` (**gitignored**) | 1600×1000 screencast; upload as a GitHub release asset |

Screenshots are 1600×1000 at 2× (3200×2000). The screencast is 1× and trimmed to 75 s: it starts at 1× speed, switches
to 4×, approves the first three prompts, opens the Git panel, then idles to the end.

## Options

`--base <url>` (default `http://localhost:4173/`), `--out <dir>` (default `docs/images`), `--video-dir <dir>` (default
`./demo-media`), `--only dashboard,permission-card,history,git-panel,review-card` (reshoot a subset).

## Notes

- The review card waits (up to 2 min) for the recorded `submit_pr_review` prompt; a fixture re-capture that changes its
  pause point (`frontend/demo/scripts.json`, `pauseOn`) needs no script change, but the shot's wait selector is on the
  tool name.
- Element shots hide the demo control strip (`.demo-bar`) so it doesn't overlap the widget's bottom edge.
- Check the images by eye after a reshoot: playback timing is deterministic, but the layout depends on the viewport.
- Fixture workflow (recording new sessions) is separate: `frontend/demo/SCENARIOS.md`.
