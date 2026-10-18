-- OAuth login (auth service): provider-linked accounts plus nullable password_hash
-- for OAuth-only users (email/OTP users always have a password).
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

CREATE TABLE IF NOT EXISTS oauth_accounts (
  id               UUID PRIMARY KEY,
  user_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider         VARCHAR(30) NOT NULL,
  provider_user_id VARCHAR(255) NOT NULL,
  email            VARCHAR(255),
  created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  CONSTRAINT uq_oauth_provider_account UNIQUE (provider, provider_user_id)
);

CREATE INDEX IF NOT EXISTS idx_oauth_user ON oauth_accounts(user_id);
