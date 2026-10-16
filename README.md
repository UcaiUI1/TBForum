# TBForum

「随缘而遇，温和交流」的论坛应用 —— 一个没有推荐算法、只有偶然遇见的好内容的社区。

Spring Boot 3.2 + React 18 + TypeScript，支持帖子/评论/标签/关注/通知/举报/管理工具，前后端一体化部署。

## 技术栈

- **后端**：Java 17、Spring Boot 3.2.5、Spring Data JPA、Spring Security、JWT（jjwt 0.12.5）、WebSocket、Flyway、Redis、Micrometer Prometheus、Bucket4j 限流、TwelveMonkeys ImageIO
- **前端**：React 18.3、TypeScript、Vite、TanStack React Query 5、marked（Markdown）、DOMPurify（XSS 净化）、highlight.js（代码高亮，按需加载）
- **数据库**：H2 文件模式（开发）/ PostgreSQL 15（生产）
- **缓存**：Redis 7（热门列表 + 浏览数防刷 + WebSocket Pub/Sub 多实例广播，带优雅降级）
- **监控**：Actuator + Micrometer Prometheus（缓存命中率、WS 连接数、发帖计数器等指标）

## 快速启动

### 开发模式（H2 文件库，开箱即用）

```powershell
mvn spring-boot:run
```
后端默认监听 `http://localhost:8089`（可通过 `--server.port=8089` 或配置文件修改），H2 文件库位于 `./data/tbforum`，`ddl-auto=update` 自动建表。

开发环境默认关闭 Redis，可通过 `$env:REDIS_ENABLED='true'` 启用缓存、浏览去重和多实例 WebSocket Pub/Sub；未启用时业务自动使用数据库及本地 WebSocket。

### 前端开发

另开终端：
```powershell
cd frontend
npm install
npm run dev
```
浏览器打开 `http://localhost:5173`，Vite 会把 `/api` 和 `/ws` 代理到后端 `127.0.0.1:8089`。

### 生产模式（PostgreSQL + Flyway）

```powershell
docker compose up -d              # 启动 PostgreSQL + Redis
$env:SPRING_PROFILES_ACTIVE='prod'
$env:JWT_SECRET='你的足够长的随机密钥'
$env:PUBLIC_BASE_URL='https://forum.example.com'
mvn spring-boot:run
```
生产 profile 会启用 Flyway 迁移、`ddl-auto=validate` 严格校验，并强制要求 `JWT_SECRET` 和 `PUBLIC_BASE_URL` 环境变量。

若应用部署在反向代理后，请通过 `TRUSTED_PROXIES` 显式配置可信代理 CIDR（逗号分隔，例如 `127.0.0.1/32,10.0.0.0/8`）；未配置时限流只使用 TCP 对端地址，不信任客户端传入的转发头。

应用默认先完成读取预热，再开放业务流量。发布时应等待 `/actuator/health/readiness` 返回成功，不能只检查端口或启动日志。预热参数、探针、等待脚本及优雅关闭流程见[冷启动与接流量说明](docs/startup-readiness.md)。

本轮列表索引、批量查询、首页缓存并发重建与标签并发修复的代码入口、压测对照和适用边界见[性能与并发优化记录](docs/performance-optimization.md)。

### 前端构建与部署

```powershell
cd frontend
npm run build:deploy
```
`build:deploy` 会执行构建并把产物自动同步到 `src/main/resources/static/`，由 Spring Boot 静态托管（生产同源部署，前后端同端口）。

### 代码格式与检查

Java 源码统一使用 Palantir Java Format；Spotless 负责格式化、导入排序、无用导入清理和行尾校验：

```powershell
mvn spotless:check  # 只检查，不修改文件
mvn spotless:apply  # 自动修复格式
mvn verify          # 运行测试，并在 verify 阶段执行格式检查
```

编辑器通用缩进、UTF-8、LF 和文件末尾换行规则见 `.editorconfig` 与 `.gitattributes`。前端构建产物 `src/main/resources/static/` 不参与 Java 格式化。

## 核心功能

- **认证**：JWT 注册/登录、token 自动刷新（前端在过期前 5 分钟续期）、用户名改名后重发 token、实名认证、注册可选填邮箱；JWT 携带服务端会话版本，密码重置后旧 token 立即失效
- **找回密码**：`forgot-password` 使用固定公开地址发送一次性重置链接（30 分钟有效、数据库原子消费、签发新链接时旧链接失效），开发环境打印在控制台，生产可接入 SMTP
- **限流**：登录 5 次/分钟、注册 3 次/分钟、上传 10 次/分钟（Bucket4j）；仅信任配置网段的代理头，Bucket 缓存有容量和闲置过期限制
- **路由**：History API 干净 URL（`/post/:id`、`/forum/:slug`、`/u/:name` 等），支持深链直达、刷新保留、前进后退
- **版块**：创建版块（需实名）、任命/撤任小版主（最多 2 名）、自定义版块举报原因、版块下线（软删除）
- **帖子**：发布、编辑、软删除、锁帖、设为精华；Markdown 渲染 + 代码高亮；编辑器带实时预览、插图上传、`#` 标签补全与草稿自动保存
- **排序**：最新 / 热门 / 精华 / 随缘（fate 用 `MOD(id*seed, 1000003)` 伪随机算法 + DB 分页）；热门采用时间衰减评分（点赞 + 评论×2 + 浏览×0.01，除以年龄衰减）
- **评论**：无限嵌套树（邻接表 + rootId + depth）、回复、编辑、置顶、分页（前端"加载更多"）、作者可删除、软删除占位
- **互动**：点赞（同一用户仅保留最后一次，`upvoted` 状态可视化）、收藏
- **标签**：发布时自动解析 `#标签`（最多 5 个）、标签云、按标签筛选、原子更新 postCount
- **关注**：关注/取关、粉丝/关注列表、互相关注（mutual）状态
- **通知**：实时 WebSocket 推送（回复/点赞/@提及/精华/关注）、未读计数、同类通知合并分组、点击定位到具体评论；Redis Pub/Sub 跨实例同步通知及账号断连，并通过实例 ID 防止自身回声重复推送
- **举报**：常规原因 + 版块特色原因；ADMIN 或版主后台处理（标记已处理/驳回）；软删帖后自动关闭关联举报
- **治理**：版主可处理本版举报；ADMIN 可封禁/禁言用户（封禁立即使已有 JWT/WS 失效，禁言统一拦截写请求）并查看审计日志（封禁、举报处理、删帖、设精、锁帖均留痕）
- **个人中心**：头像上传（图片文件，≤2MB、最大 4096×4096 / 16777216 像素，完整解码校验 + 重编码去除元数据；WebP 安全转为 PNG）、签名、简介、我的帖子/收藏/关注/粉丝；上传按用户限制 50MB，废弃文件 24 小时后自动清理；文件仅在被头像或帖子正式引用后可访问
- **发现**：全站动态 feed、热门标签云、@提及用户搜索（支持中文用户名）、RSS 订阅（`/rss.xml`）、sitemap/robots
- **界面**：深色/浅色主题切换、移动端抽屉导航、列表骨架屏、统一 SVG 图标
- **可观测**：Prometheus 指标（缓存命中率、Redis 可用状态/熔断事件、WS 活跃连接数及 Pub/Sub 事件、发帖与上传/清理计数器）、健康检查（启用 Redis 时包含 Redis 状态）

## API 概览

所有端点前缀 `/api`，写操作需 `Authorization: Bearer <token>`。

- **认证**：`POST /auth/register`、`POST /auth/login`、`GET /auth/me`、`POST /auth/refresh`、`PATCH /auth/me`、`PATCH /auth/me/verified`、`POST /auth/forgot-password`、`POST /auth/reset-password`
- **版块**：`GET/POST /forums`、`GET /forums/{slug}`、`POST/DELETE /forums/{slug}/moderators/{username}`、`GET /forums/{slug}/moderators`、`GET /forums/{slug}/posts`、`POST /forums/{slug}/posts`、`GET/PATCH /forums/{slug}/report-reasons`
- **帖子**：`GET /posts/feed?order=latest|hot|featured|fate&seed=`、`GET /posts/{id}`、`DELETE /posts/{id}`、`PATCH /posts/{id}/featured`、`PATCH /posts/{id}/locked`、`PATCH /posts/{id}`（编辑）、`POST /posts/{id}/interactions`
- **评论**：`POST /posts/{id}/comments`、`GET /posts/{id}/comments`（返回 `Page<CommentView>`）、`PATCH /posts/{id}/comments/{cid}`、`PATCH /posts/{id}/comments/{cid}/pinned`、`DELETE /posts/{id}/comments/{cid}`
- **标签**：`GET /tags`、`GET /tags/{name}/posts`
- **用户**：`GET /users/{username}`、`GET /users/search?q=`、`POST/DELETE /users/{username}/follow`、`GET /users/{username}/followers|following|posts`
- **收藏**：`POST/DELETE /posts/{id}/favorite`、`GET /users/me/favorites`
- **通知**：`GET /notifications`、`GET /notifications/unread-count`、`PATCH /notifications/{id}/read`、`PATCH /notifications/read-all`
- **举报**：`POST /reports`、`GET /moderator/reports`、`GET /moderator/reports/count`、`PATCH /moderator/reports/{id}`、`GET /admin/reports`、`GET /admin/reports/count`、`PATCH /admin/reports/{id}`
- **治理**：`PATCH /admin/users/{username}/ban?days=`、`PATCH /admin/users/{username}/mute?days=`、`GET /admin/audit`
- **搜索**：`GET /search?q=`（游客可用）
- **上传**：`POST /upload` multipart（必填 `purpose=AVATAR|POST_IMAGE`；PNG/JPEG/GIF/WebP，≤2MB，最大 4096×4096 / 16777216 像素；每用户默认 50MB）；`GET /uploads/{filename}` 仅返回已引用图片
- **SEO**：`GET /rss.xml`、`GET /sitemap.xml`、`GET /robots.txt`
- **WebSocket**：`/ws/notifications`（Sec-WebSocket-Protocol 携带 `bearer.{token}`）
- **监控**：`GET /actuator/health`、`GET /actuator/info`、`GET /actuator/metrics`、`GET /actuator/prometheus`

完整架构、数据库设计、模块详解见 [CODE_WIKI.md](CODE_WIKI.md)。
