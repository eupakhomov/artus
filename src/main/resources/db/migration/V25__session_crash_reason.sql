-- Why a CRASHED session crashed, when that is known and benign. 'backend_restart' = the startup
-- sweep found it live after a backend restart (nothing went wrong in the session itself — the
-- dashboard shows it as interrupted, not as a failure). NULL = a real crash (sidecar exit, failed
-- wake) or any non-CRASHED state; every other state transition clears it.
ALTER TABLE session ADD COLUMN crash_reason text;

-- rows already swept before this column existed: recognise them by the sweep's own journal note
UPDATE session s SET crash_reason = 'backend_restart'
WHERE s.state = 'CRASHED'
  AND (SELECT e.payload ->> 'message' FROM session_event e
       WHERE e.session_id = s.id AND e.type = 'error' ORDER BY e.seq DESC LIMIT 1)
      = 'backend restarted while session was live';
