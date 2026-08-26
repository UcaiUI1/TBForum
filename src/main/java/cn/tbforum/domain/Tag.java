package cn.tbforum.domain;

import jakarta.persistence.*;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(
        name = "tags",
        indexes = {@Index(name = "idx_tag_name", columnList = "name")})
public class Tag {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, unique = true, length = 30)
    public String name;

    @Column(nullable = false)
    public long postCount = 0;
}
