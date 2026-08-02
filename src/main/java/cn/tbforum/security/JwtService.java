package cn.tbforum.security;
import io.jsonwebtoken.*; import io.jsonwebtoken.security.Keys; import org.springframework.beans.factory.annotation.Value; import org.springframework.stereotype.Service; import java.nio.charset.StandardCharsets; import java.util.*;
@Service public class JwtService {
 private final byte[] key; private final long ttl;
 public JwtService(@Value("${app.jwt.secret}") String secret,@Value("${app.jwt.expiration-hours}") long hours){ key=secret.getBytes(StandardCharsets.UTF_8); ttl=hours*3600000; }
 public String issue(String username){ return Jwts.builder().subject(username).issuedAt(new Date()).expiration(new Date(System.currentTimeMillis()+ttl)).signWith(Keys.hmacShaKeyFor(key)).compact(); }
 public String subject(String token){ return Jwts.parser().verifyWith(Keys.hmacShaKeyFor(key)).build().parseSignedClaims(token).getPayload().getSubject(); }
}
