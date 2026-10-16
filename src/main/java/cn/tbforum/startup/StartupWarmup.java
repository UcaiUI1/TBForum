package cn.tbforum.startup;

import cn.tbforum.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

/** Warm read-only request paths before Boot publishes accepting-traffic readiness. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class StartupWarmup implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StartupWarmup.class);
    private final StartupProperties properties;
    private final StartupState state;
    private final ApplicationContext context;
    private final ObjectMapper mapper;
    private final JwtService jwt;

    public StartupWarmup(
            StartupProperties properties,
            StartupState state,
            ApplicationContext context,
            ObjectMapper mapper,
            JwtService jwt) {
        this.properties = properties;
        this.state = state;
        this.context = context;
        this.mapper = mapper;
        this.jwt = jwt;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!properties.isEnabled()) {
            state.complete();
            return;
        }
        properties.validate();
        if (!(context instanceof WebServerApplicationContext web)) {
            throw new IllegalStateException("Startup warmup requires a running web server");
        }
        long start = System.nanoTime();
        long deadline = start + properties.getTimeout().toNanos();
        // Exercise token parsing/signature verification without an account, session or database write.
        String probe = jwt.issue("startup-self-test", 0);
        for (int i = 0; i < properties.getRounds(); i++) {
            if (System.nanoTime() >= deadline) throw new TimeoutException("Startup warmup deadline exceeded");
            jwt.claims(probe);
        }
        String base = "http://127.0.0.1:" + web.getWebServer().getPort()
                + context.getEnvironment().getProperty("server.servlet.context-path", "");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(properties.getRequestTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new ProxySelector() {
                    @Override
                    public List<Proxy> select(URI uri) {
                        return List.of(Proxy.NO_PROXY);
                    }

                    @Override
                    public void connectFailed(URI uri, SocketAddress address, IOException error) {}
                })
                .build();
        ExecutorService pool = Executors.newFixedThreadPool(properties.getConcurrency(), runnable -> {
            Thread thread = new Thread(runnable, "startup-warmup");
            thread.setDaemon(true);
            return thread;
        });
        int requests = 0;
        try {
            List<String> paths = new ArrayList<>(List.of(
                    "/api/posts/feed?order=latest&page=0&size=20",
                    "/api/posts/feed?order=latest&page=1&size=20",
                    "/api/search?q=java&page=0&size=20",
                    "/api/forums?page=0&size=20"));
            JsonNode forums = request(client, base, paths.get(3), deadline);
            requests++;
            JsonNode first = forums.path("content").path(0).path("slug");
            if (first.isTextual() && !first.asText().isBlank()) {
                paths.add("/api/forums/" + UriUtils.encodePathSegment(first.asText(), StandardCharsets.UTF_8)
                        + "/posts?order=latest&page=0&size=20");
            }
            for (int round = 0; round < properties.getRounds(); round++) {
                List<Callable<JsonNode>> tasks = paths.stream()
                        .<Callable<JsonNode>>map(path -> () -> request(client, base, path, deadline))
                        .toList();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException("Startup warmup deadline exceeded");
                for (Future<JsonNode> result : pool.invokeAll(tasks, remaining, TimeUnit.NANOSECONDS)) {
                    result.get();
                    requests++;
                }
            }
            state.complete();
            log.info(
                    "Startup warmup completed requests={} elapsed_ms={}",
                    requests,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        } finally {
            pool.shutdownNow();
        }
    }

    private JsonNode request(HttpClient client, String base, String path, long deadline) throws Exception {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException("Startup warmup deadline exceeded");
        Duration timeout = Duration.ofNanos(
                Math.min(remaining, properties.getRequestTimeout().toNanos()));
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(timeout)
                .header(StartupState.WARMUP_HEADER, state.token())
                .GET()
                .build();
        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200)
            throw new IllegalStateException("Startup warmup failed status=" + response.statusCode() + " path=" + path);
        JsonNode body = mapper.readTree(response.body());
        if (body == null || !body.isContainerNode())
            throw new IllegalStateException("Invalid startup warmup response path=" + path);
        return body;
    }
}
