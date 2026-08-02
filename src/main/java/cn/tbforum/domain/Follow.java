package cn.tbforum.domain;
import jakarta.persistence.*; import java.time.Instant;
@Entity @Table(name="follows",uniqueConstraints=@UniqueConstraint(columnNames={"follower_id","followee_id"}),indexes={@Index(name="idx_follow_follower",columnList="follower_id,createdAt"),@Index(name="idx_follow_followee",columnList="followee_id,createdAt")}) public class Follow {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 @ManyToOne(optional=false) public User follower;
 @ManyToOne(optional=false) public User followee;
 @Column(nullable=false,updatable=false) public Instant createdAt=Instant.now();
}
