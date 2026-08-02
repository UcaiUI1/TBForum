package cn.tbforum.domain;
import jakarta.persistence.*; import java.time.Instant;
@Entity @Table(name="notifications",indexes=@Index(name="idx_notif_user",columnList="user_id,createdAt"))
public class Notification {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 @ManyToOne(optional=false) public User user;
 @ManyToOne public User actor;
 @Enumerated(EnumType.STRING) @Column(nullable=false) public Kind kind;
 @Column(nullable=false) public String text;
 public Long postId; public Long commentId;
 @Column(nullable=false) public Instant createdAt=Instant.now();
 public boolean read=false;
 public enum Kind{REPLY,UPVOTE,MENTION,FEATURED,FOLLOW}
}
