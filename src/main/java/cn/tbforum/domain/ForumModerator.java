package cn.tbforum.domain;

import jakarta.persistence.*;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "forum_moderators", uniqueConstraints = @UniqueConstraint(columnNames = {"forum_id", "user_id"}))
public class ForumModerator {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(optional = false)
    public Forum forum;

    @ManyToOne(optional = false)
    public User user;
}
