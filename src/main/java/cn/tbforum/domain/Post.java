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
        name = "posts",
        indexes = {
            @Index(name = "idx_posts_forum_created", columnList = "forum_id,createdAt"),
            @Index(name = "idx_posts_deleted_created", columnList = "deleted,createdAt DESC")
        })
public class Post {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(optional = false)
    public Forum forum;

    @ManyToOne(optional = false)
    public User author;

    @Column(nullable = false, length = 180)
    public String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String content;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    public long views = 0, upvotes = 0, downvotes = 0, commentCount = 0;
    public boolean featured = false, deleted = false, locked = false;
    public boolean edited = false;
    public Instant editedAt;
}
