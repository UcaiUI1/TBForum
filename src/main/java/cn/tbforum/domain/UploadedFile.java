package cn.tbforum.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "uploaded_files")
public class UploadedFile {
    public enum Purpose {
        AVATAR,
        POST_IMAGE
    }

    public enum Status {
        PENDING,
        REFERENCED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(optional = false)
    public User owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public Purpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public Status status = Status.PENDING;

    @Column(nullable = false, unique = true, length = 255)
    public String url;

    @Column(nullable = false)
    public long sizeBytes;

    public Long referenceId;

    @Column(nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    public Instant referencedAt;

    public Instant expiresAt;
}
