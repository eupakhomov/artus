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

applyPrefs();
// phase 22: `/?diagram=<sessionId>` is the Visualize pop-out — same bundle, same origin (so same stored token)
const popOutDiagramId = new URLSearchParams(window.location.search).get('diagram');
// the app's icon language, set once: 16px, 1.5px stroke, currentColor (see src/icons.ts).
// lucide's own default stroke of 2 reads heavy at 16px next to this UI's light type.
createRoot(document.getElementById('root')!).render(
  <LucideProvider size={16} strokeWidth={1.5}>
    {popOutDiagramId ? <DiagramWindow sessionId={popOutDiagramId} /> : <App />}
  </LucideProvider>,
);
