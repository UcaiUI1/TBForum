package cn.tbforum.startup;

import static org.junit.jupiter.api.Assertions.*;

import cn.tbforum.domain.Forum;
import cn.tbforum.domain.Post;
import cn.tbforum.domain.User;
import cn.tbforum.repo.ForumRepository;
import cn.tbforum.repo.PostRepository;
import cn.tbforum.repo.UserRepository;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.availability.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:cold_start;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "app.startup.enabled=true",
            "app.startup.rounds=2",
            "app.upload.legacy-reconciliation-enabled=false",
            "app.storage.local.dir=${java.io.tmpdir}/tbforum-cold-start-test-uploads"
        })
@ActiveProfiles("test")
class ColdStartIntegrationTest {
    @LocalServerPort
    int port;

    @Autowired
    Observation observation;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StartupState state;

    @Autowired
    ApplicationContext context;

    static class Observation {
        Map<String, Long> before;
        List<Integer> earlyBusinessStatuses;
        int earlyLiveness;
        int earlyReadiness;
        Long postId;
    }

    static Map<String, Long> counts(JdbcTemplate jdbc) {
        Map<String, Long> result = new TreeMap<>();
        for (String table : List.of(
                "users",
                "forums",
                "posts",
                "comments",
                "tags",
                "post_tags",
                "notifications",
                "uploaded_files",
                "interactions",
                "favorites")) {
            result.put(table, jdbc.queryForObject("select count(*) from " + table, Long.class));
        }
        result.put("views", jdbc.queryForObject("select sum(views) from posts", Long.class));
        return result;
    }

    static int status(HttpClient client, String base, String path) throws Exception {
        return client.send(
                        HttpRequest.newBuilder(URI.create(base + path))
                                .timeout(Duration.ofSeconds(5))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    @TestConfiguration
    static class BeforeWarmup {
        interface OrderedRunner extends ApplicationRunner, Ordered {
            @Override
            default int getOrder() {
                return Ordered.HIGHEST_PRECEDENCE;
            }
        }

        @Bean
        Observation observation() {
            return new Observation();
        }

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        ApplicationRunner observeBeforeWarmup(
                ApplicationContext context,
                Observation observation,
                UserRepository users,
                ForumRepository forums,
                PostRepository posts,
                JdbcTemplate jdbc) {
            return (OrderedRunner) args -> {
                User user = new User();
                user.username = "cold_start_fixture";
                user.passwordHash = "unused-test-hash";
                users.save(user);
                Forum forum = new Forum();
                forum.slug = "cold-start-board";
                forum.name = "Cold start";
                forum.owner = user;
                forums.save(forum);
                Post post = new Post();
                post.title = "java fixture";
                post.content = "java read-only warmup";
                post.author = user;
                post.forum = forum;
                post.views = 7;
                posts.save(post);
                observation.postId = post.id;
                observation.before = counts(jdbc);
                String base = "http://127.0.0.1:"
                        + ((WebServerApplicationContext) context).getWebServer().getPort();
                HttpClient client = HttpClient.newHttpClient();
                observation.earlyLiveness = status(client, base, "/actuator/health/liveness");
                observation.earlyReadiness = status(client, base, "/actuator/health/readiness");
                var requests = new ArrayList<CompletableFuture<HttpResponse<Void>>>();
                for (int i = 0; i < 16; i++)
                    requests.add(client.sendAsync(
                            HttpRequest.newBuilder(URI.create(base + "/api/posts/feed?page=0&size=20"))
                                    .header(StartupState.WARMUP_HEADER, "forged-token")
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.discarding()));
                observation.earlyBusinessStatuses = requests.stream()
                        .map(future -> future.join().statusCode())
                        .toList();
            };
        }
    }

    @Test
    void preReadyTrafficIsRefusedThenReadsOpenWithoutBusinessWrites() throws Exception {
        assertEquals(200, observation.earlyLiveness);
        assertEquals(503, observation.earlyReadiness);
        assertEquals(Collections.nCopies(16, 503), observation.earlyBusinessStatuses);
        assertTrue(state.isComplete());
        assertEquals(observation.before, counts(jdbc));
        HttpClient client = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + port;
        assertEquals(200, status(client, base, "/actuator/health/readiness"));
        assertEquals(200, status(client, base, "/api/posts/feed?page=0&size=20"));
        assertEquals(observation.before, counts(jdbc));
        AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
        try {
            assertEquals(503, status(client, base, "/actuator/health/readiness"));
            assertEquals(503, status(client, base, "/api/posts/feed"));
        } finally {
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        }
    }
}
