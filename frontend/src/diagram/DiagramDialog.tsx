import { createPortal } from 'react-dom';
import { useBackdropDismiss } from '../hooks/useBackdropDismiss';
import { Close } from '../icons';
import DiagramPanel from './DiagramPanel';

export default function DiagramDialog({ sessionId, onClose }: { sessionId: string; onClose: () => void }) {
  const backdropDismiss = useBackdropDismiss(onClose);
  const popOut = () => window.open(`${import.meta.env.BASE_URL}?diagram=${encodeURIComponent(sessionId)}`, `artus-diagram-${sessionId}`, 'popup,width=1100,height=800');
  // portalled: the widget sits in a transformed grid item, which would trap and clip a position:fixed backdrop
  return createPortal(
    <div className="modal-backdrop" {...backdropDismiss}>
      <div className="modal wide diagram-modal" onClick={(e) => e.stopPropagation()}>
        <div style={{ display: 'flex', alignItems: 'center' }}>
          <h2 style={{ flex: 1 }}>Visualize</h2>
          <button className="icon-btn" onClick={onClose} title="Close"><Close /></button>
        </div>
        <DiagramPanel sessionId={sessionId} onPopOut={popOut} />
      </div>
    </div>,
    document.body,
  );
}
