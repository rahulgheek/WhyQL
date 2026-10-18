-- Data sources (Phase 3). Credentials are AES-GCM ciphertext; sandbox rows point at
-- an isolated schema inside the app database instead of a URL.
CREATE TABLE IF NOT EXISTS data_sources (
  id              UUID PRIMARY KEY,
  owner_user_id   VARCHAR(255) NOT NULL,
  name            VARCHAR(255) NOT NULL,
  engine          VARCHAR(30) NOT NULL,
  jdbc_url_enc    TEXT,
  username_enc    TEXT,
  password_enc    TEXT,
  sandbox_schema  VARCHAR(255),
  created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_data_sources_owner ON data_sources(owner_user_id);
