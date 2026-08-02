package cn.tbforum.domain;
import jakarta.persistence.*;
@Entity @Table(name="forum_moderators",uniqueConstraints=@UniqueConstraint(columnNames={"forum_id","user_id"})) public class ForumModerator {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id; @ManyToOne(optional=false) public Forum forum; @ManyToOne(optional=false) public User user;
}
