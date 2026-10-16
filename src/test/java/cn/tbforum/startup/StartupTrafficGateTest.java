package cn.tbforum.startup;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class StartupTrafficGateTest {
    private final StartupState state = new StartupState();
    private final ApplicationAvailability availability = mock(ApplicationAvailability.class);
    private final StartupTrafficGate gate = new StartupTrafficGate(availability, new StartupProperties(), state);

    private boolean allowed(String method, String path, String remote, String token) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(remote);
        if (token != null) request.addHeader(StartupState.WARMUP_HEADER, token);
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();
        gate.doFilter(request, response, (q, s) -> called.set(true));
        if (!called.get()) {
            assertEquals(503, response.getStatus());
            assertEquals("2", response.getHeader("Retry-After"));
            assertEquals("no-store", response.getHeader("Cache-Control"));
        }
        return called.get();
    }

    @Test
    void refusesBusinessTrafficWhileProbesRemainReachable() throws Exception {
        assertFalse(allowed("GET", "/api/posts/feed", "127.0.0.1", null));
        assertFalse(allowed("POST", "/api/auth/login", "127.0.0.1", null));
        assertFalse(allowed("GET", "/ws", "127.0.0.1", null));
        assertTrue(allowed("GET", "/actuator/health/readiness", "10.0.0.1", null));
    }

    @Test
    void onlyStartupTokenOnLoopbackSafeReadsBypassesGate() throws Exception {
        assertTrue(allowed("GET", "/api/posts/feed", "127.0.0.1", state.token()));
        assertTrue(allowed("GET", "/api/forums/board/posts", "127.0.0.1", state.token()));
        assertFalse(allowed("GET", "/api/posts/feed", "10.0.0.1", state.token()));
        assertFalse(allowed("GET", "/api/posts/feed", "127.0.0.1", "forged"));
        assertFalse(allowed("GET", "/api/posts/1", "127.0.0.1", state.token()));
        assertFalse(allowed("POST", "/api/forums/board/posts", "127.0.0.1", state.token()));
    }

    @Test
    void completedWarmupStillRequiresReadinessAndTokenIsRetired() throws Exception {
        state.complete();
        when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
        assertFalse(allowed("GET", "/api/posts/feed", "127.0.0.1", state.token()));
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        assertTrue(allowed("GET", "/api/posts/feed", "10.0.0.1", null));
        when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
        assertFalse(allowed("GET", "/api/posts/feed", "127.0.0.1", null));
    }
}
