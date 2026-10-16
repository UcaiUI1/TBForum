package cn.tbforum.security;

import cn.tbforum.repo.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.springframework.context.annotation.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, JwtService jwt, UserRepository users) throws Exception {
        return http.cors(c -> {})
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.contentTypeOptions(co -> {})
                        .frameOptions(fo -> fo.deny())
                        .httpStrictTransportSecurity(
                                hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
                                        + "img-src 'self' data: blob:; connect-src 'self' ws: wss:; "
                                        + "font-src 'self' https://fonts.gstatic.com")))
                .authorizeHttpRequests(a -> a.requestMatchers(
                                HttpMethod.GET, "/api/forums/**", "/api/posts/**", "/api/tags/**", "/api/search")
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/users/*",
                                "/api/users/search",
                                "/api/users/*/followers",
                                "/api/users/*/following")
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/auth/register",
                                "/api/auth/login",
                                "/api/auth/forgot-password",
                                "/api/auth/reset-password")
                        .permitAll()
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/prometheus",
                                "/error",
                                "/",
                                "/index.html",
                                "/assets/**",
                                "/uploads/**",
                                "/ws/**",
                                "/rss.xml",
                                "/sitemap.xml",
                                "/robots.txt",
                                "/global",
                                "/profile",
                                "/search",
                                "/forum/**",
                                "/tag/**",
                                "/post/**",
                                "/u/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint((q, res, ex) -> {
                    res.setStatus(HttpStatus.UNAUTHORIZED.value());
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"请先登录\"}");
                }))
                .addFilterBefore(new JwtFilter(jwt, users), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        var config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "http://192.168.*:*",
                "http://10.*:*",
                "http://172.16.*:*"));
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    static class JwtFilter extends OncePerRequestFilter {
        private static final Logger log = org.slf4j.LoggerFactory.getLogger(JwtFilter.class);
        private final JwtService jwt;
        private final UserRepository users;

        JwtFilter(JwtService jwt, UserRepository users) {
            this.jwt = jwt;
            this.users = users;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7);
                if (!token.isBlank()) {
                    try {
                        var claims = jwt.claims(token);
                        var user = users.findByUsername(claims.username()).orElse(null);
                        if (user == null || user.deleted || claims.tokenVersion() != user.tokenVersion) {
                            reject(response, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "登录用户不存在或账号已注销，请重新登录");
                            return;
                        }
                        if (user.bannedUntil != null && user.bannedUntil.isAfter(Instant.now())) {
                            reject(response, HttpStatus.UNAUTHORIZED, "ACCOUNT_BANNED", "账号已被封禁至 " + user.bannedUntil);
                            return;
                        }
                        if (isMutation(request.getMethod())
                                && user.mutedUntil != null
                                && user.mutedUntil.isAfter(Instant.now())) {
                            reject(response, HttpStatus.FORBIDDEN, "ACCOUNT_MUTED", "账号已被禁言至 " + user.mutedUntil);
                            return;
                        }
                        SecurityContextHolder.getContext()
                                .setAuthentication(new UsernamePasswordAuthenticationToken(
                                        user.username, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role))));
                    } catch (JwtException | IllegalArgumentException e) {
                        log.warn("JWT 验证失败: {}", e.getMessage());
                        reject(response, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "登录已失效，请重新登录");
                        return;
                    }
                }
            }
            chain.doFilter(request, response);
        }

        private static boolean isMutation(String method) {
            return !("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method));
        }

        private static void reject(HttpServletResponse response, HttpStatus status, String code, String message)
                throws IOException {
            response.setStatus(status.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
        }
    }
}
