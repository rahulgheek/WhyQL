-- Dashboard cards (Phase 9: pinned job results)
CREATE TABLE IF NOT EXISTS dashboard_cards (
  id              UUID PRIMARY KEY,
  owner_user_id   VARCHAR(255) NOT NULL,
  data_source_id  UUID,
  job_id          UUID NOT NULL,
  question        TEXT NOT NULL,
  engine          VARCHAR(50),
  result_json     TEXT,
  created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  -- One card per saved job per user: re-saving is idempotent (upsert
  -- semantics at the service layer, the first card wins).
  CONSTRAINT uq_dashboard_owner_job UNIQUE (owner_user_id, job_id)
);

CREATE INDEX IF NOT EXISTS idx_dashboard_cards_owner ON dashboard_cards(owner_user_id);
