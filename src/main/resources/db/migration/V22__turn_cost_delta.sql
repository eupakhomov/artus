-- Phase 25 step 1: the Claude sidecar used to journal the SDK's result.total_cost_usd as the
-- turn's costUsd, but that value is a running total per sidecar process, so every sum over
-- turn_complete rows (header cost, budget, usage dashboard, Slack) was inflated. Rewrite each
-- old Claude turn_complete to its own cost: the difference from the previous turn_complete of
-- the same sidecar run. A run starts at each journaled `ready` event (one per sidecar
-- process); a total lower than the previous one is also a reset (/clear, a crash result).
-- The original value is kept as costUsdRaw, which also makes this statement idempotent.
-- Rows written by the fixed sidecar carry sessionCostUsd and are left alone; Codex rows are
-- already per-turn estimates. No `?` jsonb operator: EventJournalDbTest runs this file
-- through JdbcTemplate, where `?` is a bind placeholder.
WITH ev AS (
    SELECT e.session_id, e.seq, e.type,
           CASE WHEN e.type = 'turn_complete' THEN (e.payload ->> 'costUsd')::numeric END AS total
    FROM session_event e
    JOIN session s ON s.id = e.session_id
    WHERE s.provider = 'claude'
      AND (e.type = 'ready'
           OR (e.type = 'turn_complete'
               AND e.payload -> 'costUsd' IS NOT NULL
               AND e.payload -> 'costUsdRaw' IS NULL
               AND e.payload -> 'sessionCostUsd' IS NULL))
),
runs AS (
    SELECT ev.*,
           count(*) FILTER (WHERE type = 'ready') OVER (PARTITION BY session_id ORDER BY seq) AS run
    FROM ev
),
turns AS (
    SELECT session_id, seq, total,
           lag(total) OVER (PARTITION BY session_id, run ORDER BY seq) AS prev
    FROM runs
    WHERE type = 'turn_complete'
)
UPDATE session_event e
SET payload = e.payload || jsonb_build_object(
        'costUsdRaw', t.total,
        'costUsd', CASE WHEN t.prev IS NULL OR t.total < t.prev THEN t.total ELSE t.total - t.prev END)
FROM turns t
WHERE e.session_id = t.session_id
  AND e.seq = t.seq;
