package cn.tbforum.domain;

import jakarta.persistence.*;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
        name = "favorites",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "post_id"}),
        indexes = @Index(name = "idx_favorite_user", columnList = "user_id,createdAt"))
public class Favorite {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(optional = false)
    public User user;

    @ManyToOne(optional = false)
    public Post post;

    @Column(nullable = false, updatable = false)
    public Instant createdAt = Instant.now();
}
