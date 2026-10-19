package cn.tbforum.repo;

import cn.tbforum.domain.*;
import cn.tbforum.domain.Notification;
import cn.tbforum.domain.User;
import java.util.*;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    @Override
    @EntityGraph(attributePaths = {"user", "actor"})
    Optional<Notification> findById(Long id);

    @EntityGraph(attributePaths = {"actor"})
    @Query("select n from Notification n where n.user=:user and n.id < :before order by n.id desc")
    List<Notification> history(User user, long before, Pageable pageable);

    List<Notification> findTop20ByUserOrderByCreatedAtDesc(User user);

    long countByUserAndReadFalse(User user);

    List<Notification> findByUserAndReadFalse(User user);
}
