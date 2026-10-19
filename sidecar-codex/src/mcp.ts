/**
 * Translates the same Claude-shaped MCP config file the backend already writes
 * (bare `{name: {...}}` or `{mcpServers: {...}}`, see sidecar/src/session.ts's
 * loadMcpServers) into Codex's `thread/start.config.mcp_servers` shape.
 *
 * Confirmed live (docs/plan/phase-5.13-codex-provider.md's MCP follow-up): stdio
 * servers are `{command, args, env}` in both shapes, direct passthrough. HTTP servers
 * differ — Codex reads a bearer token from a *named env var*
 * (`bearer_token_env_var`), not an inline header, so a Bearer token found in the
 * Claude-shaped config's `headers.Authorization` is extracted and returned separately
 * as an env var to set on the `codex app-server` child's own process environment
 * (see rpc.ts's `extraEnv` constructor param) — never written to disk, never passed
 * as a CLI arg.
 */
import { readFile } from 'node:fs/promises';

export interface McpTranslationResult {
  /** Codex-shaped, goes into thread/start`.config.mcp_servers`. */
  mcpServers: Record<string, unknown>;
  /** Env vars to set on the codex app-server child's spawn environment. */
  extraEnv: Record<string, string>;
}

async function loadRawMcpConfig(path: string): Promise<Record<string, unknown>> {
  const parsed = JSON.parse(await readFile(path, 'utf8')) as Record<string, unknown>;
  return (parsed['mcpServers'] as Record<string, unknown>) ?? parsed;
}

function envVarNameFor(serverName: string): string {
  const slug = serverName.toUpperCase().replace(/[^A-Z0-9]/g, '_');
  return `CODEX_MCP_TOKEN_${slug}`;
}

export async function translateMcpConfig(path: string): Promise<McpTranslationResult> {
  const raw = await loadRawMcpConfig(path);
  const mcpServers: Record<string, unknown> = {};
  const extraEnv: Record<string, string> = {};

  for (const [name, value] of Object.entries(raw)) {
    const entry = value as Record<string, unknown>;
    if (typeof entry['command'] === 'string') {
      const out: Record<string, unknown> = { command: entry['command'] };
      if (Array.isArray(entry['args'])) out['args'] = entry['args'];
      if (entry['env'] && typeof entry['env'] === 'object') out['env'] = entry['env'];
      // same allowlist mapping as the url branch (a stdio catalog server is restricted the same way)
      if (Array.isArray(entry['enabledTools'])) out['enabled_tools'] = entry['enabledTools'];
      mcpServers[name] = out;
      continue;
    }
    if (typeof entry['url'] === 'string') {
      const out: Record<string, unknown> = { url: entry['url'] };
      // our own allowlist key -> Codex's per-server enabled_tools (an allowlist; verified on 0.163)
      if (Array.isArray(entry['enabledTools'])) out['enabled_tools'] = entry['enabledTools'];
      const headers = entry['headers'] as Record<string, unknown> | undefined;
      const auth = typeof headers?.['Authorization'] === 'string' ? (headers['Authorization'] as string) : undefined;
      const match = auth?.match(/^Bearer\s+(.+)$/);
      if (match) {
        const varName = envVarNameFor(name);
        extraEnv[varName] = match[1]!;
        out['bearer_token_env_var'] = varName;
      }
      // No Authorization header: emit {url} alone — relies on a prior `codex mcp
      // login <name>` done ambiently on the backend host, which this adapter has no
      // way to set up per-session. Untested — see the plan doc's "Known gap" note.
      mcpServers[name] = out;
      continue;
    }
    // Neither shape recognized — skip silently, matching the Claude sidecar's own
    // leniency toward config entries it doesn't understand.
  }

  return { mcpServers, extraEnv };
}
