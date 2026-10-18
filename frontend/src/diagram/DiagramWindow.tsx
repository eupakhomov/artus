import DiagramPanel from './DiagramPanel';

/** The pop-out page (`/?diagram=<sessionId>`): same panel, own window, loads the stored diagram by id. */
export default function DiagramWindow({ sessionId }: { sessionId: string }) {
  return (
    <div className="diagram-window">
      <DiagramPanel sessionId={sessionId} />
    </div>
  );
}
