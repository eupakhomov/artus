import { useBackdropDismiss } from '../hooks/useBackdropDismiss';

const ROWS: [string, string][] = [
  ['?', 'this cheatsheet'],
  ['n', 'new session'],
  ['q', 'quick session (service + branch + prompt)'],
  ['j / k  (also ] / [)', 'focus next / previous session'],
  ['1 – 9', 'focus session N (grid order; sidebar order in focus layout)'],
  ['Enter / i', 'focus the composer'],
  ['y / d', 'approve / deny the oldest pending permission'],
  ['a', 'jump to the session waiting longest for you'],
  ['g', 'toggle git panel'],
  ['c', 'toggle changes panel (diff vs base, ask about lines)'],
  ['f', 'maximize / restore widget (grid layout)'],
  ['x', 'minimize widget'],
  ['h', 'hide session from the dashboard (sidebar → Hidden)'],
  ['e', 'Exposé (all sessions)'],
  ['b', 'sidebar: expanded ↔ rail'],
  ['v', 'grid ↔ focus layout'],
  ['m', 'memory'],
  ['l', 'skill library'],
  ['u', 'usage'],
  ['t', 'templates'],
  [',', 'settings'],
  ['Esc', 'close dialog → blur composer → exit maximize/Exposé'],
];

export default function HotkeyCheatsheet({ onClose }: { onClose: () => void }) {
  const backdropDismiss = useBackdropDismiss(onClose);
  return (
    <div className="modal-backdrop" {...backdropDismiss}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h2>Keyboard shortcuts</h2>
        <table className="cheatsheet-table">
          <tbody>
            {ROWS.map(([key, desc]) => (
              <tr key={key}>
                <td><kbd>{key}</kbd></td>
                <td>{desc}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p style={{ color: 'var(--muted)' }}>
          Inert while typing in a field or while a dialog is open (Esc still works).
        </p>
        <div className="actions">
          <button className="primary" onClick={onClose}>Close</button>
        </div>
      </div>
    </div>
  );
}
