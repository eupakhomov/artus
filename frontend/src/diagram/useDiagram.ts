import { useCallback, useEffect, useRef, useState } from 'react';
import { api, ApiError } from '../api/rest';
import type { DiagramStatus, DiagramType } from '../protocol';
import { loadRenderer, renderOrRepair, type RenderOutcome } from './render';

const errMsg = (e: unknown) => (e instanceof ApiError ? e.message : e instanceof Error ? e.message : String(e));

/**
 * State behind the Visualize dialog and the pop-out window: the stored diagram + staleness from
 * `GET …/visualize`, generation, and the render step (renderer-as-validator with one repair turn,
 * decision 4). Re-checks staleness on window focus and every 15 s while mounted.
 */
export function useDiagram(sessionId: string) {
  const [status, setStatus] = useState<DiagramStatus | null>(null);
  const [outcome, setOutcome] = useState<RenderOutcome | null>(null);
  const [generating, setGenerating] = useState(false);
  const [repairing, setRepairing] = useState(false);
  const [error, setError] = useState('');
  /** seq whose render is already settled — a repair swaps `latest` and must not re-trigger the effect */
  const settledSeq = useRef<number | null>(null);

  const refresh = useCallback(async () => {
    try {
      const s = await api.visualizeStatus(sessionId);
      setStatus((prev) => (prev && s.latest && prev.latest && prev.latest.seq > s.latest.seq ? prev : s));
      setError('');
    } catch (e) {
      setError(errMsg(e));
    }
  }, [sessionId]);

  useEffect(() => {
    void refresh();
    const onFocus = () => void refresh();
    window.addEventListener('focus', onFocus);
    const t = setInterval(() => void refresh(), 15000);
    return () => {
      window.removeEventListener('focus', onFocus);
      clearInterval(t);
    };
  }, [refresh]);

  const latest = status?.latest ?? null;
  useEffect(() => {
    if (!latest) {
      setOutcome(null);
      return;
    }
    if (settledSeq.current === latest.seq) return;
    let cancelled = false;
    void (async () => {
      try {
        const render = await loadRenderer();
        let adopted = latest;
        const result = await renderOrRepair(latest.mermaid, render, async (source, err) => {
          // another view of this diagram (pop-out, second tab) may have repaired it already —
          // adopt its result instead of spending a second turn on the same broken source
          const fresh = await api.visualizeStatus(sessionId).catch(() => null);
          if (fresh?.latest && fresh.latest.seq > latest.seq) {
            adopted = fresh.latest;
            return fresh.latest.mermaid;
          }
          if (latest.repaired) throw new Error('already repaired once');
          setRepairing(true);
          const fixed = await api.visualizeRepair(sessionId, source, err);
          adopted = fixed;
          return fixed.mermaid;
        });
        if (cancelled) return;
        // settle BEFORE adopting into status: that setStatus re-triggers this effect, which must
        // early-return instead of racing the outcome below (all of this block is synchronous)
        settledSeq.current = adopted.seq;
        if (adopted !== latest) setStatus((s) => (s ? { ...s, latest: adopted } : s));
        setOutcome(result);
        // a raw fallback can mean a concurrent view's repair won the lane (our turn got "busy") —
        // check back soon instead of waiting out the 15 s poll
        if (result.kind === 'raw') setTimeout(() => void refresh(), 4000);
      } catch (e) {
        if (!cancelled) setError(errMsg(e));
      } finally {
        setRepairing(false);
      }
    })();
    return () => { cancelled = true; };
  }, [latest, sessionId, refresh]);

  const generate = useCallback(async (type: DiagramType | 'auto') => {
    setGenerating(true);
    setError('');
    try {
      const d = await api.visualize(sessionId, type);
      settledSeq.current = null;
      setStatus({ latest: d, currentFingerprint: d.fingerprint, stale: false });
      void refresh();
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setGenerating(false);
    }
  }, [sessionId, refresh]);

  return { status, latest, outcome, generating, repairing, error, generate, refresh };
}
