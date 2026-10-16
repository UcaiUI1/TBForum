package cn.tbforum.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.tbforum.domain.Forum;
import cn.tbforum.domain.User;
import cn.tbforum.dto.Dtos.PostInput;
import cn.tbforum.dto.Dtos.PostView;
import cn.tbforum.notif.NotificationService;
import cn.tbforum.repo.ForumRepository;
import cn.tbforum.repo.PostRepository;
import cn.tbforum.repo.TagRepository;
import cn.tbforum.repo.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        properties =
                "spring.datasource.url=jdbc:h2:mem:tag_concurrency;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000")
@ActiveProfiles("test")
class TagConcurrencyTest {
    @Autowired
    PostService service;

    @Autowired
    UserRepository users;

    @Autowired
    ForumRepository forums;

    @Autowired
    TagRepository tags;

    @Autowired
    PostRepository posts;

    @Autowired
    JdbcTemplate jdbc;

    @MockBean
    UserService userService;

    @MockBean
    NotificationService notifications;

    @MockBean
    ManagedUploadService uploads;

    String slug;

    @BeforeEach
    void fixture() {
        String key = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        User user = new User();
        user.username = "tag_" + key;
        user.passwordHash = "unused-test-password-hash";
        users.save(user);
        Forum forum = new Forum();
        forum.slug = "tag-" + key;
        forum.name = "Concurrent tags";
        forum.owner = user;
        forums.save(forum);
        slug = forum.slug;
        when(userService.current()).thenReturn(user);
    }

    private List<PostView> simultaneousPosts(int count, IntFunction<String> content) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<PostView>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return service.createPost(slug, new PostInput("post " + index, content.apply(index)))
                            .getBody();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<PostView> results = new ArrayList<>();
            for (Future<PostView> result : futures) results.add(result.get(30, TimeUnit.SECONDS));
            return results;
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void simultaneousNewTagCommitsEveryPostAndCountsExactlyOnce() throws Exception {
        String name = "shared" + slug.substring(4);
        List<PostView> results = simultaneousPosts(12, i -> "content #" + name + " #" + name);
        assertEquals(12, results.stream().map(PostView::id).distinct().count());
        assertEquals(1L, jdbc.queryForObject("select count(*) from tags where name=?", Long.class, name));
        assertEquals(12, tags.findByName(name).orElseThrow().postCount);
        assertEquals(
                12L,
                jdbc.queryForObject(
                        "select count(*) from post_tags pt join tags t on pt.tag_id=t.id where t.name=?",
                        Long.class,
                        name));
    }

    @Test
    void oppositeTagOrderDoesNotDeadlockAndKeepsDisplayOrder() throws Exception {
        String a = "aaa" + slug.substring(4);
        String b = "bbb" + slug.substring(4);
        List<PostView> results = simultaneousPosts(8, i -> i % 2 == 0 ? "#" + a + " #" + b : "#" + b + " #" + a);
        assertEquals(8, tags.findByName(a).orElseThrow().postCount);
        assertEquals(8, tags.findByName(b).orElseThrow().postCount);
        for (int i = 0; i < results.size(); i++)
            assertEquals(
                    i % 2 == 0 ? List.of(a, b) : List.of(b, a), results.get(i).tags());
    }

    @Test
    void failedPostRollsBackNewTagAndItsCounterWithThePost() {
        String name = "rollback" + slug.substring(4);
        long before = posts.count();
        doThrow(new IllegalStateException("failure after tag creation"))
                .when(notifications)
                .notifyMentions(anyString(), any(User.class), anyLong(), isNull());
        assertThrows(
                IllegalStateException.class, () -> service.createPost(slug, new PostInput("rollback", "#" + name)));
        assertEquals(before, posts.count());
        assertTrue(tags.findByName(name).isEmpty());
    }

    @Test
    void editingTagsPreservesExistingRowsAndAdjustsCounts() {
        String a = "old" + slug.substring(4);
        String b = "new" + slug.substring(4);
        PostView created =
                service.createPost(slug, new PostInput("edit", "#" + a)).getBody();
        Long oldId = tags.findByName(a).orElseThrow().id;
        service.editPost(created.id(), new PostInput("edit", "#" + b));
        assertEquals(oldId, tags.findByName(a).orElseThrow().id);
        assertEquals(0, tags.findByName(a).orElseThrow().postCount);
        assertEquals(1, tags.findByName(b).orElseThrow().postCount);
    }
}
