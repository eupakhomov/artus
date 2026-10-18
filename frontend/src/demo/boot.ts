import { parseDemoParams, parseThemeMessage } from './params';
import { setTheme } from '../prefs';

/**
 * `?theme=` seeds the stored theme before first paint (narrow embeds: see Dashboard's NARROW_DEMO). Runs before the first render.
 * An embedding page can also change it later without a reload (which would restart the replay): see parseThemeMessage.
 */
export function applyDemoParams(): void {
  document.documentElement.setAttribute('data-demo', '');
  const { theme } = parseDemoParams(location.search);
  if (theme) setTheme(theme);
  window.addEventListener('message', (e: MessageEvent) => {
    if (window.parent === window || e.source !== window.parent) return; // only the page that frames us
    const next = parseThemeMessage(e.data);
    if (next) setTheme(next);
  });
}
