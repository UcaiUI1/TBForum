package cn.tbforum.security;
import io.jsonwebtoken.*; import io.jsonwebtoken.security.Keys; import jakarta.annotation.PostConstruct; import org.springframework.beans.factory.annotation.Value; import org.springframework.core.env.Environment; import org.springframework.stereotype.Service; import java.nio.charset.StandardCharsets; import java.util.*;
@Service public class JwtService {
 static final String WEAK_DEFAULT="change-this-development-key-to-a-long-random-secret-123456789";
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(JwtService.class);
 private final byte[] key; private final long ttl; private final Environment env;
 public JwtService(@Value("${app.jwt.secret}") String secret,@Value("${app.jwt.expiration-hours}") long hours,Environment env){ key=secret.getBytes(StandardCharsets.UTF_8); if(key.length<32)throw new IllegalStateException("JWT secret 必须至少 32 字节"); ttl=hours*3600000; this.env=env; }
 @PostConstruct public void validate(){ String secret=new String(key,StandardCharsets.UTF_8); if(WEAK_DEFAULT.equals(secret)){ if(env.matchesProfiles("prod"))throw new IllegalStateException("生产环境禁止使用开发默认 JWT secret，请配置随机 JWT_SECRET 环境变量"); log.warn("当前使用开发默认 JWT secret，切勿用于生产环境！"); } }
 public String issue(String username){ return Jwts.builder().subject(username).issuedAt(new Date()).expiration(new Date(System.currentTimeMillis()+ttl)).signWith(Keys.hmacShaKeyFor(key)).compact(); }
 public String subject(String token){ return Jwts.parser().verifyWith(Keys.hmacShaKeyFor(key)).build().parseSignedClaims(token).getPayload().getSubject(); }
}
