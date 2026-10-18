#!/usr/bin/env node
/**
 * Phase 24 step 6 — screenshots and screencast of the demo build (dark theme).
 *
 *   cd frontend && npm run build:demo && npx vite preview --mode demo --port 4173 &
 *   npx -y -p playwright node scripts/shoot-demo.mjs            # screenshots -> docs/images/
 *   npx -y -p playwright node scripts/shoot-demo.mjs --video    # + screencast -> <video-dir>/artus-demo.{webm,mp4}
 *
 * Playwright is deliberately not a project dependency. If `import('playwright')` does not resolve (npx -p does not
 * put it on the ESM resolution path), set PLAYWRIGHT_DIR to a `node_modules/playwright` directory. The screencast
 * needs `ffmpeg` on PATH (webm -> mp4); the poster is dashboard.png. The demo is deterministic, so everything is re-shootable.
 *
 * Options: --base <url> (default http://localhost:4173/)  --out <dir> (default docs/images)
 *          --video-dir <dir> (default ./demo-media, outside git — upload as a release asset)  --only <name,name>
 */
import { execFileSync } from 'node:child_process';
import { mkdirSync, renameSync, readdirSync, rmSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const args = process.argv.slice(2);
const opt = (name, dflt) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 && args[i + 1] ? args[i + 1] : dflt;
};
const BASE = opt('base', 'http://localhost:4173/');
const OUT = resolve(opt('out', 'docs/images'));
const VIDEO_DIR = resolve(opt('video-dir', 'demo-media'));
const ONLY = opt('only', '') ? opt('only', '').split(',') : null;
const WANT_VIDEO = args.includes('--video');
const VIEWPORT = { width: 1600, height: 1000 };

async function loadPlaywright() {
  try {
    return await import('playwright');
  } catch {
    const dir = process.env.PLAYWRIGHT_DIR;
    if (!dir) throw new Error('playwright not resolvable: run via `npx -p playwright …` or set PLAYWRIGHT_DIR=<…/node_modules/playwright>');
    return await import(pathToFileURL(join(resolve(dir), 'index.mjs')).href);
  }
}

const url = (speed) => `${BASE}?theme=dark&speed=${speed}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const want = (name) => !ONLY || ONLY.includes(name);

async function open(browser, speed, extra = {}) {
  const ctx = await browser.newContext({ viewport: VIEWPORT, deviceScaleFactor: 2, colorScheme: 'dark', ...extra });
  const page = await ctx.newPage();
  await page.goto(url(speed));
  return { ctx, page };
}

async function shots(browser) {
  mkdirSync(OUT, { recursive: true });

  // Full dashboard + permission card + History/sidebar: 4x playback until prompts are waiting in the grid.
  const { ctx, page } = await open(browser, 4);
  await page.locator('.perm-card:not(.resolved)').first().waitFor({ timeout: 60_000 });
  await sleep(2500);
  if (want('dashboard')) await page.screenshot({ path: join(OUT, 'dashboard.png') });
  // Element shots must not include the demo strip that overlaps the widgets' bottom edge.
  const hideBar = () => page.addStyleTag({ content: '.demo-bar{display:none!important}' });
  if (want('permission-card')) {
    await hideBar();
    await page.locator('.widget:has(.perm-card:not(.resolved))').first().screenshot({ path: join(OUT, 'demo-permission-card.png') });
  }

  // Git panel: any widget's git button (g).
  if (want('git-panel')) {
    const widget = page.locator('.widget:has(button[title^="git panel"])').first();
    await widget.locator('button[title^="git panel"]').click();
    await sleep(1500);
    await page.screenshot({ path: join(OUT, 'demo-git-panel.png') });
  }
  await ctx.close();

  // History + sidebar: a short viewport keeps the sidebar compact (History sits at its bottom edge).
  if (want('history')) {
    const h = await open(browser, 4, { viewport: { width: 1600, height: 620 } });
    await h.page.locator('.perm-card:not(.resolved)').first().waitFor({ timeout: 60_000 });
    await sleep(2500);
    await h.page.locator('aside.sidebar').screenshot({ path: join(OUT, 'demo-history-sidebar.png') });
    await h.ctx.close();
  }

  // Review approval card: the review session's submit_pr_review prompt (pause point p8) — wait for it.
  if (want('review-card')) {
    const r = await open(browser, 4);
    const card = r.page.locator('.perm-card:not(.resolved):has-text("submit_pr_review")').first();
    await card.waitFor({ timeout: 120_000 });
    await sleep(800);
    await card.scrollIntoViewIfNeeded();
    await r.page.addStyleTag({ content: '.demo-bar{display:none!important}' });
    await r.page.locator('.widget:has(.perm-card:not(.resolved):has-text("submit_pr_review"))').first()
      .screenshot({ path: join(OUT, 'demo-review-card.png') });
    await r.ctx.close();
  }
}

/** ~75 s: watch the four sessions run, approve what the viewer is asked, peek at git, end on History. */
async function screencast(browser) {
  mkdirSync(VIDEO_DIR, { recursive: true });
  const tmp = join(VIDEO_DIR, '.rec');
  rmSync(tmp, { recursive: true, force: true });
  const ctx = await browser.newContext({
    viewport: VIEWPORT, deviceScaleFactor: 1, colorScheme: 'dark',
    recordVideo: { dir: tmp, size: VIEWPORT },
  });
  const page = await ctx.newPage();
  const t0 = Date.now();
  await page.goto(url(1));
  await sleep(6000);
  await page.getByTitle('4× speed').click();           // fast-forward the long thinking/tool stretches
  const approve = async (label) => {
    const b = page.locator('.perm-card:not(.resolved) .actions .primary').first();
    await b.waitFor({ timeout: 40_000 }).catch(() => {});
    if (await b.count()) { await sleep(1500); await b.click().catch(() => {}); console.log(`approved (${label}) @ ${((Date.now() - t0) / 1000).toFixed(0)}s`); }
  };
  await approve('first prompt');
  await sleep(4000);
  await page.getByTitle('Normal speed').click();      // slow down for the headline moments
  await approve('second prompt');
  await sleep(3000);
  await page.getByTitle('4× speed').click();
  const gitBtn = page.locator('button[title^="git panel"]').first();
  if (await gitBtn.count()) { await gitBtn.click(); await sleep(5000); await page.keyboard.press('Escape'); }
  await approve('third prompt');
  while (Date.now() - t0 < 75_000) await sleep(500);
  await ctx.close();                                    // flushes the video

  const rec = readdirSync(tmp).find((f) => f.endsWith('.webm'));
  const webm = join(VIDEO_DIR, 'artus-demo.webm');
  renameSync(join(tmp, rec), webm);
  rmSync(tmp, { recursive: true, force: true });
  const mp4 = join(VIDEO_DIR, 'artus-demo.mp4');
  execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-i', webm, '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '23', '-movflags', '+faststart', mp4]);
  console.log(`screencast: ${webm}, ${mp4} (poster: docs/images/dashboard.png)`);
}

const { chromium } = await loadPlaywright();
const browser = await chromium.launch();
try {
  await shots(browser);
  if (WANT_VIDEO) await screencast(browser);
} finally {
  await browser.close();
}
