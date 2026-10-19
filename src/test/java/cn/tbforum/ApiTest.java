package cn.tbforum;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.tbforum.domain.PasswordResetToken;
import cn.tbforum.domain.Role;
import cn.tbforum.notif.NotificationService;
import cn.tbforum.repo.NotificationTaskRepository;
import cn.tbforum.repo.PasswordResetTokenRepository;
import cn.tbforum.repo.UserRepository;
import cn.tbforum.service.MailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * P3 后端集成测试：覆盖认证、版块、帖子、评论、举报核心流程。
 * 使用 H2 内存数据库（test profile），@Transactional 隔离由 Spring TestContext 框架在测试方法执行后自动回滚事务来保证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ApiTest {
    @Autowired
    NotificationService notificationService;

    @Autowired
    NotificationTaskRepository notificationTasks;

    @Test
    void notificationHistoryPagesPastTwentyAndIsScopedToCurrentUser() throws Exception {
        var alice = users.findByUsername(aliceUser).orElseThrow();
        var bob = users.findByUsername(bobUser).orElseThrow();
        for (int i = 0; i < 25; i++)
            notificationService.notify(alice, bob, cn.tbforum.domain.Notification.Kind.FOLLOW, "item " + i, null, null);
        notificationService.notify(bob, alice, cn.tbforum.domain.Notification.Kind.FOLLOW, "private", null, null);
        var first = auth(aliceToken, get("/api/notifications/history?size=20"));
        Assertions.assertEquals(200, first.getResponse().getStatus());
        var page = json.readTree(first.getResponse().getContentAsString());
        Assertions.assertEquals(20, page.get("content").size());
        Assertions.assertEquals(25, page.get("unreadCount").asInt());
        var next = auth(
                aliceToken,
                get("/api/notifications/history?size=20&before="
                        + page.get("nextBefore").asLong()));
        var older = json.readTree(next.getResponse().getContentAsString());
        Assertions.assertEquals(5, older.get("content").size());
        Assertions.assertFalse(older.get("hasMore").asBoolean());
        Assertions.assertEquals(
                1,
                json.readTree(auth(bobToken, get("/api/notifications/history"))
                                .getResponse()
                                .getContentAsString())
                        .get("content")
                        .size());
        long id = page.get("content").get(0).get("id").asLong();
        Assertions.assertEquals(
                204,
                auth(aliceToken, patch("/api/notifications/" + id + "/read"))
                        .getResponse()
                        .getStatus());
        Assertions.assertEquals(
                204,
                auth(aliceToken, patch("/api/notifications/" + id + "/read"))
                        .getResponse()
                        .getStatus());
        Assertions.assertEquals(
                24,
                json.readTree(auth(aliceToken, get("/api/notifications/history"))
                                .getResponse()
                                .getContentAsString())
                        .get("unreadCount")
                        .asInt());
        Assertions.assertEquals(
                403,
                auth(bobToken, patch("/api/notifications/" + id + "/read"))
                        .getResponse()
                        .getStatus());
    }

    @Test
    void notificationTaskInspectionAndReplayRequireAdminAndOnlyReplayDead() throws Exception {
        var alice = users.findByUsername(aliceUser).orElseThrow();
        var bob = users.findByUsername(bobUser).orElseThrow();
        notificationService.notify(alice, bob, cn.tbforum.domain.Notification.Kind.FOLLOW, "retry", null, null);
        var task = notificationTasks.findAll().get(0);
        task.status = cn.tbforum.domain.NotificationTask.Status.DEAD;
        notificationTasks.saveAndFlush(task);
        Assertions.assertEquals(
                403,
                auth(aliceToken, get("/api/admin/notification-tasks"))
                        .getResponse()
                        .getStatus());
        Assertions.assertEquals(
                403,
                auth(aliceToken, post("/api/admin/notification-tasks/" + task.id + "/replay"))
                        .getResponse()
                        .getStatus());
        Assertions.assertEquals(
                200,
                auth(adminToken, get("/api/admin/notification-tasks"))
                        .getResponse()
                        .getStatus());
        Assertions.assertEquals(
                200,
                auth(adminToken, post("/api/admin/notification-tasks/" + task.id + "/replay"))
                        .getResponse()
                        .getStatus());
        Assertions.assertEquals(
                409,
                auth(adminToken, post("/api/admin/notification-tasks/" + task.id + "/replay"))
                        .getResponse()
                        .getStatus());
    }

    @Test
    void notificationHistoryValidatesBounds() throws Exception {
        Assertions.assertEquals(
                400,
                auth(aliceToken, get("/api/notifications/history?size=101"))
                        .getResponse()
                        .getStatus());
        Assertions.assertEquals(
                400,
                auth(aliceToken, get("/api/notifications/history?before=-1"))
                        .getResponse()
                        .getStatus());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordResetTokenRepository resetTokens;

    @MockBean
    MailService mail;

    String aliceToken, bobToken, adminToken;
    String aliceUser = "alice_test", bobUser = "bob_test", adminUser = "admin_test";
    String forumSlug = "test-board-" + System.currentTimeMillis();
    Long postId, commentId, reportId;

    /** 注册并返回 token */
    String register(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"password123\"}";
        MvcResult r = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    /** 带 token 发请求 */
    MvcResult auth(String token, MockHttpServletRequestBuilder req) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req).andReturn();
    }

    @BeforeEach
    void setup() throws Exception {
        aliceToken = register(aliceUser);
        bobToken = register(bobUser);
        adminToken = register(adminUser);
        // 将 admin 提升为 ADMIN 角色
        users.findByUsername(adminUser).ifPresent(u -> {
            u.role = Role.ADMIN;
            users.save(u);
        });
    }

    // ===== 认证测试 =====
    @Test
    @DisplayName("注册成功返回 token")
    void registerSuccess() throws Exception {
        String body = "{\"username\":\"newuser_test\",\"password\":\"password123\"}";
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.username").value("newuser_test"));
    }

    @Test
    @DisplayName("重复注册失败")
    void registerDuplicate() throws Exception {
        String body = "{\"username\":\"" + aliceUser + "\",\"password\":\"password123\"}";
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("密码过短注册失败")
    void registerShortPassword() throws Exception {
        String body = "{\"username\":\"shortpw_user\",\"password\":\"123\"}";
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("登录成功")
    void loginSuccess() throws Exception {
        String body = "{\"username\":\"" + aliceUser + "\",\"password\":\"password123\"}";
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists());
    }

    @Test
    @DisplayName("错误密码登录失败")
    void loginWrongPassword() throws Exception {
        String body = "{\"username\":\"" + aliceUser + "\",\"password\":\"wrongpassword\"}";
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("找回密码链接使用固定公开地址，不受 Host 请求头影响")
    void forgotPasswordIgnoresSpoofedHost() throws Exception {
        var alice = users.findByUsername(aliceUser).orElseThrow();
        alice.email = "alice-reset@example.com";
        users.saveAndFlush(alice);
        PasswordResetToken olderToken = new PasswordResetToken();
        olderToken.user = alice;
        olderToken.tokenHash = sha256("older-reset-token");
        olderToken.expiresAt = Instant.now().plusSeconds(600);
        resetTokens.saveAndFlush(olderToken);

        mvc.perform(post("/api/auth/forgot-password")
                        .header("Host", "evil.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice-reset@example.com\"}"))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(mail)
                .sendPasswordReset(
                        eq("alice-reset@example.com"),
                        argThat((String link) ->
                                link.startsWith("https://forum.test/reset?token=") && !link.contains("evil.example")));
        Assertions.assertTrue(resetTokens.findByTokenHash(olderToken.tokenHash).orElseThrow().used);
    }

    @Test
    @DisplayName("密码重置令牌只能使用一次，并立即撤销旧 JWT")
    void resetPasswordIsOneTimeAndRevokesOldJwt() throws Exception {
        var alice = users.findByUsername(aliceUser).orElseThrow();
        String rawResetToken = "known-reset-token-for-test";
        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.user = alice;
        resetToken.tokenHash = sha256(rawResetToken);
        resetToken.expiresAt = Instant.now().plusSeconds(600);
        resetTokens.saveAndFlush(resetToken);
        String resetBody = "{\"token\":\"" + rawResetToken + "\",\"newPassword\":\"new-password-456\"}";

        mvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetBody))
                .andExpect(status().isOk());

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));

        mvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetBody))
                .andExpect(status().isBadRequest());

        String oldLogin = "{\"username\":\"" + aliceUser + "\",\"password\":\"password123\"}";
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oldLogin))
                .andExpect(status().isUnauthorized());
        String newLogin = "{\"username\":\"" + aliceUser + "\",\"password\":\"new-password-456\"}";
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newLogin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists());
    }

    @Test
    @DisplayName("获取当前用户信息")
    void me() throws Exception {
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(aliceUser));
    }

    @Test
    @DisplayName("未认证访问 me 失败")
    void meUnauthenticated() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("实名认证")
    void verify() throws Exception {
        mvc.perform(patch("/api/auth/me/verified").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true));
    }

    @Test
    @DisplayName("账号注销后旧 token 立即失效")
    void deletedAccountOldTokenIsRejected() throws Exception {
        mvc.perform(delete("/api/auth/me").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    @DisplayName("账号封禁后旧 token 立即失效")
    void bannedAccountOldTokenIsRejected() throws Exception {
        var alice = users.findByUsername(aliceUser).orElseThrow();
        alice.bannedUntil = Instant.now().plusSeconds(3600);
        users.saveAndFlush(alice);

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_BANNED"));
    }

    @Test
    @DisplayName("禁言账号可读但禁止所有写请求，解除后恢复")
    void mutedAccountCanReadButCannotWrite() throws Exception {
        var alice = users.findByUsername(aliceUser).orElseThrow();
        alice.verified = true;
        alice.mutedUntil = Instant.now().plusSeconds(3600);
        users.saveAndFlush(alice);

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());

        String body = "{\"name\":\"禁言测试版块\",\"slug\":\"muted-board\",\"description\":\"测试\"}";
        mvc.perform(post("/api/forums")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_MUTED"));
        mvc.perform(patch("/api/auth/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/auth/me").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isForbidden());

        alice.mutedUntil = null;
        users.saveAndFlush(alice);
        mvc.perform(post("/api/forums")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isCreated());
    }

    // ===== 版块测试 =====
    @Test
    @DisplayName("未认证创建版块失败")
    void createForumUnauthenticated() throws Exception {
        String body = "{\"name\":\"测试版块\",\"slug\":\"" + forumSlug + "\",\"description\":\"测试\"}";
        mvc.perform(post("/api/forums").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("未实名认证创建版块失败")
    void createForumWithoutVerification() throws Exception {
        String body = "{\"name\":\"测试版块\",\"slug\":\"" + forumSlug + "\",\"description\":\"测试\"}";
        mvc.perform(post("/api/forums")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("实名认证后创建版块成功")
    void createForumAfterVerification() throws Exception {
        // 先实名认证
        mvc.perform(patch("/api/auth/me/verified").header("Authorization", "Bearer " + aliceToken));
        String body = "{\"name\":\"测试版块\",\"slug\":\"" + forumSlug + "\",\"description\":\"测试\"}";
        mvc.perform(post("/api/forums")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value(forumSlug))
                .andExpect(jsonPath("$.owner.username").value(aliceUser));
    }

    @Test
    @DisplayName("获取版块列表")
    void listForums() throws Exception {
        mvc.perform(get("/api/forums?size=50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @DisplayName("slug 格式非法创建失败")
    void createForumInvalidSlug() throws Exception {
        mvc.perform(patch("/api/auth/me/verified").header("Authorization", "Bearer " + aliceToken));
        String body = "{\"name\":\"测试版块\",\"slug\":\"INVALID SLUG\",\"description\":\"测试\"}";
        mvc.perform(post("/api/forums")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isBadRequest());
    }

    // ===== 帖子测试（依赖版块存在）=====
    void createForumAndPost() throws Exception {
        mvc.perform(patch("/api/auth/me/verified").header("Authorization", "Bearer " + aliceToken));
        String fbody = "{\"name\":\"测试版块\",\"slug\":\"" + forumSlug + "\",\"description\":\"测试\"}";
        mvc.perform(post("/api/forums")
                .contentType(MediaType.APPLICATION_JSON)
                .content(fbody)
                .header("Authorization", "Bearer " + aliceToken));
        String pbody = "{\"title\":\"测试帖子标题\",\"content\":\"测试帖子内容\"}";
        MvcResult r = mvc.perform(post("/api/forums/" + forumSlug + "/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pbody)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isCreated())
                .andReturn();
        postId = json.readTree(r.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    @DisplayName("发帖成功")
    void createPost() throws Exception {
        createForumAndPost();
        Assertions.assertNotNull(postId);
    }

    @Test
    @DisplayName("获取帖子列表")
    void listPosts() throws Exception {
        createForumAndPost();
        mvc.perform(get("/api/forums/" + forumSlug + "/posts?order=latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].title").value("测试帖子标题"));
    }

    @Test
    @DisplayName("获取帖子详情")
    void getPost() throws Exception {
        createForumAndPost();
        mvc.perform(get("/api/posts/" + postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(postId))
                .andExpect(jsonPath("$.title").value("测试帖子标题"));
    }

    @Test
    @DisplayName("作者编辑帖子")
    void editPost() throws Exception {
        createForumAndPost();
        String body = "{\"title\":\"修改后标题\",\"content\":\"修改后内容\"}";
        mvc.perform(patch("/api/posts/" + postId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("修改后标题"))
                .andExpect(jsonPath("$.edited").value(true));
    }

    @Test
    @DisplayName("非作者编辑帖子失败")
    void editPostByNonAuthor() throws Exception {
        createForumAndPost();
        String body = "{\"title\":\"恶意修改\",\"content\":\"恶意内容\"}";
        mvc.perform(patch("/api/posts/" + postId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("作者删除自己的帖子")
    void deletePostByAuthor() throws Exception {
        createForumAndPost();
        mvc.perform(delete("/api/posts/" + postId).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());
        // 删除后获取应 404
        mvc.perform(get("/api/posts/" + postId)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("非版主删除他人帖子失败")
    void deletePostByNonModerator() throws Exception {
        createForumAndPost();
        mvc.perform(delete("/api/posts/" + postId).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("版主设置精华")
    void toggleFeature() throws Exception {
        createForumAndPost();
        mvc.perform(patch("/api/posts/" + postId + "/featured?value=true")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featured").value(true));
        mvc.perform(patch("/api/posts/" + postId + "/featured?value=false")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featured").value(false));
    }

    @Test
    @DisplayName("版主锁帖")
    void toggleLock() throws Exception {
        createForumAndPost();
        mvc.perform(patch("/api/posts/" + postId + "/locked?value=true")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true));
    }

    @Test
    @DisplayName("非版主锁帖失败")
    void lockByNonModerator() throws Exception {
        createForumAndPost();
        mvc.perform(patch("/api/posts/" + postId + "/locked?value=true").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
    }

    // ===== 评论测试 =====
    void createComment() throws Exception {
        createForumAndPost();
        String body = "{\"content\":\"测试评论内容\"}";
        MvcResult r = mvc.perform(post("/api/posts/" + postId + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isCreated())
                .andReturn();
        commentId =
                json.readTree(r.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    @DisplayName("发表评论成功")
    void createCommentSuccess() throws Exception {
        createComment();
        Assertions.assertNotNull(commentId);
    }

    @Test
    @DisplayName("获取评论树")
    void getCommentTree() throws Exception {
        createComment();
        mvc.perform(get("/api/posts/" + postId + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].content").value("测试评论内容"));
    }

    @Test
    @DisplayName("版主置顶评论")
    void pinComment() throws Exception {
        createComment();
        mvc.perform(patch("/api/posts/" + postId + "/comments/" + commentId + "/pinned?value=true")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pinned").value(true));
        // 取消置顶
        mvc.perform(patch("/api/posts/" + postId + "/comments/" + commentId + "/pinned?value=false")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pinned").value(false));
    }

    @Test
    @DisplayName("非版主置顶评论失败")
    void pinCommentByNonModerator() throws Exception {
        createComment();
        mvc.perform(patch("/api/posts/" + postId + "/comments/" + commentId + "/pinned?value=true")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("锁帖后禁止评论")
    void commentOnLockedPost() throws Exception {
        createForumAndPost();
        // 先锁帖
        mvc.perform(
                patch("/api/posts/" + postId + "/locked?value=true").header("Authorization", "Bearer " + aliceToken));
        String body = "{\"content\":\"锁帖后评论\"}";
        mvc.perform(post("/api/posts/" + postId + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("作者编辑评论")
    void editComment() throws Exception {
        createComment();
        String body = "{\"content\":\"修改后的评论\"}";
        mvc.perform(patch("/api/posts/" + postId + "/comments/" + commentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("修改后的评论"))
                .andExpect(jsonPath("$.edited").value(true));
    }

    @Test
    @DisplayName("非作者编辑评论失败")
    void editCommentByNonAuthor() throws Exception {
        createComment();
        String body = "{\"content\":\"恶意修改评论\"}";
        mvc.perform(patch("/api/posts/" + postId + "/comments/" + commentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isForbidden());
    }

    // ===== 举报测试 =====
    @Test
    @DisplayName("提交举报")
    void submitReport() throws Exception {
        createForumAndPost();
        String body = "{\"targetType\":\"POST\",\"targetId\":" + postId + ",\"reason\":\"垃圾广告\"}";
        mvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("重复举报同一内容失败")
    void duplicateReport() throws Exception {
        createForumAndPost();
        String body = "{\"targetType\":\"POST\",\"targetId\":" + postId + ",\"reason\":\"垃圾广告\"}";
        mvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("举报不存在的帖子失败")
    void reportNonExistentPost() throws Exception {
        String body = "{\"targetType\":\"POST\",\"targetId\":99999,\"reason\":\"垃圾广告\"}";
        mvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("非管理员访问举报后台失败")
    void listReportsByNonAdmin() throws Exception {
        mvc.perform(get("/api/admin/reports").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("举报原因为空失败")
    void reportWithEmptyReason() throws Exception {
        createForumAndPost();
        String body = "{\"targetType\":\"POST\",\"targetId\":" + postId + ",\"reason\":\"\"}";
        mvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isBadRequest());
    }

    // ===== 互动测试 =====
    @Test
    @DisplayName("点赞帖子")
    void upvotePost() throws Exception {
        createForumAndPost();
        String body = "{\"kind\":\"UP\"}";
        mvc.perform(post("/api/posts/" + postId + "/interactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upvotes").value(1));
    }

    @Test
    @DisplayName("重复点赞取消")
    void toggleUpvote() throws Exception {
        createForumAndPost();
        String body = "{\"kind\":\"UP\"}";
        // 点赞
        mvc.perform(post("/api/posts/" + postId + "/interactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(jsonPath("$.upvotes").value(1));
        // 再点赞取消
        mvc.perform(post("/api/posts/" + postId + "/interactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(jsonPath("$.upvotes").value(0));
    }

    @Test
    @DisplayName("搜索帖子")
    void searchPosts() throws Exception {
        createForumAndPost();
        mvc.perform(get("/api/search?q=测试帖子标题").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("测试帖子标题"));
    }

    @Test
    @DisplayName("获取版块特色举报原因")
    void getReportReasons() throws Exception {
        createForumAndPost();
        mvc.perform(get("/api/forums/" + forumSlug + "/report-reasons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.standard").isArray())
                .andExpect(jsonPath("$.custom").isArray());
    }

    private static String sha256(String value) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }
}
