package cn.tbforum.service;

import cn.tbforum.config.AppProperties;
import cn.tbforum.domain.*;
import cn.tbforum.dto.Dtos.*;
import cn.tbforum.notif.NotificationService;
import cn.tbforum.repo.FollowRepository;
import cn.tbforum.repo.PasswordResetTokenRepository;
import cn.tbforum.repo.PostRepository;
import cn.tbforum.repo.UserRepository;
import cn.tbforum.security.ClientIpResolver;
import cn.tbforum.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserService {
    private final UserRepository users;
    private final FollowRepository follows;
    private final PostRepository posts;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final NotificationService notif;
    private final HttpServletRequest request;
    private final ClientIpResolver clientIpResolver;
    private final AuditService audit;
    private final PasswordResetTokenRepository resetTokens;
    private final MailService mail;
    private final ManagedUploadService uploads;
    private final String publicBaseUrl;

    public UserService(
            UserRepository u,
            FollowRepository f,
            PostRepository p,
            PasswordEncoder e,
            JwtService j,
            NotificationService n,
            HttpServletRequest r,
            ClientIpResolver cip,
            AuditService a,
            PasswordResetTokenRepository rt,
            MailService m,
            ManagedUploadService uploads,
            AppProperties properties) {
        users = u;
        follows = f;
        posts = p;
        encoder = e;
        jwt = j;
        notif = n;
        request = r;
        clientIpResolver = cip;
        audit = a;
        resetTokens = rt;
        mail = m;
        this.uploads = uploads;
        publicBaseUrl = normalizePublicBaseUrl(properties.getPublicBaseUrl());
    }

    public User current() {
        String name = SecurityContextHolder.getContext().getAuthentication() == null
                ? null
                : SecurityContextHolder.getContext().getAuthentication().getName();
        if (name == null || "anonymousUser".equals(name)) throw unauthorized("请先登录");
        User u = users.findByUsername(name).orElseThrow(() -> unauthorized("登录已失效"));
        if (u.deleted) throw unauthorized("账号已注销，请重新登录");
        if (u.bannedUntil != null && u.bannedUntil.isAfter(Instant.now()))
            throw unauthorized("账号已被封禁至 " + u.bannedUntil);
        return u;
    }

    public User currentOrNull() {
        String name = SecurityContextHolder.getContext().getAuthentication() == null
                ? null
                : SecurityContextHolder.getContext().getAuthentication().getName();
        if (name == null || "anonymousUser".equals(name)) return null;
        User u = users.findByUsername(name).orElse(null);
        if (u == null || u.deleted || (u.bannedUntil != null && u.bannedUntil.isAfter(Instant.now()))) return null;
        return u;
    }
    /** 使用统一的可信代理规则解析客户端 IP，供浏览数防刷复用。 */
    public String clientIp() {
        return clientIpResolver.resolve(request);
    }

    public ResponseEntity<Token> register(Credentials input) {
        if (users.findByUsername(input.username()).isPresent()) throw bad("用户名已存在");
        if (input.email() != null
                && !input.email().isBlank()
                && users.findByEmail(input.email()).isPresent()) throw bad("邮箱已被使用");
        User u = new User();
        u.username = input.username();
        u.passwordHash = encoder.encode(input.password());
        u.email = input.email();
        try {
            User saved = users.save(u);
            return ResponseEntity.status(201)
                    .body(new Token(jwt.issue(saved.username, saved.tokenVersion), saved.username));
        } catch (DataIntegrityViolationException e) {
            throw bad("用户名已存在");
        }
    }

    public Token login(Credentials input) {
        User u = users.findByUsername(input.username()).orElseThrow(() -> unauthorized("用户名或密码错误"));
        if (u.deleted) throw unauthorized("该账号已注销");
        if (u.bannedUntil != null && u.bannedUntil.isAfter(Instant.now()))
            throw unauthorized("账号已被封禁至 " + u.bannedUntil);
        if (!encoder.matches(input.password(), u.passwordHash)) throw unauthorized("用户名或密码错误");
        return new Token(jwt.issue(u.username, u.tokenVersion), u.username);
    }

    public Token refresh() {
        User u = current();
        return new Token(jwt.issue(u.username, u.tokenVersion), u.username);
    }

    public UserView me() {
        return UserView.of(current());
    }

    public Optional<User> findByUsername(String username) {
        return users.findByUsername(username);
    }

    @Transactional
    public ProfileUpdateView updateProfile(ProfileInput input) {
        User u = current();
        boolean renamed = false;
        if (input.username() != null && !input.username().equals(u.username)) {
            if (users.findByUsername(input.username()).isPresent()) throw bad("用户名已存在");
            u.username = input.username();
            renamed = true;
        }
        if (input.avatarUrl() != null) u.avatarUrl = uploads.claimAvatar(u, input.avatarUrl(), u.avatarUrl);
        if (input.bio() != null) u.bio = input.bio().isEmpty() ? null : input.bio();
        if (input.signature() != null) u.signature = input.signature().isEmpty() ? null : input.signature();
        try {
            users.save(u);
        } catch (DataIntegrityViolationException e) {
            throw bad("用户名已存在");
        }
        return new ProfileUpdateView(renamed ? jwt.issue(u.username, u.tokenVersion) : null, UserView.of(u));
    }

    public UserView verify() {
        User u = current();
        u.verified = true;
        return UserView.of(users.save(u));
    }
    /** 注销账号：软删用户，清空个人资料，帖子保留但作者信息不再可查 */
    @Transactional
    public ResponseEntity<Void> deleteAccount() {
        User u = current();
        uploads.claimAvatar(u, null, u.avatarUrl);
        u.deleted = true;
        u.bio = null;
        u.signature = null;
        u.avatarUrl = null;
        users.save(u);
        notif.disconnect(u.username);
        return ResponseEntity.noContent().build();
    }

    @Transactional
    public ResponseEntity<Void> follow(String username) {
        User u = current();
        requireNotRestricted(u);
        User target = users.findByUsername(username).orElseThrow(() -> notFound("用户不存在"));
        if (target.deleted) throw notFound("用户不存在");
        if (u.id.equals(target.id)) throw bad("不能关注自己");
        if (follows.findByFollowerAndFollowee(u, target).isEmpty()) {
            Follow f = new Follow();
            f.follower = u;
            f.followee = target;
            try {
                follows.save(f);
                notif.notify(target, u, Notification.Kind.FOLLOW, "关注了你", null, null);
            } catch (DataIntegrityViolationException e) {
                /* 并发关注，幂等忽略 */
            }
        }
        return ResponseEntity.status(201).build();
    }

    @Transactional
    public ResponseEntity<Void> unfollow(String username) {
        User u = current();
        User target = users.findByUsername(username).orElseThrow(() -> notFound("用户不存在"));
        follows.deleteByFollowerAndFollowee(u, target);
        return ResponseEntity.noContent().build();
    }

    public List<UserView> followers(String username) {
        User target = users.findByUsername(username).orElseThrow(() -> notFound("用户不存在"));
        return follows.findByFolloweeOrderByCreatedAtDesc(target).stream()
                .map(f -> UserView.of(f.follower))
                .toList();
    }

    public List<UserView> following(String username) {
        User target = users.findByUsername(username).orElseThrow(() -> notFound("用户不存在"));
        return follows.findByFollowerOrderByCreatedAtDesc(target).stream()
                .map(f -> UserView.of(f.followee))
                .toList();
    }

    public UserDetailView userDetail(String username) {
        User target = users.findByUsername(username).orElseThrow(() -> notFound("用户不存在"));
        return UserDetailView.of(target, currentOrNull(), follows);
    }

    public List<UserView> searchUsers(String q) {
        return users.findTop10ByUsernameContainingIgnoreCase(q).stream()
                .map(UserView::of)
                .toList();
    }

    public static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    public static ResponseStatusException unauthorized(String m) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, m);
    }

    public static ResponseStatusException forbidden(String m) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, m);
    }

    public static ResponseStatusException notFound(String m) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, m);
    }

    public void requireAdmin() {
        if (current().role != Role.ADMIN) throw forbidden("需要管理员权限");
    }

    public void requireNotRestricted(User u) {
        if (u.deleted) throw unauthorized("账号已注销，请重新登录");
        if (u.bannedUntil != null && u.bannedUntil.isAfter(Instant.now()))
            throw unauthorized("账号已被封禁至 " + u.bannedUntil);
        if (u.mutedUntil != null && u.mutedUntil.isAfter(Instant.now())) throw forbidden("账号已被禁言至 " + u.mutedUntil);
    }

    private static String sha256(String s) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional
    public void forgotPassword(String email) {
        users.findByEmail(email).ifPresent(u -> {
            if (u.deleted) return;
            resetTokens.invalidateUnusedForUser(u);
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            String token = java.util.HexFormat.of().formatHex(raw);
            PasswordResetToken t = new PasswordResetToken();
            t.user = u;
            t.tokenHash = sha256(token);
            t.expiresAt = Instant.now().plusSeconds(1800);
            resetTokens.save(t);
            mail.sendPasswordReset(email, publicBaseUrl + "/reset?token=" + token);
        });
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        PasswordResetToken t = resetTokens.findByTokenHash(sha256(token)).orElseThrow(() -> bad("重置链接无效或已过期"));
        if (resetTokens.consume(t.id, Instant.now()) != 1) throw bad("重置链接无效或已过期");
        User u = t.user;
        u.passwordHash = encoder.encode(newPassword);
        u.tokenVersion++;
        users.save(u);
        notif.disconnect(u.username);
    }

    @Transactional
    public UserView ban(String username, int days) {
        requireAdmin();
        User t = users.findByUsername(username).orElseThrow(() -> notFound("用户不存在"));
        if (t.deleted) throw notFound("用户不存在");
        t.bannedUntil = days > 0 ? Instant.now().plusSeconds(days * 86400L) : null;
        users.save(t);
        if (days > 0) notif.disconnect(t.username);
        audit.log(
                current(),
                days > 0 ? "BAN_USER" : "UNBAN_USER",
                "USER",
                t.id,
                username + (days > 0 ? " 封禁 " + days + " 天" : " 解除封禁"));
        return UserView.of(t);
    }

    @Transactional
    public UserView mute(String username, int days) {
        requireAdmin();
        User t = users.findByUsername(username).orElseThrow(() -> notFound("用户不存在"));
        if (t.deleted) throw notFound("用户不存在");
        t.mutedUntil = days > 0 ? Instant.now().plusSeconds(days * 86400L) : null;
        users.save(t);
        audit.log(
                current(),
                days > 0 ? "MUTE_USER" : "UNMUTE_USER",
                "USER",
                t.id,
                username + (days > 0 ? " 禁言 " + days + " 天" : " 解除禁言"));
        return UserView.of(t);
    }

    private static String normalizePublicBaseUrl(String value) {
        if (value == null || value.isBlank()) throw new IllegalStateException("app.public-base-url 不能为空");
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.public-base-url 格式无效", e);
        }
        if (uri.getHost() == null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null)
            throw new IllegalStateException("app.public-base-url 必须是无凭据、查询参数和片段的 HTTP(S) 地址");
        String normalized = uri.toString();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }
}
