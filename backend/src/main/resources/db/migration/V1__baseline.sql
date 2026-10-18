-- Baseline migration. pgvector is required in Phase 8 for schema embeddings;
-- creating the extension now keeps every phase runnable on a fresh database.
CREATE EXTENSION IF NOT EXISTS "vector";
