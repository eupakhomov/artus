// Short, human-readable labels for what a running turn is doing (phase 25): the transcript's
// Task-row summary line and the widget's sticky activity line share them.

import type { Activity } from './store/store';

function clip(s: string, max: number): string {
  const oneLine = s.replace(/\s+/g, ' ').trim();
  return oneLine.length > max ? oneLine.slice(0, max) + '…' : oneLine;
}

/** `Bash: npm test`, `Read: src/App.tsx` — the one input field that says what the call does. */
export function describeToolCall(name: string, input: unknown, max = 60): string {
  const i = (typeof input === 'object' && input !== null ? input : {}) as Record<string, unknown>;
  const pick = ['command', 'file_path', 'path', 'pattern', 'url', 'query', 'description', 'prompt']
    .map((k) => i[k])
    .find((v): v is string => typeof v === 'string' && v.length > 0);
  if (pick !== undefined) return `${name}: ${clip(pick, max)}`;
  const raw = JSON.stringify(input) ?? '';
  return raw && raw !== '{}' ? `${name}: ${clip(raw, max)}` : name;
}

export function describeActivity(a: Activity | null): string {
  if (!a) return 'starting…';
  switch (a.kind) {
    case 'tool':
      return (a.subagent ? `${a.subagent} › ` : '') + describeToolCall(a.name, a.input);
    case 'thinking':
      return 'thinking…';
    case 'writing':
      return 'writing…';
  }
}

/** `42s`, `3m 05s`, `1h 02m` */
export function formatDuration(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  if (h > 0) return `${h}h ${String(m).padStart(2, '0')}m`;
  if (m > 0) return `${m}m ${String(s).padStart(2, '0')}s`;
  return `${s}s`;
}
