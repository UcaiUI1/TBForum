package cn.tbforum.domain;

import jakarta.persistence.*;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
        name = "reports",
        uniqueConstraints = @UniqueConstraint(columnNames = {"reporter_id", "target_type", "target_id"}),
        indexes = {
            @Index(name = "idx_report_status", columnList = "status,createdAt"),
            @Index(name = "idx_report_target", columnList = "target_type,target_id")
        })
public class Report {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(optional = false)
    public User reporter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    public TargetType targetType;

    @Column(nullable = false)
    public Long targetId;

    @Column(nullable = false, length = 80)
    public String reason;

    @Column(nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    public Status status = Status.PENDING;

    @ManyToOne
    public User handledBy;

    public Instant handledAt;

    public enum TargetType {
        POST,
        COMMENT
    }

    public enum Status {
        PENDING,
        RESOLVED,
        DISMISSED
    }
}
