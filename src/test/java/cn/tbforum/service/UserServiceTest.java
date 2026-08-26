package cn.tbforum.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.tbforum.config.AppProperties;
import cn.tbforum.domain.Follow;
import cn.tbforum.domain.Notification;
import cn.tbforum.domain.PasswordResetToken;
import cn.tbforum.domain.User;
import cn.tbforum.dto.Dtos.Credentials;
import cn.tbforum.dto.Dtos.ProfileInput;
import cn.tbforum.dto.Dtos.Token;
import cn.tbforum.dto.Dtos.UserView;
import cn.tbforum.notif.NotificationService;
import cn.tbforum.repo.FollowRepository;
import cn.tbforum.repo.PasswordResetTokenRepository;
import cn.tbforum.repo.PostRepository;
import cn.tbforum.repo.UserRepository;
import cn.tbforum.security.ClientIpResolver;
import cn.tbforum.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    UserRepository users;

    @Mock
    FollowRepository follows;

    @Mock
    PostRepository posts;

    @Mock
    PasswordEncoder encoder;

    @Mock
    JwtService jwt;

    @Mock
    NotificationService notif;

    @Mock
    HttpServletRequest request;

    @Mock
    ClientIpResolver clientIpResolver;

    @Mock
    AuditService audit;

    @Mock
    PasswordResetTokenRepository resetTokens;

    @Mock
    MailService mail;

    @Mock
    ManagedUploadService uploads;

    UserService service;
    AppProperties appProperties;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.setPublicBaseUrl("https://forum.example.com/");
        service = new UserService(
                users,
                follows,
                posts,
                encoder,
                jwt,
                notif,
                request,
                clientIpResolver,
                audit,
                resetTokens,
                mail,
                uploads,
                appProperties);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static User user(long id, String name) {
        User u = new User();
        u.id = id;
        u.username = name;
        return u;
    }

    private static void loginAs(String name) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name, null));
    }

    @Test
    void register_withTakenUsername_rejectsWithoutSaving() {
        when(users.findByUsername("alice")).thenReturn(Optional.of(user(1L, "alice")));

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class, () -> service.register(new Credentials("alice", "password123", null)));

        assertEquals(HttpStatus.BAD_REQUEST.value(), ex.getStatusCode().value());
        verify(users, never()).save(any());
    }

    @Test
    void clientIp_usesTrustedProxyResolver() {
        when(clientIpResolver.resolve(request)).thenReturn("203.0.113.10");

        assertEquals("203.0.113.10", service.clientIp());

        verify(clientIpResolver).resolve(request);
    }

    @Test
    void register_success_hashesPasswordAndPersistsEmail() {
        when(users.findByUsername("alice")).thenReturn(Optional.empty());
        when(users.findByEmail("a@b.com")).thenReturn(Optional.empty());
        when(encoder.encode("password123")).thenReturn("hashed");
        when(jwt.issue("alice", 0L)).thenReturn("token");
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.id = 1L;
            return u;
        });

        Token token = service.register(new Credentials("alice", "password123", "a@b.com"))
                .getBody();

        assertNotNull(token);
        assertEquals("token", token.token());
        assertEquals("alice", token.username());
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertEquals("hashed", captor.getValue().passwordHash);
        assertEquals("a@b.com", captor.getValue().email);
    }

    @Test
    void follow_self_isRejected() {
        loginAs("alice");
        when(users.findByUsername("alice")).thenReturn(Optional.of(user(1L, "alice")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.follow("alice"));

        assertEquals(HttpStatus.BAD_REQUEST.value(), ex.getStatusCode().value());
        verify(follows, never()).save(any());
    }

    @Test
    void follow_success_createsFollowAndNotifiesTarget() {
        loginAs("alice");
        User alice = user(1L, "alice");
        User bob = user(2L, "bob");
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(users.findByUsername("bob")).thenReturn(Optional.of(bob));
        when(follows.findByFollowerAndFollowee(alice, bob)).thenReturn(Optional.empty());

        service.follow("bob");

        ArgumentCaptor<Follow> captor = ArgumentCaptor.forClass(Follow.class);
        verify(follows).save(captor.capture());
        assertEquals(alice, captor.getValue().follower);
        assertEquals(bob, captor.getValue().followee);
        verify(notif).notify(eq(bob), eq(alice), eq(Notification.Kind.FOLLOW), anyString(), isNull(), isNull());
    }

    @Test
    void following_returnsUsersThatTargetFollows() {
        // 回归测试：此前错误地按 followee 查询导致返回“粉丝”，而非“关注的人”
        User alice = user(1L, "alice");
        User bob = user(2L, "bob");
        Follow f = new Follow();
        f.follower = alice;
        f.followee = bob;
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(follows.findByFollowerOrderByCreatedAtDesc(alice)).thenReturn(List.of(f));

        List<UserView> list = service.following("alice");

        assertEquals(1, list.size());
        assertEquals("bob", list.get(0).username());
        verify(follows, never()).findByFolloweeOrderByCreatedAtDesc(any());
    }

    @Test
    void login_bannedUser_isRejectedWithoutIssuingToken() {
        User alice = user(1L, "alice");
        alice.passwordHash = "hash";
        alice.bannedUntil = Instant.now().plusSeconds(3600);
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class, () -> service.login(new Credentials("alice", "whatever", null)));

        assertEquals(HttpStatus.UNAUTHORIZED.value(), ex.getStatusCode().value());
        verify(jwt, never()).issue(anyString(), anyLong());
    }

    @Test
    void forgotPassword_usesConfiguredBaseUrlAndInvalidatesOlderTokens() {
        User alice = user(1L, "alice");
        alice.email = "alice@example.com";
        when(users.findByEmail(alice.email)).thenReturn(Optional.of(alice));

        service.forgotPassword(alice.email);

        verify(resetTokens).invalidateUnusedForUser(alice);
        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(resetTokens).save(tokenCaptor.capture());
        assertEquals(alice, tokenCaptor.getValue().user);
        assertEquals(64, tokenCaptor.getValue().tokenHash.length());
        ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
        verify(mail).sendPasswordReset(eq(alice.email), linkCaptor.capture());
        assertTrue(linkCaptor.getValue().startsWith("https://forum.example.com/reset?token="));
    }

    @Test
    void resetPassword_consumesTokenAndRevokesExistingSessions() {
        User alice = user(1L, "alice");
        alice.passwordHash = "old-hash";
        PasswordResetToken token = new PasswordResetToken();
        token.id = 10L;
        token.user = alice;
        when(resetTokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));
        when(resetTokens.consume(eq(10L), any(Instant.class))).thenReturn(1);
        when(encoder.encode("new-password")).thenReturn("new-hash");

        service.resetPassword("raw-token", "new-password");

        assertEquals("new-hash", alice.passwordHash);
        assertEquals(1L, alice.tokenVersion);
        verify(users).save(alice);
        verify(notif).disconnect("alice");
    }

    @Test
    void resetPassword_whenAtomicConsumeLosesRace_rejectsWithoutChangingPassword() {
        User alice = user(1L, "alice");
        PasswordResetToken token = new PasswordResetToken();
        token.id = 10L;
        token.user = alice;
        when(resetTokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));
        when(resetTokens.consume(eq(10L), any(Instant.class))).thenReturn(0);

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> service.resetPassword("raw-token", "new-password"));

        assertEquals(HttpStatus.BAD_REQUEST.value(), ex.getStatusCode().value());
        verify(encoder, never()).encode(anyString());
        verify(users, never()).save(any());
        verify(notif, never()).disconnect(anyString());
    }

    @Test
    void deleteAccount_disconnectsExistingWebSockets() {
        loginAs("alice");
        User alice = user(1L, "alice");
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));

        service.deleteAccount();

        assertTrue(alice.deleted);
        verify(users).save(alice);
        verify(uploads).claimAvatar(alice, null, null);
        verify(notif).disconnect("alice");
    }

    @Test
    void updateProfileClaimsManagedAvatarBeforePersistingIt() {
        loginAs("alice");
        User alice = user(1L, "alice");
        alice.avatarUrl = "/uploads/avatar_1_old.png";
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(uploads.claimAvatar(alice, "/uploads/avatar_1_new.png", alice.avatarUrl))
                .thenReturn("/uploads/avatar_1_new.png");

        service.updateProfile(new ProfileInput(null, "/uploads/avatar_1_new.png", null, null));

        assertEquals("/uploads/avatar_1_new.png", alice.avatarUrl);
        verify(uploads).claimAvatar(alice, "/uploads/avatar_1_new.png", "/uploads/avatar_1_old.png");
        verify(users).save(alice);
    }

    @Test
    void ban_disconnectsExistingWebSockets() {
        loginAs("admin");
        User admin = user(9L, "admin");
        admin.role = cn.tbforum.domain.Role.ADMIN;
        User alice = user(1L, "alice");
        when(users.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));

        service.ban("alice", 1);

        verify(notif).disconnect("alice");
        assertNotNull(alice.bannedUntil);
    }
}
