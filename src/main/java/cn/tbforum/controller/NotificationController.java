package cn.tbforum.controller;

import cn.tbforum.domain.Notification;
import cn.tbforum.dto.Dtos.NotificationView;
import cn.tbforum.repo.NotificationRepository;
import cn.tbforum.service.UserService;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class NotificationController {
    private final NotificationRepository notifications;
    private final UserService userService;

    public NotificationController(NotificationRepository n, UserService u) {
        notifications = n;
        userService = u;
    }

    @GetMapping("/notifications")
    public List<NotificationView> listNotifications() {
        return notifications.findTop20ByUserOrderByCreatedAtDesc(userService.current()).stream()
                .map(NotificationView::of)
                .toList();
    }

    public record NotificationPage(
            List<NotificationView> content, long unreadCount, Long nextBefore, boolean hasMore) {}

    /** A fresh snapshot on every sync also sees late commits with smaller generated IDs. */
    @GetMapping("/notifications/history")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public NotificationPage history(
            @RequestParam(required = false) Long before, @RequestParam(defaultValue = "20") int size) {
        if (before != null && before < 1 || size < 1 || size > 100)
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "无效通知分页参数");
        var user = userService.current();
        var rows = notifications.history(user, before == null ? Long.MAX_VALUE : before, PageRequest.of(0, size + 1));
        boolean more = rows.size() > size;
        var content = rows.stream().limit(size).map(NotificationView::of).toList();
        return new NotificationPage(
                content,
                notifications.countByUserAndReadFalse(user),
                more ? content.get(content.size() - 1).id() : null,
                more);
    }

    @GetMapping("/notifications/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("count", notifications.countByUserAndReadFalse(userService.current()));
    }

    @PatchMapping("/notifications/{id}/read")
    @Transactional
    public ResponseEntity<Void> markRead(@PathVariable Long id) {
        Notification n = notifications.findById(id).orElseThrow(() -> UserService.notFound("通知不存在"));
        if (!n.user.id.equals(userService.current().id)) throw UserService.forbidden("无权操作");
        n.read = true;
        notifications.save(n);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/notifications/read-all")
    @Transactional
    public ResponseEntity<Void> markAllRead() {
        for (Notification n : notifications.findByUserAndReadFalse(userService.current())) {
            n.read = true;
            notifications.save(n);
        }
        return ResponseEntity.noContent().build();
    }
}
