package cn.tbforum.security;

import cn.tbforum.config.AppProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 按可信客户端 IP 对敏感端点限流，防止暴力破解、代理头绕过与缓存耗尽。 */
@Component
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitFilter extends OncePerRequestFilter {
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(RateLimitFilter.class);
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private final Cache<String, Bucket> buckets;
    private final ClientIpResolver clientIpResolver;

    @Autowired
    public RateLimitFilter(AppProperties properties, ClientIpResolver clientIpResolver) {
        this(properties, clientIpResolver, Ticker.systemTicker());
    }

    RateLimitFilter(AppProperties properties, ClientIpResolver clientIpResolver, Ticker ticker) {
        var config = properties.getRateLimit();
        if (config.getMaxBuckets() < 1) {
            throw new IllegalArgumentException("app.rate-limit.max-buckets 必须大于 0");
        }
        if (config.getExpireAfterAccess() == null
                || config.getExpireAfterAccess().isZero()
                || config.getExpireAfterAccess().isNegative()) {
            throw new IllegalArgumentException("app.rate-limit.expire-after-access 必须大于 0");
        }
        this.buckets = Caffeine.newBuilder()
                .maximumSize(config.getMaxBuckets())
                .expireAfterAccess(config.getExpireAfterAccess())
                .ticker(ticker)
                .build();
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        LimitRule rule = ruleFor(request.getMethod(), request.getRequestURI());
        if (rule == null) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = clientIpResolver.resolve(request);
        String key = rule.name() + ":" + clientIp;
        Bucket bucket = buckets.get(key, ignored -> newBucket(rule.capacity()));
        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
            return;
        }

        log.warn("限流触发: {} ip={} path={}", rule.name(), clientIp, request.getRequestURI());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(WINDOW.toSeconds()));
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"请求过于频繁，请稍后再试\"}");
    }

    long bucketCount() {
        buckets.cleanUp();
        return buckets.estimatedSize();
    }

    private static Bucket newBucket(int capacity) {
        return Bucket.builder()
                .addLimit(Bandwidth.classic(capacity, Refill.intervally(capacity, WINDOW)))
                .build();
    }

    private static LimitRule ruleFor(String method, String path) {
        if (!"POST".equals(method)) {
            return null;
        }
        return switch (path) {
            case "/api/auth/login" -> new LimitRule("login", 5);
            case "/api/auth/register" -> new LimitRule("register", 3);
            case "/api/upload" -> new LimitRule("upload", 10);
            default -> null;
        };
    }

    private record LimitRule(String name, int capacity) {}
}
