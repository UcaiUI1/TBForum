package cn.tbforum.domain;
import jakarta.persistence.*; import java.time.Instant;
@Entity @Table(name="audit_logs",indexes=@Index(name="idx_audit_created",columnList="createdAt")) public class AuditLog {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 @ManyToOne public User actor;
 @Column(nullable=false,length=40) public String action;
 @Column(length=40) public String targetType;
 public Long targetId;
 @Column(length=300) public String detail;
 @Column(nullable=false,updatable=false) public Instant createdAt=Instant.now();
}
