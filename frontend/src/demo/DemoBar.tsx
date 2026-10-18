import { DemoPause, DemoPlay, DemoRestart } from '../icons';
import { restart, setPaused, setSpeed, useDemoControls } from './registry';

const REPO_URL = 'https://github.com/eupakhomov/artus';

/** Demo-only strip above the dashboard (mounted from main.tsx in `--mode demo` only). */
export default function DemoBar() {
  const { speed, paused } = useDemoControls();
  return (
    <div className="demo-bar" role="region" aria-label="Demo controls">
      <span className="chip demo-badge" title="Nothing runs on a server — the dashboard replays recorded sessions in your browser">
        Recording
      </span>
      <span className="demo-note">
        This is the real dashboard replaying recorded sessions. Approve or deny a prompt to continue.
      </span>
      <span className="demo-spacer" />
      <button className="icon-btn" title={paused ? 'Play' : 'Pause'} onClick={() => setPaused(!paused)}>
        {paused ? <DemoPlay /> : <DemoPause />}
      </button>
      <div className="grouping-toggle">
        <button className={speed === 1 ? 'active' : ''} title="Normal speed" onClick={() => setSpeed(1)}>1×</button>
        <button className={speed === 4 ? 'active' : ''} title="4× speed" onClick={() => setSpeed(4)}>4×</button>
      </div>
      <button className="icon-btn" title="Restart the demo" onClick={restart}><DemoRestart /></button>
      <a className="demo-link" href={REPO_URL} target="_blank" rel="noreferrer" title="Artus on GitHub">Get Artus</a>
    </div>
  );
}
