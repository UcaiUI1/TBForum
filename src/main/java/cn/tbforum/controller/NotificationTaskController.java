package cn.tbforum.controller;

import cn.tbforum.domain.NotificationTask;
import cn.tbforum.notif.NotificationTaskStore;
import cn.tbforum.repo.NotificationTaskRepository;
import cn.tbforum.service.UserService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/notification-tasks")
public class NotificationTaskController {
    private final NotificationTaskStore store;
    private final NotificationTaskRepository tasks;
    private final UserService users;

    public NotificationTaskController(
            NotificationTaskStore store, NotificationTaskRepository tasks, UserService users) {
        this.store = store;
        this.tasks = tasks;
        this.users = users;
    }

    public record TaskView(
            String id,
            long notificationId,
            NotificationTask.Status status,
            int attempts,
            Instant createdAt,
            Instant nextAttemptAt,
            String lastError) {
        static TaskView of(NotificationTask task) {
            return new TaskView(
                    task.id,
                    task.notificationId,
                    task.status,
                    task.attempts,
                    task.createdAt,
                    task.nextAttemptAt,
                    task.lastError);
        }
    }

    @GetMapping
    public List<TaskView> list(
            @RequestParam(defaultValue = "DEAD") NotificationTask.Status status,
            @RequestParam(defaultValue = "0") int page) {
        users.requireAdmin();
        if (page < 0 || page > 10000)
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST);
        return tasks.findByStatusOrderByCreatedAtAsc(status, PageRequest.of(page, 50)).stream()
                .map(TaskView::of)
                .toList();
    }

    @PostMapping("/{id}/replay")
    public Map<String, Boolean> replay(@PathVariable String id) {
        users.requireAdmin();
        if (!store.replay(id, Instant.now()))
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "仅失败任务可以重放");
        return Map.of("replayed", true);
    }
}
