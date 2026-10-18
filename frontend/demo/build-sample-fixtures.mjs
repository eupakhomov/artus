#!/usr/bin/env node
// Authors the SAMPLE demo fixtures in the exact format scripts/capture-demo.mjs writes (docs/plan/phase-24-demo-mode.md).
// They are hand-scripted, not recorded: a stand-in so the demo build works end to end. Replace them with real, scrubbed
// captures (scripts/capture-demo.mjs + frontend/demo/SCENARIOS.md) before the launch; the player/rest layers don't care.
//   node frontend/demo/build-sample-fixtures.mjs
import { writeFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const out = join(dirname(fileURLToPath(import.meta.url)), 'fixtures');
mkdirSync(out, { recursive: true });

const T0 = Date.parse('2026-10-18T09:00:00Z');
const REPO = '/home/demo/projects/artus-demo-repo';
const WT = '/home/demo/agentic-worktrees';
const uuid = (n) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;

const CLAUDE_CAPS = {
  permissionModes: ['default', 'acceptEdits', 'plan', 'bypassPermissions'], thinking: true, effort: true, planMode: true,
  resume: true, skills: true, agents: true, mcp: true, interrupt: true, fallbackModel: true, updatedInput: true,
  modelSwitch: true, compact: true, models: [
    { id: 'haiku', label: 'Haiku', tier: 'cheap' }, { id: 'sonnet', label: 'Sonnet', tier: 'standard' }, { id: 'opus', label: 'Opus', tier: 'premium' }],
};
const CODEX_CAPS = { ...CLAUDE_CAPS, permissionModes: ['default', 'bypassPermissions'], thinking: false, planMode: false, agents: false, fallbackModel: false, models: [] };

/** Journal builder: one running clock, monotonic seq. */
class Journal {
  constructor(startMs) { this.t = startMs; this.seq = 0; this.events = []; this.cost = 0; this.id = 0; }
  add(type, payload = {}, dtMs = 600) {
    this.t += dtMs;
    this.events.push({ seq: ++this.seq, ts: new Date(this.t).toISOString(), type, payload });
    return this.seq;
  }
  state(s, dt) { return this.add('state_changed', { state: s }, dt); }
  text(txt, dt = 500) {
    for (let i = 0; i < txt.length; i += 28) this.add('stream_delta', { deltaType: 'text', text: txt.slice(i, i + 28) }, 20);
    this.add('assistant_message', { content: [{ type: 'text', text: txt }] }, dt);
  }
  think(txt) {
    this.add('stream_delta', { deltaType: 'thinking', text: txt }, 300);
    this.add('thinking_progress', { estimatedTokens: Math.ceil(txt.length / 4) }, 100);
  }
  tool(name, input, output, { isError = false, dt = 700 } = {}) {
    const toolUseId = `toolu_${String(++this.id).padStart(4, '0')}`;
    this.add('tool_started', { toolUseId, name, input }, dt);
    this.add('tool_result', { toolUseId, output, isError, truncated: false }, 450);
  }
  /** WAITING_INPUT → permission_request → recorded "allow" echo → RUNNING (the player pauses at the request). */
  permission(requestId, toolName, input) {
    this.state('WAITING_INPUT', 300);
    const seq = this.add('permission_request', { requestId, toolName, input, suggestions: [] }, 200);
    this.add('permission_response', { requestId, behavior: 'allow' }, 1200);
    this.state('RUNNING', 100);
    return seq;
  }
  turn(cost, model, ctxTokens, breakdown) {
    this.cost += cost;
    this.add('turn_complete', { stopReason: 'end_turn', costUsd: cost, durationMs: 14000, numTurns: 3, usage: {}, model }, 400);
    this.add('context_usage', { tokens: ctxTokens, window: 200000, autoCompactAt: 160000, ...(breakdown ? { breakdown } : {}) }, 50);
    this.state('IDLE', 50);
  }
  start(caps, model, mode = 'default') {
    this.state('STARTING', 0);
    this.add('ready', { capabilities: caps }, 300);
    this.state('IDLE', 100);
    this.mode = mode; this.model = model;
  }
  prompt(text, model) {
    this.add('user_message', { text }, 1500);
    this.state('RUNNING', 100);
    this.add('system_init', { providerSessionId: `prov-${this.seq}`, model, cwd: '', tools: [], mcpServers: [], permissionMode: this.mode ?? 'default' }, 400);
  }
}

function session(n, over) {
  return {
    id: uuid(n), name: '', provider: 'claude', slackEnabled: false, repoPath: REPO, servicePath: REPO, cwdPath: '', branch: '',
    baseBranch: 'main', worktreePath: '', providerSessionId: `prov-${n}`, capabilities: CLAUDE_CAPS, model: 'claude-sonnet-5-5',
    permissionMode: 'default', ecosystemPath: null, contextDirs: [], thinking: null, effort: null, costBudgetUsd: 5, state: 'IDLE',
    kickoffPrompt: null, kind: 'user', sessionType: 'development', ticketRef: null, continuedFromId: null, parentSessionId: null,
    prUrl: null, prCheckStatus: null, prCheckedAt: null, reflectionEnabled: false, codeIntel: null, contextTokens: null, contextWindow: 200000, ...over,
  };
}

/** main's history, shared by every session's Git panel log (newest first) */
const BASE_LOG = [
  ['e4f5a6b', 'Merge PR #6: fix date parsing (1.1.x)', 41],
  ['d3e4f5a', 'Add report command', 49],
  ['c2d3e4f', 'Initial commit', 73],
].map(([hash, subject, hoursAgo]) => ({ hash, subject, author: 'Demo Maintainer', date: new Date(T0 - hoursAgo * 3_600_000).toISOString() }));

/** Git panel reads (`/git/status|log|diff`) in the shape the backend returns them */
function gitFor(branch, changes, commits = [], diff) {
  const dirty = (changes?.files ?? []).filter((f) => f.where.some((w) => w !== 'committed')).map((f) => f.path);
  const log = commits.map((subject, i) => ({ hash: `f${i}a1b2c`, subject, author: 'Demo Maintainer', date: new Date(T0 - (i + 1) * 600_000).toISOString() }));
  return {
    status: { branch, dirty, upstream: commits.length ? `origin/${branch}` : null, ahead: 0, behind: 0, aheadOfBase: commits.length },
    log: [...log, ...BASE_LOG],
    ...(diff ? { diff } : {}),
  };
}

function fixture(n, j, over, extra = {}) {
  const s = session(n, { ...over, cwdPath: `${WT}/${over.branch.replace(/\W+/g, '-')}`, worktreePath: `${WT}/${over.branch.replace(/\W+/g, '-')}` });
  s.contextTokens = [...j.events].reverse().find((e) => e.type === 'context_usage')?.payload.tokens ?? null;
  const last = j.events.at(-1);
  const summary = {
    id: s.id, name: s.name, provider: s.provider, repoPath: s.repoPath, servicePath: s.servicePath, branch: s.branch, model: s.model,
    permissionMode: s.permissionMode, state: s.state, kind: 'user', sessionType: s.sessionType, costToDate: +j.cost.toFixed(4),
    updatedAt: last.ts, lastSeq: last.seq, closedAt: over.closedAt ?? null, prUrl: s.prUrl,
  };
  delete s.closedAt;
  const detail = { session: s, queued: [], lastSeq: last.seq, costToDate: summary.costToDate };
  if (extra.parentName) detail.parentName = extra.parentName;
  const { seedUntilSeq, deny, parentName, commits, diff, ...rest } = extra;
  void parentName;
  const git = s.state === 'CLOSED' ? undefined : gitFor(s.branch, rest.changes, commits, diff);
  return { summary, detail, events: j.events, ...(seedUntilSeq ? { script: { seedUntilSeq, ...(deny ? { deny } : {}) } } : {}), ...rest, ...(git ? { git } : {}) };
}

const write = (file, f) => writeFileSync(join(out, file), JSON.stringify(f, null, 1) + '\n');
const fileView = (path, status, content, baseContent) =>
  ({ path, oldPath: null, status, content, baseContent, binary: false, truncated: false, size: (content ?? '').length });
const change = (path, status, where, additions, deletions) =>
  ({ path, oldPath: null, status, where, additions, deletions, mtime: 1 });

const denyTail = (text, cost = 0.006) => [
  { type: 'state_changed', payload: { state: 'RUNNING' } },
  { type: 'stream_delta', payload: { deltaType: 'text', text } },
  { type: 'assistant_message', payload: { content: [{ type: 'text', text }] } },
  { type: 'turn_complete', payload: { stopReason: 'end_turn', costUsd: cost, durationMs: 2100, numTurns: 1, usage: {}, model: null } },
];

// ---------------------------------------------------------------- 01: development session, Bash permission prompt
{
  const j = new Journal(T0);
  j.start(CLAUDE_CAPS, 'claude-sonnet-5-5');
  j.prompt('The date parser in src/dates.js returns the wrong day for ISO strings with a timezone offset. Fix it and make sure the tests pass.', 'claude-sonnet-5-5');
  j.think('The failing case is probably new Date(str) mixed with getDate() — local vs UTC. Let me read the file and the test first.');
  j.text('Let me look at the parser and its tests.');
  j.tool('Read', { file_path: 'src/dates.js' },
    "export function parseDay(iso) {\n  const d = new Date(iso);\n  return new Date(d.getFullYear(), d.getMonth(), d.getDate());\n}\n");
  j.tool('Read', { file_path: 'test/dates.test.js' },
    "import { parseDay } from '../src/dates.js';\nimport assert from 'node:assert';\nassert.equal(parseDay('2026-03-01T23:30:00-05:00').getUTCDate(), 2);\n");
  const seed = j.seq;
  j.text('The bug: `getFullYear()/getMonth()/getDate()` read the *local* calendar day, so the result depends on the machine\'s timezone. The test expects the UTC day (March 2). I\'ll switch to the UTC getters.');
  j.tool('Edit', { file_path: 'src/dates.js',
    old_string: "return new Date(d.getFullYear(), d.getMonth(), d.getDate());",
    new_string: "return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()));" }, 'The file src/dates.js has been updated.');
  j.text('Now I\'ll run the test suite to confirm.');
  const reqId = 'req-bash-1';
  j.permission(reqId, 'Bash', { command: 'npm test', description: 'Run the test suite' });
  j.tool('Bash', { command: 'npm test', description: 'Run the test suite' },
    '> artus-demo-repo@1.0.0 test\n> node --test\n\n✔ parseDay handles offsets (1.2ms)\n✔ parseDay keeps UTC midnight (0.4ms)\n\nℹ tests 2\nℹ pass 2\nℹ fail 0', { dt: 600 });
  j.text('Both tests pass. `parseDay` now returns UTC midnight of the UTC calendar day, independent of the machine\'s timezone. The change is one line in `src/dates.js`.');
  j.turn(0.0412, 'claude-sonnet-5-5', 31200, {
    categories: [{ name: 'System prompt', tokens: 3100 }, { name: 'Tools', tokens: 9400 }, { name: 'Messages', tokens: 18700 }],
    mcpServers: [{ name: 'memory', tokens: 1200, deferred: true }], topTools: [{ name: 'Read', tokens: 2300 }, { name: 'Bash', tokens: 900 }],
  });
  const dates = "export function parseDay(iso) {\n  const d = new Date(iso);\n  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()));\n}\n";
  const datesBase = "export function parseDay(iso) {\n  const d = new Date(iso);\n  return new Date(d.getFullYear(), d.getMonth(), d.getDate());\n}\n";
  j.add('diagram_generated', { diagramType: 'flowchart', fingerprint: 'demo-fp-01', repaired: false, rationale: 'Control flow of the fixed parser.',
    mermaid: 'flowchart TD\n  A[parseDay iso] --> B[new Date iso]\n  B --> C[read UTC year/month/day]\n  C --> D[Date.UTC midnight]\n  D --> E[test: offset keeps UTC day]' }, 200);
  write('01-fix-date-parsing.json', fixture(1, j,
    { name: 'Fix flaky date parsing', branch: 'fix/date-parsing', contextTokens: 31200 },
    { seedUntilSeq: seed,
      diff: 'diff --git a/src/dates.js b/src/dates.js\n--- a/src/dates.js\n+++ b/src/dates.js\n@@ -1,4 +1,4 @@\n export function parseDay(iso) {\n   const d = new Date(iso);\n-  return new Date(d.getFullYear(), d.getMonth(), d.getDate());\n+  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()));\n }\n',
      deny: { [reqId]: denyTail('Understood — I won\'t run the tests. The fix is in place in src/dates.js; run `npm test` yourself whenever you like.') },
      changes: { baseBranch: 'main', mergeBase: 'a1b2c3d', baseKnown: true, files: [change('src/dates.js', 'modified', ['unstaged'], 1, 1)] },
      files: { 'src/dates.js': fileView('src/dates.js', 'modified', dates, datesBase) },
      diagram: { diagramType: 'flowchart', mermaid: 'flowchart TD\n  A[parseDay iso] --> B[new Date iso]\n  B --> C[read UTC year/month/day]\n  C --> D[Date.UTC midnight]\n  D --> E[test: offset keeps UTC day]',
        rationale: 'Control flow of the fixed parser.', fingerprint: 'demo-fp-01', repaired: false, seq: j.seq }, currentFingerprint: 'demo-fp-01' }));
}

// ---------------------------------------------------------------- 02: review session, submit_pr_review approval card
{
  const j = new Journal(T0 + 60_000);
  j.start(CLAUDE_CAPS, 'claude-opus-5-5');
  j.prompt('Review PR #7 "Add a rate limiter to the API". Focus on correctness and edge cases, then submit your findings as a review.', 'claude-opus-5-5');
  j.text('I\'ll read the diff and the new module.');
  j.tool('Bash', { command: 'git diff origin/main...HEAD --stat' }, ' src/rateLimiter.js | 31 +++++++++++++++++++++++++++++++\n src/server.js      |  4 ++++\n 2 files changed, 35 insertions(+)');
  j.tool('Read', { file_path: 'src/rateLimiter.js' },
    "const hits = new Map();\nexport function limit(ip, max = 100, windowMs = 60000) {\n  const now = Date.now();\n  const list = (hits.get(ip) ?? []).filter((t) => t > now - windowMs);\n  list.push(now);\n  hits.set(ip, list);\n  return list.length <= max;\n}\n");
  const seed = j.seq;
  j.text('Two real problems: the `hits` map never evicts idle IPs (unbounded memory), and a rejected request is still recorded, so a client hammering the API stays blocked forever. I\'ll submit this as a review with inline comments.');
  const reqId = 'req-review-1';
  j.permission(reqId, 'mcp__memory__submit_pr_review', {
    sessionId: uuid(2), event: 'REQUEST_CHANGES',
    body: 'Nice, small and readable. Two issues to fix before merging: unbounded memory growth, and rejected requests extend the block.',
    comments: [
      { path: 'src/rateLimiter.js', line: 1, body: '`hits` never drops idle IPs — a slow scan of many addresses grows this forever. Evict empty lists or use an LRU.' },
      { path: 'src/rateLimiter.js', line: 5, body: 'Pushing before the check records rejected requests, so a client above the limit never recovers. Check first, then push.' },
    ] });
  j.tool('mcp__memory__submit_pr_review', { event: 'REQUEST_CHANGES' }, 'submitted REQUEST_CHANGES review with 2 inline comment(s) to https://github.com/eupakhomov/artus-demo-repo/pull/7', { dt: 800 });
  j.add('pr_review_submitted', { event: 'REQUEST_CHANGES', commentCount: 2, prUrl: 'https://github.com/eupakhomov/artus-demo-repo/pull/7' }, 100);
  j.text('Review submitted to PR #7: changes requested, with two inline comments.');
  j.turn(0.1184, 'claude-opus-5-5', 24800);
  const rl = "const hits = new Map();\nexport function limit(ip, max = 100, windowMs = 60000) {\n  const now = Date.now();\n  const list = (hits.get(ip) ?? []).filter((t) => t > now - windowMs);\n  list.push(now);\n  hits.set(ip, list);\n  return list.length <= max;\n}\n";
  write('02-review-pr-7.json', fixture(2, j,
    { name: 'Review PR #7: rate limiter', branch: 'feat/rate-limiter', sessionType: 'review', model: 'claude-opus-5-5',
      prUrl: 'https://github.com/eupakhomov/artus-demo-repo/pull/7', prCheckStatus: 'SUCCESS', prCheckedAt: new Date(T0 + 50_000).toISOString() },
    { seedUntilSeq: seed, commits: ['Wire the rate limiter into the server', 'Add a per-IP rate limiter'],
      deny: { [reqId]: denyTail('Okay, I won\'t post anything to GitHub. My findings stay in this transcript: unbounded `hits` growth, and rejected requests are still recorded.') },
      changes: { baseBranch: 'main', mergeBase: 'b2c3d4e', baseKnown: true, files: [change('src/rateLimiter.js', 'added', ['committed'], 8, 0), change('src/server.js', 'modified', ['committed'], 4, 0)] },
      files: { 'src/rateLimiter.js': fileView('src/rateLimiter.js', 'added', rl, null),
        'src/server.js': fileView('src/server.js', 'modified', "import { limit } from './rateLimiter.js';\n// …\n", "// …\n") } }));
}

// ---------------------------------------------------------------- 03: Codex session (no per-turn USD, estimated)
{
  const j = new Journal(T0 + 120_000);
  j.start(CODEX_CAPS, 'gpt-5-codex');
  j.prompt('Add a `--csv` flag to the report command that prints the totals as CSV.', 'gpt-5-codex');
  j.text('I\'ll find where the report command formats its output.');
  j.tool('Bash', { command: 'rg -n "formatReport" src' }, 'src/report.js:12:export function formatReport(rows) {\nsrc/cli.js:9:  console.log(formatReport(rows));');
  const seed = j.seq;
  j.tool('Edit', { file_path: 'src/report.js', old_string: 'export function formatReport(rows) {', new_string: 'export function formatCsv(rows) {\n  return ["name,total", ...rows.map((r) => `${r.name},${r.total}`)].join("\\n");\n}\n\nexport function formatReport(rows) {' }, 'Applied patch to src/report.js');
  j.tool('Edit', { file_path: 'src/cli.js', old_string: 'console.log(formatReport(rows));', new_string: "console.log(process.argv.includes('--csv') ? formatCsv(rows) : formatReport(rows));" }, 'Applied patch to src/cli.js');
  j.tool('Bash', { command: 'node --test' }, 'ℹ tests 5\nℹ pass 5\nℹ fail 0');
  j.text('Added `formatCsv` in `src/report.js` and wired `--csv` in `src/cli.js`. All 5 tests pass.');
  j.turn(0, 'gpt-5-codex', 18400);
  write('03-add-csv-export.json', fixture(3, j,
    { name: 'Add CSV export', provider: 'codex', model: 'gpt-5-codex', capabilities: CODEX_CAPS, branch: 'feat/csv-export' },
    { seedUntilSeq: seed,
      diff: "diff --git a/src/cli.js b/src/cli.js\n--- a/src/cli.js\n+++ b/src/cli.js\n@@ -9 +9 @@\n-  console.log(formatReport(rows));\n+  console.log(process.argv.includes('--csv') ? formatCsv(rows) : formatReport(rows));\n",
      changes: { baseBranch: 'main', mergeBase: 'c3d4e5f', baseKnown: true, files: [change('src/report.js', 'modified', ['unstaged'], 4, 0), change('src/cli.js', 'modified', ['unstaged'], 1, 1)] },
      files: { 'src/report.js': fileView('src/report.js', 'modified', 'export function formatCsv(rows) {}\n', 'export function formatReport(rows) {}\n'),
        'src/cli.js': fileView('src/cli.js', 'modified', "console.log(process.argv.includes('--csv') ? formatCsv(rows) : formatReport(rows));\n", 'console.log(formatReport(rows));\n') } }));
}

// ---------------------------------------------------------------- 04 + 05: parent with a child fan-out
const parentId = uuid(4);
{
  const j = new Journal(T0 + 180_000);
  j.start(CLAUDE_CAPS, 'claude-sonnet-5-5');
  j.prompt('Prepare the 1.2.0 release: the changelog needs updating in its own branch, and bump the version here.', 'claude-sonnet-5-5');
  j.text('I\'ll hand the changelog to a child session on its own branch and bump the version myself.');
  const seed = j.seq;
  j.tool('mcp__memory__spawn_child_session', { sessionId: parentId, servicePath: REPO, branch: 'chore/changelog-1.2.0', prompt: 'Update CHANGELOG.md for 1.2.0 from the merged PRs since 1.1.0.' },
    '{"sessionId":"' + uuid(5) + '","name":"chore/changelog-1.2.0"}');
  j.tool('Edit', { file_path: 'package.json', old_string: '"version": "1.1.0"', new_string: '"version": "1.2.0"' }, 'The file package.json has been updated.');
  j.text('Version bumped to 1.2.0; the child session is writing the changelog and will report back.');
  j.turn(0.0287, 'claude-sonnet-5-5', 15200);
  write('04-release-1-2-parent.json', fixture(4, j, { name: 'Release 1.2 prep', branch: 'chore/release-1.2.0' },
    { seedUntilSeq: seed, commits: ['Bump version to 1.2.0'] }));
}
{
  const j = new Journal(T0 + 200_000);
  j.start(CLAUDE_CAPS, 'claude-sonnet-5-5');
  j.prompt('Update CHANGELOG.md for 1.2.0 from the merged PRs since 1.1.0.', 'claude-sonnet-5-5');
  j.text('Reading the merged PRs since the 1.1.0 tag.');
  const seed = j.seq;
  j.tool('Bash', { command: 'git log 1.1.0..HEAD --merges --oneline' }, 'f3a91c2 Merge PR #5: CSV export\n9d2e7b1 Merge PR #6: fix date parsing\n4c8a0e5 Merge PR #7: rate limiter');
  j.tool('Edit', { file_path: 'CHANGELOG.md', old_string: '# Changelog\n', new_string: '# Changelog\n\n## 1.2.0\n- Add `--csv` output to the report command (#5)\n- Fix timezone-dependent date parsing (#6)\n- Add a request rate limiter (#7)\n' }, 'The file CHANGELOG.md has been updated.');
  j.text('CHANGELOG.md now has a 1.2.0 section covering PRs #5–#7. Reporting back to the parent.');
  j.turn(0.0162, 'claude-sonnet-5-5', 11800);
  write('05-changelog-child.json', fixture(5, j,
    { name: 'chore/changelog-1.2.0', branch: 'chore/changelog-1.2.0', parentSessionId: parentId }, { seedUntilSeq: seed, parentName: 'Release 1.2 prep', commits: ['Update CHANGELOG for 1.2.0'] }));
}

// ---------------------------------------------------------------- 06 + 07: closed sessions (History)
for (const [n, name, branch, mins, cost, pr] of [
  [6, 'Bump lodash to 4.17.21', 'chore/bump-lodash', 400, 0.0213, 'https://github.com/eupakhomov/artus-demo-repo/pull/3'],
  [7, 'Add health endpoint', 'feat/health-endpoint', 260, 0.0348, 'https://github.com/eupakhomov/artus-demo-repo/pull/4'],
]) {
  const j = new Journal(T0 - mins * 60_000);
  j.start(CLAUDE_CAPS, 'claude-sonnet-5-5');
  j.prompt(`${name}.`, 'claude-sonnet-5-5');
  j.text('On it — making the change and running the tests.');
  j.tool('Bash', { command: 'npm test' }, 'ℹ tests 4\nℹ pass 4\nℹ fail 0');
  j.text('Done: the change is in and the tests pass. Ready for a PR.');
  j.turn(cost, 'claude-sonnet-5-5', 9800);
  const closedAt = new Date(j.t + 600_000).toISOString();
  write(`0${n}-closed-${branch.split('/')[1]}.json`, fixture(n, j,
    { name, branch, state: 'CLOSED', prUrl: pr, prCheckStatus: 'MERGED', closedAt }, {}));
}
console.log('wrote sample fixtures to', out);
