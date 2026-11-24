# 本地环境搭建与运行

本文档面向「从零 clone 到跑通四个角色」的场景。所有命令均在仓库根目录执行，除特别说明外不需要切换目录。

## 1. 前置要求

| 组件 | 版本 | 是否必需 |
| --- | --- | --- |
| JDK | 17（Corretto / Temurin 均可） | 必需 |
| Maven | 3.9+ | 必需 |
| Node.js | 20+（含 npm） | 必需 |
| MySQL | 8.0 | 必需 |
| Redis | 7 | 可选（仅限流，缺失自动降级） |
| Ollama | 任意版本 | 可选（本地 AI） |

> 建议锁定 JDK 17：更高版本 JDK 可能使 Lombok 注解处理失效，编译报「找不到符号」。

## 2. 初始化数据库

```bash
mysql -uroot -p < backend/src/main/resources/db/init.sql
```

脚本会创建 `smart_inventory` 库、24 张表，并写入演示账号与示例商品。校验：

```sql
USE smart_inventory;
SELECT COUNT(*) FROM sys_user;   -- 应为 4（admin / merchant / user / kefu）
SELECT COUNT(*) FROM product;    -- 示例商品
```

字符集要求 `utf8mb4`，否则商品名与描述中的中文 / emoji 会截断。

`backend/src/main/resources/db/` 下另有若干运维脚本，按需使用，**不是首次启动的必需品**：

| 脚本 | 用途 |
| --- | --- |
| `fix-schema-drift.sql` | 把历史库补齐到 `init.sql` 的权威定义 |
| `sync_stock.sql` | 修正 `product.stock` 与 `inventory.quantity` 不一致 |
| `backfill_product_*.sql` | 回填历史商品的描述 / 销量字段 |
| `clean_invalid_user_data.sql` | 清理早期无校验时期写入的非法手机号等脏数据 |

## 3. 启动后端

```bash
cd backend
mvn -B spring-boot:run
```

- 端口 `8080`，`context-path` 为 `/api`，因此接口形如 `http://localhost:8080/api/product/list`。
- 上传目录由 `upload.path` 决定，默认 `./uploads`（相对**进程工作目录**）。请固定从 `backend/` 启动，或用 `UPLOAD_PATH` 指定绝对路径，否则不同启动目录会各写一份、互相读不到。
- 首次启动若连接数据库失败，检查 `DB_URL / DB_USERNAME / DB_PASSWORD`。

验证：

```bash
curl http://localhost:8080/api/product/list
```

## 4. 启动前端

```bash
cd frontend
npm ci
npm run dev
```

- 默认 <http://localhost:5173>，`vite.config.js` 已把 `/api` 与 `/uploads` 代理到 `8080`，无需额外配置 CORS。
- 生产构建：`npm run build`，产物在 `dist/`。

## 5. 账号与入口

| 角色 | 账号 / 密码 | 登录后可见 |
| --- | --- | --- |
| 管理员 | `admin` / `admin123` | 用户管理、日志、全平台订单与商品 |
| 商家 | `merchant` / `merchant123` | 商品、库存、采购、订单发货、售后、AI 看板 |
| 买家 | `user` / `user123` | 商城、购物车、订单、售后 |
| 客服 | `kefu` / `kefu123` | 客服工作台、工单裁定 |

接口文档：<http://localhost:8080/api/swagger-ui.html>，Knife4j 界面：<http://localhost:8080/api/doc.html>。

## 6. 启用 AI（可选）

系统默认 `AI_PROVIDER=auto`：先尝试本地 Ollama，不可用时降级到云端；两者都没有时，AI 面板会给出明确提示而不是报错。

本地模型：

```bash
# 允许容器/局域网访问时把监听地址放开
OLLAMA_HOST=0.0.0.0 ollama serve
ollama pull qwen3.5:0.8b
```

在「商家 → AI 智能助手 → AI 连接设置」中可直接填写地址与模型名，无需重启；也可用环境变量固定：

```bash
export OLLAMA_BASE_URL=http://192.168.x.x:11434
export OLLAMA_MODEL=qwen3.5:0.8b
```

> `num-ctx` 不要盲目调大：超出显存时 Ollama 会静默回退到 CPU 混合推理，表现为「变慢」而非报错。调用 `GET /api/ai/status` 可查看当前生效的提供方与模型。

云端模型：

```bash
export AI_PROVIDER=cloud
export ALIYUN_API_KEY=xxx
export ALIYUN_API_SECRET=xxx
```

## 7. 常见问题

**后端单测起不来**：`mvn -B test` 使用 `src/test/resources/application-test.yml`（H2 内存库），不依赖 MySQL / Redis；若失败请先确认 JDK 版本为 17，且未手动改动该文件。

**`mvn -o test` 失败**：离线模式要求本地仓库已预热，首次请用 `mvn -B test`（联网）。

**登录后立刻 401**：Sa-Token 使用请求头 `Authorization`；若前端未带 token，检查浏览器 localStorage 与请求拦截器。同时确认后端 `cookie.http-only` 未被改成 `false`。

**图片上传成功但访问 404**：上传目录与 `upload.access-prefix` 的挂载不一致，见第 3 节关于工作目录的说明。

**前端构建报 `Access is denied`（esbuild 临时目录）**：`npm run build` 前设置 `TMP`/`TEMP` 指向工作区内目录，或查看 `.githooks/pre-push` 的处理方式。

**i18n 门禁报缺失键**：按提示在 `frontend/src/i18n/locales/zh-CN.js` 与 `en.js` 同步补齐，再提交。
