import type {
  AuthStatus, Capabilities, ModelInfo, ProviderView, SessionDetail, SessionEntity, SessionSummary, Settings, SlackStatus,
} from '../protocol';
import { demoFixtures, playerFor } from '../demo/registry';
import { demoKnowledge } from '../demo/knowledge';
import type { api as realApi } from './rest';

// phase 24: fixture-backed stand-in for api/rest.ts, swapped in by `vite build --mode demo` (vite.config.ts).
// Nothing here ever calls fetch — see rest.demo.test.ts.

export function setToken(_token: string | null): void { /* the demo has no token */ }
export function token(): string | null { return null; }

export class ApiError extends Error {
  constructor(public status: number, message: string, public body: Record<string, unknown> | null) {
    super(message);
  }
}

type Api = typeof realApi;

const NOT_IN_DEMO = 'Not available in the demo — install Artus to use this.';

const CLAUDE_MODELS: ModelInfo[] = [
  { id: 'haiku', label: 'Haiku', tier: 'cheap' },
  { id: 'sonnet', label: 'Sonnet', tier: 'standard' },
  { id: 'opus', label: 'Opus', tier: 'premium' },
];

const caps = (models: ModelInfo[], codex: boolean): Capabilities => ({
  permissionModes: codex ? ['default', 'bypassPermissions'] : ['default', 'acceptEdits', 'plan', 'bypassPermissions'],
  thinking: !codex, effort: true, planMode: !codex, resume: true, skills: true, agents: !codex, mcp: true,
  interrupt: true, fallbackModel: !codex, updatedInput: true, modelSwitch: true, models, compact: true,
});

const PROVIDERS: ProviderView[] = [
  { id: 'claude', capabilities: caps(CLAUDE_MODELS, false) },
  { id: 'codex', capabilities: caps([], true) },
];

const SETTINGS: Settings = {
  linearOAuthEnabled: false, ticketImportSpec: '', linearApiKeyConfigured: false, ecosystemRoot: '',
  monorepoServiceGlobs: '', monorepoDetectionEnabled: false, prChecksEnabled: true, prCheckPollIntervalSeconds: 180,
  librarySkillsRoot: '', libraryAgentsRoot: '', libraryVectorize: false, librarySyncEnabled: true,
  librarySyncIntervalMinutes: 60, voyageConfigured: false, defaultProvider: 'claude', systemProvider: '', codexPricing: '{}',
  memoryRoot: '', memoryEnabled: true, memoryReflectionDefault: false, memoryReflectionModel: 'cheap',
  memorySyncIntervalMinutes: 5, memoryRetentionDays: 0, memoryReflectionApprovalRequired: true,
  serviceDiscoveryEnabled: false, serviceDiscoveryStalenessDays: 14, serviceDiscoveryModel: 'cheap',
  visualizeModel: 'standard', contextWarnPercent: 70, mcpSerenaRoot: '', mcpUvPath: 'uv', mcpGraphifyRoot: '',
  mcpCodegraphRoot: '', linearAllowedTools: '', codeIntel: 'none', authTokenRequired: false, slackDefaultEnabled: false,
};

const notInDemo = (): Promise<never> => Promise.reject(new ApiError(501, NOT_IN_DEMO, null));
const ok = <T>(v: T): Promise<T> => Promise.resolve(structuredClone(v));

function find(id: string) {
  const f = demoFixtures().find((x) => x.summary.id === id);
  if (!f) throw new ApiError(404, `unknown session ${id}`, null);
  return f;
}

const isClosed = (s: SessionSummary) => s.state === 'CLOSED';

const GIT_READ = /^\/api\/sessions\/([^/]+)\/git\/(status|log|diff)$/;

/** The Git panel/PR dialog go through `api.raw`: the recorded reads are served, every write (commit, push, PR, AI drafts) is a 501. */
function raw<T>(method: string, path: string): Promise<T> {
  const m = method === 'GET' ? GIT_READ.exec(path) : null;
  const git = m ? find(m[1]!).git : undefined;
  if (!m || !git) return notInDemo();
  if (m[2] === 'status') return ok(git.status as T);
  if (m[2] === 'log') return ok(git.log as T);
  return git.diff === undefined ? notInDemo() : ok({ diff: git.diff } as T);
}

const implemented: Partial<Api> = {
  raw,
  listSessions: () => ok(demoFixtures().map((f) => f.summary).filter((s) => !isClosed(s))),
  sessionHistory: (limit = 5) =>
    ok(demoFixtures().map((f) => f.summary).filter(isClosed)
      .sort((a, b) => Date.parse(b.closedAt ?? b.updatedAt) - Date.parse(a.closedAt ?? a.updatedAt)).slice(0, limit)),
  sessionEvents: (id) => ok(find(id).events),
  sessionDetail: (id) => ok<SessionDetail>(find(id).detail),
  patchSession: (id, body) => {
    const f = find(id);
    const player = playerFor(id);
    if (body.name !== undefined) {
      f.summary.name = body.name;
      f.detail.session.name = body.name;
      player?.inject('session_renamed', { name: body.name });
    }
    if (body.costBudgetUsd !== undefined) {
      f.detail.session.costBudgetUsd = body.costBudgetUsd;
      player?.inject('budget_updated', { costBudgetUsd: body.costBudgetUsd });
    }
    if (body.reflectionEnabled !== undefined) f.detail.session.reflectionEnabled = body.reflectionEnabled;
    return ok<SessionEntity>(f.detail.session);
  },
  setMark: (id, targetSeq, mark) => {
    playerFor(id)?.inject('block_marked', { targetSeq, mark });
    return Promise.resolve(null);
  },
  sessionChanges: (id) => {
    const f = find(id);
    return f.changes ? ok(f.changes) : ok({ baseBranch: f.detail.session.baseBranch, mergeBase: null, baseKnown: false, files: [] });
  },
  sessionChangedFile: (id, path) => {
    const v = find(id).files?.[path];
    return v ? ok(v) : Promise.reject(new ApiError(404, 'not a changed file', null));
  },
  visualizeStatus: (id) => {
    const f = find(id);
    return ok({
      latest: f.diagram ?? null,
      currentFingerprint: f.currentFingerprint ?? f.diagram?.fingerprint ?? '',
      stale: !!f.diagram && !!f.currentFingerprint && f.currentFingerprint !== f.diagram.fingerprint,
    });
  },
  getSettings: () => ok(SETTINGS),
  authStatus: () => ok<AuthStatus>({ required: false, tokenConfigured: false }),
  slackStatus: () => ok<SlackStatus>({ enabled: false, connected: false }),
  listProviders: () => ok(PROVIDERS),
  lastSessionConfig: () => ok({}),
  listTemplates: () => ok([]),
  services: () => ok({ ecosystemRoot: '', defaultRepoPath: '', services: [] }),
  ticketImportEnabled: () => ok({ enabled: false, warm: false, busy: false }),
  usage: () => ok([]),
  staleSessions: () => ok([]),
  // phase 24 / website W0.2: a small hand-made vault and library (src/demo/knowledge.ts); reads only, every write is a 501
  memoryPendingCount: () => Promise.resolve(demoKnowledge().memoryProposals().length),
  memoryProposals: (status = 'PENDING') => ok(status === 'PENDING' ? demoKnowledge().memoryProposals() : []),
  memoryDocs: (f) => ok(demoKnowledge().memoryDocs(f)),
  memoryDoc: (id) => {
    const d = demoKnowledge().memoryDoc(id);
    return d ? ok(d) : Promise.reject(new ApiError(404, 'unknown memory', null));
  },
  memoryEpisodes: (servicePath) => ok(demoKnowledge().memoryEpisodes(servicePath)),
  memorySearch: (q, opts) => ok(demoKnowledge().memorySearch(q, opts)),
  libraryAssets: (f) => ok(demoKnowledge().libraryAssets(f)),
  libraryAssetContent: (id) => {
    const c = demoKnowledge().libraryAssetContent(id);
    return c ? ok(c) : Promise.reject(new ApiError(404, 'unknown asset', null));
  },
  librarySearch: (q, k) => ok(demoKnowledge().librarySearch(q, k)),
  librarySources: () => ok(demoKnowledge().librarySources()),
  serviceDiscoveryServices: () => ok([]),
};

/**
 * Everything not listed above rejects with a 501 `ApiError` — the UI already renders `ApiError` inline, and this is the safe
 * default for any method added to the real client later (no demo edit needed, nothing can reach a network).
 */
export const api: Api = new Proxy(implemented as Api, {
  get: (target, prop: string) => (prop in target ? target[prop as keyof Api] : notInDemo),
});
