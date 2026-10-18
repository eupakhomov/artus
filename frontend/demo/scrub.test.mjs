import { describe, expect, it } from 'vitest';
import { findLeaks, scrubFixtures, stripEnvironment } from './scrub.mjs';

const sample = () => [{
  summary: { id: '11111111-2222-4333-8444-555555555555', name: 'x', updatedAt: '2026-05-01T10:00:00Z', costToDate: 0.123456789 },
  events: [
    { seq: 1, ts: '2026-05-01T10:00:00Z', type: 'tool_result', payload: { output: 'cd /home/alice/work/app && cat /mnt/d/projects/app/a.txt; mail bob@corp.io; token ghp_abcdefghijklmnopqrstuvwxyz123456' } },
    { seq: 2, ts: '2026-05-01T10:05:00Z', type: 'x', payload: { sid: '11111111-2222-4333-8444-555555555555', host: 'alicebook' } },
  ],
}];

describe('scrub', () => {
  const opts = { homes: ['/home/alice'], paths: [['/mnt/d/projects/app', '/home/demo/projects/artus-demo-repo']], names: [['alicebook', 'demo-host']] };

  it('removes paths, emails, tokens and host names; remaps uuids; rebases time; rounds cost', () => {
    const [f] = scrubFixtures(sample(), opts);
    const out = f.events[0].payload.output;
    expect(out).toContain('/home/demo/work/app');
    expect(out).toContain('/home/demo/projects/artus-demo-repo/a.txt');
    expect(out).toContain('demo@example.com');
    expect(out).toContain('[redacted]');
    expect(f.events[1].payload.host).toBe('demo-host');
    expect(f.summary.id).toBe('00000000-0000-4000-8000-000000000001');
    expect(f.events[1].payload.sid).toBe(f.summary.id);
    expect(f.events[0].ts).toBe('2026-10-18T09:00:00.000Z');
    expect(f.events[1].ts).toBe('2026-10-18T09:05:00.000Z');
    expect(f.summary.costToDate).toBe(0.1235);
  });

  it('leaves nothing for findLeaks after a scrub, and flags the raw input', () => {
    expect(findLeaks(sample()).length).toBeGreaterThan(3);
    expect(findLeaks(scrubFixtures(sample(), opts))).toEqual([]);
  });

  it('honours caller-supplied deny terms and denylist regexes', () => {
    expect(findLeaks({ a: 'written by Alice' }, ['alice'])).toHaveLength(1);
    const re = ['(?<!github\\.com/)alice'];
    expect(findLeaks({ a: 'https://github.com/alice/repo' }, [], re)).toEqual([]);
    expect(findLeaks({ a: 'Author: alice <x>' }, [], re)).toHaveLength(1);
  });

  it('keep protects a public URL from the name rule', () => {
    const [f] = scrubFixtures([{ a: 'https://github.com/alice/repo/pull/1 by alice' }], { names: [['alice', 'demo']], keep: ['github.com/alice/repo'] });
    expect(f.a).toBe('https://github.com/alice/repo/pull/1 by demo');
  });

  it('stripEnvironment drops the machine tool/MCP inventory but keeps memory', () => {
    const f = stripEnvironment({ events: [
      { seq: 1, type: 'system_init', payload: { model: 'm', tools: ['mcp__linear__x', 'Bash'], mcpServers: [{ name: 'linear' }, { name: 'memory' }] } },
      { seq: 2, type: 'context_usage', payload: { tokens: 1, breakdown: { mcpServers: [{ name: 'claude_ai_Docs', tokens: 9 }, { name: 'memory', tokens: 2 }], categories: [] } } },
    ] });
    expect(f.events[0].payload.tools).toEqual([]);
    expect(f.events[0].payload.mcpServers).toEqual([{ name: 'memory' }]);
    expect(f.events[1].payload.breakdown.mcpServers).toEqual([{ name: 'memory', tokens: 2 }]);
  });

  it('stripEnvironment drops other MCP servers from ToolSearch results', () => {
    const out = '{"type":"tool_reference","tool_name":"mcp__memory__list_services"}\n{"type":"tool_reference","tool_name":"mcp__linear__list_cycles"}';
    const f = stripEnvironment({ events: [{ seq: 1, type: 'tool_result', payload: { output: out } }] });
    expect(f.events[0].payload.output).toBe('{"type":"tool_reference","tool_name":"mcp__memory__list_services"}');
  });

  it('stripEnvironment keeps only the session fields the dashboard reads', () => {
    const f = stripEnvironment({ detail: { session: { id: 'x', name: 'n', mcpConfig: { linear: {} }, envVars: { A: '1' }, slackChannel: 'C1' } }, events: [] });
    expect(f.detail.session).toEqual({ id: 'x', name: 'n' });
  });

  it('redacts Linear API keys', () => {
    const [f] = scrubFixtures([{ a: 'Authorization: lin_api_abcdefghijklmnopqrstuvwxyz0123' }]);
    expect(f.a).toBe('Authorization: [redacted]');
  });

  it('leaves git@ SSH remotes alone and redacts given secrets', () => {
    const [f] = scrubFixtures([{ a: 'git@github.com:owner/repo.git', b: 'ARTUS_TOKEN=Abc123Def456Ghi789Jk' }], { secrets: ['Abc123Def456Ghi789Jk'] });
    expect(f.a).toBe('git@github.com:owner/repo.git');
    expect(f.b).toBe('ARTUS_TOKEN=[redacted]');
  });
});
