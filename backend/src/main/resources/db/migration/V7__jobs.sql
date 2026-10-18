-- Jobs table (Phase 5: async query pipeline)
CREATE TABLE IF NOT EXISTS jobs (
  id              UUID PRIMARY KEY,
  owner_user_id   VARCHAR(255) NOT NULL,
  data_source_id  UUID,
  question        TEXT NOT NULL,
  status          VARCHAR(30) NOT NULL DEFAULT 'QUEUED',
  attempt         INTEGER NOT NULL DEFAULT 0,
  max_attempts    INTEGER NOT NULL DEFAULT 3,
  error_message   TEXT,
  query_plan_json TEXT,
  result_json     TEXT,
  created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  started_at      TIMESTAMP WITH TIME ZONE,
  completed_at    TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_jobs_owner ON jobs(owner_user_id);
CREATE INDEX IF NOT EXISTS idx_jobs_status ON jobs(status);
CREATE INDEX IF NOT EXISTS idx_jobs_datasource ON jobs(data_source_id);
