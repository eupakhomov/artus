import { describe, expect, it } from 'vitest';
import { describeActivity, describeToolCall, formatDuration } from './activity';

describe('activity labels', () => {
  it('picks the field that says what a call does', () => {
    expect(describeToolCall('Bash', { command: 'npm test', description: 'run tests' })).toBe('Bash: npm test');
    expect(describeToolCall('Read', { file_path: '/a/b.ts' })).toBe('Read: /a/b.ts');
    expect(describeToolCall('X', {})).toBe('X');
    expect(describeToolCall('Read', { file_path: '/wt/abc/src/a.ts' }, 60, '/wt/abc')).toBe('Read: src/a.ts');
    expect(describeToolCall('Bash', { command: 'cd /wt/abc/ && ls /wt/abc/src' }, 60, '/wt/abc/')).toBe('Bash: cd . && ls src');
    expect(describeToolCall('Bash', { command: 'a'.repeat(100) }, 10)).toBe(`Bash: ${'a'.repeat(10)}…`);
  });

  it('prefixes a subagent call with its subagent', () => {
    expect(describeActivity({ kind: 'tool', name: 'Grep', input: { pattern: 'x' }, subagent: 'Explore' })).toBe('Explore › Grep: x');
    expect(describeActivity(null)).toBe('starting…');
  });

  it('formats durations compactly', () => {
    expect(formatDuration(42_000)).toBe('42s');
    expect(formatDuration(185_000)).toBe('3m 05s');
    expect(formatDuration(3_720_000)).toBe('1h 02m');
  });
});
