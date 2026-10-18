import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { FitView, ZoomIn, ZoomOut } from '../icons';
import { DIAGRAM_THEME_VARS, type RenderOutcome } from './render';

interface View { x: number; y: number; k: number }
const PAD = 16;

/** svg width/height as the renderer wrote them (it sets both attributes and a viewBox) */
function naturalSize(svg: string): { w: number; h: number } {
  const w = /<svg[^>]*\swidth="([\d.]+)"/.exec(svg)?.[1];
  const h = /<svg[^>]*\sheight="([\d.]+)"/.exec(svg)?.[1];
  return { w: Number(w) || 600, h: Number(h) || 400 };
}

/** Pan (drag) and zoom (wheel / buttons) over a rendered diagram, or the raw-source fallback. */
export default function DiagramView({ outcome, wrapRef }: { outcome: RenderOutcome; wrapRef?: React.RefObject<HTMLDivElement | null> }) {
  const box = useRef<HTMLDivElement>(null);
  const [view, setView] = useState<View>({ x: PAD, y: PAD, k: 1 });
  const drag = useRef<{ x: number; y: number; vx: number; vy: number } | null>(null);
  const svg = outcome.kind === 'svg' ? outcome.svg : null;

  const fit = useCallback(() => {
    const el = box.current;
    if (!el || !svg) return;
    const { w, h } = naturalSize(svg);
    const k = Math.min(1, (el.clientWidth - 2 * PAD) / w, (el.clientHeight - 2 * PAD) / h);
    setView({ k, x: (el.clientWidth - w * k) / 2, y: Math.max(PAD, (el.clientHeight - h * k) / 2) });
  }, [svg]);

  useLayoutEffect(fit, [fit]);

  const zoomAt = useCallback((factor: number, cx: number, cy: number) => {
    setView((v) => {
      const k = Math.min(4, Math.max(0.1, v.k * factor));
      const f = k / v.k;
      return { k, x: cx - (cx - v.x) * f, y: cy - (cy - v.y) * f };
    });
  }, []);

  useEffect(() => {
    const el = box.current;
    if (!el || !svg) return;
    // non-passive: React's onWheel is passive, so it could not stop the dialog behind from scrolling
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      const r = el.getBoundingClientRect();
      zoomAt(e.deltaY < 0 ? 1.12 : 1 / 1.12, e.clientX - r.left, e.clientY - r.top);
    };
    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, [svg, zoomAt]);

  if (outcome.kind === 'raw') {
    return (
      <div className="diagram-raw">
        <div className="error-text">Could not render this diagram: {outcome.error}</div>
        <pre><code>{outcome.source}</code></pre>
      </div>
    );
  }

  const center = () => ({ x: (box.current?.clientWidth ?? 0) / 2, y: (box.current?.clientHeight ?? 0) / 2 });
  return (
    <div className="diagram-stage" style={DIAGRAM_THEME_VARS as React.CSSProperties} ref={wrapRef}>
      <div className="diagram-zoom">
        <button className="icon-btn" title="Zoom in" onClick={() => zoomAt(1.25, center().x, center().y)}><ZoomIn /></button>
        <button className="icon-btn" title="Zoom out" onClick={() => zoomAt(0.8, center().x, center().y)}><ZoomOut /></button>
        <button className="icon-btn" title="Fit to view" onClick={fit}><FitView /></button>
      </div>
      <div
        ref={box}
        className="diagram-pan"
        onPointerDown={(e) => {
          (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
          drag.current = { x: e.clientX, y: e.clientY, vx: view.x, vy: view.y };
        }}
        onPointerMove={(e) => {
          const d = drag.current;
          if (d) setView((v) => ({ ...v, x: d.vx + e.clientX - d.x, y: d.vy + e.clientY - d.y }));
        }}
        onPointerUp={() => { drag.current = null; }}
      >
        <div
          className="diagram-canvas"
          style={{ transform: `translate(${view.x}px, ${view.y}px) scale(${view.k})` }}
          dangerouslySetInnerHTML={{ __html: outcome.svg }}
        />
      </div>
    </div>
  );
}
