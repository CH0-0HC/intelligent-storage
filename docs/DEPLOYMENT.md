# 部署指南

## 1. 生产模式（prod profile）

```bash
export SPRING_PROFILES_ACTIVE=prod
java -jar target/smart-inventory-1.0.0.jar
```

`application-prod.yml` 的核心原则是**不提供任何可用于生产的默认凭据**：必需变量缺失时 Spring 直接启动失败，避免系统带着弱口令静默运行。prod 下还会关闭 Swagger 与 Knife4j。

必需变量：

| 变量 | 说明 |
| --- | --- |
| `DB_URL` | `jdbc:mysql://<host>:3306/smart_inventory?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false` |
| `DB_USERNAME` / `DB_PASSWORD` | 数据库账号与口令（务必非默认值） |
| `REDIS_HOST` | 限流用 Redis 地址；无 Redis 时请同时确认限流降级行为符合预期 |
| `UPLOAD_PATH` | 上传目录**绝对路径**，建议独立数据卷（如 `/data/uploads`） |
| `CORS_ALLOWED_ORIGINS` | 允许的前端来源，逗号分隔，例如 `https://erp.example.com` |

可选：`AI_PROVIDER`、`OLLAMA_BASE_URL`、`OLLAMA_MODEL`、`ALIYUN_API_KEY`、`ALIYUN_API_SECRET`、`ORDER_UNPAID_TIMEOUT_MINUTES`、`ORDER_TIMEOUT_CANCEL_ENABLED`。

## 2. 构建

```bash
# 后端
cd backend && mvn -B -DskipTests package      # 产物 backend/target/smart-inventory-1.0.0.jar

# 前端
cd frontend && npm ci && npm run build        # 产物 frontend/dist
```

## 3. 容器化部署（推荐）

```bash
cp .env.example .env       # 修改 MYSQL_ROOT_PASSWORD / DB_PASSWORD / CORS_ALLOWED_ORIGINS
docker compose up -d --build
docker compose ps
docker compose logs -f backend
```

组成：MySQL 8（首次启动执行 `init.sql`）、Redis 7、后端（Maven 多阶段构建 → JRE 17）、前端（Node 构建 → Nginx）。数据卷：`mysql-data`、`uploads-data`。

对外只暴露前端端口（默认 `8088`），`/api` 由 Nginx 反代到后端容器，后端无需公网暴露。

## 4. 传统方式 + Nginx

```nginx
server {
    listen 443 ssl;
    server_name erp.example.com;
    # ssl_certificate / ssl_certificate_key ...

    root /var/www/intelligent-storage;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8080/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        client_max_body_size 20m;     # 与后端 multipart 上限一致
    }
}
```

上传文件通过后端的 `upload.access-prefix`（默认 `/uploads`）访问；若希望由 Nginx 直接分发静态文件，可把该路径指向 `UPLOAD_PATH` 并加长缓存头。

## 5. 数据与升级

- **备份**：`mysqldump --single-transaction smart_inventory > backup-$(date +%F).sql`，并同步打包 `UPLOAD_PATH`（两者需成对恢复，否则商品图会 404）。
- **升级**：替换 jar / 重建镜像后重启；`init.sql` 中的表均以 `CREATE TABLE IF NOT EXISTS` 编写，重复执行安全；结构变更请叠加独立迁移脚本并在预发环境先验证。
- **回滚**：保留上一版本 jar 与镜像 tag，数据库升级前先做冷备。

## 6. 运行检查

| 检查项 | 方式 |
| --- | --- |
| 进程存活 | `curl -s http://127.0.0.1:8080/api/product/list` 返回 `code=200` |
| AI 状态 | `GET /api/ai/status`，确认 `provider` 与 `effective` 是否符合预期 |
| 限流 | 高频请求触发 429 / 降级日志 |
| 上传 | 后台上传商品图后，直接访问返回的 URL 可见 |

日志默认输出到控制台（`com.ai.smartinventory: info`）。容器部署可用 `docker compose logs`；裸机部署建议重定向到文件并交给 logrotate。
