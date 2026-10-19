-- Repair counters left by the previous stale-entity flush / concurrent-delete races.
-- Count all non-deleted comments, including replies; update only mismatched posts.
WITH actual AS (
    SELECT p.id, count(c.id) AS comment_count
    FROM posts p
    LEFT JOIN comments c ON c.post_id = p.id AND c.deleted = false
    GROUP BY p.id
)
UPDATE posts p
SET comment_count = actual.comment_count
FROM actual
WHERE p.id = actual.id AND p.comment_count <> actual.comment_count;
