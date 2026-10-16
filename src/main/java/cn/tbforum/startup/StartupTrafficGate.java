package cn.tbforum.startup;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class StartupTrafficGate extends OncePerRequestFilter {
    private final ApplicationAvailability availability;
    private final StartupProperties properties;
    private final StartupState state;

    public StartupTrafficGate(ApplicationAvailability availability, StartupProperties properties, StartupState state) {
        this.availability = availability;
        this.properties = properties;
        this.state = state;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!properties.isEnabled()
                || path.startsWith("/actuator/")
                || path.equals("/error")
                || (state.isComplete() && availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC)
                || state.isWarmupRequest(request, path)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader("Retry-After", "2");
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"STARTING_UP\",\"message\":\"服务尚未就绪，请稍后重试\"}");
    }
}
