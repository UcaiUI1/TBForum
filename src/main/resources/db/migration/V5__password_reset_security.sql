ALTER TABLE users
  ADD COLUMN IF NOT EXISTS token_version BIGINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_user_used
  ON password_reset_tokens(user_id, used);
