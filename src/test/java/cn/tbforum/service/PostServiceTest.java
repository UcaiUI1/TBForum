package cn.tbforum.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.tbforum.cache.CacheService;
import cn.tbforum.config.AppProperties;
import cn.tbforum.domain.Forum;
import cn.tbforum.domain.Post;
import cn.tbforum.domain.Tag;
import cn.tbforum.domain.User;
import cn.tbforum.dto.Dtos.PostInput;
import cn.tbforum.dto.Dtos.PostView;
import cn.tbforum.notif.NotificationService;
import cn.tbforum.repo.FavoriteRepository;
import cn.tbforum.repo.InteractionRepository;
import cn.tbforum.repo.PostRepository;
import cn.tbforum.repo.PostTagRepository;
import cn.tbforum.repo.ReportRepository;
import cn.tbforum.repo.TagRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    @Mock
    PostRepository posts;

    @Mock
    PostTagRepository postTags;

    @Mock
    TagRepository tags;

    @Mock
    FavoriteRepository favorites;

    @Mock
    InteractionRepository interactions;

    @Mock
    ReportRepository reports;

    @Mock
    AuditService audit;

    @Mock
    CacheService cache;

    @Mock
    UserService userService;

    @Mock
    ForumService forumService;

    @Mock
    NotificationService notif;

    @Mock
    ManagedUploadService uploads;

    PostService service;

    @BeforeEach
    void setUp() {
        service = new PostService(
                posts,
                postTags,
                tags,
                favorites,
                interactions,
                reports,
                audit,
                cache,
                new ObjectMapper().findAndRegisterModules(),
                userService,
                forumService,
                notif,
                uploads,
                new AppProperties(),
                null);
    }

    private static User user(long id, String name) {
        User u = new User();
        u.id = id;
        u.username = name;
        return u;
    }

    private static Forum forum(long id) {
        Forum f = new Forum();
        f.id = id;
        f.slug = "slug";
        return f;
    }

    private static Post post(long id, Instant createdAt, long upvotes, long comments) {
        Post p = new Post();
        p.id = id;
        p.forum = forum(1L);
        p.author = user(1L, "alice");
        p.createdAt = createdAt;
        p.upvotes = upvotes;
        p.commentCount = comments;
        return p;
    }

    @Test
    void createPost_parsesTags_notifiesMentions_andEvictsCache() {
        User alice = user(1L, "alice");
        when(userService.current()).thenReturn(alice);
        when(forumService.getForum("slug")).thenReturn(forum(1L));
        when(posts.save(any(Post.class))).thenAnswer(inv -> {
            Post p = inv.getArgument(0);
            p.id = 10L;
            return p;
        });
        Tag tag = new Tag();
        tag.id = 5L;
        tag.name = "tag";
        when(tags.findByNameForUpdate("tag")).thenReturn(Optional.empty(), Optional.of(tag));

        ResponseEntity<PostView> resp = service.createPost("slug", new PostInput("title", "content #tag"));

        assertEquals(201, resp.getStatusCode().value());
        assertEquals(10L, resp.getBody().id());
        verify(tags).insertIfAbsent("tag");
        verify(tags).incrementPostCount(5L, 1);
        verify(notif).notifyMentions(eq("content #tag"), eq(alice), eq(10L), isNull());
        verify(uploads).syncPostImages(alice, 10L, "content #tag");
        verify(cache).evictByPrefix("feed:");
    }

    @Test
    void editPost_byNonAuthor_isForbidden() {
        User alice = user(1L, "alice");
        User bob = user(2L, "bob");
        Post p = post(10L, Instant.now(), 0, 0);
        p.author = alice;
        p.forum = forum(1L);
        when(posts.findById(10L)).thenReturn(Optional.of(p));
        when(userService.current()).thenReturn(bob);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class, () -> service.editPost(10L, new PostInput("new title", "new content")));

        assertEquals(HttpStatus.FORBIDDEN.value(), ex.getStatusCode().value());
        verify(posts, never()).save(any());
    }

    @Test
    void hotFeed_ordersByDecayedScore_notRawUpvotes() {
        Instant now = Instant.now();
        Post oldHigh = post(1L, now.minusSeconds(50 * 3600), 1000, 0);
        Post mid = post(2L, now.minusSeconds(10 * 3600), 300, 0);
        Post fresh = post(3L, now.minusSeconds(3600), 100, 0);
        when(posts.findHotCandidates(any(), any())).thenReturn(List.of(oldHigh, mid, fresh));

        Page<PostView> page = service.feed("hot", 0, 10, null);

        List<Long> ids = page.getContent().stream().map(PostView::id).toList();
        // 虽然老帖点赞最多，但时间衰减后排名应低于新帖
        assertEquals(List.of(3L, 2L, 1L), ids);
    }

    @Test
    void cachedHomepageNeverSharesViewerStateAcrossUsers() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        Post post = post(10L, Instant.now(), 0, 0);
        PostView common = PostView.of(post);
        String cached = mapper.writeValueAsString(java.util.Map.of("content", List.of(common), "total", 1));
        when(cache.getOrLoadFeed(eq("feed:latest:0:20"), any(), any())).thenReturn(cached);
        User alice = user(1L, "alice");
        User bob = user(2L, "bob");
        when(userService.currentOrNull()).thenReturn(alice, bob, null);
        when(favorites.findPostIdsByUserIdAndPostIdIn(1L, List.of(10L))).thenReturn(List.of(10L));
        when(favorites.findPostIdsByUserIdAndPostIdIn(2L, List.of(10L))).thenReturn(List.of());
        when(interactions.findPostIdsByUserIdAndPostIdInAndKind(
                        1L, List.of(10L), cn.tbforum.domain.Interaction.Kind.UP))
                .thenReturn(List.of());
        when(interactions.findPostIdsByUserIdAndPostIdInAndKind(
                        2L, List.of(10L), cn.tbforum.domain.Interaction.Kind.UP))
                .thenReturn(List.of(10L));

        var aliceView = service.feed("latest", 0, 20, null).getContent().get(0);
        var bobView = service.feed("latest", 0, 20, null).getContent().get(0);
        var anonymous = service.feed("latest", 0, 20, null).getContent().get(0);

        org.junit.jupiter.api.Assertions.assertTrue(aliceView.favorited());
        org.junit.jupiter.api.Assertions.assertFalse(aliceView.upvoted());
        org.junit.jupiter.api.Assertions.assertFalse(bobView.favorited());
        org.junit.jupiter.api.Assertions.assertTrue(bobView.upvoted());
        org.junit.jupiter.api.Assertions.assertFalse(anonymous.favorited());
        org.junit.jupiter.api.Assertions.assertFalse(anonymous.upvoted());
        verify(favorites, never()).existsByUserAndPost(any(), any());
        verify(interactions, never()).findByUserAndPost(any(), any());
        verify(posts, never()).findAllByDeletedFalseOrderByCreatedAtDesc(any());
    }
}
