package cn.tbforum.repo;
import cn.tbforum.domain.*; import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface NotificationRepository extends JpaRepository<Notification,Long>{
 List<Notification> findTop20ByUserOrderByCreatedAtDesc(User user); long countByUserAndReadFalse(User user); List<Notification> findByUserAndReadFalse(User user);
}
