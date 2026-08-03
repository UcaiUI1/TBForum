# TBForum

「随缘而遇，温和交流」的论坛应用 —— 一个没有推荐算法、只有偶然遇见的好内容的社区。

Spring Boot 3.2 + React 18 + TypeScript，支持帖子/评论/标签/关注/通知/举报/管理工具，前后端一体部署。

## 技术栈

- **后端**：Java 17、Spring Boot 3.2.5、Spring Data JPA、Spring Security、JWT（jjwt 0.12.5）、WebSocket、Flyway、Redis、Micrometer Prometheus、Bucket4j 限流
- **前端**：React 18.3、TypeScript、Vite、TanStack React Query 5、marked（Markdown）、DOMPurify（XSS 净化）、highlight.js（代码高亮，按需加载）
- **数据库**：H2 文件模式（开发）/ PostgreSQL 15（生产）
- **缓存**：Redis 7（热门列表 + 浏览数防刷 + WebSocket Pub/Sub 多实例广播，带优雅降级）
- **监控**：Actuator + Micrometer Prometheus（缓存命中率、WS 连接数、发帖计数等指标）

## 快速启动

### 开发模式（H2 文件库，开箱即用）

```powershell
mvn spring-boot:run
```
后端默认监听 `http://localhost:8089`（可通过 `--server.port=8089` 或配置文件修改），H2 文件库位于 `./data/tbforum`，`ddl-auto=update` 自动建表。

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
mvn spring-boot:run
```
生产 profile 会启用 Flyway 迁移、`ddl-auto=validate` 严格校验、强制要求 `JWT_SECRET` 环境变量。

### 前端构建与部署

```powershell
cd frontend
npm run build
```
产物默认输出到 `frontend/dist/`，需手动复制到 `src/main/resources/static/` 由 Spring Boot 静态托管（生产同源部署，前后端同端口）。

## 核心功能

- **认证**：JWT 注册/登录、token 自动刷新（前端在过期前 5 分钟续期）、用户名改名后重发 token、实名认证
- **限流**：登录 5 次/分钟、注册 3 次/分钟、上传 10 次/分钟（Bucket4j）
- **版块**：创建版块（需实名）、任命/撤任小版主（最多 2 名）、自定义版块举报原因、版块下线（软删除）
- **帖子**：发帖、编辑、软删除、锁帖、设为精华；Markdown 渲染 + 代码高亮
- **排序**：最新 / 热门 / 精华 / 随缘（fate 用 `MOD(id*seed, 1000003)` 伪随机算法 + DB 分页，避免 OOM）
- **评论**：无限嵌套树（邻接表 + rootId + depth）、回复、编辑、置顶、分页（返回 `Page<CommentView>`）、软删除（显示「[已删除]」）
- **互动**：点赞/踩（同一用户仅保留最后一次）、收藏
- **标签**：发帖时自动解析 `#标签`（最多 5 个）、标签云、按标签筛选、原子更新 postCount
- **关注**：关注/取关、粉丝/关注列表、互相关注（mutual）状态
- **通知**：实时 WebSocket 推送（回复/点赞/@提及/精华/关注）、未读计数、全部已读；支持 Redis Pub/Sub 多实例广播
- **举报**：常规原因 + 版块特色原因、ADMIN 后台处理（标记已处理/驳回）、软删帖后自动关闭关联举报
- **管理**：版主可设精华/锁帖/置顶评论/管理小版主；ADMIN 处理举报
- **个人中心**：头像上传（图片文件，≤2MB，魔术数校验 + ImageIO 重编码去除元数据）、昵称、简介、签名、我的帖子/收藏/关注/粉丝
- **发现**：全站动态 feed、热门标签云、@提及用户搜索（支持中文用户名）
- **可观测**：Prometheus 指标（缓存命中率、WS 活跃连接数、发帖计数器）、健康检查（含 Redis 状态）

## API 概览

所有端点前缀 `/api`，写操作需 `Authorization: Bearer <token>`。

- **认证**：`POST /auth/register`、`POST /auth/login`、`GET /auth/me`、`POST /auth/refresh`、`PATCH /auth/me`、`PATCH /auth/me/verified`
- **版块**：`GET/POST /forums`、`GET /forums/{slug}`、`POST/DELETE /forums/{slug}/moderators/{username}`、`GET /forums/{slug}/moderators`、`GET /forums/{slug}/posts`、`POST /forums/{slug}/posts`、`GET/PATCH /forums/{slug}/report-reasons`
- **帖子**：`GET /posts/feed?order=latest|hot|featured|fate&seed=`、`GET /posts/{id}`、`DELETE /posts/{id}`、`PATCH /posts/{id}/featured`、`PATCH /posts/{id}/locked`、`PATCH /posts/{id}`（编辑）、`POST /posts/{id}/interactions`
- **评论**：`POST /posts/{id}/comments`、`GET /posts/{id}/comments`（返回 `Page<CommentView>`）、`PATCH /posts/{id}/comments/{cid}`、`PATCH /posts/{id}/comments/{cid}/pinned`
- **标签**：`GET /tags`、`GET /tags/{name}/posts`
- **用户**：`GET /users/{username}`、`GET /users/search?q=`、`POST/DELETE /users/{username}/follow`、`GET /users/{username}/followers|following|posts`
- **收藏**：`POST/DELETE /posts/{id}/favorite`、`GET /users/me/favorites`
- **通知**：`GET /notifications`、`GET /notifications/unread-count`、`PATCH /notifications/{id}/read`、`PATCH /notifications/read-all`
- **举报**：`POST /reports`、`GET /admin/reports`、`GET /admin/reports/count`、`PATCH /admin/reports/{id}`
- **搜索**：`GET /search?q=`（需登录）
- **上传**：`POST /upload`（图片，≤2MB）
- **WebSocket**：`/ws/notifications`（Sec-WebSocket-Protocol 携带 `bearer.{token}`）
- **监控**：`GET /actuator/health`、`GET /actuator/info`、`GET /actuator/metrics`、`GET /actuator/prometheus`

完整架构、数据库设计、模块详解见 [CODE_WIKI.md](CODE_WIKI.md)。
