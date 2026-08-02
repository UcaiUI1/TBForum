# TBForum

“随缘而遇，温和交流”的论坛 REST API，基于 Spring Boot 3.2、Java 17、JWT 与 PostgreSQL。

## 快速启动

开发模式（默认使用内存 H2）：`mvn spring-boot:run`

生产数据库：先执行 `docker compose up -d`，再设置：

```powershell
$env:DB_URL='jdbc:postgresql://localhost:5432/tbforum'
$env:DB_USERNAME='tbforum'
$env:DB_PASSWORD='tbforum'
mvn spring-boot:run
```

## API 概览

- `POST /api/auth/register`、`POST /api/auth/login`：返回 JWT。
- `PATCH /api/auth/me/verified`：开发用实名认证状态切换。
- `GET/POST /api/forums`，`POST /api/forums/{slug}/moderators/{username}`。
- `GET/POST /api/forums/{slug}/posts?order=latest|hot|featured|fate&seed=123`。
- `GET /api/posts/{id}`、`POST /api/posts/{id}/comments`、`GET /api/posts/{id}/comments`。
- `POST /api/posts/{id}/interactions`，body 如 `{ "kind":"UP" }`。
- `GET /api/search?q=关键词`（需携带 Bearer Token）。

除公开读取外，写接口需 `Authorization: Bearer <token>`。评论使用邻接表 + `rootId` + `depth`，读取时返回无限嵌套树。随缘排序要求客户端为一个版块固定保存并传递 `seed`。

## React 前端

在另一个 PowerShell 窗口中启动后端，再执行：

```powershell
cd frontend
npm.cmd install --cache ..\work\npm-cache
npm.cmd run dev -- --host 127.0.0.1
```

浏览器打开 `http://localhost:5173`。Vite 已将 `/api` 请求代理到 `http://localhost:8080`；前端包含版块浏览、排序、搜索、登录、创建版块、发帖和点赞界面。
