CREATE TABLE notification_tasks (
    id VARCHAR(36) PRIMARY KEY,
    notification_id BIGINT NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    next_attempt_at TIMESTAMP NOT NULL,
    lease_until TIMESTAMP,
    claim_token VARCHAR(36),
    last_error VARCHAR(500),
    completed_at TIMESTAMP
);
CREATE INDEX idx_notification_task_due ON notification_tasks (status, next_attempt_at);
CREATE INDEX idx_notification_task_lease ON notification_tasks (status, lease_until);
CREATE INDEX idx_notification_task_completed ON notification_tasks (status, completed_at);
CREATE INDEX idx_notif_user_id ON notifications (user_id, id DESC);
