package cn.tbforum.domain;
import jakarta.persistence.*;
@Entity @Table(name="interactions",uniqueConstraints=@UniqueConstraint(columnNames={"user_id","post_id"})) public class Interaction {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; @ManyToOne(optional=false) public User user; @ManyToOne(optional=false) public Post post;
 @Enumerated(EnumType.STRING) @Column(nullable=false) public Kind kind; public enum Kind { UP, DOWN }
}
