package cn.tbforum.domain;

import jakarta.persistence.*;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "post_tags", uniqueConstraints = @UniqueConstraint(columnNames = {"post_id", "tag_id"}))
public class PostTag {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(optional = false)
    public Post post;

    @ManyToOne(optional = false)
    public Tag tag;
}
