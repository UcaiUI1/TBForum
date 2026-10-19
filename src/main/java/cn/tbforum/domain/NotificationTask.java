package cn.tbforum.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** One durable delivery task per persisted notification. SENT means dispatched, not read by a client. */
@Entity
@Table(
        name = "notification_tasks",
        indexes = {
            @Index(name = "idx_notification_task_due", columnList = "status,next_attempt_at"),
            @Index(name = "idx_notification_task_lease", columnList = "status,lease_until"),
            @Index(name = "idx_notification_task_completed", columnList = "status,completed_at")
        })
public class NotificationTask {
    @Id
    @Column(length = 36)
    public String id = UUID.randomUUID().toString();

    @Column(nullable = false, unique = true)
    public Long notificationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Status status = Status.READY;

    @Column(nullable = false)
    public int attempts;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    @Column(nullable = false)
    public Instant nextAttemptAt = createdAt;

    public Instant leaseUntil;

    @Column(length = 36)
    public String claimToken;

    @Column(length = 500)
    public String lastError;

    public Instant completedAt;

    public enum Status {
        READY,
        IN_FLIGHT,
        QUEUED,
        SENT,
        DEAD
    }
}
