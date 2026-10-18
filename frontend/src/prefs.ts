// Appearance preferences: theme + font size. Frontend-only, localStorage-backed —
// applied as data-* attributes on <html> so CSS (styles.css) does the actual work.

const THEME_KEY = 'artus.theme';
const FONT_SIZE_KEY = 'artus.fontSize';

export type Theme = 'light' | 'dark' | 'system';
export type FontSize = 'small' | 'medium' | 'large';

export function getTheme(): Theme {
  const v = localStorage.getItem(THEME_KEY);
  return v === 'light' || v === 'dark' ? v : 'system';
}

export function setTheme(theme: Theme): void {
  localStorage.setItem(THEME_KEY, theme);
  applyPrefs();
}

export function getFontSize(): FontSize {
  const v = localStorage.getItem(FONT_SIZE_KEY);
  return v === 'small' || v === 'large' ? v : 'medium';
}

export function setFontSize(size: FontSize): void {
  localStorage.setItem(FONT_SIZE_KEY, size);
  applyPrefs();
}

const HIDDEN_HISTORY_KEY = 'artus.hiddenHistory';

/** Closed sessions the user dismissed from the sidebar History (this browser only; nothing server-side). */
export function getHiddenHistory(): string[] {
  try {
    const v = JSON.parse(localStorage.getItem(HIDDEN_HISTORY_KEY) ?? '[]');
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [];
  } catch {
    return [];
  }
}

export function hideHistoryEntry(id: string): string[] {
  // capped so the list can't grow without bound; History only ever shows the newest few anyway
  const next = [id, ...getHiddenHistory().filter((x) => x !== id)].slice(0, 200);
  try {
    localStorage.setItem(HIDDEN_HISTORY_KEY, JSON.stringify(next));
  } catch { /* storage full/blocked: hiding just lasts until reload */ }
  return next;
}

/** Stamps data-theme / data-font-size onto <html>; call once at startup and after any change. */
export function applyPrefs(): void {
  const root = document.documentElement;
  const theme = getTheme();
  if (theme === 'system') root.removeAttribute('data-theme');
  else root.setAttribute('data-theme', theme);
  root.setAttribute('data-font-size', getFontSize());
}
