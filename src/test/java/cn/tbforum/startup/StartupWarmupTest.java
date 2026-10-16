package cn.tbforum.startup;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.tbforum.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.StandardEnvironment;

class StartupWarmupTest {
    private StartupWarmup runner(StartupProperties props, StartupState state, int port) {
        var context = mock(ServletWebServerApplicationContext.class);
        var server = mock(WebServer.class);
        when(server.getPort()).thenReturn(port);
        when(context.getWebServer()).thenReturn(server);
        when(context.getEnvironment()).thenReturn(new StandardEnvironment());
        var jwt = mock(JwtService.class);
        when(jwt.issue("startup-self-test", 0)).thenReturn("internal-test-token");
        return new StartupWarmup(props, state, context, new ObjectMapper(), jwt);
    }

    @Test
    void warmsOnlyReadPathsAndCompletesAfterEveryRequest() throws Exception {
        var state = new StartupState();
        var count = new AtomicInteger();
        var unsafe = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (!exchange.getRequestMethod().equals("GET")
                    || !state.token().equals(exchange.getRequestHeaders().getFirst(StartupState.WARMUP_HEADER)))
                unsafe.incrementAndGet();
            if (!exchange.getRequestURI().getPath().matches("/api/(posts/feed|forums|search|forums/board/posts)"))
                unsafe.incrementAndGet();
            assertFalse(state.isComplete());
            count.incrementAndGet();
            byte[] body = "{\"content\":[{\"slug\":\"board\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var props = new StartupProperties();
            props.setRounds(2);
            runner(props, state, server.getAddress().getPort()).run(new DefaultApplicationArguments());
            assertTrue(state.isComplete());
            assertEquals(11, count.get());
            assertEquals(0, unsafe.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failedWarmupNeverMarksComplete() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            var state = new StartupState();
            assertThrows(
                    IllegalStateException.class,
                    () -> runner(
                                    new StartupProperties(),
                                    state,
                                    server.getAddress().getPort())
                            .run(new DefaultApplicationArguments()));
            assertFalse(state.isComplete());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void invalidBoundsFailBeforeNetworkAccess() {
        var props = new StartupProperties();
        props.setConcurrency(100);
        assertThrows(IllegalArgumentException.class, props::validate);
        props.setConcurrency(2);
        props.setRequestTimeout(Duration.ZERO);
        assertThrows(IllegalArgumentException.class, props::validate);
    }
}
