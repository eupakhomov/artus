/**
 * Mermaid rendering for Visualize (phase 22). The renderer is the validator: `renderMermaidSVG`
 * throws on unparseable source, and `renderOrRepair` turns that into exactly one repair request
 * before falling back to the raw source. `beautiful-mermaid` is heavy (elkjs), so it is only ever
 * pulled in by a dynamic `import()` — its own chunk, never the main bundle.
 */

/** Our theme tokens, aliased under names that don't collide with the renderer's own `--bg`/`--fg`
 * custom properties (`--bg: var(--bg)` would be a self-referential cycle). Set on the view's wrapper. */
export const DIAGRAM_THEME_VARS: Record<string, string> = {
  '--dg-bg': 'var(--panel)',
  '--dg-fg': 'var(--text)',
  '--dg-accent': 'var(--accent)',
  '--dg-line': 'var(--muted)',
  '--dg-muted': 'var(--muted)',
  '--dg-surface': 'var(--panel2)',
  '--dg-border': 'var(--border)',
};

export type RenderFn = (source: string) => string;

let loaded: Promise<RenderFn> | null = null;

/** Lazily loads the renderer; colours are our CSS variables, so a theme toggle needs no re-render. */
export function loadRenderer(): Promise<RenderFn> {
  loaded ??= import('beautiful-mermaid').then(({ renderMermaidSVG }) => (source: string) =>
    sanitizeSvg(renderMermaidSVG(source, {
      bg: 'var(--dg-bg)', fg: 'var(--dg-fg)', accent: 'var(--dg-accent)', line: 'var(--dg-line)',
      muted: 'var(--dg-muted)', surface: 'var(--dg-surface)', border: 'var(--dg-border)', transparent: true,
    })));
  return loaded;
}

/**
 * Defence in depth: the source is model output derived from untrusted code. The library
 * entity-escapes labels, but the result is injected as markup, so parse it as XML (never regex
 * markup — quoting/entity tricks slip past patterns) and strip anything executable, plus the
 * Google Fonts `@import` so rendering never phones out from a LAN tool. SVG that does not parse
 * as XML is refused outright rather than injected.
 */
export function sanitizeSvg(svg: string): string {
  const doc = new DOMParser().parseFromString(svg, 'image/svg+xml');
  if (doc.getElementsByTagName('parsererror').length > 0) {
    throw new Error('renderer produced unparseable SVG');
  }
  for (const el of [...doc.querySelectorAll('script, foreignObject')]) el.remove();
  const strip = (el: Element) => {
    for (const attr of [...el.attributes]) {
      const name = attr.name.toLowerCase();
      // the DOM gives the entity-decoded value; collapse whitespace/control chars browsers ignore in schemes
      const value = attr.value.replace(/[\s\u0000-\u001f]+/g, '').toLowerCase();
      if (name.startsWith('on') || (name.endsWith('href') && value.startsWith('javascript:'))) {
        el.removeAttribute(attr.name);
      }
    }
    for (const child of [...el.children]) strip(child);
  };
  strip(doc.documentElement);
  for (const style of [...doc.querySelectorAll('style')]) {
    style.textContent = (style.textContent ?? '').replace(/@import[^;]*(;|$)/gi, '');
  }
  return new XMLSerializer().serializeToString(doc.documentElement);
}

/** Resolves the `--dg-*` variables against a live element, so an exported SVG stands alone. */
export function inlineThemeVars(svg: string, el: Element): string {
  const cs = getComputedStyle(el);
  let out = svg;
  for (const name of Object.keys(DIAGRAM_THEME_VARS)) {
    const value = cs.getPropertyValue(name).trim();
    if (value) out = out.split(`var(${name})`).join(value);
  }
  return out;
}

export type RenderOutcome =
  | { kind: 'svg'; svg: string; source: string; repaired: boolean }
  | { kind: 'raw'; source: string; error: string };

const errText = (e: unknown) => (e instanceof Error ? e.message : String(e));

/**
 * Renders `source`; on a parse failure asks `repair` for a fixed source exactly once and tries
 * again, and if that also fails (or the repair request does) returns the raw source plus the
 * error — never nothing. `onRepaired` receives the repaired source so the caller can adopt it.
 */
export async function renderOrRepair(
  source: string,
  render: RenderFn,
  repair: (source: string, error: string) => Promise<string>,
): Promise<RenderOutcome> {
  let firstError: string;
  try {
    return { kind: 'svg', svg: render(source), source, repaired: false };
  } catch (e) {
    firstError = errText(e);
  }
  let fixed: string;
  try {
    fixed = await repair(source, firstError);
  } catch (e) {
    return { kind: 'raw', source, error: `${firstError} (repair failed: ${errText(e)})` };
  }
  try {
    return { kind: 'svg', svg: render(fixed), source: fixed, repaired: true };
  } catch (e) {
    return { kind: 'raw', source: fixed, error: `${errText(e)} (after one repair attempt)` };
  }
}
