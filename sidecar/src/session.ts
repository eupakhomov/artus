import {
  query,
  type Options,
  type SDKControlGetContextUsageResponse,
  type SDKUserMessage,
} from '@anthropic-ai/claude-agent-sdk';
import {
  CLAUDE_CAPABILITIES,
  PROTOCOL_VERSION,
  type Command,
  type ContextBreakdown,
  type ContextUsageEvent,
  type EffortLevel,
  type Event,
  type ModelTokenUsage,
  type PermissionMode,
  type ThinkingSetting,
} from './protocol.js';
import { PermissionBridge, readOnlyDenial } from './permissions.js';
import { log, readLines, writeEvent } from './stdio.js';

export interface SidecarConfig {
  cwd: string;
  /** Write boundary for readOnlyDenial (docs/plan/phase-11-monorepo.md) — the worktree root; equals cwd for a polyrepo session, wider than it for a monorepo one. */
  writableRoot: string;
  resume?: string;
  model?: string;
  fallbackModel?: string;
  permissionMode?: PermissionMode;
  allowedTools?: string[];
  disallowedTools?: string[];
  mcpConfigPath?: string;
  appendSystemPrompt?: string;
  contextDirs: string[];
  /** Local plugin directories (SDK `plugins`); their MCP servers, skills and commands load with the session. */
  pluginDirs: string[];
  thinking?: ThinkingSetting;
  effort?: EffortLevel;
  maxTurns?: number;
}

function thinkingConfig(
  setting: ThinkingSetting,
): { type: 'disabled' } | { type: 'adaptive'; display: 'summarized' } | { type: 'enabled'; budgetTokens: number; display: 'summarized' } {
  if (setting === 'off') return { type: 'disabled' };
  // summarized display: on redacted-thinking models (Claude 5) this is the only way
  // to get user-visible thinking text; the raw stream carries token estimates only
  if (setting === 'adaptive') return { type: 'adaptive', display: 'summarized' };
  return { type: 'enabled', budgetTokens: setting, display: 'summarized' };
}

const TOOL_OUTPUT_LIMIT = Number(process.env['ARTUS_TOOL_OUTPUT_LIMIT'] ?? 16 * 1024);

/** Unbounded async FIFO bridging stdin commands to the SDK's streaming input. */
class AsyncQueue<T> {
  private items: T[] = [];
  private waiters: ((item: T | undefined) => void)[] = [];
  private closed = false;

  push(item: T): void {
    if (this.closed) return;
    const waiter = this.waiters.shift();
    if (waiter) waiter(item);
    else this.items.push(item);
  }

  close(): void {
    this.closed = true;
    for (const waiter of this.waiters.splice(0)) waiter(undefined);
  }

  async next(): Promise<T | undefined> {
    const item = this.items.shift();
    if (item !== undefined) return item;
    if (this.closed) return undefined;
    return new Promise((res) => this.waiters.push(res));
  }
}

function toolResultToString(content: unknown): string {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    return content
      .map((block) => {
        const b = block as { type?: string; text?: string };
        return b.type === 'text' && typeof b.text === 'string' ? b.text : JSON.stringify(block);
      })
      .join('\n');
  }
  return JSON.stringify(content ?? '');
}

async function loadMcpServers(path: string): Promise<Record<string, unknown>> {
  const { readFile } = await import('node:fs/promises');
  const parsed = JSON.parse(await readFile(path, 'utf8')) as Record<string, unknown>;
  // accept both a bare server map and the CLI's {"mcpServers": {...}} shape
  return (parsed['mcpServers'] as Record<string, unknown>) ?? parsed;
}

/**
 * Splits our own per-server `enabledTools` allowlist out of the MCP config (the SDK would pass an
 * unknown key through to the CLI). The SDK has no allow-only control for MCP tools — a per-server
 * `tools` policy is ignored for these servers, and `disallowedTools: mcp__x__*` beats `allowedTools`
 * — so enforcement is a PreToolUse hook, see {@link enabledToolsHook}.
 */
export function extractEnabledTools(servers: Record<string, unknown>): {
  servers: Record<string, unknown>;
  enabled: Map<string, Set<string>>;
} {
  const cleaned: Record<string, unknown> = {};
  const enabled = new Map<string, Set<string>>();
  for (const [name, value] of Object.entries(servers)) {
    if (value && typeof value === 'object' && Array.isArray((value as Record<string, unknown>)['enabledTools'])) {
      const { enabledTools, ...rest } = value as Record<string, unknown>;
      enabled.set(name, new Set((enabledTools as unknown[]).filter((t): t is string => typeof t === 'string')));
      cleaned[name] = rest;
    } else {
      cleaned[name] = value;
    }
  }
  return { servers: cleaned, enabled };
}

/**
 * Denies `mcp__<server>__<tool>` calls for a server with an allowlist unless the tool is on it.
 * A PreToolUse hook fires in every permission mode, bypassPermissions included (verified), and the
 * reason reaches the model, so it learns the tool is off-limits rather than seeing a bare failure.
 */
export function enabledToolsHook(enabled: Map<string, Set<string>>) {
  return async (input: { hook_event_name: string; tool_name?: string }) => {
    const m = /^mcp__(.+?)__(.+)$/.exec(input.tool_name ?? '');
    const allowed = m ? enabled.get(m[1]!) : undefined;
    if (m && allowed && !allowed.has(m[2]!)) {
      return {
        hookSpecificOutput: {
          hookEventName: 'PreToolUse' as const,
          permissionDecision: 'deny' as const,
          permissionDecisionReason: `The ${m[1]} tool ${m[2]} is not enabled for Artus sessions (allowed: ${[...allowed].join(', ') || 'none'}).`,
        },
      };
    }
    return { continue: true };
  };
}

/**
 * Pure(ish) translation of one SDK message into the zero-or-more protocol Events it
 * produces, plus any stderr-bound diagnostic lines. Mutates `modelState.currentModel`
 * on a `system/init` message (mirrors the pre-extraction inline `currentModel`
 * variable) so a later `result` message can stamp the model that produced the turn,
 * and `modelState.lastTotalCostUsd` on a `result` (see {@link turnCostDelta}).
 * Split out from {@link runSession}'s main loop so it's testable without a live SDK
 * query — feed it canned SDK message shapes directly.
 */
const TOP_TOOLS = 5;

/**
 * Maps the SDK's getContextUsage() report to our provider-neutral context_usage event.
 * Pure (no I/O) so it is unit-testable. The breakdown is display-only: zero-token rows are
 * dropped and nothing is matched on the SDK's category display names.
 */
export function toContextUsageEvent(
  usage: Pick<
    SDKControlGetContextUsageResponse,
    'totalTokens' | 'rawMaxTokens' | 'autoCompactThreshold' | 'categories' | 'mcpTools' | 'messageBreakdown'
  >,
): ContextUsageEvent {
  const categories = usage.categories
    .filter((c) => c.tokens > 0)
    .map((c) => ({ name: c.name, tokens: c.tokens }))
    .sort((a, b) => b.tokens - a.tokens);

  const servers = new Map<string, { tokens: number; allDeferred: boolean }>();
  for (const t of usage.mcpTools ?? []) {
    const s = servers.get(t.serverName) ?? { tokens: 0, allDeferred: true };
    s.tokens += t.tokens;
    s.allDeferred = s.allDeferred && t.isLoaded === false;
    servers.set(t.serverName, s);
  }
  const mcpServers = [...servers.entries()]
    .filter(([, s]) => s.tokens > 0)
    .map(([name, s]) => ({ name, tokens: s.tokens, deferred: s.allDeferred }))
    .sort((a, b) => b.tokens - a.tokens);

  const mb = usage.messageBreakdown;
  const breakdown: ContextBreakdown = {
    categories,
    mcpServers,
    ...(mb
      ? {
          messages: {
            toolResults: mb.toolResultTokens,
            toolCalls: mb.toolCallTokens,
            assistant: mb.assistantMessageTokens,
            user: mb.userMessageTokens,
            attachments: mb.attachmentTokens,
          },
        }
      : {}),
    topTools: (mb?.toolCallsByType ?? [])
      .map((t) => ({ name: t.name, tokens: t.callTokens + t.resultTokens }))
      .filter((t) => t.tokens > 0)
      .sort((a, b) => b.tokens - a.tokens)
      .slice(0, TOP_TOOLS),
  };

  return {
    type: 'context_usage',
    tokens: usage.totalTokens,
    window: usage.rawMaxTokens,
    ...(usage.autoCompactThreshold !== undefined ? { autoCompactAt: usage.autoCompactThreshold } : {}),
    breakdown,
  };
}

interface RawUsage {
  input_tokens?: number;
  output_tokens?: number;
  cache_read_input_tokens?: number;
  cache_creation_input_tokens?: number;
  cache_creation?: { ephemeral_5m_input_tokens?: number; ephemeral_1h_input_tokens?: number };
}

/** One model call's counts as seen so far; the cache-write split is kept apart from its total. */
interface CallUsage {
  model: string;
  input: number;
  output: number;
  cacheRead: number;
  cacheWrite: number;
  /** the 1-hour share of cacheWrite, from the last message that carried a breakdown; null = none seen */
  cacheWrite1h: number | null;
}

/**
 * Token usage of the current turn, one entry per model call (phase 25 step 3). The SDK
 * splits one API response into an `assistant` message per content block, each repeating the
 * same partial usage, and only the main loop's `message_delta` stream event carries the final
 * output count — so calls are keyed by message id and every field keeps its largest value.
 * The `assistant` messages carry only `cache_creation_input_tokens`, while the stream's
 * `message_start` also carries the 5m/1h breakdown: the total and the split are tracked
 * separately, so the same tokens are never counted as both kinds. Subagent calls are only
 * seen as `assistant` messages, so their output is undercounted and their cache writes are
 * priced as 5-minute ones: this feeds an estimate, never the billed figure.
 */
export class TurnUsageTracker {
  private readonly calls = new Map<string, CallUsage>();
  private streamingId: string | null = null;

  /** @returns whether any count grew */
  record(id: string, model: string, raw: RawUsage | undefined): boolean {
    if (!raw) return false;
    const write1h = raw.cache_creation ? (raw.cache_creation.ephemeral_1h_input_tokens ?? 0) : null;
    const next = {
      input: raw.input_tokens ?? 0,
      output: raw.output_tokens ?? 0,
      cacheRead: raw.cache_read_input_tokens ?? 0,
      cacheWrite: raw.cache_creation_input_tokens
        ?? (raw.cache_creation ? (raw.cache_creation.ephemeral_5m_input_tokens ?? 0) + (write1h ?? 0) : 0),
    };
    const prev = this.calls.get(id);
    if (!prev) {
      this.calls.set(id, { model, ...next, cacheWrite1h: write1h });
      return true;
    }
    let grew = false;
    for (const k of Object.keys(next) as (keyof typeof next)[]) {
      if (next[k] > prev[k]) {
        prev[k] = next[k];
        grew = true;
      }
    }
    if (write1h !== null && write1h > (prev.cacheWrite1h ?? -1)) {
      prev.cacheWrite1h = write1h;
      grew = true;
    }
    return grew;
  }

  startStream(id: string, model: string, raw: RawUsage | undefined): boolean {
    this.streamingId = id;
    return this.record(id, model, raw);
  }

  endStream(raw: RawUsage | undefined): boolean {
    const id = this.streamingId;
    const call = id ? this.calls.get(id) : undefined;
    return id && call ? this.record(id, call.model, raw) : false;
  }

  byModel(): Record<string, ModelTokenUsage> {
    const out: Record<string, ModelTokenUsage> = {};
    for (const c of this.calls.values()) {
      const acc = (out[c.model] ??= { input: 0, output: 0, cacheRead: 0, cacheWrite5m: 0, cacheWrite1h: 0 });
      // no breakdown seen = all of it is the 5-minute kind
      const write1h = Math.min(c.cacheWrite1h ?? 0, c.cacheWrite);
      acc.input += c.input;
      acc.output += c.output;
      acc.cacheRead += c.cacheRead;
      acc.cacheWrite1h += write1h;
      acc.cacheWrite5m += c.cacheWrite - write1h;
    }
    return out;
  }

  reset(): void {
    this.calls.clear();
    this.streamingId = null;
  }
}

/** What {@link translateSdkMessage} carries from one SDK message to the next. */
export interface TranslateState {
  currentModel: string;
  lastTotalCostUsd: number;
  usage: TurnUsageTracker;
}

export function newTranslateState(currentModel = ''): TranslateState {
  return { currentModel, lastTotalCostUsd: 0, usage: new TurnUsageTracker() };
}

/**
 * The SDK's `result.total_cost_usd` is a running total for the whole `query()` — one per
 * sidecar process — so a turn's own cost is the difference from the previous result. A
 * total below the previous one means the CLI reset it (`/clear`, a crash result), and
 * the total is then this turn's cost on its own (phase 25 step 1).
 */
export function turnCostDelta(total: number, lastTotal: number): number {
  return total >= lastTotal ? total - lastTotal : total;
}

export function translateSdkMessage(
  message: { type: string } & Record<string, unknown>,
  modelState: TranslateState,
): { events: Event[]; logs: string[] } {
  const events: Event[] = [];
  const logs: string[] = [];
  switch (message.type) {
    case 'system':
      if ((message as unknown as { subtype: string }).subtype === 'thinking_tokens') {
        const m = message as unknown as { estimated_tokens: number; estimated_tokens_delta: number };
        events.push({
          type: 'thinking_progress',
          estimatedTokens: m.estimated_tokens,
          estimatedTokensDelta: m.estimated_tokens_delta,
        });
      } else if (message['subtype'] === 'init') {
        const m = message as unknown as {
          model: string;
          session_id: string;
          cwd: string;
          tools: string[];
          mcp_servers: { name: string; status: string }[];
          permissionMode: string;
        };
        modelState.currentModel = m.model;
        events.push({
          type: 'system_init',
          providerSessionId: m.session_id,
          model: m.model,
          cwd: m.cwd,
          tools: m.tools,
          mcpServers: m.mcp_servers,
          permissionMode: m.permissionMode as PermissionMode,
        });
      } else if (message['subtype'] === 'compact_boundary') {
        const m = message as unknown as {
          compact_metadata: { trigger: 'manual' | 'auto'; pre_tokens: number; post_tokens?: number };
        };
        events.push({
          type: 'context_compacted',
          preTokens: m.compact_metadata.pre_tokens,
          postTokens: m.compact_metadata.post_tokens ?? m.compact_metadata.pre_tokens,
          trigger: m.compact_metadata.trigger,
        });
      } else if (message['subtype'] === 'task_started') {
        const m = message as unknown as {
          task_id: string; tool_use_id?: string; description: string; task_type?: string;
          subagent_type?: string; is_backgrounded?: boolean; skip_transcript?: boolean;
        };
        if (!m.skip_transcript) {
          events.push({
            type: 'task_started',
            taskId: m.task_id,
            ...(m.tool_use_id ? { toolUseId: m.tool_use_id } : {}),
            description: m.description,
            ...(m.task_type ? { kind: m.task_type } : {}),
            ...(m.subagent_type ? { subagentType: m.subagent_type } : {}),
            backgrounded: m.is_backgrounded === true,
          });
        }
      } else if (message['subtype'] === 'task_progress') {
        const m = message as unknown as {
          task_id: string; tool_use_id?: string; description: string; last_tool_name?: string;
          usage: { total_tokens: number; tool_uses: number; duration_ms: number };
        };
        events.push({
          type: 'task_progress',
          taskId: m.task_id,
          ...(m.tool_use_id ? { toolUseId: m.tool_use_id } : {}),
          description: m.description,
          ...(m.last_tool_name ? { lastTool: m.last_tool_name } : {}),
          toolUses: m.usage?.tool_uses ?? 0,
          tokens: m.usage?.total_tokens ?? 0,
          durationMs: m.usage?.duration_ms ?? 0,
        });
      } else if (message['subtype'] === 'task_notification') {
        const m = message as unknown as {
          task_id: string; tool_use_id?: string; status: 'completed' | 'failed' | 'stopped'; summary: string;
        };
        events.push({
          type: 'task_done',
          taskId: m.task_id,
          ...(m.tool_use_id ? { toolUseId: m.tool_use_id } : {}),
          status: m.status,
          summary: m.summary ?? '',
        });
      } else if (message['subtype'] === 'background_tasks_changed') {
        const m = message as unknown as { tasks: { task_id: string; task_type: string; description: string }[] };
        events.push({
          type: 'background_tasks',
          tasks: (m.tasks ?? []).map((t) => ({ taskId: t.task_id, kind: t.task_type, description: t.description })),
        });
      } else if (
        message['subtype'] === 'status' &&
        (message as unknown as { compact_result?: string }).compact_result === 'failed'
      ) {
        // A manual /compact can fail per-model even when normal turns on that model work
        // (docs/plan/phase-12-linear-cache-serena-context.md Step C0 outcome) — surface it
        // rather than silently leaving the chip stale.
        const m = message as unknown as { compact_error?: string };
        events.push({ type: 'error', message: `compact failed: ${m.compact_error ?? 'unknown error'}`, fatal: false });
      }
      break;
    case 'stream_event': {
      const outer = message as unknown as { event: unknown; parent_tool_use_id?: string | null };
      const parentToolUseId = outer.parent_tool_use_id ?? undefined;
      const event = outer.event as {
        type: string;
        delta?: { type: string; text?: string; thinking?: string };
        message?: { id?: string; model?: string; usage?: RawUsage };
        usage?: RawUsage;
      };
      if (event.type === 'message_start' && event.message?.id) {
        if (modelState.usage.startStream(event.message.id, event.message.model ?? modelState.currentModel, event.message.usage)) {
          events.push({ type: 'turn_usage', byModel: modelState.usage.byModel() });
        }
      } else if (event.type === 'message_delta') {
        if (modelState.usage.endStream(event.usage)) {
          events.push({ type: 'turn_usage', byModel: modelState.usage.byModel() });
        }
      } else if (event.type === 'content_block_delta' && event.delta) {
        if (event.delta.type === 'text_delta' && event.delta.text) {
          events.push({ type: 'stream_delta', deltaType: 'text', text: event.delta.text, ...(parentToolUseId ? { parentToolUseId } : {}) });
        } else if (event.delta.type === 'thinking_delta' && event.delta.thinking) {
          events.push({ type: 'stream_delta', deltaType: 'thinking', text: event.delta.thinking, ...(parentToolUseId ? { parentToolUseId } : {}) });
        } else {
          logs.push(`unmapped stream delta: ${JSON.stringify(event.delta).slice(0, 300)}`);
        }
      }
      break;
    }
    case 'assistant': {
      const outer = message as unknown as {
        message: { id?: string; model?: string; usage?: RawUsage; content: unknown[] };
        parent_tool_use_id?: string | null;
      };
      const parentToolUseId = outer.parent_tool_use_id ?? undefined;
      const content = outer.message.content;
      events.push({ type: 'assistant_message', content, ...(parentToolUseId ? { parentToolUseId } : {}) });
      if (outer.message.id && modelState.usage.record(outer.message.id, outer.message.model ?? modelState.currentModel, outer.message.usage)) {
        events.push({ type: 'turn_usage', byModel: modelState.usage.byModel() });
      }
      for (const block of content) {
        const b = block as { type: string; id?: string; name?: string; input?: Record<string, unknown> };
        if (b.type === 'tool_use' && b.id && b.name) {
          events.push({ type: 'tool_started', toolUseId: b.id, name: b.name, input: b.input ?? {}, ...(parentToolUseId ? { parentToolUseId } : {}) });
        }
      }
      break;
    }
    case 'user': {
      const outer = message as unknown as { message: { content: unknown }; parent_tool_use_id?: string | null };
      const parentToolUseId = outer.parent_tool_use_id ?? undefined;
      const content = outer.message.content;
      if (Array.isArray(content)) {
        for (const block of content) {
          const b = block as {
            type: string;
            tool_use_id?: string;
            content?: unknown;
            is_error?: boolean;
          };
          if (b.type === 'tool_result' && b.tool_use_id) {
            const full = toolResultToString(b.content);
            events.push({
              type: 'tool_result',
              toolUseId: b.tool_use_id,
              isError: b.is_error === true,
              output: full.slice(0, TOOL_OUTPUT_LIMIT),
              truncated: full.length > TOOL_OUTPUT_LIMIT,
              ...(parentToolUseId ? { parentToolUseId } : {}),
            });
          }
        }
      }
      break;
    }
    case 'result': {
      const m = message as unknown as {
        subtype: string;
        usage: unknown;
        total_cost_usd: number;
        duration_ms: number;
        num_turns: number;
      };
      const total = m.total_cost_usd ?? 0;
      const costUsd = turnCostDelta(total, modelState.lastTotalCostUsd);
      modelState.lastTotalCostUsd = total;
      modelState.usage.reset();
      events.push({
        type: 'turn_complete',
        stopReason: m.subtype,
        usage: m.usage,
        costUsd,
        sessionCostUsd: total,
        durationMs: m.duration_ms,
        numTurns: m.num_turns,
        model: modelState.currentModel,
      });
      break;
    }
    case 'rate_limit_event': {
      // surface only non-nominal rate-limit statuses so the UI shows trouble, not noise
      const rl = (message as unknown as { rate_limit?: { status?: string } }).rate_limit;
      if (rl?.status && rl.status !== 'allowed') {
        events.push({
          type: 'error',
          message: `provider rate limit: ${JSON.stringify(rl).slice(0, 300)}`,
          fatal: false,
        });
      }
      break;
    }
    default:
      logs.push(`unhandled SDK message type: ${message.type}`);
  }
  return { events, logs };
}

/**
 * What an exception out of the SDK's message stream means for this process.
 *
 * Once the backend has asked us to stop (a `shutdown` command, or stdin closing) nothing the stream
 * throws is news. In particular the SDK remembers the text of the last turn that ended in an *error
 * result* (an interrupt is `error_during_execution`; hitting max turns is `error_max_turns`) and, when
 * the agent process then exits at shutdown, replaces the exit error with that stale text
 * ("Claude Code returned an error result: …"). Reporting that as a fatal error put a red note in the
 * transcript of every session that was closed or idle-parked right after such a turn. A later normal
 * turn clears the SDK's memory, which is why only the *last* turn matters.
 *
 * An exception while NOT shutting down is still fatal, unchanged.
 */
export type StreamFailureOutcome =
  | { kind: 'expected'; reason: 'shutdown' | 'stdin_closed'; note: string }
  | { kind: 'fatal'; message: string };

export function classifyStreamFailure(
  error: unknown,
  exiting: 'shutdown' | 'stdin_closed' | undefined,
): StreamFailureOutcome {
  const message = String(error instanceof Error ? error.stack ?? error.message : error);
  if (exiting) {
    const first = message.split('\n')[0] ?? '';
    return { kind: 'expected', reason: exiting, note: `SDK stream ended with an error during ${exiting} (ignored): ${first}` };
  }
  return { kind: 'fatal', message };
}

export async function runSession(config: SidecarConfig): Promise<never> {
  const bridge = new PermissionBridge();
  const inputQueue = new AsyncQueue<SDKUserMessage>();
  let exiting: 'shutdown' | 'stdin_closed' | undefined;

  // The SDK only honours bypassPermissions when the process was launched with
  // allowDangerouslySkipPermissions, so it can't be switched to at runtime. For a session that
  // wasn't launched with it we emulate: the SDK stays in `default` (every tool call reaches
  // canUseTool) and `emulatedBypass` makes canUseTool allow it without a UI round trip.
  const launchedWithBypass = config.permissionMode === 'bypassPermissions';
  let emulatedBypass = false;
  const permissionModes = CLAUDE_CAPABILITIES.permissionModes;

  writeEvent({
    type: 'ready',
    pid: process.pid,
    protocolVersion: PROTOCOL_VERSION,
    provider: 'claude',
    capabilities: { ...CLAUDE_CAPABILITIES, permissionModes },
  });
  // the CLI sends nothing until membership changes, so a (re)spawn starts from a known-empty set
  writeEvent({ type: 'background_tasks', tasks: [] });

  async function* userMessages(): AsyncGenerator<SDKUserMessage> {
    for (;;) {
      const msg = await inputQueue.next();
      if (msg === undefined) return;
      yield msg;
    }
  }

  const mcp = config.mcpConfigPath ? extractEnabledTools(await loadMcpServers(config.mcpConfigPath)) : undefined;
  const options: Options = {
    cwd: config.cwd,
    includePartialMessages: true,
    // Forward a Task-tool subagent's own thinking/text (not just its tool_use/tool_result
    // calls) so the frontend can render a nested transcript under the Task call — harmless
    // no-op for sessions that never invoke Task.
    forwardSubagentText: true,
    // Without this, "provider default" thinking (config.thinking left unset, so no
    // `thinking` option below and no per-call `display: 'summarized'`) gets raw redacted
    // thinking blocks — empty `thinking` text plus an opaque `signature`, so the frontend
    // never has text to show even though thinking_progress events still fire. This
    // Settings-level flag requests API-side summaries independently of the per-call
    // `thinking.display`, so it also covers that case; harmless to also set when a
    // `thinking.display` is already explicit (off has nothing to summarize either way).
    settings: { showThinkingSummaries: true },
    settingSources: ['project'],
    systemPrompt: config.appendSystemPrompt
      ? { type: 'preset', preset: 'claude_code', append: config.appendSystemPrompt }
      : { type: 'preset', preset: 'claude_code' },
    ...(config.resume ? { resume: config.resume } : {}),
    ...(config.model ? { model: config.model } : {}),
    ...(config.fallbackModel ? { fallbackModel: config.fallbackModel } : {}),
    ...(config.permissionMode ? { permissionMode: config.permissionMode } : {}),
    // required by the SDK to actually honor bypassPermissions (a safety measure on its side,
    // separate from our own — readOnlyDenial in canUseTool below still applies regardless)
    ...(config.permissionMode === 'bypassPermissions' ? { allowDangerouslySkipPermissions: true } : {}),
    ...(config.allowedTools ? { allowedTools: config.allowedTools } : {}),
    ...(config.disallowedTools ? { disallowedTools: config.disallowedTools } : {}),
    ...(config.contextDirs.length > 0 ? { additionalDirectories: config.contextDirs } : {}),
    ...(config.pluginDirs.length > 0 ? { plugins: config.pluginDirs.map((path) => ({ type: 'local' as const, path })) } : {}),
    ...(config.thinking !== undefined ? { thinking: thinkingConfig(config.thinking) } : {}),
    ...(config.effort !== undefined ? { effort: config.effort } : {}),
    ...(config.maxTurns !== undefined ? { maxTurns: config.maxTurns } : {}),
    ...(mcp ? { mcpServers: mcp.servers as never } : {}),
    ...(mcp && mcp.enabled.size > 0
      ? { hooks: { PreToolUse: [{ matcher: 'mcp__.*', hooks: [enabledToolsHook(mcp.enabled) as never] }] } }
      : {}),
    stderr: (data: string) => log('cli:', data.trimEnd()),
    canUseTool: async (toolName, input, { suggestions }) => {
      const denial = readOnlyDenial(toolName, input, config.cwd, config.writableRoot);
      if (denial) {
        writeEvent({ type: 'error', message: denial, fatal: false });
        return { behavior: 'deny', message: denial };
      }
      if (emulatedBypass) return { behavior: 'allow', updatedInput: input };
      return bridge.request(toolName, input, suggestions ?? []);
    },
  };

  const q = query({ prompt: userMessages(), options });
  // Report what the CLI/gateway actually offers so the backend validates against it, not a static list.
  void q
    .supportedModels()
    .then((models) => {
      const list = models
        .filter((m) => m.value !== 'default')
        .map((m) => ({
          id: m.value,
          label: m.displayName,
          tier: m.value.includes('[1m]')
            ? ('extended' as const)
            : m.value.includes('haiku')
              ? ('cheap' as const)
              : m.value.includes('opus')
                ? ('premium' as const)
                : ('standard' as const),
        }));
      if (list.length > 0) writeEvent({ type: 'models_available', models: list });
    })
    .catch((e: unknown) => log(`supportedModels failed: ${String(e)}`));
  // updated from system_init (which reports the concrete resolved id, even for an alias like "sonnet")
  const modelState = newTranslateState();
  // Belt-and-suspenders guard for 'compact' — the backend only sends it for an
  // IDLE/parked session, but the sidecar checks too (docs/plan/phase-12-linear-cache-
  // serena-context.md decision 9). Set on any pushed user message (including /compact
  // itself), cleared when its turn_complete arrives.
  let turnInFlight = false;

  async function emitContextUsage(): Promise<void> {
    try {
      writeEvent(toContextUsageEvent(await q.getContextUsage()));
    } catch (e) {
      log(`getContextUsage failed: ${String(e)}`);
    }
  }

  const handleCommand = (line: string): void => {
    let cmd: Command;
    try {
      cmd = JSON.parse(line) as Command;
    } catch {
      writeEvent({ type: 'error', message: `malformed command line: ${line.slice(0, 200)}`, fatal: false });
      return;
    }
    switch (cmd.type) {
      case 'user_message':
        turnInFlight = true;
        inputQueue.push({
          type: 'user',
          message: { role: 'user', content: [{ type: 'text', text: cmd.text }] },
          parent_tool_use_id: null,
          session_id: '',
        } as SDKUserMessage);
        break;
      case 'compact':
        if (turnInFlight) {
          writeEvent({ type: 'error', message: 'compact: turn in progress', fatal: false });
          break;
        }
        turnInFlight = true;
        inputQueue.push({
          type: 'user',
          message: { role: 'user', content: [{ type: 'text', text: '/compact' }] },
          parent_tool_use_id: null,
          session_id: '',
        } as SDKUserMessage);
        break;
      case 'permission_response':
        bridge.resolve(cmd);
        break;
      case 'interrupt':
        bridge.denyAll('Interrupted by user');
        void q.interrupt().catch((e: unknown) => {
          writeEvent({ type: 'error', message: `interrupt failed: ${String(e)}`, fatal: false });
        });
        break;
      case 'set_permission_mode':
        if (cmd.mode === 'bypassPermissions' && !launchedWithBypass) {
          // emulate: drop the SDK to `default` first (plan/acceptEdits would otherwise still apply)
          void q
            .setPermissionMode('default')
            .then(() => {
              emulatedBypass = true;
              bridge.allowAll();
              writeEvent({ type: 'permission_mode_changed', mode: cmd.mode });
            })
            .catch((e: unknown) =>
              writeEvent({ type: 'error', message: `set_permission_mode failed: ${String(e)}`, fatal: false }),
            );
          break;
        }
        void q
          .setPermissionMode(cmd.mode)
          .then(() => {
            emulatedBypass = false;
            writeEvent({ type: 'permission_mode_changed', mode: cmd.mode });
          })
          .catch((e: unknown) =>
            writeEvent({ type: 'error', message: `set_permission_mode failed: ${String(e)}`, fatal: false }),
          );
        break;
      case 'set_model':
        void q
          .setModel(cmd.model)
          .then(() => {
            modelState.currentModel = cmd.model;
            writeEvent({ type: 'model_changed', model: cmd.model });
          })
          .catch((e: unknown) =>
            writeEvent({ type: 'error', message: `set_model failed: ${String(e)}`, fatal: false }),
          );
        break;
      case 'shutdown':
        exiting = 'shutdown';
        bridge.denyAll('Session shutting down');
        inputQueue.close();
        break;
      default:
        writeEvent({
          type: 'error',
          message: `unknown command type: ${(cmd as { type?: string }).type ?? '<none>'}`,
          fatal: false,
        });
    }
  };

  readLines(process.stdin, handleCommand, () => {
    if (!exiting) {
      exiting = 'stdin_closed';
      bridge.denyAll('stdin closed');
      inputQueue.close();
    }
  });

  try {
    for await (const message of q) {
      const { events, logs } = translateSdkMessage(message, modelState);
      for (const event of events) writeEvent(event);
      for (const line of logs) log(line);
      const compacted = events.some((e) => e.type === 'context_compacted');
      const completed = events.some((e) => e.type === 'turn_complete');
      if (completed) turnInFlight = false;
      if (compacted || completed) await emitContextUsage();
    }
  } catch (e) {
    const outcome = classifyStreamFailure(e, exiting);
    if (outcome.kind === 'expected') {
      // stderr only: the backend asked us to stop, so this is not an event anyone needs to see
      log(outcome.note);
      writeEvent({ type: 'exiting', reason: outcome.reason });
      process.exit(0);
    }
    writeEvent({ type: 'error', message: outcome.message, fatal: true });
    writeEvent({ type: 'exiting', reason: 'fatal' });
    process.exit(1);
  }

  writeEvent({ type: 'exiting', reason: exiting ?? 'shutdown' });
  process.exit(0);
}
