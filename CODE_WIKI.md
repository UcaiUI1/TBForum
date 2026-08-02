# TBForum Code Wiki

> “随缘而遇，温和交流”的论坛应用，提供前后端一体的 REST API 与 React 单页界面。
> 后端基于 Spring Boot 3.2 + Java 17 + JWT + PostgreSQL/H2，前端基于 React 18 + Vite + TypeScript。

---

## 一、项目概述

TBForum 是一个轻量级论坛系统，定位为「没有推荐算法，只有偶然看见的好内容」的温和交流社区。核心特色包括：

- **JWT 无状态认证**：登录/注册返回 token，后续请求通过 `Authorization: Bearer <token>` 鉴权。
- **版块（Forum）自治**：每个版块由创建者（吧主）管理，可任命最多 3 名副管理者。
- **多排序帖子流**：支持「最新 / 热门 / 精华 / 随缘」四种排序，其中「随缘」由客户端持有固定 `seed` 实现可复现的随机洗牌。
- **无限嵌套评论树**：评论采用邻接表 + `rootId` + `depth` 模型，读取时在内存中组装为无限嵌套树。
- **帖子互动**：支持点赞（UP）/ 踩（DOWN）/ 收藏（BOOKMARK）/ 举报（REPORT），同一用户对同一帖子仅保留最后一次互动。
- **关键词搜索**：基于 JPA `@Query` 的 title/content 模糊匹配（需登录）。
- **软删除**：帖子和评论通过 `deleted` 标记软删除，不物理移除。

---

## 二、项目整体架构

### 2.1 分层架构

项目采用经典的三层架构，前后端分离部署，通过 HTTP/JSON 通信：

```
┌─────────────────────────────────────────────────────────────┐
│                     浏览器 (React SPA)                       │
│              frontend/src/main.tsx (Vite 5173)              │
└──────────────────────────┬──────────────────────────────────┘
                           │  /api/*  (Vite dev proxy)
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                   Spring Boot 后端 (8080)                    │
│                                                              │
│  ┌───────────────┐   ┌───────────────┐   ┌───────────────┐  │
│  │ Security 层    │ → │  API 控制器    │ → │  仓储层 (JPA) │  │
│  │ JwtFilter     │   │  Api.java      │   │ Repositories  │  │
│  │ SecurityConfig│   │  (REST 端点)   │   │ (Spring Data) │  │
│  └───────────────┘   └───────────────┘   └───────┬───────┘  │
│                                                  │          │
│                                          ┌───────▼───────┐  │
│                                          │  Domain 实体   │  │
│                                          │ User/Forum/... │  │
│                                          └───────┬───────┘  │
└──────────────────────────────────────────┼──────────────────┘
                                           │
                           ┌───────────────▼───────────────┐
                           │   数据库 (H2 内存 / PostgreSQL) │
                           └───────────────────────────────┘
```

### 2.2 请求流转链路

以「发帖」为例的完整调用链：

1. 浏览器 `fetch('/api/forums/{slug}/posts', {method:'POST', body, headers:{Authorization}})`
2. Vite dev server 将 `/api` 代理到 `http://localhost:8080`
3. `SecurityConfig.JwtFilter` 解析 Bearer token → 通过 `JwtService.subject()` 校验并取出 username → 从 `Users` 仓储查到 `User` → 写入 `SecurityContextHolder`
4. `Api.createForum(...)` / `createPost(...)` 控制器方法处理业务，调用 `Repositories` 接口
5. Spring Data JPA 生成 SQL 落库（Hibernate `ddl-auto: update` 自动建表）
6. 返回 JSON，由前端 `api()` 封装解析并更新 React state

---

## 三、技术栈与依赖关系

### 3.1 后端依赖（pom.xml）

| 依赖 | 版本 | 作用 |
|------|------|------|
| `spring-boot-starter-web` | 3.2.5 | RESTful Web 服务（内嵌 Tomcat） |
| `spring-boot-starter-data-jpa` | 3.2.5 | ORM 与仓储抽象 |
| `spring-boot-starter-security` | 3.2.5 | 认证鉴权框架 |
| `spring-boot-starter-validation` | 3.2.5 | Bean Validation（`@NotBlank`/`@Size` 等） |
| `jjwt-api` / `jjwt-impl` / `jjwt-jackson` | 0.12.5 | JWT 签发与解析 |
| `postgresql` | runtime | 生产数据库驱动 |
| `h2` | runtime | 开发内存数据库 |
| `spring-boot-starter-test` | test | 测试支持 |

构建插件：`maven-compiler-plugin`（`fork=true`，确保用独立 JVM 编译）、`spring-boot-maven-plugin`（打包可执行 jar）。

### 3.2 前端依赖（frontend/package.json）

| 依赖 | 版本 | 作用 |
|------|------|------|
| `react` / `react-dom` | ^18.3.1 | UI 框架 |
| `vite` | latest | 构建工具与 dev server |
| `@vitejs/plugin-react` | latest | Vite 的 React 支持 |
| `typescript` | latest | 类型系统 |
| `@types/react` / `@types/react-dom` | - | 类型定义 |

### 3.3 基础设施（docker-compose.yml）

- `postgres:15-alpine`：生产数据库，库/用户/密码均为 `tbforum`，端口 5432，数据持久化卷 `pg-data`。
- `redis:7-alpine`：端口 6379（已声明，当前代码未实际使用，预留扩展）。

---

## 四、目录结构

```
an-zhao/
├── src/main/java/cn/tbforum/
│   ├── TbForumApplication.java     # Spring Boot 启动类
│   ├── api/
│   │   └── Api.java                # 全部 REST 端点 + DTO record
│   ├── domain/                     # JPA 实体
│   │   ├── User.java
│   │   ├── Role.java
│   │   ├── Forum.java
│   │   ├── Post.java
│   │   ├── Comment.java
│   │   ├── Interaction.java
│   │   └── ForumModerator.java
│   ├── repo/
│   │   └── Repositories.java       # 嵌套的 Spring Data JPA 接口集合
│   └── security/
│       ├── SecurityConfig.java     # 安全配置 + JWT 过滤器
│       └── JwtService.java         # JWT 签发与解析
├── src/main/resources/
│   └── application.yml             # 配置（数据源、JWT 密钥等）
├── frontend/
│   ├── index.html
│   ├── package.json
│   ├── vite.config.ts              # Vite 配置（含 /api 代理）
│   └── src/
│       ├── main.tsx                # React 应用入口与全部 UI 逻辑
│       ├── styles.css
│       └── auth.css
├── pom.xml                         # Maven 构建文件
├── docker-compose.yml              # PostgreSQL + Redis
└── README.md
```

---

## 五、后端模块详解

### 5.1 启动类 — `TbForumApplication`

- 路径：[TbForumApplication.java](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/TbForumApplication.java)
- 注解 `@SpringBootApplication` 触发自动装配与组件扫描。
- `@EnableJpaRepositories(basePackages = "cn.tbforum.repo", considerNestedRepositories = true)` 关键点：因为 `Repositories.java` 把所有仓储接口声明为**嵌套接口**，必须设置 `considerNestedRepositories = true` 才能被识别。
- `main()` 方法是程序入口。

### 5.2 安全模块 — `cn.tbforum.security`

#### `SecurityConfig`
- 路径：[SecurityConfig.java](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/security/SecurityConfig.java)
- `@Configuration @EnableWebSecurity` 配置类，定义三个 Bean：

| Bean / 组件 | 职责 |
|-------------|------|
| `passwordEncoder()` | 返回 `BCryptPasswordEncoder`，用于密码哈希与校验 |
| `security(HttpSecurity, JwtService, Users)` | 配置过滤链：开启 CORS、关闭 CSRF、无状态会话（`STATELESS`）、放行规则、注册 `JwtFilter` |
| `corsConfigurationSource()` | 允许 `http://localhost:5173` 跨域，方法 GET/POST/PATCH/DELETE/OPTIONS，允许 Authorization、Content-Type 头 |
| `JwtFilter`（内部类） | 继承 `OncePerRequestFilter`，从 `Authorization: Bearer xxx` 头解析 token，校验后把 `User` 身份写入 `SecurityContextHolder` |

- **放行规则**：`/api/auth/**`、`/api/forums/**`、`/api/posts/**`、`/actuator/health` 公开访问；其余请求需认证。
  > 注意：写操作（发帖、评论等）虽在 `/api/forums`、`/api/posts` 公开路径下，但控制器内部通过 `current()` 强制要求登录，二者共同形成「公开可读、登录可写」的语义。

#### `JwtService`
- 路径：[JwtService.java](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/security/JwtService.java)
- `@Service`，从配置读取 `app.jwt.secret` 与 `app.jwt.expiration-hours`（默认 24 小时）。
- `issue(username)`：用 HMAC-SHA 签发 JWT，subject 为 username。
- `subject(token)`：解析并验证签名，返回 subject（即 username）；token 非法时抛 `JwtException`，被 `JwtFilter` 捕获忽略。

### 5.3 API 控制器 — `cn.tbforum.api.Api`

- 路径：[Api.java](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/api/Api.java)
- `@RestController @RequestMapping("/api")`，单文件聚合所有 REST 端点。
- 通过构造函数注入 6 个仓储 + `PasswordEncoder` + `JwtService`。
- 内部以 `record` 定义所有请求/响应 DTO，避免暴露实体内部字段。

#### 5.3.1 DTO（record）清单

| record | 用途 |
|--------|------|
| `Credentials` | 登录/注册请求（username 3–40 位，password 8–100 位） |
| `Token` | 登录/注册响应（token + username） |
| `ForumInput` | 创建版块请求（name、slug 限 `[a-z0-9-]{3,64}`、description） |
| `PostInput` | 发帖请求（title 最长 180，content） |
| `CommentInput` | 评论请求（content 最长 5000，可选 parentId） |
| `InteractionInput` | 互动请求（kind: UP/DOWN/BOOKMARK/REPORT） |
| `UserView` / `ForumView` / `PostView` / `CommentView` | 各实体的对外视图，通过 `of(...)` 静态工厂从实体转换 |

#### 5.3.2 端点一览

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/api/auth/register` | 公开 | 注册，返回 201 + Token |
| POST | `/api/auth/login` | 公开 | 登录，返回 Token |
| GET | `/api/auth/me` | 登录 | 当前用户信息 |
| PATCH | `/api/auth/me/verified` | 登录 | 开发用：将当前用户标记为已实名认证 |
| GET | `/api/forums?page&size` | 公开 | 分页列出版块（按创建时间倒序，size 上限 50） |
| POST | `/api/forums` | 登录+实名 | 创建版块（slug 不可重复） |
| GET | `/api/forums/{slug}` | 公开 | 获取单个版块 |
| POST | `/api/forums/{slug}/moderators/{username}` | 吧主 | 任命副管理者（对方需实名，每版块最多 3 名） |
| GET | `/api/forums/{slug}/posts?order&seed&page&size` | 公开 | 列出版块帖子（见排序逻辑） |
| POST | `/api/forums/{slug}/posts` | 登录 | 发帖 |
| GET | `/api/posts/{id}` | 公开 | 获取帖子详情（@Transactional，自增 views） |
| DELETE | `/api/posts/{id}` | 版主/管理员 | 软删除帖子 |
| PATCH | `/api/posts/{id}/featured?value=` | 版主/管理员 | 设置/取消精华 |
| POST | `/api/posts/{id}/interactions` | 登录 | 互动（替换旧互动并增减计数） |
| POST | `/api/posts/{id}/comments` | 登录 | 评论（支持回复，@Transactional） |
| GET | `/api/posts/{id}/comments` | 公开 | 获取评论无限嵌套树 |
| GET | `/api/search?q&page&size` | 登录 | 关键词搜索帖子 |

#### 5.3.3 关键业务方法

**`listPosts` — 帖子排序逻辑**
```java
Sort sort = switch(order) {
    case "hot"      -> 按 upvotes desc, views desc;
    case "featured" -> 按 createdAt desc（仅查 featured=true）;
    case "fate"     -> 按 createdAt asc;
    default         -> 按 createdAt desc（latest）;
};
// fate 模式：用客户端传入的 seed 构造 Random，对当页内容 shuffle
```
- 「随缘」要求客户端为每个版块固定保存并传递同一 `seed`，否则每次刷新顺序不同。

**`interact` — 互动替换逻辑（@Transactional）**
1. 查找该用户对该帖的旧互动；若存在，回滚旧 kind 的计数（UP→upvotes--，DOWN→downvotes--）并删除旧记录。
2. 保存新互动并累加新 kind 计数。
3. 保存帖子。整个流程在事务内，保证计数一致。

**`comment` — 评论创建（@Transactional）**
- 若带 `parentId`：校验父评论存在且属于同一帖子，设置 `depth = parent.depth + 1`，`rootId = parent.rootId ?? parent.id`。
- 若是顶级评论：先保存获取 id，再把 `rootId` 设为自身 id 并保存。
- 帖子 `commentCount++` 并保存。

**`commentTree` — 评论树组装**
- 一次性 `findByPostAndDeletedFalseOrderByCreatedAtAsc` 取全部评论。
- 用 `Map<Long, List<Comment>>` 按 `parent.id`（顶级用 `0L`）分组。
- 递归 `tree()` 方法构建 `CommentView`（含 `children`）返回无限嵌套结构。

#### 5.3.4 权限辅助方法

| 方法 | 语义 |
|------|------|
| `current()` | 从 `SecurityContextHolder` 取当前登录用户，未登录抛 401 |
| `getForum(slug)` | 按 slug 查版块，不存在抛 404 |
| `getPost(id)` | 按 id 查未删除帖子，否则 404 |
| `requireOwner(f)` | 校验当前用户是版块吧主，否则 403 |
| `requireModerator(f)` | 校验当前用户是吧主 / 副管理者 / ADMIN，否则 403 |
| `bad/unauthorized/forbidden/notFound` | 构造对应状态码的 `ResponseStatusException` |

### 5.4 领域模型 — `cn.tbforum.domain`

所有实体使用 JPA `@Entity`，主键均为 `@GeneratedValue(IDENTITY)`，`createdAt` 默认 `Instant.now()` 且 `updatable=false`。

| 实体 | 表 | 关键字段 | 关系 |
|------|----|---------|------|
| [User](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/User.java) | `users` | username(唯一,40)、passwordHash、verified、role | role 枚举 USER/ADMIN |
| [Role](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/Role.java) | - | USER, ADMIN | 枚举 |
| [Forum](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/Forum.java) | `forums` | slug(唯一,64)、name(100)、description(500) | `@ManyToOne` owner→User |
| [Post](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/Post.java) | `posts` | title(180)、content(TEXT)、views/upvotes/downvotes/commentCount、featured、deleted | `@ManyToOne` forum、author；索引 `idx_posts_forum_created(forum_id,createdAt)` |
| [Comment](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/Comment.java) | `comments` | content(TEXT)、rootId、depth、deleted | `@ManyToOne` post、author、parent(自引用)；索引 `idx_comments_post(post_id,createdAt)`、`idx_comments_parent(parent_id)` |
| [Interaction](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/Interaction.java) | `interactions` | kind 枚举 UP/DOWN/BOOKMARK/REPORT | `@ManyToOne` user、post；唯一约束 `(user_id, post_id)` |
| [ForumModerator](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/domain/ForumModerator.java) | `forum_moderators` | - | `@ManyToOne` forum、user；唯一约束 `(forum_id, user_id)` |

> 字段均为 `public`，控制器直接读写，未使用 getter/setter，风格紧凑。

### 5.5 仓储层 — `cn.tbforum.repo.Repositories`

- 路径：[Repositories.java](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/java/cn/tbforum/repo/Repositories.java)
- `public final class`，私有构造，内含 6 个嵌套 `interface extends JpaRepository`。

| 接口 | 自定义方法 |
|------|-----------|
| `Users` | `findByUsername` |
| `Forums` | `findBySlug` |
| `Posts` | `findByForumAndDeletedFalse`、`findByForumAndFeaturedTrueAndDeletedFalse`、`search`（`@Query` JPQL 模糊匹配 title/content） |
| `Comments` | `findByPostAndDeletedFalseOrderByCreatedAtAsc` |
| `Moderators` | `countByForum`、`existsByForumAndUser` |
| `Interactions` | `findByUserAndPost`、`findByUserAndKind` |

---

## 六、前端模块详解

### 6.1 入口与构建

- `frontend/index.html`：极简 HTML，挂载点 `<div id="root">`，加载 `/src/main.tsx`。
- `frontend/vite.config.ts`：启用 React 插件，dev server 监听 5173，将 `/api` 代理到 `http://localhost:8080`。
- `frontend/package.json`：`dev`（vite）、`build`（tsc -b && vite build）、`preview`。

### 6.2 应用主体 — `frontend/src/main.tsx`

- 路径：[main.tsx](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/frontend/src/main.tsx)
- 单文件包含全部 UI 与状态逻辑，约 30 行高密度代码。

#### 类型定义
```ts
type Forum = { id; slug; name; description; owner:{username} };
type Post  = { id; title; content; forum; author; createdAt; views; upvotes; comments; featured };
type Page<T> = { content: T[]; totalElements: number };
```

#### 关键函数

| 函数 | 职责 |
|------|------|
| `api<T>(path, options, token)` | 通用 fetch 封装：自动加 Content-Type 与 Authorization 头，处理 204 空响应与错误 message 提取 |
| `time(v)` | 用 `Intl.DateTimeFormat('zh-CN')` 格式化时间 |
| `App()` | 主组件，管理 forums/active/posts/order/query/token/user/modal/notice 等状态 |
| `Modal(...)` | 通用弹窗组件，根据 type 渲染 登录/注册/发帖/建版块 四种表单 |

#### 状态与流程要点

- **Token 持久化**：登录/注册成功后写入 `localStorage`（`tb-token`、`tb-user`），刷新页面不丢失；退出时 `localStorage.clear()`。
- **随缘 seed**：`useMemo` 初始化时从 `localStorage` 读 `tb-fate-seed`，不存在则随机生成并存储，保证同一浏览器对同一版块的「随缘」顺序稳定。
- **数据加载**：`useEffect` 首次加载版块列表并默认选中第一个；`active.slug` 或 `order` 变化时重新加载帖子。
- **搜索**：需登录，结果直接替换当前帖子列表。
- **点赞**：未登录时弹出登录框，否则调用 `/api/posts/{id}/interactions` 并刷新列表。
- **UI 结构**：顶部 header（品牌/搜索/用户/发布）、左侧 aside（创建版块 + 版块导航）、主区 main（hero + 排序工具栏 + 帖子流）、toast 通知、Modal 弹窗。样式由 `styles.css` + `auth.css` 提供，含 aurora 极光背景动效。

---

## 七、数据库设计

开发环境使用 H2 内存库（`MODE=PostgreSQL` 兼容模式），生产使用 PostgreSQL 15。Hibernate `ddl-auto: update` 自动维护表结构。主要表关系：

```
users (id PK, username UK, passwordHash, verified, role, createdAt)
  │
  ├─< forums (id PK, slug UK, name, description, owner_id FK→users, createdAt)
  │       │
  │       ├─< forum_moderators (id PK, forum_id FK, user_id FK, UK(forum_id,user_id))
  │       │
  │       └─< posts (id PK, forum_id FK, author_id FK→users, title, content,
  │                   createdAt, views, upvotes, downvotes, commentCount,
  │                   featured, deleted)
  │               │  索引: idx_posts_forum_created(forum_id, createdAt)
  │               │
  │               ├─< comments (id PK, post_id FK, author_id FK→users, parent_id FK→comments,
  │               │             rootId, depth, content, createdAt, deleted)
  │               │   索引: idx_comments_post(post_id, createdAt), idx_comments_parent(parent_id)
  │               │
  │               └─< interactions (id PK, user_id FK, post_id FK, kind)
  │                       UK(user_id, post_id)
```

评论模型说明：邻接表（`parent_id` 指向父评论）+ 物化路径变体（`rootId` 指向根评论 id）+ `depth` 层级。这样既能快速按根评论聚合，也能在内存中递归组装嵌套树。

---

## 八、配置说明（application.yml）

- 路径：[application.yml](file:///c:/Users/yangc/Documents/Codex/2026-07-30/an-zhao/src/main/resources/application.yml)

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `spring.datasource.url` | `jdbc:h2:mem:tbforum;MODE=PostgreSQL;DB_CLOSE_DELAY=-1` | 通过环境变量 `DB_URL` 覆盖为 PostgreSQL |
| `spring.datasource.username` / `password` | `sa` / 空 | 由 `DB_USERNAME` / `DB_PASSWORD` 覆盖 |
| `spring.jpa.hibernate.ddl-auto` | `update` | 启动时自动建/更新表 |
| `spring.jpa.open-in-view` | `false` | 关闭 OSIV，避免视图层懒加载 |
| `server.error.include-message` | `always` | 错误响应包含 message（便于前端展示） |
| `app.jwt.secret` | 开发用占位串 | 由 `JWT_SECRET` 覆盖，生产必须替换 |
| `app.jwt.expiration-hours` | `24` | JWT 有效期 24 小时 |

---

## 九、项目运行方式

### 9.1 环境要求

- JDK 17+、Maven 3.x（用于 `mvn` 命令，项目已配 `maven-compiler-plugin` 的 `fork=true`）
- Node.js（含 npm）用于前端
- Docker（可选，用于启动生产级 PostgreSQL）

### 9.2 后端启动

**开发模式（H2 内存库，开箱即用）**：
```powershell
mvn spring-boot:run
```
后端监听 `http://localhost:8080`，自动以 PostgreSQL 兼容模式启动 H2 内存库并建表。

**生产模式（PostgreSQL）**：
```powershell
docker compose up -d                     # 启动 postgres + redis
$env:DB_URL='jdbc:postgresql://localhost:5432/tbforum'
$env:DB_USERNAME='tbforum'
$env:DB_PASSWORD='tbforum'
mvn spring-boot:run
```

### 9.3 前端启动

在另一个终端：
```powershell
cd frontend
npm.cmd install --cache ..\work\npm-cache
npm.cmd run dev -- --host 127.0.0.1
```
浏览器打开 `http://localhost:5173`。Vite 会把 `/api` 请求代理到后端 8080，因此需先启动后端。

### 9.4 构建产物

- 后端：`mvn package` 生成可执行 jar（`target/`）。
- 前端：`npm run build` 输出到 `frontend/dist/`，`index.html` + `assets/` 可直接由静态服务器或后端托管。

---

## 十、核心业务流程速查

### 10.1 认证流程
1. `POST /api/auth/register` 或 `/login`，校验 username 唯一 / 密码匹配（BCrypt）。
2. `JwtService.issue(username)` 签发 token 返回客户端。
3. 后续请求带 `Authorization: Bearer <token>`，`JwtFilter` 解析后将 User 写入 SecurityContext。
4. 控制器通过 `current()` 取出当前用户。

### 10.2 创建版块前置条件
- 已登录 + `verified=true`（通过 `PATCH /api/auth/me/verified` 设置，开发用）。
- slug 全局唯一。

### 10.3 任命副管理者
- 仅吧主可操作；被任命者需 `verified`；每版块最多 3 名；重复任命返回 204 幂等。

### 10.4 帖子互动（点赞/踩）
- 同一用户对同一帖子只能保留最后一次互动（唯一约束 + 业务替换逻辑）。
- 旧互动的计数会被回滚，新互动计数累加，事务保证一致。

### 10.5 评论与嵌套树
- 创建时维护 `parent` / `rootId` / `depth`。
- 读取时一次性取全部评论，按 parent 分组后递归组装为 `CommentView` 树。

### 10.6 权限模型
- **公开读**：版块列表、版块详情、帖子列表、帖子详情、评论树。
- **登录写**：发帖、评论、互动、搜索。
- **实名要求**：创建版块、被任命为副管理者。
- **版主权限**：删帖、加精（吧主 / 副管理者 / ADMIN）。
- **吧主权限**：任命副管理者。

---

## 十一、设计备注与注意事项

1. **代码风格紧凑**：后端大量使用单行多语句与 `record`，`Api.java` 单文件约 34 行承载全部端点；实体字段为 `public` 直接访问。阅读时建议配合 IDE 折叠与格式化。
2. **嵌套仓储**：`Repositories` 内嵌 6 个接口，依赖 `considerNestedRepositories = true` 才能被扫描，移除该配置会导致启动失败。
3. **软删除一致性**：`Posts` 仓储的查询均带 `DeletedFalse` 条件，但 `findById` 不会过滤已删除记录，控制器在 `getPost()` 中手动检查 `deleted` 标志。
4. **搜索需登录**：`/api/search` 在控制器首行调用 `current()`，因此即使路径在 `/api` 下也强制鉴权。
5. **JWT 密钥**：默认占位串仅供开发，生产必须通过 `JWT_SECRET` 环境变量替换为足够长的随机串。
6. **Redis 预留**：`docker-compose.yml` 声明了 redis 服务，但当前代码未集成，为后续缓存/会话扩展预留。
7. **CORS 白名单**：仅允许 `http://localhost:5173`，部署到其他域名需修改 `SecurityConfig.corsConfigurationSource()`。
8. **前端单文件**：`main.tsx` 集中所有逻辑，适合原型；若扩展建议拆分组件与 API 层。
