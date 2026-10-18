// must stay the first import: renames legacy `claude-ui.*` localStorage keys before
// api/rest.ts reads the auth token at module load (see storageMigration.ts)
import './storageMigration';
import { createRoot } from 'react-dom/client';
import { LucideProvider } from 'lucide-react';
import App from './App';
import './styles.css';
import 'react-grid-layout/css/styles.css';
import 'react-resizable/css/styles.css';
import { applyPrefs } from './prefs';
import DiagramWindow from './diagram/DiagramWindow';
import { lazy, Suspense } from 'react';

applyPrefs();
// phase 24: `--mode demo` only — Vite replaces MODE at build time, so the whole demo branch is dead code elsewhere
const demo = import.meta.env.MODE === 'demo';
const DemoBar = demo ? lazy(() => import('./demo/DemoBar')) : null;
// phase 22: `/?diagram=<sessionId>` is the Visualize pop-out — same bundle, same origin (so same stored token)
const popOutDiagramId = new URLSearchParams(window.location.search).get('diagram');
// the app's icon language, set once: 16px, 1.5px stroke, currentColor (see src/icons.ts).
// lucide's own default stroke of 2 reads heavy at 16px next to this UI's light type.
const render = () => createRoot(document.getElementById('root')!).render(
  <LucideProvider size={16} strokeWidth={1.5}>
    {popOutDiagramId ? <DiagramWindow sessionId={popOutDiagramId} /> : <App />}
    {DemoBar && !popOutDiagramId && <Suspense fallback={null}><DemoBar /></Suspense>}
  </LucideProvider>,
);

if (demo) void import('./demo/boot').then((m) => { m.applyDemoParams(); render(); });
else render();
