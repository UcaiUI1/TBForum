package cn.tbforum.startup;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class StartupState {
    static final String WARMUP_HEADER = "X-TBForum-Startup-Probe";
    private static final Set<String> SAFE_PATHS = Set.of("/api/posts/feed", "/api/forums", "/api/search");
    private final String token = UUID.randomUUID().toString();
    private final AtomicBoolean complete = new AtomicBoolean();

    String token() {
        return token;
    }

    public boolean isComplete() {
        return complete.get();
    }

    void complete() {
        complete.set(true);
    }

    boolean isWarmupRequest(HttpServletRequest request, String path) {
        if (complete.get() || !"GET".equals(request.getMethod())) return false;
        if (!SAFE_PATHS.contains(path) && !path.matches("/api/forums/[^/]+/posts")) return false;
        String remote = request.getRemoteAddr();
        if (!Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(remote)) return false;
        String candidate = request.getHeader(WARMUP_HEADER);
        return candidate != null
                && MessageDigest.isEqual(
                        token.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
    }
}
