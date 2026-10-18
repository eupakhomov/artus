-- Phase 23: when a session was closed. updated_at is bumped by PR polling, reflection and
-- retention, so it can't answer "most recently closed".
ALTER TABLE session ADD COLUMN closed_at timestamptz;
UPDATE session SET closed_at = updated_at WHERE state = 'CLOSED';
CREATE INDEX idx_session_closed_at ON session (closed_at DESC) WHERE state = 'CLOSED';
