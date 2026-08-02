# TBForum Code Wiki

> 「随缘而遇，温和交流」的论坛应用 —— 没有推荐算法，只有偶然看见的好内容。
> 后端 Spring Boot 3.2 + Java 17 + JWT + WebSocket + Redis + Flyway，前端 React 18 + Vite + TypeScript。

---

## 一、项目概述

TBForum 是一个功能完整的轻量级论坛系统，采用前后端分离开发、一体部署模式。核心特色：

- **JWT 无状态认证**：登录/注册返回 token，前端在过期前自动 refresh，无需 refresh token 机制。
- **版块自治**：每个版块由创建者（吧主）管理，可任命最多 2 名小版主，可自定义版块特色举报原因。
- **多排序帖子流**：最新 / 热门 / 精华 / 随缘四种排序，「随缘」由客户端持有固定 `seed` 实现可复现的随机洗牌。
- **无限嵌套评论树**：邻接表 + `rootId` + `depth` 模型，内存中递归组装为嵌套树，支持评论置顶。
- **标签系统**：发帖时正则解析 `#标签`，自动建立 Post↔Tag 多对多关系，支持标签云和按标签筛选。
- **关注与互动**：关注/取关、互相关注状态、点赞/踩/收藏，同一用户对同一帖子仅保留最后一次互动。
- **实时通知**：WebSocket 推送回复/点赞/@提及/精华/关注通知，前端指数退避重连。
- **举报与管理**：常规 + 版块特色举报原因，ADMIN 后台处理；吧主/小版主可设精华/锁帖/置顶评论。
- **Redis 缓存**：热门列表 + 浏览数防刷（按用户 ID 或 IP 去重），Redis 不可用时优雅降级。

---

## 二、项目整体架构

### 2.1 分层架构

前后端分离开发，生产一体部署（前端构建产物复制到 Spring Boot 静态资源目录）。

```
┌─────────────────────────────────────────────────────────────┐
│                     浏览器 (React SPA)                       │
│              frontend/src/main.tsx (Vite 5173)              │
└──────────────┬──────────────────────────┬───────────────────┘
               │ /api/* (HTTP)            │ /ws/notifications (WebSocket)
               │ Vite dev proxy           │ 直连 8080（开发态）
               ▼                          ▼
┌─────────────────────────────────────────────────────────────┐
│                   Spring Boot 后端 (8080)                    │
│                                                              │
│  ┌───────────────┐   ┌───────────────┐   ┌───────────────┐  │
│  │ Security 层    │ → │  API 控制器    │ → │  仓储层 (JPA) │  │
│  │ JwtFilter     │   │  Api.java      │   │ Repositories  │  │
│  │ SecurityConfig│   │  (~40 端点)    │   │ (12 嵌套接口) │  │
│  └───────────────┘   └───────┬───────┘   └───────┬───────┘  │
│                              │                   │          │
│        ┌─────────────────────┼───────────────────┘          │
│        │                     │                              │
│  ┌─────▼─────┐   ┌──────────▼──────────┐   ┌────────────┐  │
│  │ 通知服务   │   │   Domain 实体 (13)   │   │ 缓存服务   │  │
│  │ + WebSocket│   │ User/Forum/Post/...  │   │ CacheService│ │
│  └───────────┘   └─────────────────────┘   └────────────┘  │
└──────────────────────────┬──────────────────────────────────┘
                           │
           ┌───────────────▼───────────────┐
           │  H2 文件库 (开发) / PostgreSQL (生产) │
           │  Redis 7 (缓存 + 防刷)          │
           └───────────────────────────────┘
```

### 2.2 请求流转链路

以「发帖」为例：

1. 浏览器 `fetch('/api/forums/{slug}/posts', {method:'POST', body, headers:{Authorization}})`
2. Vite dev server 将 `/api` 代理到 `http://localhost:8080`（生产同源直连）
3. `JwtFilter` 解析 Bearer token → `JwtService.subject()` 校验并取出 username → `Users.findByUsername()` 查到 User → 写入 `SecurityContextHolder`
4. `Api.createPost(...)` 处理业务：保存 Post → `syncTags()` 解析 #标签 → `notif.notifyMentions()` 解析 @提及 → `cache.evictByPrefix("feed:")` 清缓存
5. Spring Data JPA 落库，返回 JSON
6. 前端 `api()` 解析响应并更新 React state；若 401 则清除 token 并派发 `tb-auth-expired` 事件触发重新登录

---

## 三、技术栈与依赖关系

### 3.1 后端依赖（pom.xml）

| 依赖 | 版本 | 作用 |
|------|------|------|
| `spring-boot-starter-web` | 3.2.5 | RESTful Web 服务（内嵌 Tomcat） |
| `spring-boot-starter-data-jpa` | 3.2.5 | ORM 与仓储抽象 |
| `spring-boot-starter-security` | 3.2.5 | 认证鉴权框架 |
| `spring-boot-starter-validation` | 3.2.5 | Bean Validation（`@NotBlank`/`@Size` 等） |
| `spring-boot-starter-websocket` | 3.2.5 | 原生 WebSocket 支持（实时通知） |
| `spring-boot-starter-data-redis` | 3.2.5 | Redis 缓存（StringRedisTemplate） |
| `flyway-core` | 随 Boot | 数据库迁移管理（生产启用） |
| `jjwt-api` / `jjwt-impl` / `jjwt-jackson` | 0.12.5 | JWT 签发与解析 |
| `postgresql` | runtime | 生产数据库驱动 |
| `h2` | runtime | 开发数据库（文件模式） |
| `spring-boot-starter-test` | test | 测试支持 |

构建插件：`maven-compiler-plugin`（`fork=true`）、`spring-boot-maven-plugin`（可执行 jar）。

### 3.2 前端依赖（frontend/package.json）

| 依赖 | 版本 | 作用 |
|------|------|------|
| `react` / `react-dom` | ^18.3.1 | UI 框架 |
| `vite` + `@vitejs/plugin-react` | latest | 构建工具与 dev server |
| `typescript` | latest | 类型系统 |
| `marked` | ^18.0.7 | Markdown 解析 |
| `dompurify` | ^3.4.12 | HTML 净化防 XSS |
| `highlight.js` | ^11.11.1 | 代码高亮（按需注册 12 种语言，减小体积 72%） |
| `@types/react` / `@types/react-dom` / `@types/dompurify` | - | 类型定义 |

### 3.3 基础设施（docker-compose.yml）

- `postgres:15-alpine`：生产数据库，库/用户/密码均为 `tbforum`，端口 5432，数据持久化卷 `pg-data`。
- `redis:7-alpine`：端口 6379，用于缓存与浏览数防刷。

---

## 四、目录结构

```
an-zhao/
├── src/main/java/cn/tbforum/
│   ├── TbForumApplication.java     # 启动类（@EnableJpaRepositories considerNestedRepositories=true）
│   ├── api/
│   │   └── Api.java                # 单文件 REST 控制器（~40 端点 + DTO record）
│   ├── cache/
│   │   └── CacheService.java       # Redis 缓存（带优雅降级）
│   ├── domain/                     # 13 个 JPA 实体/枚举
│   │   ├── User.java / Role.java
│   │   ├── Forum.java / ForumModerator.java
│   │   ├── Post.java / Comment.java
│   │   ├── Interaction.java / Favorite.java
│   │   ├── Follow.java / Notification.java
│   │   ├── Report.java
│   │   └── Tag.java / PostTag.java
│   ├── notif/
│   │   ├── NotificationService.java  # 通知服务 + WebSocket Handler（合二为一）
│   │   └── WebSocketConfig.java      # @EnableWebSocket，注册 /ws/notifications
│   ├── repo/
│   │   └── Repositories.java       # 12 个嵌套 JpaRepository 接口
│   └── security/
│       ├── SecurityConfig.java     # 安全配置 + JwtFilter + CORS + 静态资源
│       └── JwtService.java         # JWT 签发与解析
├── src/main/resources/
│   ├── application.yml             # 默认配置（H2 + ddl-auto=update + Flyway 关）
│   ├── application-prod.yml         # 生产配置（PostgreSQL + ddl-auto=validate + Flyway 开）
│   ├── db/migration/
│   │   └── V1__initial_schema.sql  # Flyway 迁移（12 张表）
│   └── static/                     # 前端构建产物（Spring Boot 静态托管）
│       ├── index.html
│       └── assets/                 # 带 hash 的 JS/CSS chunk
├── frontend/
│   ├── index.html
│   ├── package.json
│   ├── vite.config.ts              # Vite 配置（端口 5173 + /api 代理）
│   └── src/
│       ├── main.tsx                # React 应用（单文件，含全部 UI 与状态逻辑）
│       ├── styles.css              # 暗色主题样式
│       └── auth.css                # 文本链接样式
├── uploads/                        # 用户上传文件（运行时生成，gitignore）
├── data/                           # H2 数据库文件（运行时生成，gitignore）
├── pom.xml
├── docker-compose.yml
├── README.md
└── CODE_WIKI.md
```

---

## 五、后端模块详解

### 5.1 启动类 — `TbForumApplication`

- 路径：[TbForumApplication.java](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/TbForumApplication.java)
- `@SpringBootApplication` + `@EnableJpaRepositories(basePackages = "cn.tbforum.repo", considerNestedRepositories = true)`
- 关键点：`Repositories.java` 把所有仓储接口声明为**嵌套接口**，必须 `considerNestedRepositories = true` 才能被扫描。

### 5.2 安全模块 — `cn.tbforum.security`

#### `SecurityConfig`
- 路径：[SecurityConfig.java](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/security/SecurityConfig.java)
- `@Configuration @EnableWebSecurity`

| Bean / 组件 | 职责 |
|-------------|------|
| `passwordEncoder()` | `BCryptPasswordEncoder` |
| `securityFilterChain` | CORS 开启、CSRF 关闭、`STATELESS` 会话、放行规则、注册 `JwtFilter` |
| `corsConfigurationSource()` | 允许 `localhost:*`、`127.0.0.1:*`、`192.168.*:*`、`10.*:*`、`172.16.*:*`（覆盖局域网） |
| `staticResources` | 注册 `/uploads/**` → `file:uploads/` |
| `JwtFilter`（内部类） | 解析 `Authorization: Bearer xxx`，校验后写入 `SecurityContextHolder`；`JwtException` 静默忽略 |

- **放行规则**（permitAll）：`/api/auth/**`、`/api/forums/**`、`/api/posts/**`、`/api/tags/**`、`/api/users/**`、`/api/notifications/**`、`/ws/**`、`/actuator/health`、`/error`、`/`、`/index.html`、`/assets/**`、`/uploads/**`
- **鉴权策略**：放行范围极广，几乎所有 GET 端点公开；写操作鉴权由 `Api` 内部 `current()`/`requireModerator()`/`requireAdmin()` 在运行时强制（未登录调用 `current()` 抛 401）。

#### `JwtService`
- 路径：[JwtService.java](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/security/JwtService.java)
- 从配置读取 `app.jwt.secret` 与 `app.jwt.expiration-hours`（默认 24h）。
- `issue(username)`：HMAC-SHA 签发 JWT，subject 为 username。
- `subject(token)`：解析验证签名返回 subject；非法时抛 `JwtException`。
- **Refresh 机制**：`/api/auth/refresh` 基于当前已认证身份重新 `issue` 新 token。无独立 refresh token、无黑名单 —— 只要旧 token 仍有效就能换新，新 token 从此刻起再延 24h。

### 5.3 缓存服务 — `cn.tbforum.cache.CacheService`

- 路径：[CacheService.java](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/cache/CacheService.java)
- `@Service`，依赖 `StringRedisTemplate`（`@Autowired(required = false)`）。
- **优雅降级**：Redis 未配置或注入失败时 `available = false`，所有方法静默空操作。

| 方法 | 作用 |
|------|------|
| `isAvailable()` | Redis 是否可用 |
| `get(key)` / `set(key, value, ttl)` | 字符串读写 |
| `evict(key)` / `evictByPrefix(prefix)` | 删除单个 / 批量删除（用 KEYS，生产大库建议改 SCAN） |
| `tryIncrementView(viewerKey, postId, window)` | 浏览数防刷：`SETNX view:{viewerKey}:{postId} 1 TTL=window`，返回 true 表示应计入 |

**缓存使用点**：
1. 版块列表第 0 页（size≤20）缓存 10 分钟，key=`forums:list:{page}:{size}`
2. 全站 feed `latest`/`hot` 第 0 页缓存 2 分钟，key=`feed:{order}:{page}:{pageSize}`
3. 发帖/编辑帖时 `evictByPrefix("feed:")`；创建版块时 `evictByPrefix("forums:list:")`
4. 浏览数防刷窗口 5 分钟，viewerKey = 登录用户 `u:{userId}` / 匿名 `ip:{clientIp}`

### 5.4 通知服务 — `cn.tbforum.notif.NotificationService`

- 路径：[NotificationService.java](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/notif/NotificationService.java)
- `@Service` **同时 extends `TextWebSocketHandler`**（一个类兼任通知服务与 WebSocket 处理器）。
- `sessions: Map<String, Set<WebSocketSession>>` 按 username 维护在线会话集合。

**通知类型**（`Notification.Kind`）：`REPLY`、`UPVOTE`、`MENTION`、`FEATURED`、`FOLLOW`

**核心方法**：
- `notify(recipient, actor, kind, text, postId, commentId)`：自己触发自己跳过；持久化 Notification；通过 WebSocket 实时推送 JSON 给 recipient 在线会话。
- `notifyMentions(content, actor, postId, commentId)`：正则 `@(\w{3,40})` 匹配用户名，去重后对存在的用户发 `MENTION` 通知。

**WebSocket 鉴权**：`afterConnectionEstablished` 时解析 `session.getUri().getQuery()` 中的 `token=xxx`，用 `jwt.subject()` 解出 username 加入 sessions。

### 5.5 WebSocket 配置 — `cn.tbforum.notif.WebSocketConfig`

- 路径：[WebSocketConfig.java](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/notif/WebSocketConfig.java)
- `@Configuration @EnableWebSocket`，注册 handler 到 `/ws/notifications`，`setAllowedOrigins("*")`。
- 原生裸 WebSocket（非 STOMP/SockJS），消息为 TextMessage JSON。

### 5.6 API 控制器 — `cn.tbforum.api.Api`

- 路径：[Api.java](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/api/Api.java)
- `@RestController @Validated @RequestMapping("/api")`，单文件聚合所有 REST 端点。
- 构造注入 12 个仓储 + `PasswordEncoder` + `JwtService` + `NotificationService` + `CacheService`。
- 内部以 `record` 定义所有请求/响应 DTO。

#### 5.6.1 端点一览

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/auth/register` | 公开 | 注册，返回 201 + Token |
| POST | `/auth/login` | 公开 | 登录，返回 Token |
| GET | `/auth/me` | 登录 | 当前用户信息 |
| POST | `/auth/refresh` | 登录 | 重新签发 token |
| PATCH | `/auth/me` | 登录 | 修改资料（用户名改动重发 token） |
| PATCH | `/auth/me/verified` | 登录 | 标记实名认证 |
| POST | `/upload` | 登录 | 图片上传（≤2MB，存 `uploads/`） |
| GET | `/forums` | 公开 | 分页版块列表（Redis 缓存 10 分钟） |
| POST | `/forums` | 登录+实名 | 创建版块 |
| GET | `/forums/{slug}` | 公开 | 版块详情 |
| POST | `/forums/{slug}/moderators/{username}` | 吧主 | 任命小版主（最多 2 名） |
| DELETE | `/forums/{slug}/moderators/{username}` | 吧主 | 撤任小版主 |
| GET | `/forums/{slug}/moderators` | 公开 | 小版主列表 |
| GET | `/forums/{slug}/posts` | 公开 | 版块帖子列表（4 种排序） |
| POST | `/forums/{slug}/posts` | 登录 | 发帖（解析 #标签、@提及） |
| GET | `/forums/{slug}/report-reasons` | 公开 | 标准 + 版块特色举报原因 |
| PATCH | `/forums/{slug}/report-reasons` | 版主/小版主 | 设置版块特色举报原因 |
| GET | `/posts/feed` | 公开 | 全站 feed（latest/hot/featured/fate） |
| GET | `/posts/{id}` | 公开 | 帖子详情（浏览数防刷 +1） |
| DELETE | `/posts/{id}` | 作者/版主 | 软删除帖子 |
| PATCH | `/posts/{id}` | 作者 | 编辑帖子（同步标签） |
| PATCH | `/posts/{id}/featured` | 版主/小版主 | 设为/取消精华 |
| PATCH | `/posts/{id}/locked` | 版主/小版主 | 锁帖/解锁 |
| POST | `/posts/{id}/interactions` | 登录 | 点赞/踩/收藏/举报（替换旧互动） |
| POST | `/posts/{id}/comments` | 登录 | 评论（锁帖抛 403） |
| GET | `/posts/{id}/comments` | 公开 | 评论嵌套树（置顶优先） |
| PATCH | `/posts/{id}/comments/{cid}` | 作者 | 编辑评论 |
| PATCH | `/posts/{id}/comments/{cid}/pinned` | 版主/小版主 | 置顶评论 |
| GET | `/tags` | 公开 | 热门标签 Top 50 |
| GET | `/tags/{name}/posts` | 公开 | 按标签筛选帖子 |
| GET | `/users/{username}` | 公开 | 用户详情（含 isFollowing/mutual） |
| GET | `/users/search` | 登录 | 用户搜索（Top 10 模糊匹配） |
| POST | `/users/{username}/follow` | 登录 | 关注（触发 FOLLOW 通知） |
| DELETE | `/users/{username}/follow` | 登录 | 取关 |
| GET | `/users/{username}/followers` | 公开 | 粉丝列表 |
| GET | `/users/{username}/following` | 公开 | 关注列表 |
| GET | `/users/{username}/posts` | 公开 | 用户发布的帖子 |
| GET | `/users/me/favorites` | 登录 | 我的收藏 |
| POST | `/posts/{id}/favorite` | 登录 | 收藏 |
| DELETE | `/posts/{id}/favorite` | 登录 | 取消收藏 |
| GET | `/notifications` | 登录 | 通知列表（Top 20） |
| GET | `/notifications/unread-count` | 登录 | 未读计数 |
| PATCH | `/notifications/{id}/read` | 登录（属主） | 标记已读 |
| PATCH | `/notifications/read-all` | 登录 | 全部已读 |
| POST | `/reports` | 登录 | 提交举报（同目标仅一次） |
| GET | `/admin/reports` | ADMIN | 举报列表（可按 status 过滤） |
| GET | `/admin/reports/count` | ADMIN | 举报统计 |
| GET | `/admin/reports/target` | ADMIN | 按目标查全部举报 |
| PATCH | `/admin/reports/{id}` | ADMIN | 处理举报（RESOLVED/DISMISSED） |
| GET | `/search` | 登录 | 关键词搜索帖子 |

#### 5.6.2 关键业务方法

**`feed` — 全站帖子流**
- `latest`：`findAllByDeletedFalseOrderByCreatedAtDesc`，第 0 页缓存 2 分钟
- `hot`：`findHotSince(now-24h)`，公式 `upvotes + commentCount*0.5 + views*0.01`，第 0 页缓存 2 分钟
- `featured`：`findByFeaturedTrueAndDeletedFalse`，不缓存
- `fate`：`findByDeletedFalse()` 后 `Collections.shuffle`（带 seed 可复现），不缓存

**`listPosts` — 版块帖子列表**：与 feed 类似但限定 forum，`fate` 在版块全量内 shuffle。

**`syncTags` — 标签同步**：`postTags.deleteByPost(p)` 清旧 → `parseTags(content)` 正则 `#([^\#\s]{2,30})` 最多 5 个去重 → 逐个找/建 Tag 并建 PostTag → `tag.postCount++`。

**`interact` — 互动替换（@Transactional）**：查旧互动 → 回滚旧 kind 计数并删除 → 保存新互动并累加 → 若相同 kind 则取消（幂等）。UP 时触发 UPVOTE 通知。

**`comment` — 评论创建（@Transactional）**：锁帖抛 403；维护 parent/rootId/depth；帖子 commentCount++；触发 REPLY 通知 + @mention 通知。

**`commentTree` — 评论树组装**：一次性取全部评论按 `pinned DESC, createdAt ASC` 排序，按 parent.id 分组递归组装。

#### 5.6.3 权限辅助方法

| 方法 | 语义 |
|------|------|
| `current()` / `currentOrNull()` | 从 SecurityContext 取当前用户（前者未登录抛 401） |
| `getForum(slug)` / `getPost(id)` | 查询不存在抛 404 |
| `requireOwner(f)` | 校验吧主，否则 403 |
| `requireModerator(f)` | 校验吧主/小版主/ADMIN |
| `requireAdmin()` | 校验 ADMIN 角色 |
| `clientIp()` | 解析 X-Forwarded-For/X-Real-IP |

### 5.7 领域模型 — `cn.tbforum.domain`

所有实体 `@Entity`，主键 `@GeneratedValue(IDENTITY)`，`createdAt` 默认 `Instant.now()` 且 `updatable=false`。字段为 `public` 直接读写。

| 实体 | 表 | 关键字段 | 关系 / 约束 |
|------|----|---------|------|
| [User](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/User.java) | `users` | username(UK,40)、passwordHash、verified、role、avatarUrl(Lob)、bio(500)、signature(100) | role: USER/ADMIN |
| [Role](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/Role.java) | - | USER, ADMIN | 枚举 |
| [Forum](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/Forum.java) | `forums` | slug(UK,64)、name(100)、description(500)、customReportReason1/2(60) | owner→User |
| [Post](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/Post.java) | `posts` | title(180)、content(TEXT)、views/upvotes/downvotes/commentCount、featured/locked/deleted/edited、editedAt | forum、author；索引 idx_posts_forum_created |
| [Comment](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/Comment.java) | `comments` | content(TEXT)、rootId、depth、pinned、deleted、edited | post、author、parent(自引用)；索引 idx_comments_post、idx_comments_parent |
| [Interaction](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/Interaction.java) | `interactions` | kind: UP/DOWN/BOOKMARK/REPORT | user、post；UK(user_id, post_id) |
| [ForumModerator](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/ForumModerator.java) | `forum_moderators` | - | forum、user；UK(forum_id, user_id) |
| [Notification](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/Notification.java) | `notifications` | kind: REPLY/UPVOTE/MENTION/FEATURED/FOLLOW、text、postId、commentId、read | user(接收者)、actor(触发者)；索引 idx_notif_user |
| [Follow](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/Follow.java) | `follows` | - | follower、followee；UK(follower_id, followee_id)；索引 idx_follow_follower、idx_follow_followee |
| [Favorite](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/Favorite.java) | `favorites` | - | user、post；UK(user_id, post_id)；索引 idx_favorite_user |
| [Report](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/Report.java) | `reports` | targetType: POST/COMMENT、targetId、reason(80)、status: PENDING/RESOLVED/DISMISSED、handledBy、handledAt | reporter、handledBy；UK(reporter_id, target_type, target_id)；索引 idx_report_status、idx_report_target |
| [Tag](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/Tag.java) | `tags` | name(UK,30)、postCount | 索引 idx_tag_name |
| [PostTag](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/PostTag.java) | `post_tags` | - | post、tag；UK(post_id, tag_id) |

### 5.8 仓储层 — `cn.tbforum.repo.Repositories`

- 路径：[Repositories.java](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/repo/Repositories.java)
- `final` 工具类，内嵌 12 个 JpaRepository 接口。

| 接口 | 自定义方法 |
|------|-----------|
| `Users` | `findByUsername`、`findTop10ByUsernameContainingIgnoreCase` |
| `Forums` | `findBySlug` |
| `Posts` | `findByForumAndDeletedFalse`、`findByForumAndFeaturedTrueAndDeletedFalse`、`findByForumAndDeletedFalse`(List)、`findByAuthorAndDeletedFalse`、`findAllByDeletedFalseOrderByCreatedAtDesc`、`findByFeaturedTrueAndDeletedFalse`、`findByDeletedFalse`(List)、`search`(@Query JPQL)、`findHotSince`(@Query 热度公式) |
| `Comments` | `findByPostAndDeletedFalseOrderByCreatedAtAsc`、`findByPostAndDeletedFalseOrderByPinnedDescCreatedAtAsc` |
| `Moderators` | `countByForum`、`existsByForumAndUser`、`findByForum`、`deleteByForumAndUser` |
| `Interactions` | `findByUserAndPost`、`findByUserAndKind` |
| `Notifications` | `findTop20ByUserOrderByCreatedAtDesc`、`countByUserAndReadFalse`、`findByUserAndReadFalse` |
| `Follows` | `findByFollowerAndFollowee`、`countByFollowee`、`countByFollower`、`findByFollowerOrderByCreatedAtDesc`、`findByFolloweeOrderByCreatedAtDesc`、`deleteByFollowerAndFollowee` |
| `Favorites` | `findByUserAndPost`、`existsByUserAndPost`、`deleteByUserAndPost`、`findPostsByUser`(@Query) |
| `Reports` | `findByStatusOrderByCreatedAtDesc`、`findAllByOrderByCreatedAtDesc`、`existsByReporterAndTargetTypeAndTargetId`、`countByStatus`、`findByTargetTypeAndTargetId` |
| `Tags` | `findByName`、`findTop50ByOrderByPostCountDesc`、`findPopular`(@Query) |
| `PostTags` | `findByPost`、`findByTag`、`findPostsByTag`(@Query)、`deleteByPost` |

---

## 六、前端模块详解

### 6.1 入口与构建

- `frontend/index.html`：极简 HTML，挂载点 `<div id="root">`，加载 `/src/main.tsx`。
- `frontend/vite.config.ts`：React 插件，dev server 监听 5173，`/api` 代理到 `http://localhost:8080`。**未配置 `build.outDir`**，默认输出 `frontend/dist/`，需手动复制到 `src/main/resources/static/`。
- `frontend/package.json`：`dev`（vite）、`build`（tsc -b && vite build）、`preview`。
- **生产部署**：前端构建产物部署到 `src/main/resources/static/`，由 Spring Boot 静态托管，前后端同源（端口 8080）。

### 6.2 应用主体 — `frontend/src/main.tsx`

- 路径：[main.tsx](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/frontend/src/main.tsx)
- 单文件包含全部 UI 与状态逻辑。

#### 类型定义

```ts
type Forum = { id, slug, name, description, owner:{username}, customReportReason1?, customReportReason2? }
type Post  = { id, title, content, forum, author, authorAvatar?, createdAt, views, upvotes, comments,
               featured, locked, edited, editedAt, favorited, tags? }
type Comment = { id, author, authorAvatar?, content, depth, createdAt, children:Comment[],
                 edited, editedAt, pinned }
type UserProfile = { id, username, verified, avatarUrl, bio, signature,
                     following, followers, isFollowing, isSelf, mutual }
type TagView = { id, name, postCount }
type Notification = { id, kind, text, actor, postId?, commentId?, createdAt, read }
type ReportReasons = { standard:string[], custom:string[] }
type ReportView = { id, reporter, targetType, targetId, reason, createdAt, status, handledBy?, handledAt? }
```

#### `api()` 函数

- 自动加 `Content-Type: application/json` 与 `Authorization: Bearer` 头
- **401 处理**：当 `res.status===401 && token` 时，清除 localStorage 的 token/user，派发 `tb-auth-expired` 全局事件触发重新登录
- **空响应处理**：`res.text()` 读取，空字符串返回 undefined，非空才 `JSON.parse`（兼容 201/204 无 body 响应）
- 错误消息提取：优先 `res.json().message`，回退 `请求失败 (${res.status})`

#### 主要状态（useState）

- **导航**：`forums`、`active`、`posts`、`order`、`query`、`page`、`totalPages`、`seed`、`globalMode`、`activeTag`、`popularTags`
- **认证**：`token`、`user`、`verified`、`isAdmin`
- **弹窗/视图**：`modal`、`notice`、`menu`、`viewPost`、`viewProfile`、`viewUser`、`confirmDel`、`ctxMenu`、`reportModal`、`modPanel`、`adminPanel`
- **帖子详情**：`comments`、`replyTo`、`editPost*`、`postForm`、`commentContent`
- **个人中心**：`profileForm`、`profileTab`、`profilePosts`、`profileFavPosts`、`profileFollowList`
- **用户主页**：`userTab`、`userPosts`、`userFollowList`
- **通知**：`notifications`、`unreadCount`、`showNotif`
- **管理**：`mods`、`modList`、`reason1/2`、`reports`、`reportFilter`、`reportPage`、`reportTotalPages`、`reportCounts`、`reportReason`、`reportSubmitting`

#### 副作用（useEffect，共 12 个）

| 依赖 | 作用 |
|------|------|
| `[menu]` | 用户菜单外部点击关闭 |
| `[showNotif]` | 通知面板外部点击关闭 |
| `[ctxMenu]` | 右键菜单外部点击/滚动/resize 关闭 |
| `[]` | 挂载时 `loadForums()` + `loadPopularTags()` |
| `[active?.slug, order, seed, page, globalMode, activeTag]` | `loadPosts()`：按 activeTag → globalMode → active 三种模式路由 |
| `[active?.slug]` | 拉取版主列表 |
| `[token]` | 调 `/api/auth/me` 获取 verified/role；401 则 logout |
| `[]` | 监听 `tb-auth-expired` 事件：logout + 清视图 + 弹登录框 |
| `[token]` | **JWT refresh 定时器**：解析 exp，在过期前 5 分钟或半程（取较小者，不早于 30s）调 `/api/auth/refresh` |
| `[token]` | 拉取通知列表 + 未读计数 |
| `[token]` | **WebSocket 连接**（指数退避重连） |
| `[mention.open]` | @提及下拉外部点击关闭（MentionTextarea 子组件） |

#### 三种主页模式（互斥）

- `globalMode`（全站动态）：`enterGlobal()` → `/api/posts/feed`
- `activeTag`（标签筛选）：`enterTag(tag)` → `/api/tags/{tag}/posts`
- `active`（版块内）：`enterForum(f)` → `/api/forums/{slug}/posts`

`loadPosts()` 按此优先级路由；`fate` 排序附加 `&seed=` 参数。

#### WebSocket 连接（指数退避重连）

- URL：`ws://localhost:8080/ws/notifications?token=${token}`（硬编码，不走 Vite 代理）
- `onopen`：重置 retryCount
- `onmessage`：数组批量前插（最多 50 条）+ 累加未读；单对象前插 +1
- `onclose`：未主动关闭时按 `Math.min(3000 * 2^retryCount, 30000)` 延迟重连（3s→6s→12s→24s→30s 封顶）
- `onerror`：调 `ws.close()` 触发 onclose 重连路径

#### 关注逻辑（toggleFollow）

- 未登录弹登录框；`isSelf` 直接返回
- 已关注 → `DELETE`；未关注 → `POST`
- 操作后重新 `GET /api/users/{username}` 刷新 viewUser（反映新 isFollowing/followers/mutual）

#### 举报弹窗

- `openReport(type, id)`：GET `/api/forums/{slug}/report-reasons` 获取 `{standard, custom}` 两组原因
- UI：常规原因 radio + 版块特色原因 radio + 自定义文本（maxLength 80）
- `submitReport()`：POST `/api/reports`

#### 上下文菜单（右键）

- 仅 `token && (canDelete || isModerator)` 时触发
- 菜单项：设为/取消精华、锁定/解锁（版主）；删除帖子（作者/版主）

#### 个人中心

- 5 个 tab：info（资料编辑）/ posts / favorites / following / followers
- 头像上传：前端校验 ≤2MB，`FormData` + 原生 `fetch('/api/upload')`（不走 api()，不带 JSON Content-Type）

#### 管理面板

- **版主面板**（modPanel）：小版主列表 + 撤任 + 版块特色举报原因编辑
- **管理员面板**（adminPanel，仅 ADMIN）：4 tab（PENDING/RESOLVED/DISMISSED/ALL）+ 分页（size=15）+ 统计

#### 子组件

- `Modal`：根据 type 渲染 登录/注册/发帖/建版 表单，发帖与建版用 `MentionTextarea` 支持 @ 提及
- `CommentView`：递归渲染评论树（按 `depth*24` 缩进），支持编辑/置顶/回复/举报
- `MentionTextarea`：textarea + @提及下拉，200ms 防抖调 `/api/users/search?q=`，最多 8 条结果

#### 主要渲染结构

```
<div className="app">
  <div className="aurora a1"/> <div className="aurora a2"/>   // 极光背景
  <header>品牌 + 搜索 + 用户菜单/通知/发布</header>
  <aside>创建版块 + 全站动态 + 版块列表 + 标签云</aside>
  <main>
    viewProfile ? 个人中心(5 tab)
    : viewUser ? 用户主页(3 tab + 关注按钮)
    : viewPost ? 帖子详情 + 评论树
    : hero + toolbar(4 排序) + feed(帖子列表) + 分页
  </main>
  {toast} {confirmDel} {ctxMenu} {Modal} {reportModal} {modPanel} {adminPanel}
</div>
```

### 6.3 样式特性

**styles.css**：
- **暗色主题**：背景 `#07101d` / 卡片 `#0c1b2d` / 边框 `#33516a` / 主色薄荷青 `#8ef5dc`
- **Aurora 极光背景**：两个固定模糊光斑（青绿 + 蓝紫），`filter:blur(100px)`，静态无动画
- **header**：`backdrop-filter:blur(18px)` 半透明毛玻璃
- **标签云**：`.tag-pill` 圆角 12px，选中态 `#12302a` 背景
- **响应式**：`@media(max-width:760px)` 隐藏 aside、缩小 hero
- 完整 Markdown 渲染样式（h1-h6、blockquote、code、table、hljs 高亮）

**auth.css**：仅定义 `.text-link`（内联文本链接样式）。

---

## 七、数据库设计

开发使用 H2 文件库（`jdbc:h2:file:./data/tbforum;MODE=PostgreSQL;AUTO_SERVER=TRUE`），生产使用 PostgreSQL 15 + Flyway 迁移。

### 7.1 表关系

```
users (id PK, username UK, passwordHash, verified, role, avatarUrl, bio, signature, createdAt)
  │
  ├─< forums (id PK, slug UK, name, description, owner_id FK, customReportReason1/2, createdAt)
  │       │
  │       ├─< forum_moderators (id PK, forum_id FK, user_id FK, UK(forum_id,user_id))
  │       │
  │       └─< posts (id PK, forum_id FK, author_id FK, title, content, createdAt,
  │                   views, upvotes, downvotes, commentCount,
  │                   featured, locked, deleted, edited, editedAt)
  │               │  索引: idx_posts_forum_created(forum_id, createdAt)
  │               │
  │               ├─< comments (id PK, post_id FK, author_id FK, parent_id FK→comments,
  │               │             rootId, depth, content, createdAt, pinned, deleted, edited, editedAt)
  │               │   索引: idx_comments_post(post_id, createdAt), idx_comments_parent(parent_id)
  │               │
  │               ├─< interactions (id PK, user_id FK, post_id FK, kind)
  │               │       UK(user_id, post_id)
  │               │
  │               ├─< favorites (id PK, user_id FK, post_id FK, createdAt)
  │               │       UK(user_id, post_id), 索引 idx_favorite_user
  │               │
  │               └─< post_tags (id PK, post_id FK, tag_id FK, UK(post_id, tag_id))
  │
  ├─< follows (id PK, follower_id FK, followee_id FK, createdAt)
  │       UK(follower_id, followee_id), 索引 idx_follow_follower, idx_follow_followee
  │
  ├─< notifications (id PK, user_id FK, actor_id FK, kind, text, postId, commentId, read, createdAt)
  │       索引 idx_notif_user(user_id, createdAt)
  │
  └─< reports (id PK, reporter_id FK, targetType, targetId, reason, status, handledBy_id FK, handledAt, createdAt)
          UK(reporter_id, target_type, target_id), 索引 idx_report_status, idx_report_target

tags (id PK, name UK, postCount)
  │  索引 idx_tag_name
  └─< post_tags (见上)
```

### 7.2 评论模型

邻接表（`parent_id` 指向父评论）+ 物化路径变体（`rootId` 指向根评论 id）+ `depth` 层级。既能快速按根评论聚合，也能在内存中递归组装嵌套树。

### 7.3 Flyway 迁移

- 路径：[src/main/resources/db/migration/](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/resources/db/migration/)
- `V1__initial_schema.sql`：创建全部 12 张表，带 `BIGSERIAL PRIMARY KEY`、外键 `REFERENCES`、唯一约束 `CONSTRAINT uk_*`、索引 `CREATE INDEX`。
- 开发环境 `spring.flyway.enabled=false`，由 Hibernate `ddl-auto=update` 维护表结构；生产环境 `spring.flyway.enabled=true` + `ddl-auto=validate`。

---

## 八、配置说明

### 8.1 application.yml（默认/开发）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `spring.datasource.url` | `${DB_URL:jdbc:h2:file:./data/tbforum;MODE=PostgreSQL;AUTO_SERVER=TRUE}` | H2 文件模式，多连接支持 |
| `spring.datasource.username` / `password` | `${DB_USERNAME:sa}` / `${DB_PASSWORD:}` | |
| `spring.jpa.hibernate.ddl-auto` | `${DDL_AUTO:update}` | 开发自动建表 |
| `spring.jpa.open-in-view` | `false` | 关闭 OSIV |
| `spring.flyway.enabled` | `${FLYWAY_ENABLED:false}` | 开发关闭 |
| `spring.data.redis.host` / `port` | `${REDIS_HOST:localhost}` / `${REDIS_PORT:6379}` | |
| `spring.servlet.multipart.max-file-size` | `2MB` | 图片上传限制 |
| `server.error.include-message` | `always` | 错误响应含 message |
| `app.jwt.secret` | `${JWT_SECRET:change-this-...}` | 开发占位串 |
| `app.jwt.expiration-hours` | `24` | JWT 有效期 |

### 8.2 application-prod.yml（生产）

| 配置项 | 生产值 | 差异说明 |
|--------|--------|------|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/tbforum` | PostgreSQL |
| `spring.jpa.hibernate.ddl-auto` | `validate` | 严格校验，不自动建表 |
| `spring.flyway.enabled` | `true` | 启用 Flyway 迁移 |
| `app.jwt.secret` | `${JWT_SECRET}` | **无默认值，必须由环境变量提供** |
| `spring.jpa.properties.hibernate.format_sql` | `false` | 关闭 SQL 格式化 |

---

## 九、项目运行方式

### 9.1 环境要求

- JDK 17+、Maven 3.x
- Node.js（含 npm）用于前端
- Docker（可选，用于启动 PostgreSQL + Redis）

### 9.2 后端启动

**开发模式（H2 文件库）**：
```powershell
mvn spring-boot:run
```

**生产模式（PostgreSQL + Flyway + Redis）**：
```powershell
docker compose up -d
$env:SPRING_PROFILES_ACTIVE='prod'
$env:JWT_SECRET='足够长的随机密钥'
mvn spring-boot:run
```

### 9.3 前端开发

```powershell
cd frontend
npm install
npm run dev
```
浏览器打开 `http://localhost:5173`，Vite 代理 `/api` 到后端 8080。

### 9.4 前端构建与部署

```powershell
cd frontend
npm run build
# 手动复制 frontend/dist/* 到 src/main/resources/static/
```
生产部署后前后端同源（端口 8080），`/api/*` 由 Spring Boot 处理，`/assets/*` 与 `/` 返回静态资源。

### 9.5 构建产物

- 后端：`mvn package` 生成可执行 jar（`target/`）
- 前端：`npm run build` 输出到 `frontend/dist/`（需复制到 `static/`）

---

## 十、核心业务流程速查

### 10.1 认证流程

1. `POST /api/auth/register` 或 `/login`，BCrypt 校验密码
2. `JwtService.issue(username)` 签发 token 返回客户端
3. 前端存 localStorage（`tb-token`、`tb-user`），后续请求带 `Authorization: Bearer`
4. `JwtFilter` 解析后写入 SecurityContext，控制器通过 `current()` 取出
5. **前端 refresh**：解析 JWT exp，在过期前 5 分钟或半程调 `POST /api/auth/refresh` 换新 token
6. **401 处理**：`api()` 收到 401 时清除 token 并派发 `tb-auth-expired` 事件，触发 logout + 弹登录框

### 10.2 权限模型

- **公开读**：版块/帖子/评论/标签/用户资料/通知列表的大部分 GET 端点
- **登录写**：发帖、评论、互动、关注、收藏、举报、上传
- **实名要求**：创建版块、被任命为小版主
- **版主/小版主**：设精华、锁帖、置顶评论、管理版块特色举报原因
- **吧主**：任命/撤任小版主
- **ADMIN**：处理举报、查看举报后台

### 10.3 帖子排序

- `latest`：按 createdAt 降序
- `hot`：最近 24h 内按 `upvotes + commentCount*0.5 + views*0.01` 降序
- `featured`：仅 featured=true，按 createdAt 降序
- `fate`：全量取出后 `Collections.shuffle`（带 seed 可复现），再分页

### 10.4 帖子互动

- 同一用户对同一帖子只能保留最后一次互动（唯一约束 + 业务替换）
- 旧互动计数回滚，新互动累加，事务保证一致
- 相同 kind 重复请求 = 取消互动（幂等）

### 10.5 评论与嵌套树

- 创建时维护 `parent` / `rootId` / `depth`
- 读取时按 `pinned DESC, createdAt ASC` 排序，按 parent 分组递归组装
- 锁帖后禁止评论（403）

### 10.6 标签系统

- 发帖/编辑时正则 `#([^\#\s]{2,30})` 解析，最多 5 个去重
- `syncTags` 全删全建，`tag.postCount` 手动维护
- 标签云 Top 50，按 postCount 降序

### 10.7 关注与互相关注

- 关注/取关触发 FOLLOW 通知
- `UserDetailView.of()` 计算 `isFollowing`/`mutual` 状态
- 前端操作后重新 GET 用户详情刷新状态

### 10.8 举报处理

- 同一用户对同一目标（POST/COMMENT）只能举报一次（唯一约束）
- 举报原因：7 个标准原因 + 版块特色原因（最多 2 个）+ 自定义文本
- ADMIN 后台可标记 RESOLVED/DISMISSED，记录 handledBy/handledAt

---

## 十一、设计备注与注意事项

1. **代码风格紧凑**：后端大量使用单行多语句与 `record`，`Api.java` 单文件承载 ~40 个端点；实体字段为 `public` 直接访问。阅读时建议配合 IDE 折叠与格式化。
2. **嵌套仓储**：`Repositories` 内嵌 12 个接口，依赖 `considerNestedRepositories = true` 才能被扫描，移除该配置会导致启动失败。
3. **JWT Refresh 非标准方案**：无独立 refresh token、无黑名单 —— 只要旧 access token 仍有效就能换新。一旦 token 泄露，在有效期内可无限续期。生产环境如需更高安全性应引入 refresh token + 黑名单机制。
4. **Security 放行过宽**：`/api/posts/**`、`/api/users/**` 等全部 permitAll，鉴权完全依赖 `Api` 内部 `current()`/`requireModerator()` 等方法在运行时强制。优点是 GET 公开访问友好；缺点是路径级权限粒度粗，完全靠方法内校验。
5. **WebSocket 鉴权薄弱**：JWT 放在 URL query string（`?token=xxx`），会被记入 access log；`setAllowedOrigins("*")` 跨域完全放开。生产环境应改用握手时校验 Header 或 Sec-WebSocket-Protocol。
6. **缓存策略**：仅缓存 forums 列表（10 分钟）和 latest/hot feed 第 0 页（2 分钟）；featured/fate 不缓存；帖子详情不缓存（仅浏览数防刷）。`evictByPrefix` 用 `KEYS` 命令，大库上性能差，生产建议改 `SCAN`。
7. **浏览数防刷**：登录用户按 `u:{userId}` 去重，匿名用户按 `ip:{clientIp}` 去重，5 分钟窗口。Redis 不可用时降级为每次 +1。
8. **前端单文件**：`main.tsx` 集中所有逻辑（~170 行压缩代码），适合当前规模；若继续扩展建议拆分组件、hooks、API 层。
9. **前端构建产物部署**：`vite.config.ts` 未配置 `build.outDir: '../src/main/resources/static'`，`frontend/dist/` → `static/` 的复制是手动步骤，可通过脚本或 CI 自动化。
10. **H2 文件库**：开发使用 `jdbc:h2:file:./data/tbforum;AUTO_SERVER=TRUE`，支持多连接（同时运行后端和 H2 控制台）。重启后端不会丢数据，但 schema 变更（新增字段）需 `ddl-auto=update` 自动维护；若遇到字段缺失错误，需停止后端、删除 `data/tbforum*.db` 文件、重启重建。
11. **Flyway 迁移**：开发环境关闭（`FLYWAY_ENABLED=false`），由 Hibernate 维护表结构；生产环境启用（`FLYWAY_ENABLED=true`）+ `ddl-auto=validate` 严格校验。修改实体后需同步更新 `V1__initial_schema.sql` 或新增 `V2__*.sql` 迁移脚本。
12. **CORS 白名单**：允许 `localhost:*`、`127.0.0.1:*`、`192.168.*:*`、`10.*:*`、`172.16.*:*`，覆盖典型局域网段，方便局域网内测试。部署到其他域名需修改 `SecurityConfig.corsConfigurationSource()`。
13. **错误处理**：前端 `api()` 统一处理 401（清 token + 弹登录框）和错误消息提取；后端通过 `ResponseStatusException` 返回标准 HTTP 状态码 + message。
