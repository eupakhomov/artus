import { statSync } from 'node:fs';
import { resolve } from 'node:path';
import { runSession, type SidecarConfig } from './session.js';
import type { EffortLevel, PermissionMode } from './protocol.js';
import { log } from './stdio.js';

const PERMISSION_MODES: PermissionMode[] = ['default', 'acceptEdits', 'plan', 'bypassPermissions'];

function usage(): never {
  console.error(
    `usage: sidecar --cwd <dir> [--writable-root <dir>] [--resume <provider-session-id>] [--model <m>]
       [--fallback-model <m>] [--permission-mode ${PERMISSION_MODES.join('|')}]
       [--allowed-tools <csv>] [--disallowed-tools <csv>] [--mcp-config <path.json>]
       [--append-system-prompt <text>] [--context-dir <path>]... [--plugin-dir <path>]...
       [--thinking off|adaptive|<budgetTokens>] [--effort low|medium|high|xhigh|max]
       [--max-turns <n>]`,
  );
  process.exit(2);
}

function parseArgs(argv: string[]): SidecarConfig {
  const config: SidecarConfig = { cwd: '', writableRoot: '', contextDirs: [], pluginDirs: [] };
  for (let i = 0; i < argv.length; i++) {
    const flag = argv[i];
    const value = argv[i + 1];
    const need = (): string => {
      if (value === undefined) usage();
      i++;
      return value;
    };
    switch (flag) {
      case '--cwd': config.cwd = resolve(need()); break;
      case '--writable-root': config.writableRoot = resolve(need()); break;
      case '--resume': config.resume = need(); break;
      case '--model': config.model = need(); break;
      case '--fallback-model': config.fallbackModel = need(); break;
      case '--permission-mode': {
        const mode = need() as PermissionMode;
        if (!PERMISSION_MODES.includes(mode)) usage();
        config.permissionMode = mode;
        break;
      }
      case '--allowed-tools': config.allowedTools = need().split(',').map((s) => s.trim()).filter(Boolean); break;
      case '--disallowed-tools': config.disallowedTools = need().split(',').map((s) => s.trim()).filter(Boolean); break;
      case '--mcp-config': config.mcpConfigPath = resolve(need()); break;
      case '--append-system-prompt': config.appendSystemPrompt = need(); break;
      case '--plugin-dir': config.pluginDirs.push(resolve(need())); break;
      case '--context-dir': config.contextDirs.push(resolve(need())); break;
      case '--thinking': {
        const v = need();
        if (v === 'off' || v === 'adaptive') config.thinking = v;
        else if (/^\d+$/.test(v)) config.thinking = Number(v);
        else usage();
        break;
      }
      case '--effort': {
        const level = need() as EffortLevel;
        if (!['low', 'medium', 'high', 'xhigh', 'max'].includes(level)) usage();
        config.effort = level;
        break;
      }
      case '--max-turns': config.maxTurns = Number(need()); break;
      default: usage();
    }
  }
  if (!config.cwd) usage();
  if (!config.writableRoot) config.writableRoot = config.cwd;
  return config;
}

const config = parseArgs(process.argv.slice(2));
try {
  if (!statSync(config.cwd).isDirectory()) throw new Error('not a directory');
} catch {
  console.error(`sidecar: cwd is not a usable directory: ${config.cwd}`);
  process.exit(2);
}

log(`starting in ${config.cwd} (pid ${process.pid})`);
void runSession(config);
