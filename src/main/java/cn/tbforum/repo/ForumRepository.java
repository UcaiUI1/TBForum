package cn.tbforum.repo;
import cn.tbforum.domain.Forum; import org.springframework.data.domain.Page; import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ForumRepository extends JpaRepository<Forum,Long>{
 Optional<Forum> findBySlug(String slug);
 Optional<Forum> findBySlugAndDeletedFalse(String slug);
 Page<Forum> findByDeletedFalse(Pageable p);
}
