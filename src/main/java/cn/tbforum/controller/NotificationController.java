package cn.tbforum.controller;
import cn.tbforum.domain.Notification; import cn.tbforum.dto.Dtos.NotificationView;
import cn.tbforum.repo.NotificationRepository; import cn.tbforum.service.UserService;
import org.springframework.http.ResponseEntity; import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api") public class NotificationController {
 private final NotificationRepository notifications; private final UserService userService;
 public NotificationController(NotificationRepository n,UserService u){notifications=n;userService=u;}
 @GetMapping("/notifications") public List<NotificationView> listNotifications(){return notifications.findTop20ByUserOrderByCreatedAtDesc(userService.current()).stream().map(NotificationView::of).toList();}
 @GetMapping("/notifications/unread-count") public Map<String,Long> unreadCount(){return Map.of("count",notifications.countByUserAndReadFalse(userService.current()));}
 @PatchMapping("/notifications/{id}/read") @Transactional public ResponseEntity<Void> markRead(@PathVariable Long id){Notification n=notifications.findById(id).orElseThrow(()->UserService.notFound("通知不存在"));if(!n.user.id.equals(userService.current().id))throw UserService.forbidden("无权操作");n.read=true;notifications.save(n);return ResponseEntity.noContent().build();}
 @PatchMapping("/notifications/read-all") @Transactional public ResponseEntity<Void> markAllRead(){for(Notification n:notifications.findByUserAndReadFalse(userService.current())){n.read=true;notifications.save(n);}return ResponseEntity.noContent().build();}
}
