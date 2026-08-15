ALTER TABLE users ADD COLUMN IF NOT EXISTS banned_until TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS muted_until TIMESTAMP;
CREATE TABLE IF NOT EXISTS audit_logs (
  id BIGSERIAL PRIMARY KEY,
  actor_id BIGINT,
  action VARCHAR(40) NOT NULL,
  target_type VARCHAR(40),
  target_id BIGINT,
  detail VARCHAR(300),
  created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_logs (created_at);
