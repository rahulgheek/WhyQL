-- Identity tables (Phase 2). Schema is owned by Flyway; Hibernate only validates.
CREATE TABLE IF NOT EXISTS users (
  id                 UUID PRIMARY KEY,
  email              VARCHAR(255) UNIQUE NOT NULL,
  password_hash      VARCHAR(255) NOT NULL,
  status             VARCHAR(30) NOT NULL DEFAULT 'PENDING',
  email_verified_at  TIMESTAMP WITH TIME ZONE,
  created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  last_login_at      TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
