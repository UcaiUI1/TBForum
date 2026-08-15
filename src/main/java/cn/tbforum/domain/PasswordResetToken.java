package cn.tbforum.domain;
import jakarta.persistence.*; import java.time.Instant;
@Entity @Table(name="password_reset_tokens") public class PasswordResetToken {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 @ManyToOne(optional=false) public User user;
 @Column(nullable=false,length=100,unique=true) public String tokenHash;
 @Column(nullable=false) public Instant expiresAt;
 @Column(nullable=false) public boolean used=false;
 @Column(nullable=false,updatable=false) public Instant createdAt=Instant.now();
}
