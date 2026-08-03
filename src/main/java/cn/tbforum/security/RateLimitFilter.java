package cn.tbforum.security;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
/** 按 IP 维度对敏感端点限流，防止暴力破解与上传滥用。 */
@Component
@ConditionalOnProperty(name="app.rate-limit.enabled",havingValue="true",matchIfMissing=true)
public class RateLimitFilter extends OncePerRequestFilter {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(RateLimitFilter.class);
    private final Map<String,Bucket> buckets=new ConcurrentHashMap<>();
    @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain c)throws ServletException,IOException{
        String path=r.getRequestURI();
        String method=r.getMethod();
        LimitRule rule=ruleFor(method,path);
        if(rule==null){c.doFilter(r,s);return;}
        String ip=clientIp(r);
        String key=rule.name+":"+ip;
        Bucket bucket=buckets.computeIfAbsent(key,k->Bucket.builder().addLimit(Bandwidth.classic(rule.capacity,Refill.intervally(rule.capacity,Duration.ofMinutes(1)))).build());
        if(bucket.tryConsume(1)){
            c.doFilter(r,s);
        }else{
            log.warn("限流触发: {} ip={} path={}",rule.name,ip,path);
            s.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            s.setContentType("application/json;charset=UTF-8");
            s.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"请求过于频繁，请稍后再试\"}");
        }
    }
    private LimitRule ruleFor(String method,String path){
        if("POST".equals(method)){
            if("/api/auth/login".equals(path))return new LimitRule("login",5);
            if("/api/auth/register".equals(path))return new LimitRule("register",3);
            if("/api/upload".equals(path))return new LimitRule("upload",10);
        }
        return null;
    }
    private String clientIp(HttpServletRequest r){
        String xff=r.getHeader("X-Forwarded-For");
        if(xff!=null&&!xff.isBlank()){int comma=xff.indexOf(',');return (comma>0?xff.substring(0,comma):xff).trim();}
        String real=r.getHeader("X-Real-IP");
        if(real!=null&&!real.isBlank())return real.trim();
        return r.getRemoteAddr();
    }
    private record LimitRule(String name,int capacity){}
}
