package cn.tbforum;
import cn.tbforum.domain.Role;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * P3 后端集成测试：覆盖认证、版块、帖子、评论、举报核心流程。
 * 使用 H2 内存数据库（test profile），@Transactional 隔离由 Spring TestContext 框架在测试方法执行后自动回滚事务来保证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired cn.tbforum.repo.UserRepository users;

    String aliceToken, bobToken, adminToken;
    String aliceUser="alice_test", bobUser="bob_test", adminUser="admin_test";
    String forumSlug="test-board-"+System.currentTimeMillis();
    Long postId, commentId, reportId;

    /** 注册并返回 token */
    String register(String username) throws Exception {
        String body = "{\"username\":\""+username+"\",\"password\":\"password123\"}";
        MvcResult r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    /** 带 token 发请求 */
    MvcResult auth(String token, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req).andReturn();
    }

    @BeforeEach
    void setup() throws Exception {
        aliceToken = register(aliceUser);
        bobToken = register(bobUser);
        adminToken = register(adminUser);
        // 将 admin 提升为 ADMIN 角色
        users.findByUsername(adminUser).ifPresent(u->{u.role=Role.ADMIN;users.save(u);});
    }

    // ===== 认证测试 =====
    @Test
    @DisplayName("注册成功返回 token")
    void registerSuccess() throws Exception {
        String body = "{\"username\":\"newuser_test\",\"password\":\"password123\"}";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.token").exists())
            .andExpect(jsonPath("$.username").value("newuser_test"));
    }

    @Test
    @DisplayName("重复注册失败")
    void registerDuplicate() throws Exception {
        String body = "{\"username\":\""+aliceUser+"\",\"password\":\"password123\"}";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("密码过短注册失败")
    void registerShortPassword() throws Exception {
        String body = "{\"username\":\"shortpw_user\",\"password\":\"123\"}";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("登录成功")
    void loginSuccess() throws Exception {
        String body = "{\"username\":\""+aliceUser+"\",\"password\":\"password123\"}";
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").exists());
    }

    @Test
    @DisplayName("错误密码登录失败")
    void loginWrongPassword() throws Exception {
        String body = "{\"username\":\""+aliceUser+"\",\"password\":\"wrongpassword\"}";
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
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
        mvc.perform(get("/api/auth/me"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("实名认证")
    void verify() throws Exception {
        mvc.perform(patch("/api/auth/me/verified").header("Authorization", "Bearer " + aliceToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.verified").value(true));
    }

    // ===== 版块测试 =====
    @Test
    @DisplayName("未认证创建版块失败")
    void createForumUnauthenticated() throws Exception {
        String body = "{\"name\":\"测试版块\",\"slug\":\""+forumSlug+"\",\"description\":\"测试\"}";
        mvc.perform(post("/api/forums").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("未实名认证创建版块失败")
    void createForumWithoutVerification() throws Exception {
        String body = "{\"name\":\"测试版块\",\"slug\":\""+forumSlug+"\",\"description\":\"测试\"}";
        mvc.perform(post("/api/forums").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + bobToken))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("实名认证后创建版块成功")
    void createForumAfterVerification() throws Exception {
        // 先实名认证
        mvc.perform(patch("/api/auth/me/verified").header("Authorization", "Bearer " + aliceToken));
        String body = "{\"name\":\"测试版块\",\"slug\":\""+forumSlug+"\",\"description\":\"测试\"}";
        mvc.perform(post("/api/forums").contentType(MediaType.APPLICATION_JSON).content(body)
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
        mvc.perform(post("/api/forums").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + aliceToken))
            .andExpect(status().isBadRequest());
    }

    // ===== 帖子测试（依赖版块存在）=====
    void createForumAndPost() throws Exception {
        mvc.perform(patch("/api/auth/me/verified").header("Authorization", "Bearer " + aliceToken));
        String fbody = "{\"name\":\"测试版块\",\"slug\":\""+forumSlug+"\",\"description\":\"测试\"}";
        mvc.perform(post("/api/forums").contentType(MediaType.APPLICATION_JSON).content(fbody)
                .header("Authorization", "Bearer " + aliceToken));
        String pbody = "{\"title\":\"测试帖子标题\",\"content\":\"测试帖子内容\"}";
        MvcResult r = mvc.perform(post("/api/forums/"+forumSlug+"/posts").contentType(MediaType.APPLICATION_JSON).content(pbody)
                .header("Authorization", "Bearer " + aliceToken))
            .andExpect(status().isCreated()).andReturn();
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
        mvc.perform(get("/api/forums/"+forumSlug+"/posts?order=latest"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.content[0].title").value("测试帖子标题"));
    }

    @Test
    @DisplayName("获取帖子详情")
    void getPost() throws Exception {
        createForumAndPost();
        mvc.perform(get("/api/posts/"+postId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(postId))
            .andExpect(jsonPath("$.title").value("测试帖子标题"));
    }

    @Test
    @DisplayName("作者编辑帖子")
    void editPost() throws Exception {
        createForumAndPost();
        String body = "{\"title\":\"修改后标题\",\"content\":\"修改后内容\"}";
        mvc.perform(patch("/api/posts/"+postId).contentType(MediaType.APPLICATION_JSON).content(body)
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
        mvc.perform(patch("/api/posts/"+postId).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + bobToken))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("作者删除自己的帖子")
    void deletePostByAuthor() throws Exception {
        createForumAndPost();
        mvc.perform(delete("/api/posts/"+postId).header("Authorization", "Bearer " + aliceToken))
            .andExpect(status().isNoContent());
        // 删除后获取应 404
        mvc.perform(get("/api/posts/"+postId)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("非版主删除他人帖子失败")
    void deletePostByNonModerator() throws Exception {
        createForumAndPost();
        mvc.perform(delete("/api/posts/"+postId).header("Authorization", "Bearer " + bobToken))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("版主设置精华")
    void toggleFeature() throws Exception {
        createForumAndPost();
        mvc.perform(patch("/api/posts/"+postId+"/featured?value=true").header("Authorization", "Bearer " + aliceToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.featured").value(true));
        mvc.perform(patch("/api/posts/"+postId+"/featured?value=false").header("Authorization", "Bearer " + aliceToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.featured").value(false));
    }

    @Test
    @DisplayName("版主锁帖")
    void toggleLock() throws Exception {
        createForumAndPost();
        mvc.perform(patch("/api/posts/"+postId+"/locked?value=true").header("Authorization", "Bearer " + aliceToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.locked").value(true));
    }

    @Test
    @DisplayName("非版主锁帖失败")
    void lockByNonModerator() throws Exception {
        createForumAndPost();
        mvc.perform(patch("/api/posts/"+postId+"/locked?value=true").header("Authorization", "Bearer " + bobToken))
            .andExpect(status().isForbidden());
    }

    // ===== 评论测试 =====
    void createComment() throws Exception {
        createForumAndPost();
        String body = "{\"content\":\"测试评论内容\"}";
        MvcResult r = mvc.perform(post("/api/posts/"+postId+"/comments").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + bobToken))
            .andExpect(status().isCreated()).andReturn();
        commentId = json.readTree(r.getResponse().getContentAsString()).get("id").asLong();
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
        mvc.perform(get("/api/posts/"+postId+"/comments"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].content").value("测试评论内容"));
    }

    @Test
    @DisplayName("版主置顶评论")
    void pinComment() throws Exception {
        createComment();
        mvc.perform(patch("/api/posts/"+postId+"/comments/"+commentId+"/pinned?value=true")
                .header("Authorization", "Bearer " + aliceToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pinned").value(true));
        // 取消置顶
        mvc.perform(patch("/api/posts/"+postId+"/comments/"+commentId+"/pinned?value=false")
                .header("Authorization", "Bearer " + aliceToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pinned").value(false));
    }

    @Test
    @DisplayName("非版主置顶评论失败")
    void pinCommentByNonModerator() throws Exception {
        createComment();
        mvc.perform(patch("/api/posts/"+postId+"/comments/"+commentId+"/pinned?value=true")
                .header("Authorization", "Bearer " + bobToken))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("锁帖后禁止评论")
    void commentOnLockedPost() throws Exception {
        createForumAndPost();
        // 先锁帖
        mvc.perform(patch("/api/posts/"+postId+"/locked?value=true").header("Authorization", "Bearer " + aliceToken));
        String body = "{\"content\":\"锁帖后评论\"}";
        mvc.perform(post("/api/posts/"+postId+"/comments").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + bobToken))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("作者编辑评论")
    void editComment() throws Exception {
        createComment();
        String body = "{\"content\":\"修改后的评论\"}";
        mvc.perform(patch("/api/posts/"+postId+"/comments/"+commentId).contentType(MediaType.APPLICATION_JSON).content(body)
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
        mvc.perform(patch("/api/posts/"+postId+"/comments/"+commentId).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + aliceToken))
            .andExpect(status().isForbidden());
    }

    // ===== 举报测试 =====
    @Test
    @DisplayName("提交举报")
    void submitReport() throws Exception {
        createForumAndPost();
        String body = "{\"targetType\":\"POST\",\"targetId\":"+postId+",\"reason\":\"垃圾广告\"}";
        mvc.perform(post("/api/reports").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + bobToken))
            .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("重复举报同一内容失败")
    void duplicateReport() throws Exception {
        createForumAndPost();
        String body = "{\"targetType\":\"POST\",\"targetId\":"+postId+",\"reason\":\"垃圾广告\"}";
        mvc.perform(post("/api/reports").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + bobToken))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/reports").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + bobToken))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("举报不存在的帖子失败")
    void reportNonExistentPost() throws Exception {
        String body = "{\"targetType\":\"POST\",\"targetId\":99999,\"reason\":\"垃圾广告\"}";
        mvc.perform(post("/api/reports").contentType(MediaType.APPLICATION_JSON).content(body)
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
        String body = "{\"targetType\":\"POST\",\"targetId\":"+postId+",\"reason\":\"\"}";
        mvc.perform(post("/api/reports").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + bobToken))
            .andExpect(status().isBadRequest());
    }

    // ===== 互动测试 =====
    @Test
    @DisplayName("点赞帖子")
    void upvotePost() throws Exception {
        createForumAndPost();
        String body = "{\"kind\":\"UP\"}";
        mvc.perform(post("/api/posts/"+postId+"/interactions").contentType(MediaType.APPLICATION_JSON).content(body)
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
        mvc.perform(post("/api/posts/"+postId+"/interactions").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + bobToken))
            .andExpect(jsonPath("$.upvotes").value(1));
        // 再点赞取消
        mvc.perform(post("/api/posts/"+postId+"/interactions").contentType(MediaType.APPLICATION_JSON).content(body)
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
        mvc.perform(get("/api/forums/"+forumSlug+"/report-reasons"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.standard").isArray())
            .andExpect(jsonPath("$.custom").isArray());
    }
}
