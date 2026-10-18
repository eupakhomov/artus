-- Phase 21: a proposal is a reflection's memory ops ('memory'), an extracted skill ('skill'), or the
-- ops for one block the user asked to remember ('remember'); see docs/plan/phase-21-memory-curation.md
-- decisions 10-11 and 14. For a skill the episode column holds the proposed SKILL.md body and ops
-- holds {name, description, tags, evidence}; for 'remember' the episode is empty.
ALTER TABLE memory_proposal ADD COLUMN kind TEXT NOT NULL DEFAULT 'memory'
    CHECK (kind IN ('memory', 'skill', 'remember'));

-- skill extraction and "remember this" never mark a session reflected, so they carry no seq
ALTER TABLE memory_proposal ALTER COLUMN reflected_seq DROP NOT NULL;

-- at most one pending proposal per session *per kind*: a pending skill must not block a reflection.
-- 'remember' proposals are exempt - several blocks may be waiting for approval at once.
DROP INDEX idx_memory_proposal_one_pending_per_session;
CREATE UNIQUE INDEX idx_memory_proposal_one_pending_per_session_kind ON memory_proposal (session_id, kind)
    WHERE status = 'PENDING' AND kind <> 'remember';
