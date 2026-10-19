package cn.tbforum.notif;

import cn.tbforum.cache.CacheService;
import cn.tbforum.config.AppProperties;
import cn.tbforum.domain.*;
import cn.tbforum.domain.Notification;
import cn.tbforum.domain.User;
import cn.tbforum.dto.Dtos.NotificationView;
import cn.tbforum.repo.NotificationRepository;
import cn.tbforum.repo.NotificationTaskRepository;
import cn.tbforum.repo.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.*;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Service
public class NotificationService extends TextWebSocketHandler {
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(NotificationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final String WS_CHANNEL = "tbforum:ws:events";

    private final NotificationRepository notifications;
    private final NotificationTaskRepository tasks;
    private final UserRepository users;
    private final CacheService cache;
    private final MeterRegistry meterRegistry;
    private final boolean redisEnabled;
    private final String instanceId = UUID.randomUUID().toString();
    private final Map<String, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    @Autowired
    public NotificationService(
            NotificationRepository notifications,
            NotificationTaskRepository tasks,
            UserRepository users,
            CacheService cache,
            ObjectProvider<RedisMessageListenerContainer> containerProvider,
            ObjectProvider<MeterRegistry> registryProvider,
            AppProperties properties) {
        this(
                notifications,
                tasks,
                users,
                cache,
                containerProvider.getIfAvailable(),
                registryProvider.getIfAvailable(),
                properties);
    }

    NotificationService(
            NotificationRepository notifications,
            NotificationTaskRepository tasks,
            UserRepository users,
            CacheService cache,
            RedisMessageListenerContainer container,
            MeterRegistry registry,
            AppProperties properties) {
        this.notifications = notifications;
        this.tasks = tasks;
        this.users = users;
        this.cache = cache;
        this.meterRegistry = registry;
        this.redisEnabled = properties.getRedis().isEnabled();

        if (registry != null) {
            registry.gauge(
                    "tbforum.ws.connections.active",
                    sessions,
                    value -> value.values().stream().mapToInt(Set::size).sum());
        }
        if (redisEnabled && container != null) {
            container.addMessageListener(this::onRedisMessage, new ChannelTopic(WS_CHANNEL));
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String username = authUser(session);
        if (username == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        sessions.computeIfAbsent(username, key -> ConcurrentHashMap.newKeySet())
                .add(new ConcurrentWebSocketSessionDecorator(session, 5000, 65536));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String username = authUser(session);
        if (username == null) {
            return;
        }
        var active = sessions.get(username);
        if (active != null) {
            active.removeIf(registered -> Objects.equals(registered.getId(), session.getId()));
            if (active.isEmpty()) {
                sessions.remove(username, active);
            }
        }
    }

    /** 注销、封禁或密码重置提交后，在所有实例关闭该用户连接。 */
    public void disconnect(String username) {
        afterCommit(() -> {
            disconnectLocal(username);
            publishEvent("disconnect", username, null);
        });
    }

    @Transactional
    public void notify(User recipient, User actor, Notification.Kind kind, String text, Long postId, Long commentId) {
        if (recipient == null || actor == null || recipient.id.equals(actor.id)) {
            return;
        }
        Notification notification = new Notification();
        notification.user = recipient;
        notification.actor = actor;
        notification.kind = kind;
        notification.text = text;
        notification.postId = postId;
        notification.commentId = commentId;
        notifications.save(notification);
        NotificationTask task = new NotificationTask();
        task.notificationId = notification.id;
        tasks.save(task);
    }

    /** Reuse the persisted notification; retries never create another notification or unread entry. */
    public void deliver(long notificationId) {
        Notification notification = notifications.findById(notificationId).orElse(null);
        if (notification == null || notification.user.deleted) return;
        try {
            // Include current read state and ISO date strings for retries and the browser.
            dispatch(notification.user.username, MAPPER.writeValueAsString(NotificationView.of(notification)));
        } catch (Exception e) {
            throw new IllegalStateException("Notification dispatch failed", e);
        }
    }

    @Transactional
    public void notifyMentions(String content, User actor, Long postId, Long commentId) {
        var matcher =
                java.util.regex.Pattern.compile("@([\\w\\u4e00-\\u9fa5]{2,40})").matcher(content);
        var seen = new HashSet<String>();
        while (matcher.find()) {
            String username = matcher.group(1);
            if (seen.add(username)) {
                users.findByUsername(username)
                        .ifPresent(
                                user -> notify(user, actor, Notification.Kind.MENTION, "在帖子中提到了你", postId, commentId));
            }
        }
    }

    void onRedisMessage(Message message, byte[] pattern) {
        try {
            var event = MAPPER.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            if (instanceId.equals(event.path("source").asText())) {
                return;
            }
            String type = event.path("type").asText();
            String username = event.path("user").asText(null);
            if (username == null || username.isBlank()) {
                throw new IllegalArgumentException("missing user");
            }
            switch (type) {
                case "notification" -> {
                    String payload = event.path("payload").asText(null);
                    if (payload == null) {
                        throw new IllegalArgumentException("missing payload");
                    }
                    sendWs(username, payload);
                }
                case "disconnect" -> disconnectLocal(username);
                default -> throw new IllegalArgumentException("unknown event type");
            }
            recordPubSub(type, "received");
        } catch (Exception e) {
            log.warn("WebSocket Redis 事件解析失败: {}", e.getMessage());
            recordPubSub("invalid", "rejected");
        }
    }

    String instanceId() {
        return instanceId;
    }

    private void dispatch(String username, String payload) {
        sendWs(username, payload);
        if (!publishEvent("notification", username, payload))
            throw new IllegalStateException("Notification broadcast unavailable");
    }

    private boolean publishEvent(String type, String username, String payload) {
        if (!redisEnabled) {
            return true;
        }
        try {
            var event = MAPPER.createObjectNode();
            event.put("type", type);
            event.put("source", instanceId);
            event.put("user", username);
            if (payload == null) event.putNull("payload");
            else event.put("payload", payload);
            boolean published = cache.publish(WS_CHANNEL, MAPPER.writeValueAsString(event));
            recordPubSub(type, published ? "published" : "dropped");
            return published;
        } catch (Exception e) {
            log.warn("WebSocket Redis 事件序列化失败 type={} user={}", type, username, e);
            return false;
        }
    }

    private void sendWs(String username, String payload) {
        var active = sessions.get(username);
        if (active == null) {
            return;
        }
        for (var session : active) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(payload));
                } catch (Exception e) {
                    log.warn("WebSocket 发送失败 user={}", username, e);
                }
            }
        }
    }

    private void disconnectLocal(String username) {
        var active = sessions.remove(username);
        if (active == null) {
            return;
        }
        for (var session : active) {
            if (session.isOpen()) {
                try {
                    session.close(CloseStatus.POLICY_VIOLATION);
                } catch (Exception e) {
                    log.warn("WebSocket 关闭失败 user={}", username, e);
                }
            }
        }
    }

    private void recordPubSub(String type, String result) {
        if (meterRegistry != null) {
            meterRegistry
                    .counter("tbforum.ws.pubsub.events", "type", type, "result", result)
                    .increment();
        }
    }

    private static void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    private static String authUser(WebSocketSession session) {
        Object username = session.getAttributes().get("ws-user");
        return username == null ? null : username.toString();
    }
}
