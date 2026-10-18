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
