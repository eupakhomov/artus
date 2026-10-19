import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { translateMcpConfig } from '../src/mcp.js';

let dir: string;

beforeEach(async () => {
  dir = await mkdtemp(join(tmpdir(), 'mcp-test-'));
});

afterEach(async () => {
  await rm(dir, { recursive: true, force: true });
});

async function configPath(content: unknown): Promise<string> {
  const p = join(dir, 'mcp.json');
  await writeFile(p, JSON.stringify(content));
  return p;
}

describe('translateMcpConfig', () => {
  it('passes stdio servers through directly', async () => {
    const path = await configPath({
      memory: { command: 'node', args: ['dist/index.js'], env: { FOO: 'bar' } },
    });
    const result = await translateMcpConfig(path);
    expect(result.mcpServers).toEqual({
      memory: { command: 'node', args: ['dist/index.js'], env: { FOO: 'bar' } },
    });
    expect(result.extraEnv).toEqual({});
  });

  it('extracts a Bearer token from an HTTP server into a named env var, not the config', async () => {
    const path = await configPath({
      linear: { url: 'https://mcp.linear.app/mcp', headers: { Authorization: 'Bearer secret-token-123' } },
    });
    const result = await translateMcpConfig(path);
    expect(result.mcpServers['linear']).toEqual({
      url: 'https://mcp.linear.app/mcp',
      bearer_token_env_var: 'CODEX_MCP_TOKEN_LINEAR',
    });
    expect(result.extraEnv).toEqual({ CODEX_MCP_TOKEN_LINEAR: 'secret-token-123' });
    // the raw token must never appear directly in the translated config
    expect(JSON.stringify(result.mcpServers)).not.toContain('secret-token-123');
  });

  it('slugifies non-alphanumeric server names for the env var', async () => {
    const path = await configPath({
      'my-server.v2': { url: 'https://example.com', headers: { Authorization: 'Bearer tok' } },
    });
    const result = await translateMcpConfig(path);
    expect(result.extraEnv).toEqual({ CODEX_MCP_TOKEN_MY_SERVER_V2: 'tok' });
  });

  it('emits an HTTP server with no Authorization header as {url} alone', async () => {
    const path = await configPath({ anon: { url: 'https://example.com' } });
    const result = await translateMcpConfig(path);
    expect(result.mcpServers).toEqual({ anon: { url: 'https://example.com' } });
    expect(result.extraEnv).toEqual({});
  });

  it('ignores a non-Bearer Authorization header', async () => {
    const path = await configPath({
      basic: { url: 'https://example.com', headers: { Authorization: 'Basic abc123' } },
    });
    const result = await translateMcpConfig(path);
    expect(result.mcpServers['basic']).toEqual({ url: 'https://example.com' });
    expect(result.extraEnv).toEqual({});
  });

  it('accepts the CLI-shaped {mcpServers: {...}} wrapper as well as a bare map', async () => {
    const path = await configPath({ mcpServers: { memory: { command: 'node' } } });
    const result = await translateMcpConfig(path);
    expect(result.mcpServers).toEqual({ memory: { command: 'node' } });
  });

  it('silently skips an entry matching neither the stdio nor http shape', async () => {
    const path = await configPath({ weird: { foo: 'bar' } });
    const result = await translateMcpConfig(path);
    expect(result.mcpServers).toEqual({});
  });
});

describe('enabledTools allowlist', () => {
  it('maps a url server\'s enabledTools to Codex enabled_tools', async () => {
    const dir = await mkdtemp(join(tmpdir(), 'mcp-'));
    const path = join(dir, 'mcp.json');
    await writeFile(path, JSON.stringify({ linear: { type: 'http', url: 'https://x', enabledTools: ['get_issue'] }, other: { type: 'http', url: 'https://y' } }));
    const { mcpServers } = await translateMcpConfig(path);
    expect(mcpServers['linear']).toEqual({ url: 'https://x', enabled_tools: ['get_issue'] });
    expect(mcpServers['other']).toEqual({ url: 'https://y' });
  });

  it('maps a stdio server\'s enabledTools to enabled_tools too', async () => {
    const dir = await mkdtemp(join(tmpdir(), 'mcp-'));
    const path = join(dir, 'mcp.json');
    await writeFile(path, JSON.stringify({ bq: { command: 'npx', args: ['bq-mcp'], env: { A: '1' }, enabledTools: ['query'] } }));
    const { mcpServers } = await translateMcpConfig(path);
    expect(mcpServers['bq']).toEqual({ command: 'npx', args: ['bq-mcp'], env: { A: '1' }, enabled_tools: ['query'] });
  });
});
