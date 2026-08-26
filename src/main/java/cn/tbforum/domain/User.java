package cn.tbforum.domain;

import jakarta.persistence.*;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, unique = true, length = 40)
    public String username;

    @Column(nullable = false)
    public String passwordHash;

    @Column(nullable = false)
    public boolean verified = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Role role = Role.USER;

    @Column(nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    @Column(columnDefinition = "text")
    public String avatarUrl;

    @Column(length = 500)
    public String bio;

    @Column(length = 100)
    public String signature;

    public boolean deleted = false;
    public Instant bannedUntil;
    public Instant mutedUntil;

    @Column(length = 255)
    public String email;

    @Column(nullable = false)
    public long tokenVersion = 0;
}
