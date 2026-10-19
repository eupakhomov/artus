import { useEffect, useRef } from 'react';
import { useStore } from '../store/store';
import { getWidget } from './widgetRegistry';
import { permissionResponseFor } from '../permissions';

export interface HotkeyActions {
  /** visible widget ids in on-screen order (grid order, or sidebar order in focus mode) — recomputed on every call */
  orderedIds: () => string[];
  openCreate: () => void;
  openQuickCreate: () => void;
  openMemory: () => void;
  openLibrary: () => void;
  openUsage: () => void;
  openTemplates: () => void;
  openSettings: () => void;
  openCheatsheet: () => void;
  toggleMaximizeFocused: () => void;
  toggleMinimizeFocused: () => void;
  /** phase 25: keep the focused session off the dashboard (sidebar → Hidden) */
  hideFocused: () => void;
  openExpose: () => void;
  /** phase 16: sidebar expanded ↔ rail */
  cycleSidebar: () => void;
  /** phase 16: grid ↔ focus layout */
  toggleLayoutMode: () => void;
  /** phase 16: restore + focus the session that has waited longest for a permission answer */
  jumpToNeedsYou: () => void;
  /** true while any Dashboard-level dialog (including the cheatsheet/Exposé) is open */
  anyDialogOpen: () => boolean;
  /** closes the topmost open dialog, if any; returns whether it closed something */
  closeTopDialog: () => boolean;
  /** Esc's last resort: exit Exposé, else restore a maximized widget; returns whether it did something */
  exitOverlay: () => boolean;
}

function isTypingTarget(el: Element | null): boolean {
  if (!el) return false;
  const tag = el.tagName;
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true;
  return (el as HTMLElement).isContentEditable;
}

/**
 * One document-level keydown listener for the whole dashboard. Registered once; reads
 * fresh state/actions via refs each keystroke so it never needs to re-subscribe (avoids
 * add/removeEventListener churn on every Dashboard render).
 */
export function useHotkeys(actions: HotkeyActions): void {
  const actionsRef = useRef(actions);
  actionsRef.current = actions;

  const focusedId = useStore((s) => s.focusedId);
  const focusedIdRef = useRef(focusedId);
  focusedIdRef.current = focusedId;

  const setFocused = useStore((s) => s.setFocused);

  const views = useStore((s) => s.views);
  const viewsRef = useRef(views);
  viewsRef.current = views;

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.isComposing || e.metaKey || e.ctrlKey || e.altKey) return;

      if (e.key === 'Escape') {
        if (actionsRef.current.closeTopDialog()) {
          e.preventDefault();
          return;
        }
        if (document.activeElement instanceof HTMLElement && isTypingTarget(document.activeElement)) {
          document.activeElement.blur();
          return;
        }
        actionsRef.current.exitOverlay();
        return;
      }

      if (isTypingTarget(document.activeElement) || actionsRef.current.anyDialogOpen()) return;

      // past this point nothing on the page should be editable-focused, so any browser
      // default action (e.g. inserting the character) can only be a same-tick focus-shift
      // race — e.g. opening a dialog whose first field autofocuses before the browser gets
      // around to the default text-insertion step for *this* keydown. Own the keystroke.
      e.preventDefault();

      const a = actionsRef.current;
      const focused = focusedIdRef.current;

      switch (e.key) {
        case '?':
          a.openCheatsheet();
          return;
        case 'n':
          a.openCreate();
          return;
        case 'q':
          a.openQuickCreate();
          return;
        case 'm':
          a.openMemory();
          return;
        case 'l':
          a.openLibrary();
          return;
        case 'u':
          a.openUsage();
          return;
        case 't':
          a.openTemplates();
          return;
        case ',':
          a.openSettings();
          return;
        case 'j':
        case ']': {
          const ids = a.orderedIds();
          if (ids.length === 0) return;
          const idx = focused ? ids.indexOf(focused) : -1;
          setFocused(ids[(idx + 1 + ids.length) % ids.length]!);
          return;
        }
        case 'k':
        case '[': {
          const ids = a.orderedIds();
          if (ids.length === 0) return;
          const idx = focused ? ids.indexOf(focused) : -1;
          setFocused(ids[(idx - 1 + ids.length) % ids.length]!);
          return;
        }
        case 'Enter':
        case 'i':
          if (focused) getWidget(focused)?.focusComposer();
          return;
        case 'g':
          if (focused) getWidget(focused)?.toggleGit();
          return;
        case 'c':
          if (focused) getWidget(focused)?.toggleChanges();
          return;
        case 'f':
          if (focused) a.toggleMaximizeFocused();
          return;
        case 'x':
          if (focused) a.toggleMinimizeFocused();
          return;
        case 'h':
          if (focused) a.hideFocused();
          return;
        case 'e':
          a.openExpose();
          return;
        case 'b':
          a.cycleSidebar();
          return;
        case 'v':
          a.toggleLayoutMode();
          return;
        case 'a':
          a.jumpToNeedsYou();
          return;
        case 'y':
        case 'd': {
          if (!focused) return;
          const pending = viewsRef.current[focused]?.pendingPermission;
          if (!pending) return;
          const response = permissionResponseFor(pending.toolName, pending.plan !== null, e.key === 'y' ? 'allow' : 'deny');
          if (response) getWidget(focused)?.respondPermission(pending.requestId, response);
          return;
        }
        default:
          if (e.key >= '1' && e.key <= '9') {
            const ids = a.orderedIds();
            const target = ids[Number(e.key) - 1];
            if (target) setFocused(target);
          }
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [setFocused]);
}
