package cn.tbforum.repo;
import cn.tbforum.domain.User; import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface UserRepository extends JpaRepository<User,Long>{
 Optional<User> findByUsername(String username); List<User> findTop10ByUsernameContainingIgnoreCase(String username);
}
