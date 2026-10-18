# Provider Adapter Protocol (v1)

The contract between the backend and a per-session **provider adapter** process.
`sidecar/` is the Claude reference implementation (`sidecar/src/protocol.ts` is the
typed source of truth); any process speaking this protocol plugs into the same
backend and UI. Provider-specific behavior stays inside the adapter.

## Transport

- NDJSON over stdio: one JSON object per line. stdin carries **commands**, stdout
  carries **events** and nothing else. All logging goes to stderr.
- No line-length limit on either side. Malformed or unknown input must produce a
  non-fatal `error` event, never a crash.
- Closing stdin is equivalent to `shutdown`. Exit code 0 = clean end, non-zero = fatal.

## Adapter invocation (Claude sidecar)

```
node sidecar/dist/index.js --cwd <dir>
  [--resume <provider-session-id>] [--model <m>] [--fallback-model <m>]
  [--permission-mode default|acceptEdits|plan|bypassPermissions]
  [--allowed-tools <csv>] [--disallowed-tools <csv>] [--mcp-config <path.json>]
  [--append-system-prompt <text>] [--context-dir <path>]...
  [--thinking off|adaptive|<budgetTokens>] [--effort low|medium|high|xhigh|max]
  [--max-turns <n>]
```

`--context-dir` (repeatable) attaches read-only context directories: reads are
allowed, file-modifying tools targeting paths outside `--cwd` are auto-denied by the
adapter with an explanatory non-fatal `error` event (no permission round-trip).
Bash is not path-policed — it flows through the normal approval path.

## Commands (backend → adapter)

| type | fields | semantics |
|---|---|---|
| `user_message` | `text` | queue the next user turn |
| `permission_response` | `requestId`, `behavior: allow\|deny`, `updatedInput?`, `message?` | settle a `permission_request`; `updatedInput` replaces the tool input on allow (e.g. an edited Bash command); `message` is the denial reason shown to the model |
| `interrupt` | — | abort the in-flight turn; outstanding permission requests are denied internally; the session stays alive |
| `set_permission_mode` | `mode` | switch permission mode mid-session; acknowledged by `permission_mode_changed` |
| `set_model` | `model` | switch model mid-session; takes effect starting the next assistant response; acknowledged by `model_changed` |
| `compact` | — | in-place context compaction (see docs/plan/phase-12-linear-cache-serena-context.md decision 9); rejected with a non-fatal `error` (`"compact: turn in progress"`) if a turn is already queued or running — the backend only sends it for an IDLE/parked session, this is the adapter-side belt |
| `shutdown` | — | finish current work, then exit 0 with `exiting` |

## Events (adapter → backend)

| type | fields | when |
|---|---|---|
| `ready` | `pid`, `protocolVersion: 1`, `provider`, `capabilities` | first event after start |
| `system_init` | `providerSessionId`, `model`, `cwd`, `tools[]`, `mcpServers[]`, `permissionMode` | provider session established — arrives with the **first turn**, not at startup (`ready` is the liveness signal); the backend must persist `providerSessionId` to enable `--resume` |
| `stream_delta` | `deltaType: text\|thinking`, `text`, `parentToolUseId?` | incremental generation output |
| `assistant_message` | `content[]` (Anthropic-format blocks), `parentToolUseId?` | each completed assistant message |
| `tool_started` | `toolUseId`, `name`, `input`, `parentToolUseId?` | tool call issued |
| `tool_result` | `toolUseId`, `isError`, `output` (≤16 KB), `truncated`, `parentToolUseId?` | tool finished |
| `permission_request` | `requestId`, `toolName`, `input`, `suggestions[]` | user approval needed; adapter blocks that tool until the matching `permission_response` (no timeout — waiting is the UI's job) |
| `thinking_progress` | `estimatedTokens`, `estimatedTokensDelta` | running token estimate while the model thinks (drive spinners/pills; not billed usage) |
| `permission_mode_changed` | `mode` | confirms `set_permission_mode` |
| `model_changed` | `model` | confirms `set_model` |
| `turn_complete` | `stopReason`, `usage`, `costUsd`, `durationMs`, `numTurns`, `model` | end of an agentic turn; `model` is the model that produced it (tracked from the preceding `system_init`, which fires at the start of every turn) |
| `context_usage` | `tokens`, `window`, `autoCompactAt?`, `breakdown?` | how full the *next* request's context will be — never `turn_complete.usage`'s per-turn summary. Emitted after every `turn_complete` and after a `context_compacted`, never mid-turn. `breakdown` (Claude only; display-only, journaled with the event, not persisted on the session row) = `{categories: [{name, tokens}], mcpServers: [{name, tokens, deferred}], messages?: {toolResults, toolCalls, assistant, user, attachments}, topTools: [{name, tokens}]}` — zero-token rows omitted, `mcpServers[].deferred` = the server's tool schemas are not loaded up front (tool search), `topTools` = max 5 by call+result tokens |
| `context_compacted` | `preTokens`, `postTokens`, `trigger: manual\|auto` | a compaction boundary just happened (a `compact` command or the provider's own auto-compact) |
| `error` | `message`, `fatal` | non-fatal: guard denials, malformed input, transient provider errors; fatal: precedes exit 1 |
| `exiting` | `reason: shutdown\|fatal\|stdin_closed` | last event before exit. An error thrown by the underlying agent stream *after* a `shutdown` command or a closed stdin is expected and is only logged to stderr (`exiting` keeps that reason, exit 0); the same error while the sidecar was not asked to stop is `fatal` |

## Capabilities

`ready.capabilities` declares what the adapter supports; the UI renders controls from
this, never from the provider name:

```json
{
  "permissionModes": ["default", "acceptEdits", "plan", "bypassPermissions"],
  "thinking": true, "effort": true, "planMode": true, "resume": true, "skills": true,
  "agents": true, "mcp": true, "interrupt": true, "fallbackModel": true,
  "updatedInput": true, "modelSwitch": true,
  "models": [
    {"id": "haiku", "label": "haiku", "tier": "cheap"},
    {"id": "sonnet", "label": "sonnet", "tier": "standard"},
    {"id": "opus", "label": "opus", "tier": "premium"}
  ],
  "unsupportedSessionFields": [],
  "contextDirs": true,
  "reportsCostUsd": true,
  "compact": true,
  "serenaContext": "claude-code"
}
```

`models` is each provider's known model catalog (`tier` one of `cheap`/`standard`/
`premium`); empty means the provider has no fixed list (Codex resolves its own default
model/alias, so the UI falls back to a free-text model field). `GET /api/providers`
exposes the same shape statically (before any session exists to report it live) —
backed by `ModelCatalog` on the backend, which also lets backend-initiated system
turns pick a model by tier instead of a hardcoded alias (see CLAUDE.md's
`session.system-provider` setting).

`unsupportedSessionFields`/`contextDirs`/`reportsCostUsd` are what let the backend
reject/degrade/estimate by **capability** instead of a hardcoded provider name (see
docs/plan/phase-10-review-followups.md R1): `unsupportedSessionFields` names
session/create-option JSON keys this provider rejects outright at creation
(`SessionConfigFactory.prepare`) and that `SidecarManager.buildArgs` also omits as
defense-in-depth; `contextDirs: false` is the one "warn-and-drop" exception (ecosystem/
context directories degrade with a journaled warning rather than a hard reject);
`reportsCostUsd: false` means the adapter's own `turn_complete.costUsd` is always 0 and
the backend estimates it instead from raw token usage against a
Settings-editable `<provider>.pricing` table. These three are backend-enforcement
details only — never read by the frontend, and (unlike the rest of `Capabilities`)
not carried on `GET /api/providers`' `ProviderView.capabilities`, only on a live
session's `ready.capabilities`/`SessionEntity.capabilities`. Sourced from each
adapter's own `capabilities.json` (committed at the package root, generated by
`npm run build` via `scripts/gen-provider-capabilities.mjs` from that package's
`*_CAPABILITIES` const — see `ProviderCatalog.java`), not hand-maintained in the
backend.

`compact: false` (unlike the three above) **is** a frontend-visible capability — the
widget still shows the `context_usage` chip but hides the Compact button/action for
that provider (see docs/plan/phase-12-linear-cache-serena-context.md Step C0: both
shipped providers report `true`, but a future adapter could reasonably not).

`serenaContext` (Track B) is Serena's own built-in `--context` name for this provider
(`"claude-code"`/`"codex"`); empty/absent means the provider doesn't support Serena.
Backend-enforcement only, same as `unsupportedSessionFields`/`contextDirs`/
`reportsCostUsd` above — `SessionConfigFactory` reads it to build the Serena MCP entry's
`--context` argument and to gate the Claude-Code-specific system-prompt override, never
a hardcoded provider name.

## Behavioral notes (Claude adapter)

- **Not every Bash call prompts.** Claude Code sandboxes harmless commands (e.g. a
  pure `echo`) and runs them without a permission request; commands with side effects
  outside the sandbox trigger `permission_request`. UIs must not assume one prompt per
  Bash invocation.
- `permission_request.suggestions` carries the SDK's proposed permission rules
  (opaque; forward to the UI for future "always allow" affordances).
- Skills/agents are discovered from `<cwd>/.claude/skills` and `.claude/agents` at
  process start (the adapter loads project settings); changes require a respawn —
  which `--resume` makes cheap.
- **Subagent nesting.** The adapter always sets `forwardSubagentText: true`, so a
  `Task` tool call's own subagent conversation (thinking, tool calls, text) is
  forwarded as normal `stream_delta`/`assistant_message`/`tool_started`/`tool_result`
  events, each carrying `parentToolUseId` set to the `toolUseId` of the enclosing
  `Task` call — the frontend nests these under that call instead of rendering them
  flat. `thinking_progress` carries no such field (the underlying SDK message has
  none) and is applied positionally by the frontend instead. A subagent's own
  `permission_request`, if any, is **not** correlated to its parent `Task` call (the
  SDK's tool-approval callback exposes no parent id either) and always renders flat.
  Codex has no subagent mechanism and never populates `parentToolUseId`, but the field
  is still declared in its (synced) protocol types for parity.
- Thinking control is `--thinking` (off / adaptive / fixed budget) + `--effort`
  (reasoning-effort level). On Claude 5 models thinking is **redacted**: raw deltas
  carry token estimates, not text — the adapter requests `display: 'summarized'`, so
  UIs receive readable summarized `stream_delta(thinking)` text plus
  `thinking_progress` estimates. "Provider default" (no `--thinking` override) gets no
  per-call `display` field, so the adapter also sets the Settings-level
  `showThinkingSummaries: true` flag unconditionally — without it, provider-default
  turns still emit `thinking_progress` but the redacted `thinking` block itself has
  empty text (just an opaque `signature`), so the frontend would have nothing to show.
  Adaptive mode means the model decides when to think: trivial prompts legitimately
  produce zero thinking events even with thinking enabled — confirmed live: the same
  session, same `adaptive` setting, switched from Haiku (thinks regardless of prompt
  triviality) to Sonnet (correctly skips thinking for "what is 7+5", still thinks fully
  for a genuinely hard problem) mid-conversation. This is intended per-model behavior,
  not something to work around by forcing a fixed budget on model switch. (The SDK's
  `maxThinkingTokens` is deprecated and not used.)
- Model IDs in `system_init` are concrete (e.g. `claude-sonnet-5`) even when the
  adapter was launched with an alias (`sonnet`).
- After `interrupt`, the turn ends with a `turn_complete` (possibly an error subtype)
  or a non-fatal `error`; the process remains usable for further turns.
- A `rate_limit_event` from the provider is currently ignored by the adapter
  (backend-level rate-limit surfacing arrives with Phase 4).
- **`compact`** sends a `/compact` local slash command as a `user` message — confirmed
  live (docs/plan/phase-12-linear-cache-serena-context.md Step C0) to go through the
  same `query()` path as any other prompt. On success the SDK emits (in order) a
  `system{subtype:'status', status:'compacting'}`, a `system{subtype:'status',
  compact_result:'success'}`, a second `system{subtype:'init'}` (re-announces
  model/tools, harmless to re-translate), then `system{subtype:'compact_boundary',
  compact_metadata:{trigger, pre_tokens, post_tokens, ...}}` — mapped straight to
  `context_compacted` — followed by the CLI auto-continuing the turn (its own
  `turn_complete`). **Compaction can fail per-model** even when normal turns on that
  model work fine (observed: `claude-3-5-haiku-20241022` — "may not exist or you may
  not have access to it"), and separately fails outright on a conversation too short
  to compact ("Not enough messages to compact"); either way there's no re-init/
  `compact_boundary`, just the two status messages, surfaced as a non-fatal `error`
  from `compact_result:'failed'`'s `compact_error`.

## Behavioral notes (Codex adapter)

`sidecar-codex/` wraps `codex app-server`'s JSON-RPC-over-stdio protocol (the same one
IDE integrations use), not `codex exec`, which is non-interactive and has no
approval round-trip. Full mapping tables and rationale:
`docs/plan/phase-5.13-codex-provider.md`. Live-confirmed quirks (codex-cli 0.151.0):

- **Not every command/edit prompts, either** — same "not every Bash call prompts"
  behavior as Claude: `default` mode (`workspace-write` sandbox + `on-request`
  approval policy) auto-allows anything the sandbox already permits (in-workspace
  reads/writes, no network); only an action the sandbox denies by default (network
  access, writing outside the workspace) triggers a `permission_request`.
- **Deny semantics differ by item kind.** Declining a file-change approval lets the
  turn continue normally (the model sees the denial and responds in text). Declining a
  command-exec approval can instead **abort the whole turn** (`turn_complete` with
  `stopReason: interrupted`, no closing assistant message) when Codex didn't offer a
  plain `"decline"` option for that specific request (observed: only `"accept"` and
  `"cancel"` offered) — `"cancel"` is a turn-level abort, not a per-call denial. The
  adapter always picks from that request's own `availableDecisions`, never a hardcoded
  decision string.
- Only the **item-based** approval scheme (`item/commandExecution/requestApproval`,
  `item/fileChange/requestApproval`) was observed firing; the legacy scheme
  (`execCommandApproval`, `applyPatchApproval`) never appeared. An unrecognized
  server-request method is auto-declined with a logged warning, never left hanging.
- Usage arrives as running token counts via `thread/tokenUsage/updated` notifications
  during the turn, not on the `Turn` object itself — the adapter tracks the
  last-seen value and attaches it to `turn_complete.usage`. Codex reports no per-turn
  USD; `turn_complete.costUsd` is always `0` from this adapter — the backend
  overwrites it with an estimate from `usage` for `provider: codex` sessions
  (`SessionService.applyCodexCostEstimate`, Settings-editable price table).
- `numTurns` in `turn_complete` is always `1` — Codex has no multi-step-within-a-turn
  counter the way Claude's SDK does; one completed `turn/start` call is one turn.
- `set_permission_mode`/`set_model` apply starting the **next** turn (no live
  mid-turn change), acknowledged optimistically like the Claude adapter.
- A large volume of notifications (`mcpServer/*` for Codex's always-on internal
  `codex_apps` server, `remoteControl/*`, `account/*`, `thread/status/changed`,
  `turn/started`, `turn/diff/updated`, `serverRequest/resolved`, …) is expected and
  dropped silently — roughly 15 notification types beyond the ones this adapter maps,
  in a single two-tool-call turn.
- **Skills** (`skills: true`, 2026-08-30 follow-up): Codex reads the same `SKILL.md`
  format Claude does, but doesn't auto-discover `<cwd>/.claude/skills` — the adapter
  calls `skills/extraRoots/set({extraRoots:[...]})` once, before `thread/start`/
  `thread/resume`, pointing at that already-materialized directory. Confirmed live
  that the model then discovers and invokes a skill autonomously (matching its
  `description`), same UX as Claude — no explicit per-turn skill reference needed.
- **MCP** (`mcp: true`, 2026-08-30 follow-up): `thread/start`/`thread/resume`'s generic
  `config: {mcp_servers: {...}}` field spins up a **thread-scoped** MCP server (not a
  global `codex mcp add` registration) — confirmed via a real stdio server's full
  `starting`→`ready` lifecycle and a genuine tool call. The adapter translates the same
  Claude-shaped mcp config file the backend already writes; the one real shape
  difference is HTTP auth — Codex reads a bearer token from a **named env var**
  (`bearer_token_env_var`) on the `codex app-server` process, not an inline header, so
  the adapter extracts a `Bearer <token>` from the Claude-shaped config's
  `headers.Authorization` and sets it as an env var on the child it spawns. MCP tool
  calls surface as `item/started`/`item/completed` with `type: "mcpToolCall"`, mapped
  to `tool_started`/`tool_result` named `mcp__<server>__<tool>` — matching the Claude
  Agent SDK's own MCP tool naming convention. Live per-server connection status
  (`mcpServer/startupStatus/updated`) is not streamed into the journal; `system_init.
  mcpServers` reports a static `"configuring"` snapshot of what was configured.
- **`context_usage`/`compact`**: `thread/tokenUsage/updated` notifications carry
  `tokenUsage.last.totalTokens` (current-request context, distinct from `.total` used
  for cost estimation) and `tokenUsage.modelContextWindow` — the adapter tracks both
  and emits `context_usage` after every `turn_complete`. `compact` sends
  `thread/compact/start {threadId}` and awaits the `thread/compacted` notification;
  that notification carries **no token counts** (`{threadId, turnId}` only — it's
  marked deprecated in the app-server's own schema, in favor of a `context_compaction`
  item type this adapter doesn't otherwise consume), so the adapter derives
  `context_compacted`'s `preTokens`/`postTokens` from its own before/after snapshot of
  `tokenUsage.last.totalTokens` instead. Confirmed against the installed codex-cli
  0.151.0's protocol schema (`codex app-server generate-json-schema`), not yet against
  a live compaction run — see docs/plan/phase-12-linear-cache-serena-context.md Step
  C0 outcome for the exact fields.

## WebSocket contract (backend ↔ UI)

- Endpoint: `ws://host:8080/ws/sessions/{sessionId}?afterSeq=<n>`.
- Subprotocol carries auth: the client requests `["artus.v1", "bearer.<token>"]`;
  the server validates the bearer entry and echoes `artus.v1`. With no token
  configured (loopback-only mode) the bearer entry may be omitted.
- **Outbound**: every adapter event, wrapped in a journal envelope
  `{seq, ts, type, payload}` — `seq` is the per-session monotonic journal sequence.
  The backend also journals/broadcasts its own event types: `state_changed {state}`,
  `user_message {text}` (inbound messages echoed into the transcript),
  `queue_updated {queued:[{pos,text}]}`, `warning {message}`, `error`,
  `permission_response` (echo of the user's decision; a decision made in Slack also carries
  `source: "slack"` and `slackUser` — journal-only fields the backend strips before forwarding to the adapter),
  `slack_input {source: "slack", slackUser, kind: "prompt"|"stop"|"close"|"start", tool?}` (audit of a Slack-originated prompt,
  Stop, or — journaled on the new session — the `@bot review`/`dev` command that created it, with `tool` = the session
  type; no state effect, the prompt's text is the `user_message` that follows),
  `budget_updated {costBudgetUsd}`
  (the session's cost budget changed via `PATCH /api/sessions/{id}`; `costBudgetUsd` is
  `null` when the budget is cleared), `budget_exhausted {costBudgetUsd, costToDate}`
  (a turn was refused, or a queued message held rather than sent, because cumulative
  cost reached the budget — raise it to continue), `session_renamed {name, auto}`
  (`auto: true` for the automatic post-first-turn title, `false` for a manual rename),
  and `pr_status_changed {url, status, previousStatus, headSha}` — emitted by the background
  PR-check poller (`PrCheckPollingService`) whenever a session's tracked PR's aggregate
  check-suite status changes; `status`/`previousStatus` are one of
  `PENDING|SUCCESS|FAILURE|MERGED|CLOSED|ERROR`. `reflection_setting_changed
  {reflectionEnabled}`, `reflection_proposed {proposalId, episode, ops[]}` (default:
  a reflection computes but does not apply — the proposal awaits explicit
  approve/discard in the 🧠 dashboard dialog; `memory.reflection-approval-required`
  turns this off), and `reflection_complete {episode, created[], updated[],
  archived[], warnings[]}` — emitted by `ReflectionService` once a reflection is
  actually applied (auto-apply, or an approved proposal), listing exactly what was
  written to long-term memory. `reflection_discarded {episode, reason?}` marks a discarded
  proposal — nothing was written (`reason: "superseded by refresh"` when a newer proposal
  replaced it). See docs/plan/phase-5.3-memory-reflection.md. Phase 21
  (docs/plan/phase-21-memory-curation.md) adds `block_marked {targetSeq, mark}` — `mark` is
  `"important"`, `"excluded"` or `null` (clears); the current mark of a block is the last
  `block_marked` for its `targetSeq`, and only `user_message`/`assistant_message` seqs are
  markable — and `skill_extracted {name, assetId}` when an approved skill proposal lands in
  the library. `reflection_proposed` gains `kind` (`memory` default, `skill`, `remember`;
  non-memory kinds carry a self-describing `episode` line instead of `ops[]`), and
  `reflection_complete.episode` is `""` for an applied "remember this" proposal.
  `child_reported {childId, childName, service, summary}` — journaled on *both* the
  child and the parent when the child calls the `report_result` MCP tool (7.4); the
  same payload also lands as a `user_message` in the parent's queue/transcript, tagged
  `[child report — <name> / <service>]`. See docs/plan/phase-7-ux-and-orchestration.md.
  `code_intel_status {tool, status, nodes?, edges?, durationMs?, message?}` — emitted by
  `GraphifyService` on every transition of a `codeIntel: "graphify"` session's background
  graph build: `status` is `BUILDING|READY|FAILED`, `nodes`/`edges` are the last known
  counts (carried over on a no-change refresh), `durationMs` is the run that produced a
  READY/FAILED, `message` the failure reason. Journaled (so the widget chip replays after
  a reconnect/restart); a FAILED build never fails the session — the next completed turn
  triggers a retry. See docs/plan/phase-13-graphify.md Step 3. Also emitted by
  `CodegraphService` for a `codeIntel: "codegraph"` session — same shape, but **once**
  (BUILDING at the start of `codegraph init`, READY/FAILED at its end, during
  PROVISIONING, not a background loop): the tool's own file watcher keeps the index
  current after that with no further journaling. See docs/plan/phase-14-codegraph.md
  Step 2.
  `pr_review_submitted {event, commentCount, prUrl}` — journaled when a review session's
  `submit_pr_review` MCP tool call succeeds: `event` is `COMMENT|REQUEST_CHANGES|APPROVE`,
  `commentCount` the number of inline comments posted. See
  docs/plan/phase-15-review-sessions.md.
- On connect the journal is replayed from `afterSeq`, terminated by
  `{seq, type: "replay_complete", payload:{lastSeq}}`, then live events follow —
  no gaps, no duplicates (seq strictly increases).
- **Inbound** commands: `user_message` (queued FIFO if a turn is running),
  `permission_response`, `interrupt`, `set_permission_mode`, `set_model`. Invalid input
  returns a non-journaled `{type: "command_error", payload:{message}}` frame.
  `compact` is the one adapter command **not** sent over this socket — it goes through
  `POST /api/sessions/{id}/compact` instead, so a synchronous 409 can refuse it
  outright for a non-IDLE/PARKED session (`SessionService.compact`) rather than
  round-tripping a `command_error` frame.
- Slow consumers are disconnected (close code 1013); reconnect with the last seen
  `afterSeq` to catch up losslessly from the journal.
