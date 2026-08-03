package cn.tbforum.repo;
import cn.tbforum.domain.Tag; import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional; import java.util.*;
public interface TagRepository extends JpaRepository<Tag,Long>{
 Optional<Tag> findByName(String name); List<Tag> findTop50ByOrderByPostCountDesc();
 @Modifying @Transactional @Query("update Tag t set t.postCount=t.postCount+:delta where t.id=:id") void incrementPostCount(@Param("id") Long id,@Param("delta") int delta);
}
