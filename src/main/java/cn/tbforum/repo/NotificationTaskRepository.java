package cn.tbforum.repo;

import cn.tbforum.domain.NotificationTask;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationTaskRepository extends JpaRepository<NotificationTask, String> {
    List<NotificationTask> findByStatusOrderByCreatedAtAsc(NotificationTask.Status status, Pageable pageable);
}
