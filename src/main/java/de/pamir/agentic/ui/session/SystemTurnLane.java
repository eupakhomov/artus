package de.pamir.agentic.ui.session;

/**
 * Which side of {@link SystemSessionService}'s single-system-session lock a caller is on (see
 * docs/plan/phase-9-production-hardening.md S2). {@code INTERACTIVE} callers sit in front of a
 * human waiting on a response (ticket import, git-assist branch/commit-message suggestions,
 * continue-from handoff summaries, library AI-fill) — if a background turn is already holding
 * the system session, they fail fast with a clear "busy" error instead of silently blocking for
 * however long that background turn's own timeout is. {@code BACKGROUND} callers (reflection,
 * service discovery, auto-titling) are fire-and-forget from a human's perspective and can afford
 * to simply wait out their own full timeout for the lock. {@code VISUALIZE} (phase 22) is its own
 * worker: a diagram turn is the longest task a human waits on, so sharing INTERACTIVE would make the
 * quick dialogs report "busy" for its whole duration; it fails fast like INTERACTIVE, but only against
 * another diagram request, and is never warmed up.
 */
public enum SystemTurnLane {
	INTERACTIVE, BACKGROUND, VISUALIZE
}
