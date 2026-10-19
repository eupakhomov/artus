import type { Envelope, AssetKind, AuthStatus, ChangeList, ChangedFileView, DiagramStatus, DiagramType, DiagramView, FilledMeta, ImportItemResult, LibraryAsset, LibraryAssetContent, LibrarySearchHit, LibrarySource, MemoryDoc, MemoryDocDetail, MemoryEpisode, MemoryProposal, MemoryProposedOp, MemorySearchHit, PrInfo, ProviderView, ScanResult, ServiceProfileView, ServicesResponse, SessionDetail, SessionEntity, SessionSummary, Settings, SkillProposalMeta, SlackStatus, StaleSession, Template, TicketList, TurnUsage } from '../protocol';

let authToken: string | null = localStorage.getItem('artus.token');

export function setToken(token: string | null): void {
  authToken = token;
  if (token) localStorage.setItem('artus.token', token);
  else localStorage.removeItem('artus.token');
}

export function token(): string | null {
  return authToken;
}

export class ApiError extends Error {
  constructor(public status: number, message: string, public body: Record<string, unknown> | null) {
    super(message);
  }
}

/** For non-JSON responses (e.g. the transcript export's text/markdown) — request() always JSON-parses. */
async function requestText(path: string, method: string = 'GET'): Promise<string> {
  const headers: Record<string, string> = {};
  if (authToken) headers['authorization'] = `Bearer ${authToken}`;
  const res = await fetch(path, { method, headers });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new ApiError(res.status, text || res.statusText, null);
  }
  return res.text();
}

async function request<T>(method: string, path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const headers: Record<string, string> = { 'content-type': 'application/json' };
  if (authToken) headers['authorization'] = `Bearer ${authToken}`;
  if (import.meta.env.DEV) console.debug('[artus] api request', method, path, body ?? '');
  let res: Response;
  try {
    res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal });
  } catch (e) {
    // network failure, CORS, or abort — never an ApiError since there's no HTTP response to read
    console.error('[artus] api request failed (network/abort)', method, path, e);
    throw e;
  }
  const text = await res.text();
  let parsed: Record<string, unknown> | null;
  try {
    parsed = text ? (JSON.parse(text) as Record<string, unknown>) : null;
  } catch (e) {
    console.error('[artus] api response was not valid JSON', method, path, res.status, text.slice(0, 500));
    throw e;
  }
  if (!res.ok) {
    const detail = parsed && typeof parsed['detail'] === 'string' ? (parsed['detail'] as string) : res.statusText;
    console.error('[artus] api error', method, path, res.status, detail, parsed);
    throw new ApiError(res.status, detail, parsed);
  }
  if (import.meta.env.DEV) console.debug('[artus] api response', method, path, res.status);
  return parsed as T;
}

export const api = {
  raw: <T>(method: string, path: string, body?: unknown) => request<T>(method, path, body),
  listSessions: () => request<SessionSummary[]>('GET', '/api/sessions'),
  sessionHistory: (limit = 5) => request<SessionSummary[]>('GET', `/api/sessions/history?limit=${limit}`),
  sessionEvents: (id: string) => request<Envelope[]>('GET', `/api/sessions/${id}/events`),
  sessionDetail: (id: string) => request<SessionDetail>('GET', `/api/sessions/${id}`),
  createSession: (body: unknown) => request<SessionEntity>('POST', '/api/sessions', body),
  resumeSession: (id: string) => request<SessionEntity>('POST', `/api/sessions/${id}/resume`),
  duplicateSession: (id: string, body: { branch: string; name?: string; syncBaseBranch?: boolean }) =>
    request<SessionEntity>('POST', `/api/sessions/${id}/duplicate`, body),
  lastSessionConfig: () => request<Record<string, unknown>>('GET', '/api/sessions/last-config'),
  closeSession: (id: string, dirty: string, commitMessage?: string) =>
    request<null>('DELETE', `/api/sessions/${id}?dirty=${dirty}${commitMessage ? `&commitMessage=${encodeURIComponent(commitMessage)}` : ''}`),
  deleteQueued: (id: string, pos: number) => request<null>('DELETE', `/api/sessions/${id}/queue/${pos}`),
  patchSession: (id: string, body: { costBudgetUsd?: number; name?: string; reflectionEnabled?: boolean; hidden?: boolean }) =>
    request<SessionEntity>('PATCH', `/api/sessions/${id}`, body),
  reflectSession: (id: string) => request<null>('POST', `/api/sessions/${id}/reflect`),
  /** phase 21: distill the session's demonstrated procedure into a pending skill proposal */
  extractSkill: (id: string) => request<null>('POST', `/api/sessions/${id}/extract-skill`),
  /** phase 21: turn one marked block into a small pending memory proposal */
  rememberBlock: (id: string, targetSeq: number) => request<null>('POST', `/api/sessions/${id}/remember`, { targetSeq }),
  /** phase 21: mark a user/assistant block important/excluded for memory curation; null clears */
  setMark: (id: string, targetSeq: number, mark: 'important' | 'excluded' | null) =>
    request<null>('POST', `/api/sessions/${id}/marks`, { targetSeq, mark }),
  compactSession: (id: string) => request<null>('POST', `/api/sessions/${id}/compact`),
  exportTranscript: (id: string) => requestText(`/api/sessions/${id}/export.md`),
  handoffSummary: (id: string) => requestText(`/api/sessions/${id}/handoff-summary`, 'POST'),
  services: () => request<ServicesResponse>('GET', '/api/repo/services'),
  branches: (repo?: string, remote?: boolean) => {
    const params = new URLSearchParams();
    if (repo) params.set('repo', repo);
    if (remote) params.set('remote', 'true');
    const qs = params.toString();
    return request<string[]>('GET', `/api/repo/branches${qs ? `?${qs}` : ''}`);
  },
  listPrs: (repo?: string) =>
    request<PrInfo[]>('GET', `/api/repo/prs${repo ? `?repo=${encodeURIComponent(repo)}` : ''}`),
  listTemplates: () => request<Template[]>('GET', '/api/templates'),
  createTemplate: (body: unknown) => request<Template>('POST', '/api/templates', body),
  updateTemplate: (id: string, body: unknown) => request<Template>('PUT', `/api/templates/${id}`, body),
  deleteTemplate: (id: string) => request<null>('DELETE', `/api/templates/${id}`),
  ticketImportEnabled: () => request<{ enabled: boolean; warm: boolean; busy: boolean }>('GET', '/api/tickets/import/enabled'),
  importTicket: (ticketRef: string, signal?: AbortSignal) =>
    request<{ branchName: string; prompt: string; recommendedModel: string | null; ticketRef: string | null }>(
      'POST', '/api/tickets/import', { ticketRef }, signal),
  listRecentTickets: (refresh: boolean, signal?: AbortSignal) =>
    request<TicketList>('POST', `/api/tickets/recent?refresh=${refresh}`, undefined, signal),
  getSettings: () => request<Settings>('GET', '/api/settings'),
  sessionChanges: (sessionId: string) => request<ChangeList>('GET', `/api/sessions/${sessionId}/changes`),
  sessionChangedFile: (sessionId: string, path: string) =>
    request<ChangedFileView>('GET', `/api/sessions/${sessionId}/changes/file?path=${encodeURIComponent(path)}`),
  visualizeStatus: (sessionId: string) =>
    request<DiagramStatus>('GET', `/api/sessions/${sessionId}/visualize`),
  visualize: (sessionId: string, type: DiagramType | 'auto') =>
    request<DiagramView>('POST', `/api/sessions/${sessionId}/visualize`, { type }),
  visualizeRepair: (sessionId: string, mermaid: string, error: string) =>
    request<DiagramView>('POST', `/api/sessions/${sessionId}/visualize/repair`, { mermaid, error }),
  authStatus: () => request<AuthStatus>('GET', '/api/auth/status'),
  slackStatus: () => request<SlackStatus>('GET', '/api/slack/status'),
  updateSettings: (patch: Partial<Pick<Settings, 'linearOAuthEnabled' | 'ticketImportSpec' | 'ecosystemRoot'
    | 'monorepoServiceGlobs' | 'monorepoDetectionEnabled'
    | 'prChecksEnabled' | 'prCheckPollIntervalSeconds' | 'librarySkillsRoot' | 'libraryAgentsRoot'
    | 'libraryVectorize' | 'librarySyncEnabled' | 'librarySyncIntervalMinutes'
    | 'defaultProvider' | 'systemProvider' | 'codexPricing' | 'claudePricing' | 'memoryRoot' | 'memoryEnabled' | 'memoryReflectionDefault'
    | 'memoryReflectionModel' | 'memorySyncIntervalMinutes' | 'memoryRetentionDays'
    | 'memoryReflectionApprovalRequired' | 'serviceDiscoveryEnabled' | 'serviceDiscoveryStalenessDays'
    | 'serviceDiscoveryModel' | 'visualizeModel' | 'contextWarnPercent' | 'mcpSerenaRoot' | 'mcpUvPath' | 'mcpGraphifyRoot'
    | 'mcpCodegraphRoot' | 'codeIntel' | 'linearAllowedTools' | 'authTokenRequired' | 'slackDefaultEnabled' | 'hideOnPark' | 'autoTitle'>>) =>
    request<Settings>('PATCH', '/api/settings', patch),
  listProviders: () => request<ProviderView[]>('GET', '/api/providers'),
  libraryScan: (type: 'dir' | 'repo', ref: string, signal?: AbortSignal) =>
    request<ScanResult>('POST', '/api/library/scan', { type, ref }, signal),
  libraryImport: (source: { type: 'dir' | 'repo'; ref: string; syncEnabled: boolean },
    items: { path: string; kind: AssetKind; name: string; description: string; tags: string[] }[]) =>
    request<ImportItemResult[]>('POST', '/api/library/import', { source, items }),
  libraryAiFill: (type: 'dir' | 'repo', ref: string, paths: string[], signal?: AbortSignal) =>
    request<FilledMeta[]>('POST', '/api/library/ai-fill', { type, ref, paths }, signal),
  libraryAssets: (filters?: { kind?: string; status?: string; q?: string; limit?: number; offset?: number }) => {
    const params = new URLSearchParams();
    if (filters?.kind) params.set('kind', filters.kind);
    if (filters?.status) params.set('status', filters.status);
    if (filters?.q) params.set('q', filters.q);
    if (filters?.limit !== undefined) params.set('limit', String(filters.limit));
    if (filters?.offset !== undefined) params.set('offset', String(filters.offset));
    const qs = params.toString();
    return request<LibraryAsset[]>('GET', `/api/library/assets${qs ? `?${qs}` : ''}`);
  },
  libraryUpdateAsset: (id: string, patch: { name?: string; description?: string; tags?: string[]; status?: string }) =>
    request<LibraryAsset>('PATCH', `/api/library/assets/${id}`, patch),
  libraryDeleteAsset: (id: string) => request<null>('DELETE', `/api/library/assets/${id}`),
  libraryAssetContent: (id: string) => request<LibraryAssetContent>('GET', `/api/library/assets/${id}/content`),
  librarySearch: (q: string, k = 10, kind?: AssetKind) =>
    request<LibrarySearchHit[]>('GET',
      `/api/library/search?q=${encodeURIComponent(q)}&k=${k}${kind ? `&kind=${kind}` : ''}`),
  librarySources: () => request<LibrarySource[]>('GET', '/api/library/sources'),
  libraryUpdateSource: (id: string, patch: { syncEnabled?: boolean }) =>
    request<LibrarySource>('PATCH', `/api/library/sources/${id}`, patch),
  librarySyncNow: (id: string) => request<LibrarySource>('POST', `/api/library/sources/${id}/sync`),
  libraryDeleteSource: (id: string) => request<null>('DELETE', `/api/library/sources/${id}`),
  libraryDismiss: (id: string, paths?: string[]) =>
    request<LibrarySource>('POST', `/api/library/sources/${id}/discoveries/dismiss`, paths ? { paths } : {}),
  usage: (months = 6) => request<TurnUsage[]>('GET', `/api/usage?months=${months}`),
  staleSessions: () => request<StaleSession[]>('GET', '/api/usage/stale-sessions'),
  memorySearch: (q: string, opts?: { kind?: 'semantic' | 'episodic' | 'all'; servicePath?: string; tags?: string[]; limit?: number }) => {
    const params = new URLSearchParams({ q });
    if (opts?.kind) params.set('kind', opts.kind);
    if (opts?.servicePath) params.set('servicePath', opts.servicePath);
    if (opts?.limit !== undefined) params.set('limit', String(opts.limit));
    for (const t of opts?.tags ?? []) params.append('tags', t);
    return request<MemorySearchHit[]>('GET', `/api/memory/search?${params.toString()}`);
  },
  memoryDocs: (filters?: { scope?: string; servicePath?: string; status?: string; q?: string; limit?: number; offset?: number }) => {
    const params = new URLSearchParams();
    if (filters?.scope) params.set('scope', filters.scope);
    if (filters?.servicePath) params.set('servicePath', filters.servicePath);
    if (filters?.status) params.set('status', filters.status);
    if (filters?.q) params.set('q', filters.q);
    if (filters?.limit !== undefined) params.set('limit', String(filters.limit));
    if (filters?.offset !== undefined) params.set('offset', String(filters.offset));
    const qs = params.toString();
    return request<MemoryDoc[]>('GET', `/api/memory/docs${qs ? `?${qs}` : ''}`);
  },
  memoryDoc: (id: string, page = 1) => request<MemoryDocDetail>('GET', `/api/memory/docs/${id}?page=${page}`),
  memoryCreateDoc: (body: { scope: string; servicePath?: string; name: string; description: string; tags: string[]; content: string }) =>
    request<MemoryDoc>('POST', '/api/memory/docs', body),
  memoryUpdateDoc: (id: string, patch: { description?: string; tags?: string[]; content?: string }) =>
    request<MemoryDoc>('PUT', `/api/memory/docs/${id}`, patch),
  memoryArchiveDoc: (id: string) => request<null>('DELETE', `/api/memory/docs/${id}`),
  memoryRestoreDoc: (id: string) => request<MemoryDoc>('POST', `/api/memory/docs/${id}/restore`),
  memoryProposals: (status: 'PENDING' | 'APPROVED' | 'DISCARDED' = 'PENDING') =>
    request<MemoryProposal[]>('GET', `/api/memory/proposals?status=${status}`),
  memoryApproveProposal: (id: string, edited?: { episode?: string; ops?: MemoryProposedOp[] | SkillProposalMeta }) =>
    request<null>('POST', `/api/memory/proposals/${id}/approve`, edited ?? {}),
  /** the topbar badge's cheap poll — no evidence resolution (phase 21 F6) */
  memoryPendingCount: () => request<{ count: number }>('GET', '/api/memory/proposals/pending-count').then((r) => r.count),
  memoryDiscardProposal: (id: string) => request<null>('POST', `/api/memory/proposals/${id}/discard`),
  memoryEpisodes: (servicePath: string, limit?: number, offset?: number) => {
    const params = new URLSearchParams({ servicePath });
    if (limit !== undefined) params.set('limit', String(limit));
    if (offset !== undefined) params.set('offset', String(offset));
    return request<MemoryEpisode[]>('GET', `/api/memory/episodes?${params.toString()}`);
  },
  serviceDiscoveryServices: () => request<ServiceProfileView[]>('GET', '/api/service-discovery/services'),
  serviceDiscoveryRediscover: (servicePath: string) =>
    request<ServiceProfileView>('POST', '/api/service-discovery/services/rediscover', { servicePath }),
  serviceDiscoveryUpdate: (servicePath: string, patch: { description: string; tags: string[] }) =>
    request<ServiceProfileView>('PATCH', '/api/service-discovery/services', { servicePath, ...patch }),
};
