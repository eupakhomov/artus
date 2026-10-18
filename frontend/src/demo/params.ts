export interface DemoParams {
  theme: 'dark' | 'light' | null;
  speed: 1 | 4;
  autoplay: boolean;
}

/** Parses the embed contract's query params (`?theme=&speed=&autoplay=`); unknown values fall back to defaults. */
export function parseDemoParams(search: string): DemoParams {
  const q = new URLSearchParams(search);
  const theme = q.get('theme');
  const speed = q.get('speed');
  return {
    theme: theme === 'dark' || theme === 'light' ? theme : null,
    speed: speed === '4' ? 4 : 1,
    autoplay: q.get('autoplay') !== '0',
  };
}

export type DemoTheme = 'dark' | 'light';

/**
 * The embedding page's theme message: `{type: 'artus-demo:theme', theme: 'dark'|'light'}`. Returns the theme, or null for
 * anything else (other message types, bad values, non-objects) so a foreign `postMessage` can never do more than pick a theme.
 */
export function parseThemeMessage(data: unknown): DemoTheme | null {
  if (typeof data !== 'object' || data === null) return null;
  const m = data as { type?: unknown; theme?: unknown };
  return m.type === 'artus-demo:theme' && (m.theme === 'dark' || m.theme === 'light') ? m.theme : null;
}
