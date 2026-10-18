// Pure scrubbing helpers for demo fixtures (phase 24). Used by scripts/capture-demo.mjs; unit-tested in scrub.test.mjs.
// A scrub is a net, not a proof: capture-demo.mjs --check re-scans the committed fixtures and a human reads them too.

export const DEMO_HOME = '/home/demo';
const ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/;
const UUID = /\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi;

/** secrets that must never survive: provider/GitHub tokens, bearer headers */
const SECRET_PATTERNS = [
  /\bgh[pousr]_[A-Za-z0-9]{20,}\b/g,
  /\bgithub_pat_[A-Za-z0-9_]{20,}\b/g,
  /\bsk-[A-Za-z0-9_-]{20,}\b/g,
  /\bxox[abprs]-[A-Za-z0-9-]{10,}\b/g,
  /\blin_(?:api|oauth)_[A-Za-z0-9]{20,}\b/g,
  /\bBearer\s+[A-Za-z0-9._~+/=-]{16,}/g,
];
// `git@host:` SSH remotes look like addresses but aren't personal — left alone
const EMAIL = /\b(?!git@)[A-Za-z0-9._%+-]+@(?!example\.com\b)[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b/g;

/**
 * what --check rejects (after scrubbing): real home/drive paths, secrets, real emails, plus caller-supplied literal terms
 * (`extraDeny`, e.g. the capturing user's name or token) and regex lines (`denyRegexes`, from frontend/demo/denylist.txt)
 */
export function leakPatterns(extraDeny = [], denyRegexes = []) {
  return [
    ...SECRET_PATTERNS,
    EMAIL,
    /\/home\/(?!demo\b)[A-Za-z0-9._-]+/g,
    /\/Users\/(?!demo\b)[A-Za-z0-9._-]+/g,
    /\/mnt\/[a-z]\//g,
    ...extraDeny.filter(Boolean).map((t) => new RegExp(t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi')),
    ...denyRegexes.map((r) => new RegExp(r, 'gi')),
  ];
}

function mapStrings(value, fn) {
  if (typeof value === 'string') return fn(value);
  if (Array.isArray(value)) return value.map((v) => mapStrings(v, fn));
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [fn(k), mapStrings(v, fn)]));
  }
  return value;
}

/**
 * @param fixtures  array of {summary, detail, events, ...} as the capture script collects them
 * @param opts      {paths: [[from, to]...] longest-first literal prefixes (repo, worktree root, home),
 *                   homes: ['/home/me', '/Users/me'] -> DEMO_HOME, names: [[from,to]] literal words (user/host names),
 *                   secrets: literal strings -> '[redacted]', keep: literal strings no rule may touch (a public repo URL
 *                   that contains the user name), stripEnvironment: drop the capturing machine's tool/MCP inventory,
 *                   epoch: ISO string the earliest timestamp is rebased to}
 */
export function scrubFixtures(fixtures, opts = {}) {
  if (opts.stripEnvironment) fixtures = fixtures.map(stripEnvironment);
  const paths = [...(opts.paths ?? [])].sort((a, b) => b[0].length - a[0].length);
  const homes = opts.homes ?? [];
  const names = opts.names ?? [];
  const secrets = (opts.secrets ?? []).filter(Boolean);
  const keep = (opts.keep ?? []).filter(Boolean);
  const KEEP = (i) => `\u0000keep${i}\u0000`;
  const uuids = new Map();
  const epoch = Date.parse(opts.epoch ?? '2026-10-18T09:00:00Z');

  let earliest = Infinity;
  mapStrings(fixtures, (s) => { if (ISO.test(s)) earliest = Math.min(earliest, Date.parse(s)); return s; });
  const shift = Number.isFinite(earliest) ? epoch - earliest : 0;

  const scrubString = (s) => {
    if (ISO.test(s)) return new Date(Date.parse(s) + shift).toISOString();
    let out = s;
    keep.forEach((k, i) => { out = out.split(k).join(KEEP(i)); });
    for (const [from, to] of paths) out = out.split(from).join(to);
    for (const h of homes) out = out.split(h).join(DEMO_HOME);
    for (const [from, to] of names) out = out.split(from).join(to);
    for (const secret of secrets) out = out.split(secret).join('[redacted]');
    for (const re of SECRET_PATTERNS) out = out.replace(re, '[redacted]');
    out = out.replace(EMAIL, 'demo@example.com');
    out = out.replace(UUID, (m) => {
      const key = m.toLowerCase();
      if (!uuids.has(key)) uuids.set(key, `00000000-0000-4000-8000-${String(uuids.size + 1).padStart(12, '0')}`);
      return uuids.get(key);
    });
    keep.forEach((k, i) => { out = out.split(KEEP(i)).join(k); });
    return out;
  };

  const round = (v) => (typeof v === 'number' ? Math.round(v * 10_000) / 10_000 : v);
  const walk = (v) => {
    if (Array.isArray(v)) return v.map(walk);
    if (v && typeof v === 'object') {
      return Object.fromEntries(Object.entries(v).map(([k, x]) => [scrubString(k), k === 'costUsd' || k === 'costToDate' ? round(x) : walk(x)]));
    }
    return typeof v === 'string' ? scrubString(v) : v;
  };
  return walk(fixtures);
}

/** The SessionEntity fields the dashboard reads (frontend/src/protocol.ts); everything else on the row is backend config. */
const SESSION_FIELDS = [
  'id', 'name', 'provider', 'slackEnabled', 'repoPath', 'servicePath', 'cwdPath', 'branch', 'baseBranch', 'worktreePath',
  'providerSessionId', 'capabilities', 'model', 'permissionMode', 'ecosystemPath', 'contextDirs', 'thinking', 'effort',
  'costBudgetUsd', 'state', 'kickoffPrompt', 'kind', 'sessionType', 'ticketRef', 'continuedFromId', 'parentSessionId', 'prUrl',
  'prCheckStatus', 'prCheckedAt', 'reflectionEnabled', 'codeIntel', 'contextTokens', 'contextWindow',
];

/**
 * The tool list in `system_init` and the per-MCP-server context breakdown describe the capturing machine (its claude.ai
 * connectors, Linear, personal MCP servers) rather than the session: the demo needs neither, so both are reduced to
 * Artus's own `memory` server. The session row is cut down to the fields the dashboard reads, which drops mcpConfig
 * (server URLs, auth headers), envVars, providerConfig and Slack ids.
 */
export function stripEnvironment(fixture) {
  const session = fixture.detail?.session;
  const detail = session
    ? { ...fixture.detail, session: Object.fromEntries(SESSION_FIELDS.filter((k) => k in session).map((k) => [k, session[k]])) }
    : fixture.detail;
  const events = fixture.events.map((e) => {
    if (e.type === 'system_init') {
      return { ...e, payload: { ...e.payload, tools: [], mcpServers: (e.payload.mcpServers ?? []).filter((m) => m?.name === 'memory') } };
    }
    // ToolSearch results list every deferred tool that matched, i.e. the machine's other MCP servers too
    if (e.type === 'tool_result' && typeof e.payload.output === 'string' && e.payload.output.includes('"tool_reference"')) {
      const output = e.payload.output.split('\n')
        .filter((l) => !/"tool_name":"mcp__(?!memory__)/.test(l)).join('\n');
      return { ...e, payload: { ...e.payload, output } };
    }
    if (e.type === 'context_usage' && e.payload.breakdown) {
      const b = e.payload.breakdown;
      return { ...e, payload: { ...e.payload, breakdown: { ...b, mcpServers: (b.mcpServers ?? []).filter((m) => m.name === 'memory') } } };
    }
    return e;
  });
  return { ...fixture, detail, events };
}

/** every leak in a parsed value: [{pattern, match, where}] — `where` is a short JSON path */
export function findLeaks(value, extraDeny = [], denyRegexes = []) {
  const found = [];
  const patterns = leakPatterns(extraDeny, denyRegexes);
  const visit = (v, where) => {
    if (typeof v === 'string') {
      for (const re of patterns) {
        re.lastIndex = 0;
        for (const m of v.matchAll(re)) found.push({ pattern: String(re), match: m[0], where });
      }
    } else if (Array.isArray(v)) v.forEach((x, i) => visit(x, `${where}[${i}]`));
    else if (v && typeof v === 'object') for (const [k, x] of Object.entries(v)) { visit(k, `${where}.${k}(key)`); visit(x, `${where}.${k}`); }
  };
  visit(value, '$');
  return found;
}
