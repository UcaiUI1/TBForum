package cn.tbforum.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

import cn.tbforum.domain.*;
import cn.tbforum.dto.Dtos.CommentInput;
import cn.tbforum.repo.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        properties =
                "spring.datasource.url=jdbc:h2:mem:comment_concurrency;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000")
@ActiveProfiles("test")
class CommentConcurrencyTest {
    @Autowired
    CommentService service;

    @Autowired
    PostRepository posts;

    @Autowired
    CommentRepository comments;

    @Autowired
    UserRepository users;

    @Autowired
    ForumRepository forums;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager manager;

    @MockBean
    UserService identity;

    Post post;
    User actor;

    @BeforeEach
    void fixture() {
        String key = UUID.randomUUID().toString().substring(0, 12);
        User owner = new User();
        owner.username = "owner_" + key;
        owner.passwordHash = "unused";
        users.save(owner);
        actor = new User();
        actor.username = "actor_" + key;
        actor.passwordHash = "unused";
        users.save(actor);
        when(identity.current()).thenReturn(actor);
        Forum forum = new Forum();
        forum.slug = "comment-" + key;
        forum.name = "Concurrent comments";
        forum.owner = owner;
        forums.save(forum);
        post = new Post();
        post.forum = forum;
        post.author = owner;
        post.title = "counter";
        post.content = "fixture";
        posts.save(post);
    }

    long create(String text) {
        return service.createComment(post.id, new CommentInput(text, null))
                .getBody()
                .id();
    }

    void simultaneously(int count, IntConsumer operation) throws Exception {
        var pool = Executors.newFixedThreadPool(count);
        var gate = new CountDownLatch(1);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                results.add(pool.submit(() -> {
                    gate.await();
                    operation.accept(index);
                    return null;
                }));
            }
            gate.countDown();
            for (var result : results) result.get(30, TimeUnit.SECONDS);
        } finally {
            gate.countDown();
            pool.shutdownNow();
        }
    }

    void assertCount(long expected) {
        assertThat(posts.findById(post.id).orElseThrow().commentCount).isEqualTo(expected);
        assertThat(jdbc.queryForObject(
                        "select count(*) from comments where post_id=? and deleted=false", Long.class, post.id))
                .isEqualTo(expected);
    }

    @Test
    void concurrentCreatesPreserveCounterAndExactlyOneTaskPerNotification() throws Exception {
        simultaneously(24, i -> create("comment " + i));
        assertCount(24);
        assertThat(jdbc.queryForObject(
                        "select count(*) from notification_tasks t join notifications n on n.id=t.notification_id where n.post_id=?",
                        Long.class,
                        post.id))
                .isEqualTo(24);
    }

    @Test
    void concurrentRepeatedDeletionDecrementsAndAuditsOnlyOnce() throws Exception {
        long id = create("delete once");
        simultaneously(16, i -> service.deleteComment(post.id, id));
        assertCount(0);
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_logs where action='DELETE_COMMENT' and target_id=?",
                        Long.class,
                        id))
                .isEqualTo(1);
    }

    @Test
    void concurrentCreateAndDeleteUseDatabaseCounterInBothDirections() throws Exception {
        List<Long> existing = new ArrayList<>();
        for (int i = 0; i < 10; i++) existing.add(create("old " + i));
        simultaneously(30, i -> {
            if (i < 10) service.deleteComment(post.id, existing.get(i));
            else create("new " + i);
        });
        assertCount(20);
    }

    @Test
    void stalePostEditCannotOverwriteACommittedCommentCounter() throws Exception {
        var read = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var edit = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                Post stale = posts.findById(post.id).orElseThrow();
                read.countDown();
                try {
                    assertThat(proceed.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                stale.title = "edited while a comment was created";
                posts.save(stale);
            }));
            assertThat(read.await(5, TimeUnit.SECONDS)).isTrue();
            create("commits before stale edit");
            proceed.countDown();
            edit.get(10, TimeUnit.SECONDS);
            assertCount(1);
        } finally {
            proceed.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void staleCommentEditCannotResurrectASoftDeletedRow() throws Exception {
        long id = create("delete while editing");
        var read = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var edit = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                Comment stale = comments.findById(id).orElseThrow();
                read.countDown();
                try {
                    assertThat(proceed.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                stale.content = "late edit";
                stale.edited = true;
                comments.save(stale);
            }));
            assertThat(read.await(5, TimeUnit.SECONDS)).isTrue();
            service.deleteComment(post.id, id);
            proceed.countDown();
            edit.get(10, TimeUnit.SECONDS);
            assertThat(comments.findById(id).orElseThrow().deleted).isTrue();
            assertCount(0);
        } finally {
            proceed.countDown();
            pool.shutdownNow();
        }
    }
}
