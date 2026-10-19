package cn.tbforum.notif;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.tbforum.cache.CacheService;
import cn.tbforum.config.AppProperties;
import cn.tbforum.domain.Notification;
import cn.tbforum.domain.User;
import cn.tbforum.repo.NotificationRepository;
import cn.tbforum.repo.NotificationTaskRepository;
import cn.tbforum.repo.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {
    @Mock
    NotificationRepository notifications;

    @Mock
    NotificationTaskRepository tasks;

    @Mock
    UserRepository users;

    @Mock
    CacheService cache;

    @Mock
    RedisMessageListenerContainer container;

    @Mock
    WebSocketSession session;

    private NotificationService service;
    private MessageListener listener;

    @BeforeEach
    void setUp() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getRedis().setEnabled(true);
        service = new NotificationService(notifications, tasks, users, cache, container, null, properties);

        ArgumentCaptor<MessageListener> listenerCaptor = ArgumentCaptor.forClass(MessageListener.class);
        verify(container)
                .addMessageListener(listenerCaptor.capture(), eq(new ChannelTopic(NotificationService.WS_CHANNEL)));
        listener = listenerCaptor.getValue();

        when(session.getAttributes()).thenReturn(Map.of("ws-user", "alice"));
        lenient().when(session.isOpen()).thenReturn(true);
        service.afterConnectionEstablished(session);
    }

    @Test
    void notificationIsSentLocallyPublishedAndSelfEchoIgnored() throws Exception {
        User alice = user(1L, "alice");
        User bob = user(2L, "bob");
        when(notifications.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification notification = invocation.getArgument(0);
            notification.id = 9L;
            return notification;
        });
        when(cache.publish(eq(NotificationService.WS_CHANNEL), any())).thenReturn(true);

        service.notify(alice, bob, Notification.Kind.FOLLOW, "关注了你", null, null);

        verify(tasks).save(argThat(task -> task.notificationId.equals(9L)));
        verify(session, never()).sendMessage(any());
        Notification persisted = new Notification();
        persisted.id = 9L;
        persisted.user = alice;
        persisted.actor = bob;
        persisted.kind = Notification.Kind.FOLLOW;
        persisted.text = "关注了你";
        when(notifications.findById(9L)).thenReturn(java.util.Optional.of(persisted));
        service.deliver(9L);

        verify(session)
                .sendMessage(argThat(message ->
                        message instanceof TextMessage text && text.getPayload().contains("FOLLOW")));
        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        verify(cache).publish(eq(NotificationService.WS_CHANNEL), eventCaptor.capture());
        assertTrue(eventCaptor.getValue().contains("\"type\":\"notification\""));

        listener.onMessage(message(eventCaptor.getValue()), null);
        verify(session, org.mockito.Mockito.times(1)).sendMessage(any(TextMessage.class));
    }

    @Test
    void remoteNotificationIsDeliveredToLocalSession() throws Exception {
        String event = "{\"type\":\"notification\",\"source\":\"other-instance\","
                + "\"user\":\"alice\",\"payload\":\"hello\"}";

        listener.onMessage(message(event), null);

        verify(session)
                .sendMessage(
                        argThat(message -> message instanceof TextMessage text && "hello".equals(text.getPayload())));
    }

    @Test
    void remoteDisconnectClosesLocalSession() throws Exception {
        String event =
                "{\"type\":\"disconnect\",\"source\":\"other-instance\"," + "\"user\":\"alice\",\"payload\":null}";

        listener.onMessage(message(event), null);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void localDisconnectClosesAndPublishesAcrossInstances() throws Exception {
        when(cache.publish(eq(NotificationService.WS_CHANNEL), any())).thenReturn(true);

        service.disconnect("alice");

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(cache)
                .publish(
                        eq(NotificationService.WS_CHANNEL),
                        argThat(event ->
                                event.contains("\"type\":\"disconnect\"") && event.contains("\"user\":\"alice\"")));
    }

    @Test
    void disabledRedisDoesNotRegisterOrPublish() {
        clearInvocations(container, cache);
        AppProperties properties = new AppProperties();
        NotificationService disabled =
                new NotificationService(notifications, tasks, users, cache, container, null, properties);

        disabled.disconnect("alice");

        verify(container, never()).addMessageListener(any(MessageListener.class), any(ChannelTopic.class));
        verify(cache, never()).publish(any(), any());
    }

    private static User user(long id, String username) {
        User user = new User();
        user.id = id;
        user.username = username;
        return user;
    }

    private static Message message(String json) {
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(json.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
