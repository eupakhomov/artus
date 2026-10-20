// Mirrors docs/PROTOCOL.md (WS envelope + payloads) and the REST DTOs.

export type PermissionMode = 'default' | 'acceptEdits' | 'plan' | 'bypassPermissions';

/** 'development' (default) — normal branch/PR workflow. 'review' — targets an existing PR/branch,
 * commit/push/PR are disabled (docs/plan/phase-15-review-sessions.md). */
export type SessionType = 'development' | 'review';

export type SessionState =
  | 'CREATING' | 'PROVISIONING' | 'STARTING' | 'IDLE' | 'RUNNING'
  | 'WAITING_INPUT' | 'PARKED' | 'CRASHED' | 'CLOSING' | 'CLOSED' | 'FAILED';

export interface ModelInfo {
  id: string;
  label: string;
  tier: 'cheap' | 'standard' | 'premium';
}

export interface Capabilities {
  permissionModes: PermissionMode[];
  thinking: boolean;
  effort: boolean;
  planMode: boolean;
  resume: boolean;
  skills: boolean;
  agents: boolean;
  mcp: boolean;
  interrupt: boolean;
  fallbackModel: boolean;
  updatedInput: boolean;
  modelSwitch: boolean;
  /** known model catalog for this provider; empty = no fixed list (free-text model input) */
  models: ModelInfo[];
  /**
   * Backend enforcement details, not read by any UI-gating code — present on a session's live
   * `ready.capabilities` (SessionEntity.capabilities), absent from the static GET /api/providers
   * listing (ProviderController.Capabilities doesn't carry them). See docs/PROTOCOL.md.
   */
  unsupportedSessionFields?: string[];
  contextDirs?: boolean;
  reportsCostUsd?: boolean;
  /** supports the compact command; absent/false hides the widget's Compact action but the ctx chip still shows */
  compact?: boolean;
  /** provider honours allowedTools/disallowedTools — gates the built-in tool toggles (phase 26) */
  toolPresets?: boolean;
}

/** GET /api/providers — static per-provider capabilities, used to gate create-dialog/
 * template controls before any session exists (see ProviderController.java). */
export interface ProviderView {
  id: string;
  capabilities: Capabilities;
}

/** Journal envelope arriving over the WebSocket. */
export interface Envelope {
  seq: number;
  ts?: string;
  type: string;
  payload: Record<string, unknown> & { [k: string]: unknown };
}

/** GET /api/slack/status — whether the server has Slack on and its socket is connected (phase 18). */
export interface SlackStatus {
  enabled: boolean;
  connected: boolean;
}

export interface SessionEntity {
  id: string;
  name: string;
  provider: string;
  /** phase 18: this session is mirrored into a Slack thread */
  slackEnabled?: boolean;
  repoPath: string;
  /** The service identity — a folder inside repoPath's git repo; equals repoPath for a polyrepo session (phase 11) */
  servicePath: string;
  /** This session's sidecar cwd, worktree-relative to servicePath — equals worktreePath for a polyrepo session (phase 11) */
  cwdPath: string;
  branch: string;
  baseBranch: string;
  worktreePath: string;
  providerSessionId: string | null;
  capabilities: Capabilities | null;
  model: string | null;
  permissionMode: PermissionMode;
  ecosystemPath: string | null;
  contextDirs: string[];
  thinking: string | null;
  effort: string | null;
  costBudgetUsd: number | null;
  state: SessionState;
  kickoffPrompt: string | null;
  /** 'user' (default) or 'system' — backend-initiated tasks, hidden by default in the dashboard */
  kind: 'user' | 'system';
  /** 'development' (default) or 'review' — see docs/plan/phase-15-review-sessions.md */
  sessionType: SessionType;
  /** Canonical ticket identifier (e.g. "ENG-123") if this session was created via ticket import */
  ticketRef: string | null;
  /** Source session this one carried a handoff summary/digest from (phase 7.3); null otherwise */
  continuedFromId: string | null;
  /** Parent session this one was spawned by via spawn_child_session (phase 7.4); null for ordinary/parent sessions */
  parentSessionId: string | null;
  /** GitHub PR URL opened from this session's branch, if any; one PR tracked per session */
  prUrl: string | null;
  /** phase 25: kept off the dashboard */
  hidden: boolean;
  prCheckStatus: PrCheckStatus | null;
  prCheckedAt: string | null;
  /** Opt-in end-of-session memory retrospective (phase 5.3) */
  reflectionEnabled: boolean;
  /** which code-intelligence MCP server was attached at creation (phase 13 decision 5); null = none */
  codeIntel: 'serena' | 'graphify' | 'codegraph' | null;
  /** Latest known context-window usage (phase 12 track C); null until the first turn */
  contextTokens: number | null;
  contextWindow: number | null;
  /** phase 26: tool-catalog names picked at creation (absent in demo fixtures) */
  tools?: string[];
}

/** Mirrors docs/PROTOCOL.md `context_usage.breakdown` (Claude only; absent for other providers). */
export interface ContextBreakdown {
  categories: { name: string; tokens: number }[];
  mcpServers: { name: string; tokens: number; deferred: boolean }[];
  messages?: { toolResults: number; toolCalls: number; assistant: number; user: number; attachments: number };
  topTools: { name: string; tokens: number }[];
}

export type PrCheckStatus = 'PENDING' | 'SUCCESS' | 'FAILURE' | 'MERGED' | 'CLOSED' | 'ERROR';

/** GET /api/repo/prs — open PRs for the review create-dialog's PR picker (phase 15). */
export interface PrInfo {
  number: number;
  title: string;
  headRefName: string;
  baseRefName: string;
  url: string;
  author: string;
  isDraft: boolean;
}

export interface SessionSummary {
  id: string;
  name: string;
  provider: string;
  repoPath: string;
  servicePath: string;
  branch: string;
  model: string | null;
  permissionMode: PermissionMode;
  state: SessionState;
  kind: 'user' | 'system';
  sessionType: SessionType;
  costToDate: number;
  updatedAt: string;
  lastSeq: number;
  /** set once a session is closed (phase 23); null while open */
  closedAt: string | null;
  prUrl: string | null;
  /** phase 25: kept off the dashboard — listed in the sidebar's Hidden section */
  hidden: boolean;
}

export interface QueuedMessage {
  pos: number;
  text: string;
}

export interface SessionDetail {
  session: SessionEntity;
  queued: QueuedMessage[];
  lastSeq: number;
  costToDate: number;
  /** Name of the source session, when `session.continuedFromId` is set (phase 7.3) */
  continuedFromName?: string;
  /** Name of the parent session, when `session.parentSessionId` is set (phase 7.4) */
  parentName?: string;
}

/** A template's live reference to a library asset (Phase 5.4) — resolved for display. */
export interface TemplateAsset {
  id: string;
  kind: AssetKind;
  name: string;
  location: string;
  status: AssetStatus;
}

export interface Template {
  id: string;
  name: string;
  description: string | null;
  config: Record<string, unknown>;
  assets: TemplateAsset[];
}

export interface ServiceInfo {
  name: string;
  path: string;
  /** The git root — equals path for a plain polyrepo service, the monorepo root for a package */
  repoPath: string;
  monorepo: boolean;
}

export interface ServicesResponse {
  ecosystemRoot: string;
  defaultRepoPath: string;
  services: ServiceInfo[];
}

export interface Settings {
  linearOAuthEnabled: boolean;
  ticketImportSpec: string;
  linearApiKeyConfigured: boolean;
  ecosystemRoot: string;
  /** comma-separated glob fallback for monorepo package detection, e.g. "packages/*,services/*" */
  monorepoServiceGlobs: string;
  /** off by default: a repo is one poly-repo service unless workspace detection is explicitly enabled */
  monorepoDetectionEnabled: boolean;
  prChecksEnabled: boolean;
  prCheckPollIntervalSeconds: number;
  librarySkillsRoot: string;
  libraryAgentsRoot: string;
  libraryVectorize: boolean;
  librarySyncEnabled: boolean;
  librarySyncIntervalMinutes: number;
  voyageConfigured: boolean;
  defaultProvider: string;
  /** provider the singleton system session spawns as; '' = follow defaultProvider */
  systemProvider: string;
  /** JSON: {"<model>"|"default": {"inputPer1M":n, "cachedInputPer1M":n, "outputPer1M":n}} */
  codexPricing: string;
  /** phase 25: $/1M-token table for the live, mid-turn cost estimate (same JSON-object-as-string shape) */
  claudePricing: string;
  memoryRoot: string;
  memoryEnabled: boolean;
  memoryReflectionDefault: boolean;
  /** tier: 'cheap' | 'standard' | 'premium' */
  memoryReflectionModel: string;
  memorySyncIntervalMinutes: number;
  memoryRetentionDays: number;
  memoryReflectionApprovalRequired: boolean;
  serviceDiscoveryEnabled: boolean;
  serviceDiscoveryStalenessDays: number;
  /** tier: 'cheap' | 'standard' | 'premium' */
  serviceDiscoveryModel: string;
  /** tier: 'cheap' | 'standard' | 'premium' — the model for Visualize diagram turns (default standard) */
  visualizeModel: string;
  /** widget ctx chip amber threshold, 30-95 (default 70) */
  contextWarnPercent: number;
  /** Serena checkout root; empty = Serena unavailable (phase 12 track B) */
  mcpSerenaRoot: string;
  /** `uv` command/path, for hosts where it isn't on the backend's PATH; default "uv" */
  mcpUvPath: string;
  /** graphify checkout root; empty = graphify unavailable (phase 13) */
  mcpGraphifyRoot: string;
  /** codegraph checkout root; empty = codegraph unavailable (phase 14) */
  mcpCodegraphRoot: string;
  /** comma-separated Linear MCP tool names new sessions may call */
  linearAllowedTools: string;
  /** which code-intelligence tool sessions may opt into — one per install (phase 13 decision 1); always resolved */
  codeIntel: CodeIntel;
  /** phase 17: stored "require the access token" choice — read once at backend startup, so it applies on restart */
  authTokenRequired: boolean;
  /** phase 18: new sessions start with Slack mirroring on (only meaningful when the server has Slack enabled) */
  slackDefaultEnabled: boolean;
  /** phase 25: idle parking also hides the session from the dashboard */
  hideOnPark: boolean;
  /** phase 25: rename a session after its first turn while its name is still the branch */
  autoTitle: boolean;
}

/** phase 17 C: one row of the changes panel (working tree vs merge-base, plus untracked) */
export interface ChangedFile {
  path: string;
  /** set for a rename */
  oldPath: string | null;
  status: 'added' | 'modified' | 'deleted' | 'renamed';
  /** which git states the change is in; a file can be several (committed then edited again) */
  where: Array<'committed' | 'staged' | 'unstaged' | 'untracked'>;
  additions: number;
  deletions: number;
  /** working-tree mtime (epoch ms), 0 if absent — only compared for equality */
  mtime: number;
}

export interface ChangeList {
  baseBranch: string;
  /** merge-base commit, null when the base branch can't be resolved */
  mergeBase: string | null;
  baseKnown: boolean;
  files: ChangedFile[];
}

export interface ChangedFileView {
  path: string;
  oldPath: string | null;
  status: ChangedFile['status'];
  /** null for a deleted or binary file */
  content: string | null;
  /** null for an added or binary file */
  baseContent: string | null;
  binary: boolean;
  /** over 1 MB: the first 1 MB is shown */
  truncated: boolean;
  size: number;
}

/** `GET /api/auth/status` — what the running backend actually enforces (always open) */
export interface AuthStatus {
  required: boolean;
  tokenConfigured: boolean;
}

/** phase 13/14: the global one-of code-intelligence selector; `none` hides the create-dialog checkbox */
export type CodeIntel = 'none' | 'serena' | 'graphify' | 'codegraph';

// --- layered memory (phase 5.3) ---

export type MemoryScope = 'ecosystem' | 'service';
export type MemoryStatus = 'ACTIVE' | 'ARCHIVED';

export interface MemoryLinkRef {
  slug: string;
  docId: string | null;
  name: string | null;
  description: string | null;
  dangling: boolean;
}

export interface MemoryDoc {
  id: string;
  scope: MemoryScope;
  servicePath: string | null;
  name: string;
  description: string;
  tags: string[];
  status: MemoryStatus;
  createdAt: string;
  updatedAt: string;
}

export interface MemoryDocDetail {
  doc: MemoryDoc;
  content: string;
  page: number;
  totalPages: number;
  outgoing: MemoryLinkRef[];
  backlinks: MemoryLinkRef[];
}

export type ProposalStatus = 'PENDING' | 'APPROVED' | 'DISCARDED';

export interface MemoryProposedOp {
  op: 'create' | 'update' | 'archive';
  scope: MemoryScope;
  name: string;
  description?: string;
  tags?: string[];
  content?: string;
  reason?: string;
  /** journal seqs of the transcript blocks this op was derived from (phase 21) */
  evidence?: number[];
}

/** The metadata half of a skill proposal; its SKILL.md body travels in `episode`. */
export interface SkillProposalMeta {
  name: string;
  description: string;
  tags?: string[];
  evidence?: number[];
}

interface ProposalBase {
  id: string;
  sessionId: string;
  sessionName: string;
  servicePath: string;
  /** null for proposals that must not mark the session reflected (skill extraction) */
  reflectedSeq: number | null;
  /** memory proposals: the episode summary; skill proposals: the proposed SKILL.md body */
  episode: string;
  status: ProposalStatus;
  createdAt: string;
  decidedAt: string | null;
  /** quoted transcript snippets for every seq the proposal's ops cite, keyed by seq (phase 21) */
  evidence: Record<string, string>;
}

/** 'remember' = one block the user asked to remember: memory ops with an empty episode. */
export type MemoryProposal =
  | (ProposalBase & { kind: 'memory' | 'remember'; ops: MemoryProposedOp[] })
  | (ProposalBase & { kind: 'skill'; ops: SkillProposalMeta });

export interface MemoryEpisode {
  id: string;
  sessionId: string;
  sessionName: string;
  servicePath: string;
  ts: string;
  summary: string;
}

export interface MemorySearchHit {
  kind: 'semantic' | 'episodic';
  id: string;
  name: string | null;
  scope: MemoryScope | null;
  servicePath: string | null;
  description: string;
  tags: string[];
  sessionName: string | null;
  ts: string | null;
  score: number;
}

// --- ecosystem service discovery (phase 8) ---

export interface ServiceProfileView {
  name: string;
  servicePath: string;
  description: string | null;
  tags: string[];
  discoveredAt: string | null;
  stale: boolean;
}

// --- skill & agent library ---

export type AssetKind = 'skill' | 'agent';
export type AssetStatus = 'ACTIVE' | 'ARCHIVED';

export interface LibraryAsset {
  id: string;
  sourceId: string | null;
  kind: AssetKind;
  name: string;
  description: string;
  location: string;
  sourcePath: string | null;
  contentHash: string;
  status: AssetStatus;
  tags: string[];
  createdAt: string;
  updatedAt: string;
}

export interface ScanCandidate {
  path: string;
  kind: AssetKind;
  confidence: 'high' | 'low';
  name: string;
  description: string;
  hash: string;
  sizeBytes: number;
  alreadyImported: boolean;
  changedSinceImport: boolean;
}

export interface ScanResult {
  type: 'dir' | 'repo';
  ref: string;
  candidates: ScanCandidate[];
}

export interface ImportItemResult {
  path: string;
  assetId: string | null;
  warning: string | null;
}

export interface FilledMeta {
  path: string;
  name: string;
  description: string;
  tags: string[];
}

export interface LibraryDiscovery {
  path: string;
  kind: AssetKind;
  firstSeenAt: string;
}

export interface LibrarySource {
  id: string;
  type: 'dir' | 'repo';
  ref: string;
  syncEnabled: boolean;
  lastSyncedAt: string | null;
  lastSyncStatus: string | null;
  lastSyncError: string | null;
  assetCount: number;
  discoveries: LibraryDiscovery[];
}

export interface LibrarySearchHit {
  asset: LibraryAsset;
  score: number;
}

export interface LibraryAssetContent {
  content: string;
  truncated: boolean;
  sourceFile: string;
}

export interface TicketSummary {
  ref: string;
  title: string;
  status: string;
}

export interface TicketList {
  tickets: TicketSummary[];
  fetchedAt: string;
  cached: boolean;
}

export interface TurnUsage {
  sessionId: string;
  sessionName: string;
  ts: string;
  model: string | null;
  costUsd: number;
}

export interface StaleSession {
  id: string;
  name: string;
  branch: string;
  state: SessionState;
  updatedAt: string;
  worktreeExists: boolean;
  dirty: boolean;
}

export const LIVE_STATES: SessionState[] = ['STARTING', 'IDLE', 'RUNNING', 'WAITING_INPUT'];

/**
 * Picks a sensible base branch out of a repo's local branches: "main", else "master", else
 * the first branch reported by git (alphabetical — could be any stray local/feature branch,
 * so this last resort is a guess, not a real default). Falls back to "main" for an empty list
 * so create/branch calls still get a non-blank value and fail with a clear git error instead
 * of a blank one.
 */
export function pickDefaultBranch(branches: string[]): string {
  if (branches.includes('main')) return 'main';
  if (branches.includes('master')) return 'master';
  return branches[0] ?? 'main';
}

/** Extract {{placeholders}} from a kickoff prompt template. */
export function placeholdersOf(prompt: string): string[] {
  return [...new Set([...prompt.matchAll(/\{\{(\w+)\}\}/g)].map((m) => m[1]!))];
}

/** A template's resolved asset link, shaped as a (placeholder-filled) LibraryAsset for reuse in AssetPickerDialog. */
export function assetStub(a: TemplateAsset): LibraryAsset {
  return {
    id: a.id, sourceId: null, kind: a.kind, name: a.name, description: '', location: a.location,
    sourcePath: null, contentHash: '', status: a.status, tags: [], createdAt: '', updatedAt: '',
  };
}

/** Multi-line chip tooltip for a context_usage snapshot; the breakdown block only when one was reported. */
export function contextUsageTitle(
  tokens: number, window: number, autoCompactAt: number | null, breakdown: ContextBreakdown | null,
): string {
  const n = (x: number) => x.toLocaleString();
  let head = `${n(tokens)} / ${n(window)} tokens`;
  if (autoCompactAt != null) head += ` (auto-compact at ${Math.round((autoCompactAt / window) * 100)}%)`;
  if (!breakdown) return head;
  const lines = [head];
  if (breakdown.categories.length > 0) {
    lines.push('', ...breakdown.categories.map((c) => `${c.name}: ${n(c.tokens)}`));
  }
  if (breakdown.mcpServers.length > 0) {
    lines.push('', 'MCP servers:', ...breakdown.mcpServers.map(
      (m) => `  ${m.name}: ${n(m.tokens)}${m.deferred ? ' (deferred)' : ''}`));
  }
  const m = breakdown.messages;
  if (m) {
    lines.push('', 'Messages:',
      `  tool results: ${n(m.toolResults)}`, `  tool calls: ${n(m.toolCalls)}`,
      `  assistant: ${n(m.assistant)}`, `  user: ${n(m.user)}`);
    if (m.attachments > 0) lines.push(`  attachments: ${n(m.attachments)}`);
  }
  if (breakdown.topTools.length > 0) {
    lines.push('', 'Heaviest tools:', ...breakdown.topTools.map((t) => `  ${t.name}: ${n(t.tokens)}`));
  }
  return lines.join('\n');
}

// --- phase 22: Visualize ---

export type DiagramType = 'flowchart' | 'sequence' | 'class' | 'er' | 'state';
export const DIAGRAM_TYPES: DiagramType[] = ['flowchart', 'sequence', 'class', 'er', 'state'];

/** a `diagram_generated` journal event's payload (+ its seq) */
export interface DiagramView {
  diagramType: DiagramType;
  mermaid: string;
  rationale: string | null;
  /** SHA-256 of the diff it was generated from */
  fingerprint: string;
  repaired: boolean;
  seq: number;
}

export interface DiagramStatus {
  latest: DiagramView | null;
  currentFingerprint: string;
  stale: boolean;
}

/** One tool-catalog entry (phase 26): an MCP server, or a reference to an installed plugin directory. */
export interface ToolEntry {
  name: string;
  kind: 'mcp' | 'plugin';
  description: string;
  /** Claude-shaped MCP block (`command`/`args`/`env` or `url`/`headers`); secrets only as `${ENV_VAR}` */
  server: Record<string, unknown> | null;
  enabledTools: string[];
  preApprove: boolean;
  source: 'claude' | 'codex' | null;
  path: string | null;
}

/** GET /api/tools/catalog row: the entry plus host environment variables it references that are unset. */
export interface ToolCatalogRow {
  entry: ToolEntry;
  missingEnv: string[];
}

export interface ToolProbeResult {
  ok: boolean;
  tools: string[];
  error: string | null;
}

/** GET /api/tools/import row — something found on the host that can be added to the catalog. */
export interface ToolCandidate {
  name: string;
  kind: 'mcp' | 'plugin';
  source: 'claude' | 'codex';
  path: string | null;
  server: Record<string, unknown> | null;
  servers: string[];
  origin: string;
  /** `${VAR}` placeholders that replaced literal secret values found in the source */
  redacted: string[];
}
