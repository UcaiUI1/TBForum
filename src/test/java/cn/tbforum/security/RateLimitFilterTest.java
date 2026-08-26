package cn.tbforum.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import cn.tbforum.config.AppProperties;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {
    @Test
    void spoofedForwardedForCannotBypassLimit() throws Exception {
        AppProperties properties = properties(List.of(), 100, Duration.ofMinutes(10));
        RateLimitFilter filter = filter(properties);
        FilterChain chain = mock(FilterChain.class);

        for (int attempt = 0; attempt < 5; attempt++) {
            MockHttpServletResponse response = execute(filter, chain, "203.0.113.10", "198.51.100." + attempt);
            assertEquals(200, response.getStatus());
        }
        MockHttpServletResponse limited = execute(filter, chain, "203.0.113.10", "198.51.100.250");

        assertEquals(429, limited.getStatus());
        assertEquals("60", limited.getHeader("Retry-After"));
        assertTrue(limited.getContentAsString().contains("RATE_LIMITED"));
        verify(chain, times(5)).doFilter(any(), any());
    }

    @Test
    void differentRealClientsHaveIndependentBuckets() throws Exception {
        AppProperties properties = properties(List.of(), 100, Duration.ofMinutes(10));
        RateLimitFilter filter = filter(properties);
        FilterChain chain = mock(FilterChain.class);

        for (int attempt = 0; attempt < 5; attempt++) {
            assertEquals(200, execute(filter, chain, "203.0.113.10", null).getStatus());
        }

        assertEquals(200, execute(filter, chain, "203.0.113.11", null).getStatus());
    }

    @Test
    void cacheIsBoundedByConfiguredMaximum() throws Exception {
        AppProperties properties = properties(List.of(), 2, Duration.ofMinutes(10));
        RateLimitFilter filter = filter(properties);
        FilterChain chain = mock(FilterChain.class);

        for (int client = 1; client <= 8; client++) {
            execute(filter, chain, "203.0.113." + client, null);
        }

        assertTrue(filter.bucketCount() <= 2);
    }

    @Test
    void idleBucketsExpire() throws Exception {
        AppProperties properties = properties(List.of(), 100, Duration.ofMinutes(10));
        AtomicLong clock = new AtomicLong();
        ClientIpResolver resolver = new ClientIpResolver(properties);
        RateLimitFilter filter = new RateLimitFilter(properties, resolver, clock::get);

        execute(filter, mock(FilterChain.class), "203.0.113.10", null);
        assertEquals(1, filter.bucketCount());

        clock.addAndGet(TimeUnit.MINUTES.toNanos(11));
        assertEquals(0, filter.bucketCount());
    }

    private static RateLimitFilter filter(AppProperties properties) {
        return new RateLimitFilter(properties, new ClientIpResolver(properties));
    }

    private static AppProperties properties(List<String> trustedProxies, int maxBuckets, Duration expireAfterAccess) {
        AppProperties properties = new AppProperties();
        properties.getRateLimit().setTrustedProxies(trustedProxies);
        properties.getRateLimit().setMaxBuckets(maxBuckets);
        properties.getRateLimit().setExpireAfterAccess(expireAfterAccess);
        return properties;
    }

    private static MockHttpServletResponse execute(
            RateLimitFilter filter, FilterChain chain, String remoteAddress, String forwardedFor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr(remoteAddress);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);
        return response;
    }
}
