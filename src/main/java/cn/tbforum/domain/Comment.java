package cn.tbforum.domain;

import jakarta.persistence.*;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(
        name = "comments",
        indexes = {
            @Index(name = "idx_comments_post", columnList = "post_id,createdAt"),
            @Index(name = "idx_comments_parent", columnList = "parent_id")
        })
public class Comment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(optional = false)
    public Post post;

    @ManyToOne(optional = false)
    public User author;

    @ManyToOne
    public Comment parent;

    public Long rootId;

    @Column(nullable = false)
    public int depth;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String content;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    /** Soft deletion is conditional in the repository; a stale edit must not resurrect a deleted row. */
    @Column(updatable = false)
    public boolean deleted = false;

    public boolean edited = false;
    public Instant editedAt;
    public boolean pinned = false;
}
