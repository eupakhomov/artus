#!/usr/bin/env node
// Captures sessions from a running Artus backend into scrubbed demo fixtures (phase 24; see frontend/demo/SCENARIOS.md).
//   node scripts/capture-demo.mjs --url http://localhost:8080 --token $T --out frontend/demo/fixtures \
//        --sessions <id>,<id>,… [--home /home/me] [--path /real/repo=/home/demo/projects/artus-demo-repo] \
//        [--name alicebook=demo-host] [--deny alice] [--keep github.com/alice/public-repo] [--keep-environment]
//   node scripts/capture-demo.mjs --check [--out frontend/demo/fixtures] [--deny alice]   # exit 1 on any leak
// Both modes also apply frontend/demo/denylist.txt and treat the Artus token (--token / $ARTUS_TOKEN) as a secret: it is
// redacted on capture and its presence fails the check. Needs Node >= 22, no dependencies. Playback scripting
// (seed point, pause points, deny tails, trims) lives in frontend/demo/scripts.json, which a capture never touches.
import { existsSync, readdirSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { findLeaks, scrubFixtures } from '../frontend/demo/scrub.mjs';

const args = process.argv.slice(2);
const flag = (n) => args.includes(`--${n}`);
const opt = (n) => args.flatMap((a, i) => (a === `--${n}` ? [args[i + 1]] : []));
const out = opt('out')[0] ?? 'frontend/demo/fixtures';
const token = opt('token')[0] ?? process.env.ARTUS_TOKEN;
const deny = [...opt('deny'), ...(token ? [token] : [])];
const denylist = join(dirname(fileURLToPath(import.meta.url)), '..', 'frontend', 'demo', 'denylist.txt');
const denyRegexes = readFileSync(denylist, 'utf8').split(/\r?\n/).map((l) => l.trim()).filter((l) => l && !l.startsWith('#'));

if (flag('check')) {
  let bad = 0;
  // the recorded fixtures, plus the hand-made memory/library data that sits next to them (website W0.2)
  const files = readdirSync(out).filter((x) => x.endsWith('.json')).map((f) => join(out, f));
  for (const f of ['memory.json', 'library.json']) {
    const p = join(out, '..', f);
    if (existsSync(p)) files.push(p);
  }
  for (const f of files) {
    const leaks = findLeaks(JSON.parse(readFileSync(f, 'utf8')), deny, denyRegexes);
    for (const l of leaks) { bad++; console.error(`${f}: ${l.where}: ${l.match}`); }
  }
  console.log(bad ? `${bad} leak(s) found` : `fixtures clean (${out})`);
  process.exit(bad ? 1 : 0);
}

const base = (opt('url')[0] ?? 'http://localhost:8080').replace(/\/$/, '');
const ids = (opt('sessions')[0] ?? '').split(',').filter(Boolean);
if (!ids.length) { console.error('--sessions <id,id,…> is required'); process.exit(2); }

async function get(path, optional = false) {
  const res = await fetch(base + path, { headers: token ? { authorization: `Bearer ${token}` } : {} });
  if (!res.ok) {
    if (optional) return null;
    throw new Error(`${path}: HTTP ${res.status}`);
  }
  return res.json();
}

const pairs = (k) => opt(k).map((v) => v.split('=')).filter((p) => p.length === 2);
const summaries = [...(await get('/api/sessions')), ...(await get('/api/sessions/history?limit=20'))];
const captured = [];
for (const id of ids) {
  const detail = await get(`/api/sessions/${id}`);
  const summary = summaries.find((s) => s.id === id);
  if (!summary) throw new Error(`session ${id} is neither open nor in the closed history`);
  const events = await get(`/api/sessions/${id}/events`);
  const changes = await get(`/api/sessions/${id}/changes`, true);
  const files = {};
  for (const f of changes?.files ?? []) {
    const view = await get(`/api/sessions/${id}/changes/file?path=${encodeURIComponent(f.path)}`, true);
    if (view) files[f.path] = view;
  }
  const vis = await get(`/api/sessions/${id}/visualize`, true);
  // the Git panel's reads; a closed session has no worktree left, so these are absent there
  const gitStatus = await get(`/api/sessions/${id}/git/status`, true);
  const gitLog = gitStatus ? await get(`/api/sessions/${id}/git/log`, true) : null;
  const gitDiff = gitStatus ? await get(`/api/sessions/${id}/git/diff`, true) : null;
  captured.push({
    summary, detail, events, ...(changes ? { changes, files } : {}),
    ...(gitStatus ? { git: { status: gitStatus, log: gitLog ?? [], ...(gitDiff?.diff ? { diff: gitDiff.diff } : {}) } } : {}),
    ...(vis?.latest ? { diagram: vis.latest, currentFingerprint: vis.currentFingerprint } : {}),
  });
  console.log(`captured ${summary.name} (${events.length} events)`);
}

const scrubbed = scrubFixtures(captured, {
  homes: opt('home'), paths: pairs('path'), names: pairs('name'), keep: opt('keep'), secrets: token ? [token] : [],
  stripEnvironment: !flag('keep-environment'),
});
mkdirSync(out, { recursive: true });
scrubbed.forEach((f, i) => {
  const slug = f.summary.name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 40);
  writeFileSync(join(out, `${String(i + 1).padStart(2, '0')}-${slug}.json`), JSON.stringify(f, null, 1) + '\n');
});
const leaks = scrubbed.flatMap((f) => findLeaks(f, deny, denyRegexes));
for (const l of leaks) console.error(`LEAK ${l.where}: ${l.match}`);
console.log(leaks.length ? `${leaks.length} leak(s) remain — fix the scrub options and re-run` : `wrote ${scrubbed.length} fixtures to ${out}; check frontend/demo/scripts.json still matches, then read them through`);
process.exit(leaks.length ? 1 : 0);
