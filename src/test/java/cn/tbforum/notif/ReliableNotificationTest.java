package cn.tbforum.notif;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.tbforum.config.AppProperties;
import cn.tbforum.domain.*;
import cn.tbforum.repo.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        properties = "spring.datasource.url=jdbc:h2:mem:reliable_notifications;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class ReliableNotificationTest {
    @Autowired
    NotificationTaskStore store;

    @Autowired
    NotificationTaskRepository tasks;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    UserRepository users;

    @Autowired
    NotificationProperties properties;

    @Autowired
    PlatformTransactionManager manager;

    @Autowired
    JdbcTemplate jdbc;

    @SpyBean
    NotificationService service;

    User alice, bob;

    @BeforeEach
    void setup() {
        tasks.deleteAll();
        notifications.deleteAll();
        alice = user("a");
        bob = user("b");
    }

    User user(String prefix) {
        User user = new User();
        user.username = prefix + UUID.randomUUID().toString().substring(0, 12);
        user.passwordHash = "test-unused";
        return users.save(user);
    }

    NotificationTask enqueue() {
        service.notify(alice, bob, Notification.Kind.FOLLOW, "test", null, null);
        return tasks.findAll().get(tasks.findAll().size() - 1);
    }

    @Test
    void businessNotificationAndTaskRollbackTogetherAndNoNetworkBeforeCommit() {
        long before = users.count();
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            user("rollback");
            enqueue();
            assertThat(tasks.count()).isEqualTo(1);
            tx.setRollbackOnly();
        });
        assertThat(users.count()).isEqualTo(before);
        assertThat(notifications.count()).isZero();
        assertThat(tasks.count()).isZero();
        verify(service, never()).deliver(anyLong());
    }

    @Test
    void competingInstancesClaimOnlyOnceAndExpiredOwnerCannotCompleteNewLease() throws Exception {
        var task = enqueue();
        var pool = Executors.newFixedThreadPool(12);
        var gate = new CountDownLatch(1);
        try {
            List<Future<NotificationTaskStore.Claim>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++)
                results.add(pool.submit(() -> {
                    gate.await();
                    return store.claimDue(task.id, Instant.now());
                }));
            gate.countDown();
            List<NotificationTaskStore.Claim> winners = new ArrayList<>();
            for (var result : results) {
                var claim = result.get(10, TimeUnit.SECONDS);
                if (claim != null) winners.add(claim);
            }
            assertThat(winners).hasSize(1);
            var first = winners.get(0);
            var recovered = store.claimDue(
                    task.id, Instant.now().plus(properties.getLease()).plusSeconds(1));
            assertThat(recovered).isNotNull();
            assertThat(recovered.attempts()).isEqualTo(2);
            assertThat(store.sent(first, Instant.now())).isFalse();
            assertThat(store.sent(recovered, Instant.now())).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void transientFailureBacksOffExhaustsAndCanBeReplayedWithoutAnotherNotification() {
        var task = enqueue();
        Instant now = Instant.now();
        for (int i = 1; i <= properties.getMaxAttempts(); i++) {
            var claim = store.claimDue(task.id, now);
            assertThat(claim).isNotNull();
            store.failed(claim, now, new IllegalStateException("secret-must-not-be-stored"), false);
            assertThat(store.claimDue(task.id, now)).isNull();
            now = now.plus(properties.getRetryMax()).plusSeconds(1);
        }
        assertThat(tasks.findById(task.id).orElseThrow().status).isEqualTo(NotificationTask.Status.DEAD);
        assertThat(tasks.findById(task.id).orElseThrow().lastError).doesNotContain("secret");
        assertThat(store.replay(task.id, Instant.now())).isTrue();
        assertThat(store.replay(task.id, Instant.now())).isFalse();
        var retry = store.claimDue(task.id, Instant.now());
        assertThat(retry.attempts()).isEqualTo(1);
        assertThat(notifications.count()).isEqualTo(1);
    }

    @Test
    void queuedBeforePublishCrashAndMissingBrokerRecordAreRecoveredFromDatabase() {
        var task = enqueue();
        Instant now = Instant.now();
        var claim = store.claimDue(task.id, now);
        assertThat(store.queued(claim, now)).isTrue();
        assertThat(store.claimDue(task.id, now)).isNull();
        var recovered =
                store.claimDue(task.id, now.plus(properties.getQueueTimeout()).plusSeconds(1));
        assertThat(recovered).isNotNull();
        assertThat(recovered.attempts()).isEqualTo(2);
    }

    @Test
    void slowCompletionLockIsSkippedWithoutBlockingOtherWork() throws Exception {
        var task = enqueue();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                jdbc.queryForList("select id from notification_tasks where id=? for update", String.class, task.id);
                locked.countDown();
                try {
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(pool.submit(() -> store.claimDue(task.id, Instant.now())).get(1, TimeUnit.SECONDS))
                    .isNull();
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertThat(store.claimDue(task.id, Instant.now())).isNotNull();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void retentionDeletesOnlyOldSuccessfulDeliveryBookkeeping() {
        var first = enqueue();
        var claim = store.claimDue(first.id, Instant.now());
        store.sent(
                claim, Instant.now().minus(properties.getCompletedRetention()).minusSeconds(60));
        service.notify(alice, bob, Notification.Kind.REPLY, "must keep", null, null);
        assertThat(store.purgeCompleted(Instant.now())).isEqualTo(1);
        assertThat(tasks.count()).isEqualTo(1);
        assertThat(notifications.count()).isEqualTo(2);
        assertThat(tasks.findAll().get(0).status).isEqualTo(NotificationTask.Status.READY);
    }

    @Test
    void delayedPublisherFailureCannotUndoConsumerOrNewOwner() {
        var task = enqueue();
        Instant now = Instant.now();
        var publisher = store.claimDue(task.id, now);
        store.queued(publisher, now);
        var consumer = store.claimQueued(task.id, now);
        store.failed(publisher, now, new RuntimeException(), true);
        assertThat(store.sent(consumer, now)).isTrue();
        store.failed(publisher, now, new RuntimeException(), true);
        assertThat(tasks.findById(task.id).orElseThrow().status).isEqualTo(NotificationTask.Status.SENT);
    }

    NotificationWorker worker(NotificationStream stream) {
        var app = new AppProperties();
        app.getRedis().setEnabled(true);
        return new NotificationWorker(store, service, stream, properties, app, new SimpleMeterRegistry());
    }

    @Test
    void brokerOutageKeepsCommittedNotificationAndSchedulesRetry() {
        var task = enqueue();
        var stream = mock(NotificationStream.class);
        doThrow(new IllegalStateException("offline")).when(stream).publish(anyString());
        worker(stream).relay();
        var saved = tasks.findById(task.id).orElseThrow();
        assertThat(saved.status).isEqualTo(NotificationTask.Status.READY);
        assertThat(saved.attempts).isEqualTo(1);
        assertThat(notifications.count()).isEqualTo(1);
    }

    @Test
    void duplicateBrokerRecordsDispatchOnceAndDoNotIncreaseUnreadCount() {
        var task = enqueue();
        var stream = mock(NotificationStream.class);
        var worker = worker(stream);
        worker.relay();
        var first = StreamRecords.string(Map.of("taskId", task.id))
                .withStreamKey("test")
                .withId(RecordId.of("1-0"));
        var duplicate = StreamRecords.string(Map.of("taskId", task.id))
                .withStreamKey("test")
                .withId(RecordId.of("1-1"));
        when(stream.read(anyString())).thenReturn(List.of(first, duplicate));
        when(stream.reclaim(anyString())).thenReturn(List.of());
        worker.consume();
        verify(service, times(1)).deliver(task.notificationId);
        verify(stream).acknowledge(first.getId());
        verify(stream).acknowledge(duplicate.getId());
        assertThat(tasks.findById(task.id).orElseThrow().status).isEqualTo(NotificationTask.Status.SENT);
        assertThat(notifications.countByUserAndReadFalse(alice)).isEqualTo(1);
    }

    @Test
    void dispatchFailureRemainsRecoverableAfterBrokerAcknowledgement() {
        var task = enqueue();
        var stream = mock(NotificationStream.class);
        var worker = worker(stream);
        worker.relay();
        var record = StreamRecords.string(Map.of("taskId", task.id))
                .withStreamKey("test")
                .withId(RecordId.of("2-0"));
        when(stream.read(anyString())).thenReturn(List.of(record));
        when(stream.reclaim(anyString())).thenReturn(List.of());
        doThrow(new IllegalStateException()).when(service).deliver(task.notificationId);
        worker.consume();
        verify(stream).acknowledge(record.getId());
        assertThat(tasks.findById(task.id).orElseThrow().status).isEqualTo(NotificationTask.Status.READY);
    }
}
