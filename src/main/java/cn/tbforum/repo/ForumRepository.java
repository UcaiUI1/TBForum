package cn.tbforum.repo;
import cn.tbforum.domain.Forum; import cn.tbforum.domain.User; import org.springframework.data.domain.Page; import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ForumRepository extends JpaRepository<Forum,Long>{
 Optional<Forum> findBySlug(String slug);
 Optional<Forum> findBySlugAndDeletedFalse(String slug);
 List<Forum> findByOwner(User owner);
 Page<Forum> findByDeletedFalse(Pageable p);
}
