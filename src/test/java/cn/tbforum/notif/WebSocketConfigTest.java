package cn.tbforum.notif;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.tbforum.domain.User;
import cn.tbforum.repo.UserRepository;
import cn.tbforum.security.JwtService;
import java.time.Instant;
import java.util.HashMap;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

@ExtendWith(MockitoExtension.class)
class WebSocketConfigTest {
    @Mock
    JwtService jwt;

    @Mock
    UserRepository users;

    @Mock
    ServerHttpRequest request;

    @Mock
    ServerHttpResponse response;

    private WebSocketConfig.AccountHandshakeInterceptor interceptor;
    private HttpHeaders headers;

    @BeforeEach
    void setUp() {
        interceptor = new WebSocketConfig.AccountHandshakeInterceptor(jwt, users);
        headers = new HttpHeaders();
        headers.add("Sec-WebSocket-Protocol", "bearer.valid-token");
        when(request.getHeaders()).thenReturn(headers);
        when(jwt.claims("valid-token")).thenReturn(new JwtService.TokenClaims("alice", 0L));
    }

    @Test
    void activeUserCanConnect() {
        User user = user("alice");
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        var attributes = new HashMap<String, Object>();

        boolean accepted = interceptor.beforeHandshake(request, response, mock(WebSocketHandler.class), attributes);

        assertTrue(accepted);
        assertTrue("alice".equals(attributes.get("ws-user")));
    }

    @Test
    void deletedUserCannotConnect() {
        User user = user("alice");
        user.deleted = true;
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));

        boolean accepted =
                interceptor.beforeHandshake(request, response, mock(WebSocketHandler.class), new HashMap<>());

        assertFalse(accepted);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void bannedUserCannotConnect() {
        User user = user("alice");
        user.bannedUntil = Instant.now().plusSeconds(3600);
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));

        boolean accepted =
                interceptor.beforeHandshake(request, response, mock(WebSocketHandler.class), new HashMap<>());

        assertFalse(accepted);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tokenFromBeforePasswordResetCannotConnect() {
        User user = user("alice");
        user.tokenVersion = 1L;
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));

        boolean accepted =
                interceptor.beforeHandshake(request, response, mock(WebSocketHandler.class), new HashMap<>());

        assertFalse(accepted);
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    private static User user(String username) {
        User user = new User();
        user.id = 1L;
        user.username = username;
        return user;
    }
}
