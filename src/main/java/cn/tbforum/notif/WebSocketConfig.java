package cn.tbforum.notif;

import cn.tbforum.repo.UserRepository;
import cn.tbforum.security.JwtService;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final NotificationService handler;
    private final JwtService jwt;
    private final UserRepository users;

    public WebSocketConfig(NotificationService handler, JwtService jwt, UserRepository users) {
        this.handler = handler;
        this.jwt = jwt;
        this.users = users;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/notifications")
                .setAllowedOriginPatterns(
                        "http://localhost:*",
                        "http://127.0.0.1:*",
                        "http://192.168.*:*",
                        "http://10.*:*",
                        "http://172.16.*:*")
                .addInterceptors(new AccountHandshakeInterceptor(jwt, users));
    }

    static class AccountHandshakeInterceptor implements HandshakeInterceptor {
        private final JwtService jwt;
        private final UserRepository users;

        AccountHandshakeInterceptor(JwtService jwt, UserRepository users) {
            this.jwt = jwt;
            this.users = users;
        }

        @Override
        public boolean beforeHandshake(
                ServerHttpRequest request,
                ServerHttpResponse response,
                WebSocketHandler handler,
                Map<String, Object> attributes) {
            List<String> protocols = request.getHeaders().get("Sec-WebSocket-Protocol");
            if (protocols != null) {
                for (String protocol : protocols) {
                    for (String value : protocol.split(",")) {
                        String candidate = value.trim();
                        if (!candidate.startsWith("bearer.") || candidate.length() <= 7) {
                            continue;
                        }
                        try {
                            var claims = jwt.claims(candidate.substring(7));
                            var user = users.findByUsername(claims.username()).orElse(null);
                            if (user != null
                                    && !user.deleted
                                    && claims.tokenVersion() == user.tokenVersion
                                    && (user.bannedUntil == null || !user.bannedUntil.isAfter(Instant.now()))) {
                                attributes.put("ws-user", user.username);
                                return true;
                            }
                        } catch (Exception ignored) {
                            // 统一在循环结束后拒绝握手，避免向客户端泄露 token 解析细节。
                        }
                    }
                }
            }
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        @Override
        public void afterHandshake(
                ServerHttpRequest request,
                ServerHttpResponse response,
                WebSocketHandler handler,
                Exception exception) {}
    }
}
