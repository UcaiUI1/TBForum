package cn.tbforum.domain;
import jakarta.persistence.*;
@Entity @Table(name="tags",indexes={@Index(name="idx_tag_name",columnList="name")}) public class Tag {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 @Column(nullable=false,unique=true,length=30) public String name;
 @Column(nullable=false) public long postCount=0;
}
