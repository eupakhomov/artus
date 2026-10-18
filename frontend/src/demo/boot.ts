import { parseDemoParams } from './params';
import { setTheme } from '../prefs';

/** `?theme=` seeds the stored theme before first paint (narrow embeds: see Dashboard's NARROW_DEMO). Runs before the first render. */
export function applyDemoParams(): void {
  document.documentElement.setAttribute('data-demo', '');
  const { theme } = parseDemoParams(location.search);
  if (theme) setTheme(theme);
}
