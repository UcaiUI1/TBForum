package cn.tbforum.domain;
import jakarta.persistence.*; import java.time.Instant;
@Entity @Table(name="users") public class User {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 @Column(nullable=false,unique=true,length=40) public String username;
 @Column(nullable=false) public String passwordHash;
 @Column(nullable=false) public boolean verified=false;
 @Enumerated(EnumType.STRING) @Column(nullable=false) public Role role=Role.USER;
 @Column(nullable=false,updatable=false) public Instant createdAt=Instant.now();
 @Lob public String avatarUrl;
 @Column(length=500) public String bio;
 @Column(length=100) public String signature;
}
