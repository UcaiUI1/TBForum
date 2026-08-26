package cn.tbforum.repo;

import cn.tbforum.domain.*;
import cn.tbforum.domain.Notification;
import cn.tbforum.domain.User;
import java.util.*;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findTop20ByUserOrderByCreatedAtDesc(User user);

    long countByUserAndReadFalse(User user);

    List<Notification> findByUserAndReadFalse(User user);
}
