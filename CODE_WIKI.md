# TBForum Code Wiki

> 「随缘而遇，温和交流」的论坛应用 —— 没有推荐算法，只有偶然看见的好内容。
> 后端 Spring Boot 3.2 + Java 17 + JWT + WebSocket + Redis + Flyway + Micrometer，前端 React 18 + Vite + TypeScript + React Query。

---

## 一、项目概述

TBForum 是一个功能完整的轻量级论坛系统，采用前后端分离开发、一体部署模式。核心特色：

- **JWT 无状态认证**：登录/注册返回 token，前端在过期前自动 refresh；无效 token 返回 401 而非静默降级。
- **2026-08 新增**：History API 路由（`/post/:id` 等）、评论"加载更多"分页、游客搜索、编辑器预览/插图/#标签补全/草稿、点赞状态可视化、版主举报闭环、封禁/禁言与审计日志、通知分组与评论定位、移动端抽屉、浅色主题、骨架屏、SVG 图标、RSS/sitemap、热门排序时间衰减、邮箱找回密码、WebSocket 未读去重。
- **限流防护**：登录 5 次/分钟、注册 3 次/分钟、上传 10 次/分钟（Bucket4j）。
- **版块自治**：每个版块由创建者（吧主）管理，可任命最多 2 名小版主，可自定义版块特色举报原因，支持版块下线（软删除）。
- **多排序帖子流**：最新 / 热门 / 精华 / 随缘四种排序。「随缘」用 `MOD(id*seed, 1000003)` 伪随机算法 + DB 分页，避免全表加载 OOM；「热门」用 `findHotSince` DB 分页。
- **无限嵌套评论树**：邻接表 + `rootId` + `depth` 模型，按根评论分页返回 `Page<CommentView>`，支持评论置顶、软删除（显示「[已删除]」占位）。
- **标签系统**：发帖时正则解析 `#标签`，自动建立 Post↔Tag 多对多关系，`tag.postCount` 原子 SQL 更新防并发丢失。
- **关注与互动**：关注/取关、互相关注状态、点赞/踩/收藏，同一用户对同一帖子仅保留最后一次互动（TOCTOU 竞态处理）。
- **实时通知**：WebSocket 推送回复/点赞/@提及/精华/关注通知，前端指数退避重连；后端通过 Redis Pub/Sub 支持多实例通知与账号断连，实例 ID 防止自身回声，Redis 不可用时降级为本地处理。
- **举报与管理**：常规 + 版块特色举报原因，ADMIN 后台处理；软删帖后自动关闭关联举报。
- **Redis 缓存**：热门列表 + 浏览数防刷（按用户 ID 或 IP 去重），Redis 不可用时优雅降级。
- **可观测性**：Micrometer Prometheus 监控（缓存命中率、WS 活跃连接数、发帖计数器），Actuator 健康检查（启用 Redis 时包含 Redis 状态）。
- **存储抽象**：`StorageService` 接口 + `LocalStorageService` 实现，未来切换 MinIO 只需新增实现类。
- **集中配置**：`AppProperties`（`@ConfigurationProperties`）管理标签限制、缓存 TTL、浏览防刷窗口、上传限制等参数，消除代码中的魔法数字。

---

## 二、项目整体架构

### 2.1 分层架构

前后端分离开发，生产一体部署（前端构建产物复制到 Spring Boot 静态资源目录）。后端采用标准的 Controller → Service → Repository 分层。

```
┌─────────────────────────────────────────────────────────────┐
│                     浏览器 (React SPA)                       │
│          frontend/src/main.tsx (Vite 5173)                  │
│   Provider 树: QueryClient → Auth → Notification → App      │
└──────────────┬──────────────────────────┬───────────────────┘
               │ /api/* (HTTP)            │ /ws/notifications (WebSocket)
               │ Vite dev proxy           │ Sec-WebSocket-Protocol: bearer.{token}
               ▼                          ▼
┌─────────────────────────────────────────────────────────────┐
│                   Spring Boot 后端 (8089)                    │
│                                                              │
│  ┌───────────────┐   ┌───────────────┐   ┌───────────────┐  │
│  │ Security 层    │ → │  Controller   │ → │   Service     │  │
│  │ JwtFilter     │   │  (14 个控制器) │   │  (11 个服务)   │  │
│  │ RateLimitFilter│  │               │   │               │  │
│  │ SecurityConfig │   └───────┬───────┘   └───────┬───────┘  │
│  └───────────────┘           │                   │          │
│        ┌─────────────────────┼───────────────────┘          │
│        │                     │                              │
│  ┌─────▼─────┐   ┌──────────▼──────────┐   ┌────────────┐  │
│  │ 通知服务   │   │ Repository 层 (14)  │   │ 缓存服务   │  │
│  │ + WebSocket│   │ + Domain 实体 (15)  │   │ + 指标记录 │  │
│  │ + Redis PS │   │ + DTO (22 record)   │   └────────────┘  │
│  └───────────┘   └─────────────────────┘                    │
│                                                              │
│  ┌─────────────┐   ┌─────────────┐   ┌─────────────────┐   │
│  │ AppProperties│   │ StorageService│   │ WebExceptionHandler│  │
│  │ (集中配置)   │   │ (存储抽象)   │   │ (全局异常处理)   │   │
│  └─────────────┘   └─────────────┘   └─────────────────┘   │
└──────────────────────────┬──────────────────────────────────┘
                           │
           ┌───────────────▼───────────────┐
           │  H2 文件库 (开发) / PostgreSQL (生产) │
           │  Redis 7 (缓存 + 防刷 + Pub/Sub)    │
           └───────────────────────────────┘
```

### 2.2 请求流转链路

以「发帖」为例：

1. 浏览器 `fetch('/api/forums/{slug}/posts', {method:'POST', body, headers:{Authorization}})`
2. Vite dev server 将 `/api` 代理到 `http://127.0.0.1:8089`（生产同源直连）
3. `RateLimitFilter` 检查限流（如上传端点 10 次/分钟）
4. `JwtFilter` 解析 Bearer token → `JwtService.subject()` 校验并取出 username → `UserRepository.findByUsername()` 查到 User → 写入 `SecurityContextHolder`；非法 token 返回 401
5. `ForumController.createPost(...)` → `PostService.createPost(...)` 处理业务：保存 Post → `syncTags()` 解析 #标签（原子更新 postCount）→ `notif.notifyMentions()` 解析 @提及（支持中文用户名正则 `@([\w\u4e00-\u9fa5]{2,40})`）→ `cache.evictByPrefix("feed:")` 清缓存 → `meterRegistry.counter("tbforum.posts.created").increment()` 记录指标
6. Spring Data JPA 落库，返回 JSON
7. 前端 React Query 自动解析响应并更新缓存；若 401 则清除 token 并派发 `tb-auth-expired` 事件触发重新登录

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
| `spring-boot-starter-data-redis` | 3.2.5 | Redis 缓存 + Pub/Sub（StringRedisTemplate） |
| `spring-boot-starter-actuator` | 3.2.5 | 健康检查、指标暴露 |
| `micrometer-registry-prometheus` | 随 Boot | Prometheus 指标格式导出 |
| `flyway-core` | 随 Boot | 数据库迁移管理（生产启用） |
| `jjwt-api` / `jjwt-impl` / `jjwt-jackson` | 0.12.5 | JWT 签发与解析 |
| `bucket4j-core` | 8.10.1 | 限流（登录/注册/上传） |
| `imageio-webp` | 3.14.0 | WebP 完整解码校验与内存分配防护 |
| `postgresql` | runtime | 生产数据库驱动 |
| `h2` | runtime | 开发数据库（文件模式） |
| `spring-boot-starter-test` | test | 测试支持 |

构建插件：`maven-compiler-plugin`（`fork=true`）、`spring-boot-maven-plugin`（可执行 jar）、`spotless-maven-plugin` 3.10.0（Palantir Java Format 2.97.0；`verify` 阶段自动检查 Java 格式）。

### 3.2 前端依赖（frontend/package.json）

| 依赖 | 版本 | 作用 |
|------|------|------|
| `react` / `react-dom` | ^18.3.1 | UI 框架 |
| `@tanstack/react-query` | ^5.101.4 | 服务端状态管理（数据获取、缓存、失效） |
| `vite` + `@vitejs/plugin-react` | ^5.2.10 | 构建工具与 dev server |
| `typescript` | ^5.4.5 | 类型系统 |
| `marked` | ^18.0.7 | Markdown 解析 |
| `dompurify` | ^3.4.12 | HTML 净化防 XSS |
| `highlight.js` | ^11.11.1 | 代码高亮（按需注册 12 种语言，减小体积 72%） |
| `@types/react` / `@types/react-dom` | - | 类型定义 |

### 3.3 基础设施（docker-compose.yml）

- `postgres:15-alpine`：生产数据库，库/用户/密码均为 `tbforum`（密码支持 `POSTGRES_PASSWORD` 覆盖），端口 5432，数据持久化卷 `pg-data`，健康检查 `pg_isready`。
- `redis:7-alpine`：端口 6379，带 `--requirepass`（密码支持 `REDIS_PASSWORD` 覆盖），健康检查 `redis-cli ping`。

---

## 四、目录结构

```
an-zhao/
├── .editorconfig                    # 编辑器通用缩进、编码与行尾规则
├── .gitattributes                   # Git 文本文件统一使用 LF
├── src/main/java/cn/tbforum/
│   ├── TbForumApplication.java       # 启动类
│   ├── cache/
│   │   └── CacheService.java         # Redis 缓存/发布（熔断恢复 + 指标记录）
│   ├── config/
│   │   ├── AppProperties.java        # 集中配置（@ConfigurationProperties）
│   │   └── RedisConfig.java          # 条件化 Redis Pub/Sub 监听容器
│   ├── controller/                   # REST 控制器（12 个）
│   │   ├── AuthController.java       # 认证：注册/登录/refresh/me/verify
│   │   ├── ForumController.java      # 版块：CRUD/版主/举报原因
│   │   ├── PostController.java       # 帖子：feed/详情/编辑/删除/精华/锁帖
│   │   ├── CommentController.java    # 评论：创建/编辑/置顶/树查询
│   │   ├── InteractionController.java # 互动：点赞/踩
│   │   ├── FollowController.java     # 关注：关注/取关/列表
│   │   ├── TagController.java        # 标签：热门/按标签筛选
│   │   ├── NotificationController.java # 通知：列表/未读/已读
│   │   ├── ReportController.java     # 举报：提交/Admin 处理
│   │   ├── UploadController.java     # 图片格式、尺寸、像素及完整解码校验
│   │   ├── AdminController.java      # 管理员后台
│   │   └── WebExceptionHandler.java  # 全局异常处理（@RestControllerAdvice）
│   ├── domain/                       # JPA 实体/枚举
│   │   ├── User.java / Role.java
│   │   ├── Forum.java / ForumModerator.java
│   │   ├── Post.java / Comment.java
│   │   ├── Interaction.java / Favorite.java
│   │   ├── Follow.java / Notification.java
│   │   ├── Report.java
│   │   ├── Tag.java / PostTag.java
│   │   └── UploadedFile.java         # 上传所有者、用途、引用状态与过期时间
│   ├── dto/
│   │   └── Dtos.java                 # 19 个 record DTO（请求/响应）
│   ├── notif/
│   │   ├── NotificationService.java  # 通知服务 + WebSocket Handler + 跨实例事件
│   │   └── WebSocketConfig.java      # @EnableWebSocket，注册 /ws/notifications
│   ├── repo/                         # 12 个 JpaRepository 接口
│   │   ├── UserRepository.java / ForumRepository.java
│   │   ├── PostRepository.java / CommentRepository.java
│   │   ├── InteractionRepository.java / FavoriteRepository.java
│   │   ├── FollowRepository.java / NotificationRepository.java
│   │   ├── ReportRepository.java / TagRepository.java
│   │   ├── PostTagRepository.java / ModeratorRepository.java
│   │   └── UploadedFileRepository.java # 配额、引用与过期文件锁定查询
│   ├── security/
│   │   ├── SecurityConfig.java       # 安全配置 + JwtFilter + CORS + 限流放行
│   │   ├── JwtService.java           # JWT 签发与解析
│   │   └── RateLimitFilter.java      # Bucket4j 限流过滤器
│   └── service/                      # 业务服务层（8 个）
│       ├── UserService.java          # 用户：注册/登录/资料/实名
│       ├── ForumService.java         # 版块：创建/版主管理/下线
│       ├── PostService.java          # 帖子：发帖/编辑/删除/feed/搜索
│       ├── CommentService.java       # 评论：创建/编辑/树组装/分页
│       ├── InteractionService.java   # 互动：点赞/踩/收藏
│       ├── ReportService.java        # 举报：提交/处理/关联关闭
│       ├── ManagedUploadService.java # 上传登记、配额、引用确认和过期清理
│       ├── StorageService.java       # 存储抽象接口
│       └── LocalStorageService.java  # 目录隔离 + UUID 原子新建存储
├── src/main/resources/
│   ├── application.yml               # 默认配置（H2 + ddl-auto=update + Actuator）
│   ├── application-prod.yml          # 生产配置（PostgreSQL + ddl-auto=validate + Flyway）
│   ├── db/migration/
│   │   ├── V1__initial_schema.sql    # Flyway 初始 schema（12 张表）
│   │   ├── V2__add_soft_delete.sql   # 软删除字段（users.deleted, forums.deleted）
│   │   └── V6__uploaded_file_lifecycle.sql # 上传文件生命周期表与索引
│   └── static/                       # 前端构建产物（Spring Boot 静态托管）
├── src/test/
│   ├── java/cn/tbforum/ApiTest.java  # 集成测试
│   └── resources/application-test.yml # 测试配置（限流禁用）
├── frontend/
│   ├── index.html
│   ├── package.json
│   ├── vite.config.ts                # Vite 配置（端口 5173 + /api 代理到 8089）
│   ├── tsconfig.json
│   └── src/
│       ├── main.tsx                  # 入口：Provider 树（QueryClient→Auth→Notification→App）
│       ├── App.tsx                   # 主组件：组装所有页面 + lazy 代码分割
│       ├── types.ts                  # 全局类型定义
│       ├── styles.css                # 暗色主题样式
│       ├── auth.css                  # 登录/注册样式
│       ├── vite-env.d.ts             # Vite 类型声明
│       ├── components/               # UI 组件（14 个）
│       │   ├── Header.tsx            # 顶栏：品牌/搜索/用户菜单/通知/发布
│       │   ├── Sidebar.tsx           # 侧栏：版块列表/标签云
│       │   ├── PostList.tsx          # 帖子列表：hero/排序/卡片/分页
│       │   ├── PostDetail.tsx        # 帖子详情：正文/评论树
│       │   ├── CommentView.tsx       # 递归评论组件
│       │   ├── ProfileView.tsx       # 个人中心：资料/帖子/收藏/关注
│       │   ├── Modal.tsx             # 弹窗：登录/注册/发帖/建版
│       │   ├── MentionTextarea.tsx   # @提及输入框
│       │   ├── ConfirmModal.tsx      # 确认弹窗
│       │   ├── ContextMenu.tsx       # 右键菜单
│       │   ├── ReportModal.tsx       # 举报弹窗
│       │   ├── ModPanel.tsx          # 版主面板（lazy 加载）
│       │   ├── AdminPanel.tsx        # 管理员面板（lazy 加载）
│       │   └── ErrorBoundary.tsx     # 错误边界
│       ├── contexts/                 # React Context（2 个）
│       │   ├── AuthContext.tsx       # 认证状态：token/user/verified/isAdmin
│       │   └── NotificationContext.tsx # 通知状态 + WebSocket 连接
│       ├── hooks/                    # 自定义 Hooks（7 个）
│       │   ├── useForums.ts          # 版块列表 + 热门标签
│       │   ├── usePosts.ts           # 帖子列表（React Query）
│       │   ├── usePostDetail.ts      # 帖子详情 + 评论树
│       │   ├── useUserProfile.ts     # 用户资料 + 关注列表
│       │   ├── useReportState.ts     # 举报状态
│       │   ├── useModState.ts        # 版主状态
│       │   └── useAdminState.ts      # 管理员状态
│       ├── services/
│       │   └── api.ts               # API 服务层（集中管理 fetch 请求）
│       └── utils/
│           ├── format.ts            # 时间格式化
│           └── markdown.ts          # Markdown 渲染 + DOMPurify 净化
├── uploads/                          # 用户上传文件（运行时生成，gitignore）
├── data/                             # H2 数据库文件（运行时生成，gitignore）
├── pom.xml
├── docker-compose.yml
├── README.md
└── CODE_WIKI.md
```

---

## 五、后端模块详解

### 5.1 启动类 — `TbForumApplication`

- 路径：[TbForumApplication.java](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/TbForumApplication.java)
- `@SpringBootApplication` + `@EnableJpaRepositories(basePackages = "cn.tbforum.repo")`

### 5.2 安全模块 — `cn.tbforum.security`

#### `SecurityConfig`
- 路径：[SecurityConfig.java](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/security/SecurityConfig.java)
- `@Configuration @EnableWebSecurity`

| Bean / 组件 | 职责 |
|-------------|------|
| `passwordEncoder()` | `BCryptPasswordEncoder` |
| `securityFilterChain` | CORS 开启、CSRF 关闭、`STATELESS` 会话、安全响应头、放行规则、注册 `JwtFilter` |
| `corsConfigurationSource()` | 允许 `localhost:*`、`127.0.0.1:*`、`192.168.*:*`、`10.*:*`、`172.16.*:*`（覆盖局域网） |
| `JwtFilter`（内部类） | 解析 `Authorization: Bearer xxx`，校验签名、用户状态和 `tokenVersion` 后写入 `SecurityContextHolder`；无效 token 返回 401 |

- **安全响应头**：`X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、HSTS（31536000s）、CSP（`default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' ...`）
- **放行规则**（permitAll）：GET `/api/forums/**`、`/api/posts/**`、`/api/tags/**`、`/api/search`；GET `/api/users/*`、`/api/users/search`、`/api/users/*/followers`、`/api/users/*/following`；POST `/api/auth/register`、`/api/auth/login`；`/actuator/health`、`/actuator/info`、`/actuator/prometheus`、`/error`、`/`、`/index.html`、`/assets/**`、`/uploads/**`、`/ws/**`
- **API 白名单**：`/api/users/*` 限定为 `me`/`favorites` 子路径，防止用户数据暴露

#### `JwtService`
- 路径：[JwtService.java](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/security/JwtService.java)
- 从配置读取 `app.jwt.secret` 与 `app.jwt.expiration-hours`（默认 24h）。
- `issue(username, tokenVersion)`：HMAC-SHA 签发 JWT，subject 为 username，并写入服务端会话版本 `ver`。
- `claims(token)`：验证签名并返回 username 与 tokenVersion；非法时抛 `JwtException`。
- **账号状态校验**：JWT 和 WebSocket 握手时查询用户状态并比对 tokenVersion；注销、封禁、密码重置或版本不匹配均拒绝旧 token；禁言账号允许只读请求，统一拒绝写请求并返回 403。
- **Refresh 机制**：`/api/auth/refresh` 基于当前已认证身份重新 `issue` 新 token。

#### `RateLimitFilter`
- 路径：[RateLimitFilter.java](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/security/RateLimitFilter.java)
- Bucket4j 限流：登录 5 次/分钟、注册 3 次/分钟、上传 10 次/分钟。
- 客户端 IP 默认取 TCP 对端地址；仅当对端命中 `trustedProxies` 时解析 `X-Forwarded-For` / `X-Real-IP`，并从代理链右侧选择第一个非可信地址，避免伪造最左侧地址绕过限流；浏览数防刷复用同一解析器。
- Bucket 使用 Caffeine 保存，默认最多 10000 条，闲置 10 分钟回收；429 响应包含 `Retry-After: 60`。
- 可通过 `app.rate-limit.enabled=false` 禁用（测试环境）。

### 5.3 配置 — `cn.tbforum.config.AppProperties`

- 路径：[AppProperties.java](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/config/AppProperties.java)
- `@Configuration @ConfigurationProperties(prefix="app")`，集中管理魔法数字：

| 配置组 | 属性 | 默认值 |
|--------|------|--------|
| `Upload` | maxFileSize | 2MB |
| `Tag` | maxPerPost / minLength / maxLength | 5 / 2 / 30 |
| `Moderator` | maxPerForum | 2 |
| `Cache` | feedTtl / forumListTtl | 2 分钟 / 10 分钟 |
| `View` | dedupWindow | 5 分钟 |
| `RateLimit` | maxBuckets / expireAfterAccess / trustedProxies | 10000 / 10 分钟 / 空列表 |
| `Redis` | enabled / failureCooldown | false / 30 秒 |

### 5.4 缓存服务 — `cn.tbforum.cache.CacheService`

- 路径：[CacheService.java](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/cache/CacheService.java)
- `@Service`，通过 `ObjectProvider` 可选获取 `StringRedisTemplate` 与 `MeterRegistry`。
- **显式启用**：开发默认 `app.redis.enabled=false`；关闭时不访问 Redis，也不启动 Pub/Sub 监听容器。
- **熔断恢复**：操作失败后进入默认 30 秒冷却期，期间不重复连接；冷却后由下一次操作探测恢复。故障和恢复各记录一次限频日志及指标。

| 方法 | 作用 |
|------|------|
| `isAvailable()` | 最近一次操作成功且当前未处于冷却期时为 true |
| `get(key)` / `set(key, value, ttl)` | 字符串读写，记录命中/未命中指标 |
| `evict(key)` / `evictByPrefix(prefix)` | 删除单个 / 批量删除（用 SCAN） |
| `tryIncrementView(viewerKey, postId, window)` | 浏览数防刷：`SETNX view:{viewerKey}:{postId} 1 TTL=window` |
| `publish(channel, payload)` | 使用同一熔断状态发布 Pub/Sub 事件 |

**缓存/Redis 指标**：`tbforum.cache.requests{result=hit|miss}`、`tbforum.redis.available`、`tbforum.redis.circuit.events{event=opened|recovered}`

### 5.5 通知服务 — `cn.tbforum.notif.NotificationService`

- 路径：[NotificationService.java](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/notif/NotificationService.java)
- `@Service` **同时 extends `TextWebSocketHandler`**。
- `sessions: Map<String, Set<WebSocketSession>>` 按 username 维护在线会话集合。
- **Redis Pub/Sub 多实例广播**：`RedisConfig` 在启用 Redis 时显式创建监听容器；事件分为 `notification` 与 `disconnect`，分别负责跨实例推送和注销/封禁/密码重置后的连接关闭。事件携带实例 ID，发送实例忽略自身回声。
- **事务安全**：WS 消息在事务提交后（`afterCommit`）才发送，避免回滚后消息已发。

**通知类型**（`Notification.Kind`）：`REPLY`、`UPVOTE`、`MENTION`、`FEATURED`、`FOLLOW`

**mention 正则**：`@([\w\u4e00-\u9fa5]{2,40})`，支持中文用户名。

**WebSocket 鉴权**：从 `Sec-WebSocket-Protocol` header 提取 `bearer.{token}`（非 URL query），验证 JWT 后继续查询用户状态；注销或封禁账号拒绝握手，并在状态事务提交后主动关闭已有连接；禁言账号仍可接收通知。

**WS 指标**：`tbforum.ws.connections.active`（gauge，本实例活跃连接数）、`tbforum.ws.pubsub.events{type,result}`

### 5.6 WebSocket 配置 — `cn.tbforum.notif.WebSocketConfig`

- 路径：[WebSocketConfig.java](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/notif/WebSocketConfig.java)
- `@Configuration @EnableWebSocket`，注册 handler 到 `/ws/notifications`。
- 使用自定义 `HandshakeInterceptor` 在握手阶段提取 `Sec-WebSocket-Protocol` 中的 token，并校验用户是否存在、注销或处于封禁期。

### 5.7 控制器层 — `cn.tbforum.controller`

14 个 `@RestController`，按业务域拆分：

| 控制器 | 路径前缀 | 核心端点 |
|--------|---------|---------|
| [AuthController](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/controller/AuthController.java) | `/api/auth` | register, login, me, refresh, updateProfile, verify, forgotPassword, resetPassword |
| [ForumController](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/controller/ForumController.java) | `/api/forums` | list, create, get, moderators, reportReasons, posts |
| [PostController](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/controller/PostController.java) | `/api/posts` | feed, get, edit, delete, feature, lock, interactions |
| [CommentController](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/controller/CommentController.java) | `/api/posts/{id}/comments` | create, tree(分页), edit, pin |
| [InteractionController](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/controller/InteractionController.java) | `/api/posts/{id}` | interactions, favorite |
| [FollowController](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/controller/FollowController.java) | `/api/users` | follow, followers, following |
| [TagController](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/controller/TagController.java) | `/api/tags` | popular, postsByTag |
| [NotificationController](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/controller/NotificationController.java) | `/api/notifications` | list, unreadCount, markRead, markAllRead |
| [ReportController](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/controller/ReportController.java) | `/api/reports` | submit, moderatorList, moderatorCounts, handleModerated |
| [UploadController](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/controller/UploadController.java) | `/api/upload` | `purpose=AVATAR|POST_IMAGE`、魔数、尺寸、像素、完整解码与重编码校验 |
| [UploadResourceController](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/controller/UploadResourceController.java) | `/uploads/{filename}` | 仅读取数据库状态为 `REFERENCED` 的图片，限制媒体类型并返回 `nosniff` 与不可变缓存头 |
| [AdminController](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/controller/AdminController.java) | `/api/admin` | 举报 / 用户治理（封禁/禁言）/ 审计日志 |
| [WebExceptionHandler](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/controller/WebExceptionHandler.java) | `@RestControllerAdvice` | 全局异常处理 |

### 5.8 服务层 — `cn.tbforum.service`

| 服务 | 职责 |
|------|------|
| [UserService](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/service/UserService.java) | 注册（TOCTOU 处理）、登录、资料更新、实名认证、当前用户获取 |
| [ForumService](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/service/ForumService.java) | 版块创建、版主管理、版块下线（软删除）、权限校验 |
| [PostService](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/service/PostService.java) | 发帖、编辑、软删除（关闭关联举报）、标签同步（原子计数）、feed 多排序、搜索、缓存 |
| [CommentService](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/service/CommentService.java) | 评论创建（锁帖检查）、编辑、树组装（分页 `Page<CommentView>`）、软删除占位 |
| [InteractionService](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/service/InteractionService.java) | 点赞/踩（替换式 + 原子计数）、收藏 |
| [ReportService](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/service/ReportService.java) | 举报提交（唯一约束）、Admin 处理、软删帖自动关闭关联举报 |
| [AuditService](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/service/AuditService.java) | 审计日志：封禁/举报处理/删帖/设精/锁帖留痕，Admin 可查 |
| [MailService](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/service/MailService.java) / [ConsoleMailService](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/service/ConsoleMailService.java) | 邮件发送抽象（开发环境将密码重置链接打印到控制台） |
| [StorageService](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/service/StorageService.java) | 存储、受控读取与删除抽象（未来切 MinIO 只需新增实现） |
| [LocalStorageService](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/service/LocalStorageService.java) | 本地文件存储（规范化目录、UUID 文件名、`CREATE_NEW` 禁止覆盖、路径与符号链接防护） |
| [ManagedUploadService](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/service/ManagedUploadService.java) | 上传登记、用户配额锁、引用同步、受控读取、过期及释放文件清理 |
| [LegacyUploadReconciler](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/service/LegacyUploadReconciler.java) | 启动时按主键分批、幂等登记旧头像和帖子中的本地图片引用 |

**业务指标**：`tbforum.posts.created_total{forum=...}`、`tbforum.uploads.stored_total{purpose=...}`、`tbforum.uploads.deleted_total{reason=...}`、`tbforum.uploads.quota_rejected_total`、`tbforum.uploads.delete_failures_total`、`tbforum.uploads.legacy_reconciled_total{purpose=...}`。

### 5.9 领域模型 — `cn.tbforum.domain`

所有实体 `@Entity`，主键 `@GeneratedValue(IDENTITY)`，`createdAt` 默认 `Instant.now()` 且 `updatable=false`。字段为 `public` 直接读写。

| 实体 | 表 | 关键字段 | 关系 / 约束 |
|------|----|---------|------|
| [User](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/User.java) | `users` | username(UK,40)、passwordHash、verified、deleted、role、avatarUrl(Lob)、bio(500)、signature(100)、email(255)、bannedUntil、mutedUntil | role: USER/ADMIN；deleted 用于软删除 |
| [Role](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/Role.java) | - | USER, ADMIN | 枚举 |
| [Forum](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/Forum.java) | `forums` | slug(UK,64)、name(100)、description(500)、deleted、customReportReason1/2(60) | owner→User；deleted 用于下线 |
| [Post](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/Post.java) | `posts` | title(180)、content(TEXT)、views/upvotes/downvotes/commentCount、featured/locked/deleted/edited、editedAt | forum、author；索引 idx_posts_forum_created |
| [Comment](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/Comment.java) | `comments` | content(TEXT)、rootId、depth、pinned、deleted、edited | post、author、parent(自引用)；索引 idx_comments_post、idx_comments_parent |
| [Interaction](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/Interaction.java) | `interactions` | kind: UP/DOWN | user、post；UK(user_id, post_id) |
| [ForumModerator](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/ForumModerator.java) | `forum_moderators` | - | forum、user；UK(forum_id, user_id) |
| [Notification](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/Notification.java) | `notifications` | kind: REPLY/UPVOTE/MENTION/FEATURED/FOLLOW、text、postId、commentId、read | user(接收者)、actor(触发者)；索引 idx_notif_user |
| [Follow](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/Follow.java) | `follows` | - | follower、followee；UK(follower_id, followee_id) |
| [Favorite](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/Favorite.java) | `favorites` | - | user、post；UK(user_id, post_id) |
| [Report](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/Report.java) | `reports` | targetType: POST/COMMENT、targetId、reason(80)、status: PENDING/RESOLVED/DISMISSED、handledBy、handledAt | reporter、handledBy；UK(reporter_id, target_type, target_id) |
| [Tag](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/Tag.java) | `tags` | name(UK,30)、postCount | 索引 idx_tag_name |
| [PostTag](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/PostTag.java) | `post_tags` | - | post、tag；UK(post_id, tag_id) |
| [AuditLog](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/AuditLog.java) | `audit_logs` | action/targetType/targetId/detail | actor→User；审计留痕 |
| [PasswordResetToken](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/PasswordResetToken.java) | `password_reset_tokens` | tokenHash(UK,100)/expiresAt/used | user→User；一次性重置凭证 |
| [UploadedFile](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/UploadedFile.java) | `uploaded_files` | purpose/status/url(UK)/sizeBytes/referenceId/expiresAt | owner→User；用途 AVATAR/POST_IMAGE；状态 PENDING/REFERENCED |

### 5.10 仓储层 — `cn.tbforum.repo`

14 个独立 JpaRepository 接口文件。关键自定义查询：

| 接口 | 自定义方法 |
|------|-----------|
| `UserRepository` | `findByUsername`、`findTop10ByUsernameContainingIgnoreCase` |
| `PostRepository` | `findFateBySeed`（MOD 伪随机分页）、`findFateRandom`、`findHotCandidates`（近 7 天候选，服务层衰减评分）、`search`、`incrementViews`（原子更新）、`findAllByDeletedFalseOrderByCreatedAtDesc` |
| `CommentRepository` | `findByPostOrderByPinnedDescCreatedAtAsc` |
| `TagRepository` | `findByName`、`findTop50ByOrderByPostCountDesc`、`incrementPostCount`（原子更新） |
| `ReportRepository` | `dismissPendingByTarget`（软删帖时关闭关联举报，参数化枚举） |
| `FavoriteRepository` | `existsByUserAndPost`、`findPostsByUser` |
| `FollowRepository` | `countByFollowee`、`countByFollower`、`findByFollowerAndFollowee` |

---

## 六、前端模块详解

### 6.1 入口与构建

- `frontend/index.html`：极简 HTML，挂载点 `<div id="root">`，加载 `/src/main.tsx`。
- `frontend/vite.config.ts`：React 插件，dev server `host=0.0.0.0` 监听 5173，`/api` 代理到 `http://127.0.0.1:8089`，`/ws` 代理到 `ws://127.0.0.1:8089`。
- `frontend/package.json`：`dev`（vite）、`build`（tsc -b && vite build）、`build:deploy`（构建并同步 static）、`preview`。

### 6.2 应用入口 — `frontend/src/main.tsx`

- 路径：[main.tsx](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/frontend/src/main.tsx)
- 仅负责 Provider 树组装，不包含业务逻辑：

```tsx
const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false, staleTime: 30_000 } },
});
createRoot(rootEl).render(
  <React.StrictMode>
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <NotificationProvider>
            <App />
          </NotificationProvider>
        </AuthProvider>
      </QueryClientProvider>
    </ErrorBoundary>
  </React.StrictMode>
);
```

### 6.3 状态管理

#### AuthContext — `contexts/AuthContext.tsx`

- 路径：[AuthContext.tsx](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/frontend/src/contexts/AuthContext.tsx)
- 集中管理：`token`、`user`、`verified`、`isAdmin`
- 提供：`login`、`logout`、`setVerified`、`refreshIdentity`
- JWT refresh 定时器：解析 exp，在过期前 5 分钟或半程（取较小者，不早于 30s）调 `/api/auth/refresh`
- 监听 `tb-auth-expired` 事件：logout + 弹登录框

#### NotificationContext — `contexts/NotificationContext.tsx`

- 路径：[NotificationContext.tsx](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/frontend/src/contexts/NotificationContext.tsx)
- 集中管理：`notifications`、`unreadCount`、`showNotif`
- WebSocket 连接（指数退避重连：3s→6s→12s→24s→30s 封顶，连接成功后重置；WS 消息按 id 去重，重连后自动拉取快照补齐未读）
- URL：`ws://${location.host}/ws/notifications`，Sec-WebSocket-Protocol 携带 `bearer.${token}`

#### React Query Hooks — `hooks/`

| Hook | 作用 |
|------|------|
| `useForums` | 版块列表 + 热门标签 |
| `usePosts` | 帖子列表（支持 global/forum/tag 三种来源 × 4 种排序） |
| `usePostDetail` | 帖子详情 + 评论树（评论按根评论无限滚动分页（useInfiniteQuery + 加载更多）） |
| `useUserProfile` | 用户资料 + 关注列表 |
| `useReportState` | 举报状态管理 |
| `useModState` | 版主状态管理 |
| `useAdminState` | 管理员举报后台状态 |

### 6.4 API 服务层 — `services/api.ts`

- 路径：[api.ts](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/frontend/src/services/api.ts)
- `api<T>(path, options, token?)`：统一 fetch 封装，自动注入 JSON 头与 Authorization
- **401 处理**：清除 localStorage token，派发 `tb-auth-expired` 事件
- **空响应处理**：兼容 201/204 无 body
- 按业务域分组：`authApi`、`forumApi`、`postApi`、`commentApi`、`userApi`、`notifApi`、`tagApi`、`reportApi`、`adminApi`
- `uploadFile(file, token, purpose)`：FormData 上传，`purpose` 为 `AVATAR` 或 `POST_IMAGE`，不手动设置 JSON Content-Type

### 6.5 组件层 — `components/`

16 个 UI 组件，关键组件：

- **Header**：顶栏（品牌/搜索/用户菜单/通知铃铛/发布按钮），含实名认证入口
- **Sidebar**：侧栏（创建版块/全站动态/版块列表/标签云）
- **PostList**：帖子列表（hero/排序工具栏/帖子卡片/分页）
- **PostDetail**：帖子详情 + 评论树
- **CommentView**：递归渲染评论树（按 `depth*24` 缩进）
- **ProfileView**：个人中心（5 tab：info/posts/favorites/following/followers）
- **MentionTextarea**：textarea + @提及下拉（200ms 防抖）
- **ErrorBoundary**：错误边界，防止组件异常导致白屏
- **ModPanel / AdminPanel**：`React.lazy` 懒加载，实现代码分割

### 6.6 主组件 — `App.tsx`

- 路径：[App.tsx](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/frontend/src/App.tsx)
- 组装所有组件，管理导航状态（`active`/`globalMode`/`activeTag`/`order`/`page`/`seed`）；History API 路由（`/post/:id`、`/forum/:slug`、`/tag/:name`、`/u/:name`、`/profile`、`/reset`），SPA 回退由后端 `SpaController` 提供
- 使用 `useReducer` 聚合复杂状态
- `React.lazy` 代码分割 `ModPanel` 和 `AdminPanel`

### 6.7 样式特性

**styles.css**：
- **暗色主题**：背景 `#07101d` / 卡片 `#0c1b2d` / 边框 `#33516a` / 主色薄荷青 `#8ef5dc`
- **Aurora 极光背景**：两个固定模糊光斑（青绿 + 蓝紫），`filter:blur(100px)`
- **header**：`backdrop-filter:blur(18px)` 半透明毛玻璃
- **响应式**：`@media(max-width:760px)` 隐藏 aside、抽屉式导航、缩小 hero
- 完整 Markdown 渲染样式（h1-h6、blockquote、code、table、hljs 高亮）

---

## 七、数据库设计

开发使用 H2 文件库（`jdbc:h2:file:./data/tbforum;MODE=PostgreSQL;AUTO_SERVER=TRUE`），生产使用 PostgreSQL 15 + Flyway 迁移。

### 7.1 表关系

```
users (id PK, username UK, passwordHash, verified, deleted, role, avatarUrl, bio, signature, email, bannedUntil, mutedUntil, createdAt)
  │
  ├─< forums (id PK, slug UK, name, description, owner_id FK, deleted, customReportReason1/2, createdAt)
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
audit_logs (id PK, actor_id FK→users, action, targetType, targetId, detail, createdAt)
password_reset_tokens (id PK, user_id FK→users, tokenHash UK, expiresAt, used, createdAt)

tags (id PK, name UK, postCount)
  │  索引 idx_tag_name
  └─< post_tags (见上)
```

### 7.2 Flyway 迁移

- 路径：[src/main/resources/db/migration/](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/resources/db/migration/)
- `V1__initial_schema.sql`：创建全部 12 张表（PostgreSQL 语法，BIGSERIAL/REFERENCES/CONSTRAINT/INDEX）
- `V2__add_soft_delete.sql`：P2 阶段新增 `users.deleted` 和 `forums.deleted` 字段（`ADD COLUMN IF NOT EXISTS`）
- `V3__moderation_audit.sql`：新增封禁、禁言和治理审计结构
- `V4__email_password_reset.sql`：新增用户邮箱及密码重置令牌表
- `V5__password_reset_security.sql`：新增 `users.token_version` 和重置令牌查询索引
- `V6__uploaded_file_lifecycle.sql`：新增 `uploaded_files`，记录所有者、用途、大小、引用和清理时间，并建立配额/引用/清理索引
- 开发环境 `spring.flyway.enabled=false`，由 Hibernate `ddl-auto=update` 维护表结构；生产环境 `spring.flyway.enabled=true` + `ddl-auto=validate`

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
| `app.redis.enabled` | `${REDIS_ENABLED:false}` | 开发默认关闭 Redis 和 Pub/Sub |
| `app.redis.failure-cooldown` | `${REDIS_FAILURE_COOLDOWN:30s}` | Redis 失败后的熔断冷却时间 |
| `spring.servlet.multipart.max-file-size` | `2MB` | 图片上传限制 |
| `app.upload.max-file-size` | `${UPLOAD_MAX_FILE_SIZE:2097152}` | Controller 输入及重编码结果字节上限 |
| `app.upload.max-width` / `max-height` | `${UPLOAD_MAX_WIDTH:4096}` / `${UPLOAD_MAX_HEIGHT:4096}` | 图片宽高上限 |
| `app.upload.max-pixels` | `${UPLOAD_MAX_PIXELS:16777216}` | 图片总像素上限，防止解压内存放大 |
| `app.upload.max-user-storage` | `${UPLOAD_MAX_USER_STORAGE:52428800}` | 单用户受管上传总配额，默认 50MB |
| `app.upload.pending-ttl` | `${UPLOAD_PENDING_TTL:24h}` | 未被头像或帖子引用的上传保留时间 |
| `app.upload.cleanup-interval` | `${UPLOAD_CLEANUP_INTERVAL:1h}` | 过期上传清理周期 |
| `app.upload.cleanup-batch-size` | `${UPLOAD_CLEANUP_BATCH_SIZE:100}` | 单轮加锁并清理的最大记录数 |
| `app.upload.legacy-reconciliation-enabled` | `${UPLOAD_LEGACY_RECONCILIATION_ENABLED:true}` | 启动时登记旧版本已有的头像与帖子图片引用；处理幂等，确认完成后可关闭 |
| `app.upload.legacy-reconciliation-batch-size` | `${UPLOAD_LEGACY_RECONCILIATION_BATCH_SIZE:200}` | 历史引用协调的单批记录数 |
| `app.storage.local.dir` | `${UPLOAD_DIR:uploads}` | 本地上传根目录；仅通过受控读取端点访问 |
| `app.jwt.secret` | `${JWT_SECRET:change-this-...}` | 开发占位串 |
| `app.jwt.expiration-hours` | `24` | JWT 有效期 |
| `app.public-base-url` | `${PUBLIC_BASE_URL:http://localhost:8089}` | 生成密码重置链接的固定公开地址，不读取请求 Host |
| `management.endpoints.web.exposure.include` | `health,info,metrics,prometheus` | Actuator 端点 |
| `management.health.redis.enabled` | `${REDIS_ENABLED:false}` | 仅启用 Redis 时加入健康检查 |
| `management.metrics.tags.application` | `tbforum` | 全局指标标签 |

### 8.2 application-prod.yml（生产）

| 配置项 | 生产值 | 差异说明 |
|--------|--------|------|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/tbforum` | PostgreSQL |
| `spring.jpa.hibernate.ddl-auto` | `validate` | 严格校验，不自动建表 |
| `spring.flyway.enabled` | `true` | 启用 Flyway 迁移 |
| `app.jwt.secret` | `${JWT_SECRET}` | **无默认值，必须由环境变量提供** |
| `app.public-base-url` | `${PUBLIC_BASE_URL}` | **无默认值，必须配置论坛公开 HTTP(S) 地址** |
| `app.redis.enabled` | `${REDIS_ENABLED:true}` | 生产默认启用 Redis；可显式关闭以降级运行 |
| `server.error.include-message` | `never` | 生产不暴露错误详情 |

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
浏览器打开 `http://localhost:5173`，Vite 代理 `/api` 和 `/ws` 到后端 `127.0.0.1:8089`。

### 9.4 前端构建与部署

```powershell
cd frontend
npm run build:deploy
# 自动同步到 src/main/resources/static/（见 frontend/scripts/sync-static.mjs）
```

### 9.5 代码格式与检查

```powershell
mvn spotless:check  # 检查 Java 格式，不修改文件
mvn spotless:apply  # 自动格式化 Java 源码和测试
mvn verify          # 运行测试，并在 verify 阶段自动执行格式检查
```

- Java 使用 Palantir Java Format，Spotless 同时执行导入排序、无用导入清理、尾随空格清理和文件末尾换行校验。
- `.editorconfig` 统一 UTF-8、LF、缩进和末尾换行；`.gitattributes` 固定主要文本文件的 Git 行尾。
- 格式化范围仅为 `src/main/java/**/*.java` 和 `src/test/java/**/*.java`，不处理前端生成的静态资源。

### 9.6 监控端点

- `GET /actuator/health` — 健康检查（启用 Redis 时包含 Redis 状态；启用后故障返回 DOWN）
- `GET /actuator/info` — 应用信息
- `GET /actuator/metrics` — 指标列表
- `GET /actuator/prometheus` — Prometheus 格式指标（含缓存、WebSocket、发帖，以及上传成功/删除/配额拒绝/删除失败等自定义指标）

---

## 十、核心业务流程速查

### 10.1 认证流程

1. `POST /api/auth/register` 或 `/login`，BCrypt 校验密码
2. `JwtService.issue(username, tokenVersion)` 签发带会话版本的 token 返回客户端
3. 前端存 localStorage（`tb-token`、`tb-user`），后续请求带 `Authorization: Bearer`
4. `JwtFilter` 解析后写入 SecurityContext，控制器通过 `UserService.current()` 取出
5. **前端 refresh**：解析 JWT exp，在过期前 5 分钟或半程调 `POST /api/auth/refresh` 换新 token
6. **401 处理**：`api()` 收到 401 时清除 token 并派发 `tb-auth-expired` 事件，触发 logout + 弹登录框
7. **无效 token**：后端返回 401（非静默降级为匿名）；密码重置递增服务端 tokenVersion，立即撤销旧 JWT

### 10.2 密码重置流程

1. `forgot-password` 始终返回成功，避免泄露邮箱是否注册。
2. 重置链接只使用 `app.public-base-url`，不信任请求 `Host` 或转发头。
3. 签发新令牌前将该用户旧的未使用令牌全部置为已使用；数据库仅保存 SHA-256 摘要。
4. `reset-password` 使用条件 UPDATE 原子消费令牌，并同时校验未使用和未过期，避免并发复用。
5. 重置成功后更新 BCrypt 密码、递增 tokenVersion，并关闭当前实例上的既有 WebSocket。

### 10.3 权限模型

- **公开读**：版块/帖子/评论/标签/用户资料的大部分 GET 端点
- **登录写**：发帖、评论、互动、关注、收藏、举报、上传
- **实名要求**：创建版块
- **版主/小版主**：设精华、锁帖、置顶评论、管理版块特色举报原因
- **吧主**：任命/撤任小版主、版块下线
- **ADMIN**：处理举报、查看举报后台

### 10.3 帖子排序（DB 分页，避免 OOM）

- `latest`：`findAllByDeletedFalseOrderByCreatedAtDesc`，第 0 页缓存 2 分钟
- `hot`：近 7 天候选 + 服务层时间衰减评分：`(upvotes + commentCount*2 + views*0.01) / (ageHours+2)^1.5`，第 0 页缓存 2 分钟
- `featured`：`findByFeaturedTrueAndDeletedFalse`，不缓存
- `fate`：`findFateBySeed(seed)` 用 `MOD(id*seed, 1000003)` 伪随机 + DB 分页（不加载全表到内存）

### 10.4 帖子互动

- 同一用户对同一帖子只能保留最后一次互动（唯一约束 + 业务替换）
- 旧互动计数回滚，新互动累加，事务保证一致
- 相同 kind 重复请求 = 取消互动（幂等）
- 计数器使用 `@Modifying @Query` 原子更新防竞态

### 10.5 评论与嵌套树

- 创建时维护 `parent` / `rootId` / `depth`
- 锁帖后禁止评论（403）
- 读取时按根评论分页，返回 `Page<CommentView>`
- 软删除评论保留在树中，显示「[已删除]」占位

### 10.6 标签系统

- 发帖/编辑时正则 `#([^\#\s]{2,30})` 解析，最多 5 个去重
- `syncTags` 全删全建，`tag.postCount` 用原子 SQL `incrementPostCount` 更新
- 删帖时同步递减 `tag.postCount`
- 标签云 Top 50，按 postCount 降序

### 10.7 关注与互相关注

- 关注/取关触发 FOLLOW 通知
- TOCTOU 竞态处理：捕获 `DataIntegrityViolationException`，返回友好错误
- 前端操作后重新 GET 用户详情刷新状态

### 10.8 举报处理

- 同一用户对同一目标（POST/COMMENT）只能举报一次（唯一约束）
- 举报原因：7 个标准原因 + 版块特色原因（最多 2 个）+ 自定义文本
- ADMIN 或版主后台可标记 RESOLVED/DISMISSED，记录 handledBy/handledAt；版主只能处理自己管辖版块的举报
- **软删帖后自动关闭关联举报**：`ReportRepository.dismissPendingByTarget` 将 PENDING 举报标记为 DISMISSED

### 10.9 数据一致性保障

- **软删除**：User 和 Forum 支持 `deleted` 字段逻辑删除
- **原子计数器**：views/upvotes/downvotes/commentCount/postCount 使用 `@Modifying @Query` 原子更新
- **TOCTOU 竞态**：注册/关注等操作捕获 `DataIntegrityViolationException`
- **事务安全**：WS 消息在事务 `afterCommit` 后发送
- **缓存一致性**：缓存 key 排除 viewer 相关字段，favorited 状态客户端重算
- **上传一致性**：上传先登记为 PENDING；资料/帖子事务中校验所有者和用途后转为 REFERENCED；替换、移除或删帖仅在事务提交后删除物理文件，失败记录保留供定时任务重试

---

## 十一、设计备注与注意事项

1. **分层架构**：后端从单文件 `Api.java` 重构为 Controller → Service → Repository 分层，职责清晰。DTO 使用 `record` 定义在 `Dtos.java` 中。
2. **前端组件化**：从 61KB 单文件 `main.tsx` 重构为 `App.tsx` + 14 个组件 + 7 个 hooks + 2 个 context，引入 React Query 管理服务端状态。
3. **代码分割**：`ModPanel` 和 `AdminPanel` 使用 `React.lazy` 懒加载，减小首屏 bundle 体积。
4. **JWT Refresh**：无独立 refresh token、无黑名单 —— 只要旧 access token 仍有效就能换新。生产环境如需更高安全性应引入 refresh token + 黑名单机制。
5. **WebSocket 安全**：JWT 从 `Sec-WebSocket-Protocol` header 提取（非 URL query），避免 token 记入 access log。
6. **限流**：Bucket4j 实现登录/注册/上传限流，可通过 `app.rate-limit.enabled=false` 禁用。
7. **缓存策略**：仅缓存 forums 列表（10 分钟）和 latest/hot feed 第 0 页（2 分钟）；featured/fate 不缓存。`evictByPrefix` 使用 SCAN 命令。
8. **存储抽象**：`StorageService` 接口 + `LocalStorageService` 实现，未来切换 MinIO 只需新增实现类并配置 `app.storage.type=minio`。
9. **可观测性**：Micrometer Prometheus 指标覆盖缓存命中率（`tbforum.cache.requests`）、WS 连接数（`tbforum.ws.connections.active`）、发帖计数（`tbforum.posts.created_total{forum=...}`）。启用 Redis 时，健康检查同步反映其状态。
10. **H2 文件库**：开发使用 `jdbc:h2:file:./data/tbforum;AUTO_SERVER=TRUE`，支持多连接。重启后端不会丢数据，schema 变更由 `ddl-auto=update` 维护。
11. **Flyway 迁移**：开发环境关闭，由 Hibernate 维护表结构；生产环境启用 + `ddl-auto=validate`。修改实体后需同步新增 `V{n}__*.sql` 迁移脚本。
12. **CORS 白名单**：允许 `localhost:*`、`127.0.0.1:*`、`192.168.*:*`、`10.*:*`、`172.16.*:*`，覆盖典型局域网段。
13. **错误处理**：前端 `api()` 统一处理 401（清 token + 弹登录框）和错误消息提取；后端 `WebExceptionHandler`（`@RestControllerAdvice`）统一处理 `ResponseStatusException`、`MethodArgumentNotValidException`、`DataIntegrityViolationException` 和通用 `Exception`。
14. **安全响应头**：`X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、HSTS、CSP（meta tag + SecurityConfig 双重保障）。
15. **图片上传安全与生命周期**：输入与重编码结果限制为 2MB；先校验魔数、宽高和总像素，再执行完整解码与重编码。WebP 由 TwelveMonkeys 解码后转为 PNG；本地文件使用 UUID + `CREATE_NEW`。每个文件记录所有者、用途和引用状态，跨用户/跨用途引用会被拒绝；单用户默认配额 50MB，未引用文件 24 小时后分批清理。

---

## 十二、近期更新记录（2026-08）

本轮对项目做了大量功能与体验增强，要点如下：

- **路由**：由 hash 路由升级为 History API（`/post/:id`、`/forum/:slug`、`/tag/:name`、`/u/:name`、`/profile`、`/reset?token=`），后端新增 `SpaController` 做 SPA 回退；RSS/sitemap 链接同步更新。
- **评论**：前端补上"加载更多"分页（useInfiniteQuery），作者可删除自己的评论。
- **搜索**：对游客开放（后端本就 permitAll），补齐分页与空态。
- **编辑器**：Markdown 实时预览、插图上传、`#` 标签补全、发帖草稿自动保存。
- **互动**：`PostView` 新增 `upvoted`，列表/详情高亮已支持状态（顺带修复详情接口未带 token 导致收藏/点赞状态不显示的问题）。
- **治理**：版主可处理本版举报（`/api/moderator/reports`）；新增封禁/禁言（`bannedUntil`/`mutedUntil`，写入操作被拦截）与审计日志（`AuditLog`，管理后台可查）；管理后台扩展为举报/用户/审计三个页签。
- **通知**：同类通知合并分组展示；点击通知跳转并高亮到具体评论；WebSocket 消息按 id 去重、重连后自动拉取快照。
- **界面**：浅色主题切换、移动端抽屉导航、列表骨架屏、统一 SVG 图标（`Icons.tsx`）。
- **发现**：`/rss.xml`、`/sitemap.xml`、`/robots.txt`；热门排序改为时间衰减评分；OG 标签与 favicon。
- **账号**：注册可选邮箱，`POST /api/auth/forgot-password` 与 `POST /api/auth/reset-password` 支持找回密码（开发环境重置链接打印在控制台，生产需接入 SMTP 实现 `MailService`）。
- **上传治理**：上传区分头像和帖子图片，增加所有权/用途校验、单用户配额、引用同步、头像替换/删帖释放及过期文件定时清理。
- **迁移**：新增 `V3__moderation_audit.sql`（users 封禁/禁言列 + audit_logs 表）与 `V4__email_password_reset.sql`（users.email + password_reset_tokens 表）。
