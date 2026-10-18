-- Schema snapshots (Phase 3). Every ingestion origin (live introspection, SQL
-- upload, model zip, GitHub repo, manual definition) converges here as JSON.
CREATE TABLE IF NOT EXISTS schema_snapshots (
  id              UUID PRIMARY KEY,
  owner_user_id   VARCHAR(255) NOT NULL,
  data_source_id  UUID,
  name            VARCHAR(255) NOT NULL,
  version         INTEGER NOT NULL,
  snapshot_json   TEXT NOT NULL,
  source          VARCHAR(255) NOT NULL,
  created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_snapshots_owner ON schema_snapshots(owner_user_id);
CREATE INDEX IF NOT EXISTS idx_snapshots_datasource ON schema_snapshots(data_source_id);
