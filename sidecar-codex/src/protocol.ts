/**
 * Provider adapter protocol, version 1.
 *
 * NDJSON over stdio: each line on stdin is one Command, each line on stdout is one
 * Event. stdout carries protocol JSON exclusively; all logging goes to stderr.
 * The contract is provider-neutral — `sidecar/` is the Claude reference
 * implementation. See docs/PROTOCOL.md.
 *
 * This file is a synced copy of `sidecar/src/protocol.ts`'s shared types (kept as a
 * plain copy rather than a cross-package import to avoid coupling this package's
 * build to `sidecar/`'s build order — see docs/plan/phase-5.13-codex-provider.md
 * Decision 4). If protocol v1 changes, update both copies. `CODEX_CAPABILITIES` at
 * the bottom is specific to this package.
 */

export const PROTOCOL_VERSION = 1;

export type PermissionMode = 'default' | 'acceptEdits' | 'plan' | 'bypassPermissions';

// ---------------------------------------------------------------------------
// Commands (backend/driver -> sidecar)
// ---------------------------------------------------------------------------

export interface UserMessageCommand {
  type: 'user_message';
  text: string;
}

export interface PermissionResponseCommand {
  type: 'permission_response';
  requestId: string;
  behavior: 'allow' | 'deny';
  /** allow only: replaces the tool input (e.g. an edited Bash command) */
  updatedInput?: Record<string, unknown>;
  /** deny only: reason shown to the model */
  message?: string;
}

export interface InterruptCommand {
  type: 'interrupt';
}

export interface SetPermissionModeCommand {
  type: 'set_permission_mode';
  mode: PermissionMode;
}

export interface SetModelCommand {
  type: 'set_model';
  model: string;
}

export interface ShutdownCommand {
  type: 'shutdown';
}

/** In-place, provider-side context compaction. Rejected with a non-fatal error if a
 * turn is already in flight — the backend gates this too (only sends it for an
 * IDLE/parked session), this is the sidecar-side belt. */
export interface CompactCommand {
  type: 'compact';
}

export type Command =
  | UserMessageCommand
  | PermissionResponseCommand
  | InterruptCommand
  | SetPermissionModeCommand
  | SetModelCommand
  | ShutdownCommand
  | CompactCommand;

// ---------------------------------------------------------------------------
// Events (sidecar -> backend/driver)
// ---------------------------------------------------------------------------

export type EffortLevel = 'low' | 'medium' | 'high' | 'xhigh' | 'max';

/** 'off' | 'adaptive' (model decides) | fixed token budget */
export type ThinkingSetting = 'off' | 'adaptive' | number;

export interface ModelInfo {
  id: string;
  label: string;
  tier: 'cheap' | 'standard' | 'premium';
}

export interface Capabilities {
  permissionModes: PermissionMode[];
  thinking: boolean;
  /** supports reasoning-effort levels (low..max) */
  effort: boolean;
  planMode: boolean;
  resume: boolean;
  skills: boolean;
  agents: boolean;
  mcp: boolean;
  interrupt: boolean;
  fallbackModel: boolean;
  /** permission responses may carry updatedInput */
  updatedInput: boolean;
  /** supports set_model mid-session */
  modelSwitch: boolean;
  /** known model catalog for this provider; empty = no fixed list (free-text model input) */
  models: ModelInfo[];
  /**
   * Session/create-option field names this provider rejects outright (backend enforces
   * at creation time — see docs/plan/phase-10-review-followups.md R1). Field names match
   * the create-options JSON keys, e.g. "allowedTools", "maxTurns".
   */
  unsupportedSessionFields: string[];
  /** supports read-only ecosystem/service context directories (--context-dir); false degrades with a warning, not a hard reject */
  contextDirs: boolean;
  /** turn_complete.costUsd is authoritative; false = the backend estimates from token usage against a price table */
  reportsCostUsd: boolean;
  /** supports the compact command (in-place context compaction) */
  compact: boolean;
  /** Serena's built-in `--context` name for this provider (e.g. "claude-code"/"codex"); empty/absent = Serena unsupported */
  serenaContext: string;
}

export interface ReadyEvent {
  type: 'ready';
  pid: number;
  protocolVersion: typeof PROTOCOL_VERSION;
  provider: string;
  capabilities: Capabilities;
}

export interface SystemInitEvent {
  type: 'system_init';
  providerSessionId: string;
  model: string;
  cwd: string;
  tools: string[];
  mcpServers: { name: string; status: string }[];
  permissionMode: PermissionMode;
}

export interface StreamDeltaEvent {
  type: 'stream_delta';
  deltaType: 'text' | 'thinking';
  text: string;
  /** set when this delta was produced inside a subagent — the toolUseId of the enclosing Task call */
  parentToolUseId?: string;
}

export interface AssistantMessageEvent {
  type: 'assistant_message';
  /** Anthropic-format content blocks (text / thinking / tool_use) */
  content: unknown[];
  /** set when this message was produced inside a subagent — the toolUseId of the enclosing Task call */
  parentToolUseId?: string;
}

export interface ToolStartedEvent {
  type: 'tool_started';
  toolUseId: string;
  name: string;
  input: Record<string, unknown>;
  /** set when this tool call was made by a subagent — the toolUseId of the enclosing Task call */
  parentToolUseId?: string;
}

export interface ToolResultEvent {
  type: 'tool_result';
  toolUseId: string;
  isError: boolean;
  output: string;
  truncated: boolean;
  /** set when this result belongs to a subagent's tool call — the toolUseId of the enclosing Task call */
  parentToolUseId?: string;
}

export interface PermissionRequestEvent {
  type: 'permission_request';
  requestId: string;
  toolName: string;
  input: Record<string, unknown>;
  /** provider-suggested permission updates, opaque to the backend */
  suggestions: unknown[];
}

/**
 * Live progress while the model thinks with redacted thinking (Claude 5 models):
 * a running token estimate for the current thinking block. UIs use this for
 * spinners/"thinking…" pills; it is not billed usage.
 */
export interface ThinkingProgressEvent {
  type: 'thinking_progress';
  estimatedTokens: number;
  estimatedTokensDelta: number;
}

export interface PermissionModeChangedEvent {
  type: 'permission_mode_changed';
  mode: PermissionMode;
}

export interface ModelChangedEvent {
  type: 'model_changed';
  model: string;
}

export interface TurnCompleteEvent {
  type: 'turn_complete';
  stopReason: string;
  usage: unknown;
  /** this turn's own cost — never a running total (consumers sum it for the session's cost) */
  costUsd: number;
  /** adapter's raw running total since the provider process started, when it reports one (debugging only) */
  sessionCostUsd?: number;
  durationMs: number;
  numTurns: number;
  /** model that produced this turn (last-seen system_init.model at completion time) */
  model: string;
}

/**
 * Between-turns context-window snapshot: how full the *next* request will be, not a
 * summary of the turn just completed (that's turn_complete.usage). Emitted after every
 * turn_complete and after a context_compacted, never mid-turn.
 */
export interface ContextUsageEvent {
  type: 'context_usage';
  tokens: number;
  window: number;
  /** absolute token count at which the provider will auto-compact, when it reports one */
  autoCompactAt?: number;
  /** what the tokens are made of, when the provider can say (Claude only; absent otherwise) */
  breakdown?: ContextBreakdown;
}

/** Compact, display-oriented split of a context_usage snapshot; zero-token rows are omitted. */
export interface ContextBreakdown {
  /** provider's own category rows (system prompt, tools, messages, ...), largest first */
  categories: { name: string; tokens: number }[];
  /** MCP tool-schema tokens rolled up per server; `deferred` = schemas not loaded up front */
  mcpServers: { name: string; tokens: number; deferred: boolean }[];
  /** conversation split, when the provider reports one */
  messages?: {
    toolResults: number;
    toolCalls: number;
    assistant: number;
    user: number;
    attachments: number;
  };
  /** tools whose calls+results take the most conversation tokens (max 5) */
  topTools: { name: string; tokens: number }[];
}

/** A compaction boundary just happened (manual command or provider auto-compact). */
export interface ContextCompactedEvent {
  type: 'context_compacted';
  preTokens: number;
  postTokens: number;
  trigger: 'manual' | 'auto';
}

export interface ErrorEvent {
  type: 'error';
  message: string;
  fatal: boolean;
}

export interface ExitingEvent {
  type: 'exiting';
  reason: 'shutdown' | 'fatal' | 'stdin_closed';
}

/** Token counts of one model within the current turn (phase 25). */
export interface ModelTokenUsage {
  input: number;
  output: number;
  cacheRead: number;
  cacheWrite5m: number;
  cacheWrite1h: number;
}

/**
 * Running token usage of the current turn, per model (subagents may run another model),
 * re-emitted whenever a model call's usage becomes known. Cumulative within the turn, reset
 * at turn_complete. The backend prices it into `estimatedCostUsd` for the live cost display;
 * the authoritative figure is still turn_complete.costUsd. Optional: adapters that cannot
 * see per-call usage never send it.
 */
export interface TurnUsageEvent {
  type: 'turn_usage';
  byModel: Record<string, ModelTokenUsage>;
}

/**
 * A provider-side task started: a subagent or a backgrounded shell command (phase 25).
 * `toolUseId` links it to the tool call that spawned it, when there is one.
 */
export interface TaskStartedEvent {
  type: 'task_started';
  taskId: string;
  toolUseId?: string;
  description: string;
  /** provider's task kind, e.g. `local_agent`, `local_bash` */
  kind?: string;
  subagentType?: string;
  backgrounded: boolean;
}

/** Periodic progress of a running task. */
export interface TaskProgressEvent {
  type: 'task_progress';
  taskId: string;
  toolUseId?: string;
  description: string;
  lastTool?: string;
  toolUses: number;
  tokens: number;
  durationMs: number;
}

/** A task settled. */
export interface TaskDoneEvent {
  type: 'task_done';
  taskId: string;
  toolUseId?: string;
  status: 'completed' | 'failed' | 'stopped';
  summary: string;
}

/**
 * The full set of live background tasks (replace semantics). The adapter sends `[]` once at
 * startup, so a consumer can reset on (re)spawn.
 */
export interface BackgroundTasksEvent {
  type: 'background_tasks';
  tasks: { taskId: string; kind: string; description: string }[];
}

export type Event =
  | ReadyEvent
  | SystemInitEvent
  | StreamDeltaEvent
  | AssistantMessageEvent
  | ToolStartedEvent
  | ToolResultEvent
  | PermissionRequestEvent
  | ThinkingProgressEvent
  | PermissionModeChangedEvent
  | ModelChangedEvent
  | TurnCompleteEvent
  | ContextUsageEvent
  | ContextCompactedEvent
  | ErrorEvent
  | ExitingEvent
  | TurnUsageEvent
  | TaskStartedEvent
  | TaskProgressEvent
  | TaskDoneEvent
  | BackgroundTasksEvent;

/**
 * Codex has no plan mode, no static subagent-file equivalent, and no thinking-budget
 * axis distinct from reasoning effort. skills/mcp were flipped true in the
 * 2026-08-30 follow-up once both were confirmed live-feasible. See
 * docs/plan/phase-5.13-codex-provider.md "Capabilities announcement" for the
 * live-confirmed rationale behind each value — must stay in sync with
 * ProviderController.java's Java-side constant.
 */
export const CODEX_CAPABILITIES: Capabilities = {
  permissionModes: ['default', 'bypassPermissions'],
  thinking: false,
  effort: true,
  planMode: false,
  resume: true,
  skills: true,
  agents: false,
  mcp: true,
  interrupt: true,
  fallbackModel: false,
  updatedInput: false,
  modelSwitch: true,
  // Codex resolves its own default model/alias with no fixed enumeration we can offer
  // up front (docs/plan/phase-5.13-codex-provider.md: "no hardcoded Codex model list")
  // — empty means the frontend falls back to a free-text model field.
  models: [],
  // pluginDirs (phase 26): Codex has no equivalent of the Claude SDK's `plugins` option — a
  // plugin-kind catalog tool is carried as its `.mcp.json` servers in config.mcp_servers instead.
  unsupportedSessionFields: ['allowedTools', 'disallowedTools', 'thinking', 'maxTurns', 'fallbackModel', 'agentSources', 'pluginDirs'],
  contextDirs: false,
  reportsCostUsd: false,
  // Confirmed live from the installed codex-cli's own app-server protocol schema
  // (docs/plan/phase-12-linear-cache-serena-context.md Step C0): thread/compact/start
  // + thread/compacted exist on the default protocol surface, no --experimental flag.
  compact: true,
  serenaContext: 'codex',
};
