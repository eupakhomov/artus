// phase 26: the create dialog / template editor's built-in tool toggles. The three built-ins are
// blocked by default (backend DEFAULT_DISALLOWED_TOOLS); a session that sends its own
// `disallowedTools` replaces that default wholesale, so ticking one box must send the *rest* of the
// defaults, never an empty list.

export const BUILTIN_TOOLS = [
  { id: 'WebSearch', label: 'Web search' },
  { id: 'WebFetch', label: 'Web fetch' },
  { id: 'NotebookEdit', label: 'Notebook edit' },
] as const;

/**
 * Names to send as `disallowedTools`, or `undefined` to leave the key out (= the backend default).
 * `always` forces the key even when nothing is ticked — needed when a template defines its own
 * `disallowedTools`, since an omitted override would let the template's list win the config merge.
 */
export function disallowedFor(enabled: ReadonlySet<string>, always = false): string[] | undefined {
  if (enabled.size === 0 && !always) return undefined;
  return BUILTIN_TOOLS.map((t) => t.id).filter((id) => !enabled.has(id));
}

/** True for a list holding nothing but built-in tool names (so the toggles can represent it exactly). */
export function builtinOnly(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((v) => BUILTIN_TOOLS.some((t) => t.id === v));
}

/** Which built-ins a stored `disallowedTools` list leaves enabled; no list = the default = none. */
export function enabledFrom(disallowed: unknown): Set<string> {
  // a list with other names (a template's own raw choice) is left to the backend merge, not re-expressed here
  if (!builtinOnly(disallowed)) return new Set();
  const blocked = new Set(disallowed.filter((x): x is string => typeof x === 'string'));
  return new Set(BUILTIN_TOOLS.map((t) => t.id).filter((id) => !blocked.has(id)));
}
