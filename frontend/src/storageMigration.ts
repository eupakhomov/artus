// One-shot localStorage key migration from the app's previous names. Every persisted
// browser preference was keyed `claude-ui.*` until the 2026-09 rename to agentic-ui, and
// `agentic-ui.*` until the 2026-10 rename to Artus; without this a returning browser
// would be logged out and lose theme/layout/etc.
//
// Runs as a side effect at import time and must be the FIRST import in main.tsx:
// api/rest.ts reads the token at module load, so the copy has to happen before that
// module evaluates. Idempotent — once the old keys are gone it does nothing.

const OLD_PREFIXES = ['claude-ui.', 'agentic-ui.'];
const NEW_PREFIX = 'artus.';

function migrate(): void {
  try {
    const oldKeys: { key: string; prefix: string }[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      const prefix = k ? OLD_PREFIXES.find((p) => k.startsWith(p)) : undefined;
      if (k && prefix) oldKeys.push({ key: k, prefix });
    }
    for (const { key: oldKey, prefix } of oldKeys) {
      const newKey = NEW_PREFIX + oldKey.slice(prefix.length);
      // never clobber a value already written under the new name
      if (localStorage.getItem(newKey) === null) {
        const v = localStorage.getItem(oldKey);
        if (v !== null) localStorage.setItem(newKey, v);
      }
      localStorage.removeItem(oldKey);
    }
  } catch {
    // storage unavailable (private mode, blocked site data) — nothing to migrate
  }
}

// phase 24: the demo build runs on the website's origin and holds no token — never touch another app's keys there
if (import.meta.env.MODE !== 'demo') migrate();
