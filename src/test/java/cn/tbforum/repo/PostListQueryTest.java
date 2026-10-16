package cn.tbforum.repo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.tbforum.cache.CacheService;
import cn.tbforum.config.AppProperties;
import cn.tbforum.domain.*;
import cn.tbforum.dto.Dtos.PostView;
import cn.tbforum.notif.NotificationService;
import cn.tbforum.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest(properties = {"spring.jpa.properties.hibernate.generate_statistics=true", "spring.jpa.show-sql=false"})
@ActiveProfiles("test")
class PostListQueryTest {
    @Autowired
    EntityManager em;

    @Autowired
    PostRepository posts;

    @Autowired
    PostTagRepository tags;

    @Autowired
    TagRepository tagRepository;

    @Autowired
    FavoriteRepository favorites;

    @Autowired
    InteractionRepository interactions;

    @Autowired
    ReportRepository reports;

    User viewer;
    List<Long> ids;
    Statistics statistics;
    UserService users;
    PostService service;

    @BeforeEach
    void seed() {
        User owner = user("owner");
        viewer = user("viewer");
        Forum forum = new Forum();
        forum.owner = owner;
        forum.slug = "list-query-test";
        forum.name = "List query test";
        em.persist(forum);
        Tag first = new Tag();
        first.name = "first";
        em.persist(first);
        Tag second = new Tag();
        second.name = "second";
        em.persist(second);
        ids = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < 40; i++) {
            Post post = new Post();
            post.author = owner;
            post.forum = forum;
            post.title = "java " + i;
            post.content = "body";
            post.createdAt = now.minusSeconds(i);
            em.persist(post);
            ids.add(post.id);
            for (Tag tag : List.of(first, second)) {
                PostTag link = new PostTag();
                link.post = post;
                link.tag = tag;
                em.persist(link);
            }
            if (i == 20) {
                Favorite favorite = new Favorite();
                favorite.user = viewer;
                favorite.post = post;
                em.persist(favorite);
            }
            if (i == 21 || i == 22) {
                Interaction interaction = new Interaction();
                interaction.user = viewer;
                interaction.post = post;
                interaction.kind = i == 21 ? Interaction.Kind.UP : Interaction.Kind.DOWN;
                em.persist(interaction);
            }
        }
        em.flush();
        em.clear();
        statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        users = mock(UserService.class);
        service = new PostService(
                posts,
                tags,
                tagRepository,
                favorites,
                interactions,
                reports,
                mock(AuditService.class),
                mock(CacheService.class),
                new ObjectMapper().findAndRegisterModules(),
                users,
                mock(ForumService.class),
                mock(NotificationService.class),
                mock(ManagedUploadService.class),
                new AppProperties(),
                null);
    }

    private User user(String name) {
        User user = new User();
        user.username = name;
        user.passwordHash = "not-for-login";
        em.persist(user);
        return user;
    }

    @Test
    void anonymousPageLoadsAssociationsAndTagsInThreeQueries() {
        var result = service.feed("latest", 1, 20, null);
        assertEquals(
                ids.subList(20, 40),
                result.getContent().stream().map(PostView::id).toList());
        assertEquals(40, result.getTotalElements());
        for (PostView post : result) {
            assertEquals("owner", post.author());
            assertEquals("list-query-test", post.forum());
            assertEquals(List.of("first", "second"), post.tags());
            assertFalse(post.upvoted());
            assertFalse(post.favorited());
        }
        assertEquals(3, statistics.getPrepareStatementCount());
    }

    @Test
    void authenticatedPageAddsOnlyTwoQueriesAndPreservesPerPostState() {
        when(users.currentOrNull()).thenReturn(viewer);
        var result = service.feed("latest", 1, 20, null);
        assertTrue(result.getContent().get(0).favorited());
        assertTrue(result.getContent().get(1).upvoted());
        assertFalse(result.getContent().get(2).upvoted());
        assertEquals(1, result.stream().filter(PostView::favorited).count());
        assertEquals(1, result.stream().filter(PostView::upvoted).count());
        assertEquals(5, statistics.getPrepareStatementCount());
    }

    @Test
    void searchAndTagAndFavoritePagesPreserveTotalsAndAssociations() {
        var search = service.search("java", 1, 20);
        assertEquals(40, search.getTotalElements());
        assertEquals(20, search.getContent().size());
        var tagPage = service.postsByTag("first", 1, 20);
        assertEquals(40, tagPage.getTotalElements());
        assertEquals(20, tagPage.getContent().size());
        when(users.current()).thenReturn(viewer);
        var favoritePage = service.myFavorites(0, 20);
        assertEquals(1, favoritePage.getTotalElements());
        assertEquals(ids.get(20), favoritePage.getContent().get(0).id());
        assertTrue(favoritePage.getContent().get(0).favorited());
        assertEquals("owner", favoritePage.getContent().get(0).author());
    }
}
