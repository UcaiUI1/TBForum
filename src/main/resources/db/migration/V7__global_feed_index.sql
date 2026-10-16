-- Match the live global feed filter and newest-first pagination.
-- A composite index also works with the H2 development database.
CREATE INDEX idx_posts_deleted_created ON posts (deleted, created_at DESC);
