package cn.tbforum.domain;

import jakarta.persistence.*;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "forums")
public class Forum {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, unique = true, length = 64)
    public String slug;

    @Column(nullable = false, length = 100)
    public String name;

    @Column(length = 500)
    public String description;

    @ManyToOne(optional = false)
    public User owner;

    @Column(nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    @Column(length = 60)
    public String customReportReason1;

    @Column(length = 60)
    public String customReportReason2;

    public boolean deleted = false;
}
