package cn.tbforum.service;

import cn.tbforum.domain.Follow;
import cn.tbforum.domain.Notification;
import cn.tbforum.domain.User;
import cn.tbforum.dto.Dtos.Credentials;
import cn.tbforum.dto.Dtos.Token;
import cn.tbforum.dto.Dtos.UserView;
import cn.tbforum.notif.NotificationService;
import cn.tbforum.repo.AuditLogRepository;
import cn.tbforum.repo.FollowRepository;
import cn.tbforum.repo.PasswordResetTokenRepository;
import cn.tbforum.repo.PostRepository;
import cn.tbforum.repo.UserRepository;
import cn.tbforum.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

  @Mock UserRepository users;
  @Mock FollowRepository follows;
  @Mock PostRepository posts;
  @Mock PasswordEncoder encoder;
  @Mock JwtService jwt;
  @Mock NotificationService notif;
  @Mock HttpServletRequest request;
  @Mock AuditService audit;
  @Mock PasswordResetTokenRepository resetTokens;
  @Mock MailService mail;

  UserService service;

  @BeforeEach
  void setUp() {
    service = new UserService(users, follows, posts, encoder, jwt, notif, request, audit, resetTokens, mail);
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

    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
        () -> service.register(new Credentials("alice", "password123", null)));

    assertEquals(HttpStatus.BAD_REQUEST.value(), ex.getStatusCode().value());
    verify(users, never()).save(any());
  }

  @Test
  void register_success_hashesPasswordAndPersistsEmail() {
    when(users.findByUsername("alice")).thenReturn(Optional.empty());
    when(users.findByEmail("a@b.com")).thenReturn(Optional.empty());
    when(encoder.encode("password123")).thenReturn("hashed");
    when(jwt.issue("alice")).thenReturn("token");
    when(users.save(any(User.class))).thenAnswer(inv -> {
      User u = inv.getArgument(0);
      u.id = 1L;
      return u;
    });

    Token token = service.register(new Credentials("alice", "password123", "a@b.com")).getBody();

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

    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
        () -> service.login(new Credentials("alice", "whatever", null)));

    assertEquals(HttpStatus.UNAUTHORIZED.value(), ex.getStatusCode().value());
    verify(jwt, never()).issue(anyString());
  }
}
