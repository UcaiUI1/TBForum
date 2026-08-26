package cn.tbforum.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.tbforum.config.AppProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {
    @Test
    void untrustedPeerCannotSpoofForwardedHeaders() {
        ClientIpResolver resolver = resolver(List.of());
        MockHttpServletRequest request = request("203.0.113.10");
        request.addHeader("X-Forwarded-For", "198.51.100.25");
        request.addHeader("X-Real-IP", "198.51.100.26");

        assertEquals("203.0.113.10", resolver.resolve(request));
    }

    @Test
    void trustedProxyUsesRightmostUntrustedAddress() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));
        MockHttpServletRequest request = request("10.0.0.3");
        request.addHeader("X-Forwarded-For", "192.0.2.99, 198.51.100.25, 10.0.0.2");

        assertEquals("198.51.100.25", resolver.resolve(request));
    }

    @Test
    void trustedProxyUsesRealIpWhenForwardedForIsAbsent() {
        ClientIpResolver resolver = resolver(List.of("127.0.0.1/32"));
        MockHttpServletRequest request = request("127.0.0.1");
        request.addHeader("X-Real-IP", "198.51.100.25");

        assertEquals("198.51.100.25", resolver.resolve(request));
    }

    @Test
    void malformedForwardedChainFallsBackToTrustedPeer() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));
        MockHttpServletRequest request = request("10.0.0.3");
        request.addHeader("X-Forwarded-For", "not-an-ip, 198.51.100.25");
        request.addHeader("X-Real-IP", "198.51.100.26");

        assertEquals("10.0.0.3", resolver.resolve(request));
    }

    private static ClientIpResolver resolver(List<String> trustedProxies) {
        AppProperties properties = new AppProperties();
        properties.getRateLimit().setTrustedProxies(trustedProxies);
        return new ClientIpResolver(properties);
    }

    private static MockHttpServletRequest request(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
