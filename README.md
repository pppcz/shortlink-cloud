# shortlink-cloud · 高并发短链平台

[![CI](https://github.com/OWNER/shortlink-cloud/actions/workflows/ci.yml/badge.svg)](https://github.com/OWNER/shortlink-cloud/actions/workflows/ci.yml)

> 短链生成 / 跳转 / 统计 / 限流 / 防刷 / 管理后台，一站式可一键启动的实现。
>
> **技术栈**：Java 17 · Spring Boot 3.3 · MyBatis-Plus · MySQL 8 · Redis 7 · Redisson · RabbitMQ · Sentinel · Flyway · Vue 3 · Element Plus · ECharts · Docker Compose · Nginx

> ⚠️ **CI 徽章里的 `OWNER` 需要替换成你的 GitHub 用户名**，否则徽章会显示 404。

---

## 目录

- [架构总览](#架构总览)
- [快速开始](#快速开始)
- [接口一览](#接口一览)
- [项目结构](#项目结构)
- [验证与测试](#验证与测试)
- [压测报告](#压测报告)
- [开发进度](#开发进度)
- [环境说明](#环境说明)

---

## 架构总览

```
                        ┌──────────────────────────────────────────┐
                        │              浏览器 / 调用方              │
                        └───────────────────┬──────────────────────┘
                                            │
                       ┌────────────────────▼─────────────────────┐
                       │      Nginx (frontend 容器, :80)          │
                       │  · 托管 Vue3 静态资源 (history 路由回退)  │
                       │  · /api 反向代理 → backend:8080          │
                       └────────────────────┬─────────────────────┘
                                            │
        ┌───────────────────────────────────▼────────────────────────────────────┐
        │                    Spring Boot 后端 (backend 容器, :8080)               │
        │                                                                        │
        │  ┌──────────────┐   ┌───────────────┐   ┌──────────────────────────┐   │
        │  │ 跳转入口      │   │ 短链管理 API   │   │ 统计查询 API             │   │
        │  │ GET /{code}  │   │ /api/link/**  │   │ /api/stats/**            │   │
        │  └──────┬───────┘   └───────┬───────┘   └────────────┬─────────────┘   │
        │         │                   │                        │                 │
        │  ┌──────▼───────────────────▼────────────────────────▼─────────────┐   │
        │  │   限流拦截器 (Redis+Lua，IP 维度) · Sentinel 接口级 QPS 兜底      │   │
        │  └──────────────────────────┬──────────────────────────────────────┘   │
        │  ┌──────────────────────────▼──────────────────────────────────────┐   │
        │  │                    业务层 Service                               │   │
        │  │  ShortLinkService · StatsService · AuthService                  │   │
        │  └──────┬───────────────┬────────────────┬───────────────┬─────────┘   │
        │         │               │                │               │             │
        │  ┌──────▼─────┐  ┌──────▼──────┐  ┌──────▼──────┐  ┌─────▼─────────┐  │
        │  │ Redis 缓存  │  │ 布隆过滤器   │  │ Redis 发号   │  │ MQ 生产者      │  │
        │  │ 空值+抖动   │  │ Redisson    │  │ INCR+Base62 │  │ 访问日志       │  │
        │  └──────┬─────┘  └──────┬──────┘  └──────┬──────┘  └─────┬─────────┘  │
        └─────────┼───────────────┼────────────────┼───────────────┼────────────┘
                  │               │                │               │
        ┌─────────▼───────────────▼────────────────▼───┐   ┌───────▼─────────────┐
        │              Redis 7 (:6379)                 │   │  RabbitMQ (:5672)   │
        │  url 缓存 · 布隆位图 · 限流计数 · 发号器      │   │  link.access        │
        └──────────────────────────────────────────────┘   │  → 消费者攒批 500/2s │
                                                           └───────┬─────────────┘
        ┌──────────────────────────────────────────────────────────▼─────────────┐
        │                            MySQL 8 (:3306)                             │
        │  t_user · t_short_link · t_link_access_log · t_link_stats · t_link_uv_log│
        └────────────────────────────────────────────────────────────────────────┘
```

### 高并发设计要点

| 关注点 | 方案 | 代码位置 |
| --- | --- | --- |
| 缓存穿透 | Redisson 布隆过滤器预判（一定不存在直接 404）+ 空值缓存（短 TTL） | `ShortLinkCacheManager` |
| 缓存击穿 | 缓存 TTL 取 `min(1h, 距短链过期秒数)`，避免大量 key 同时失效 | `ShortLinkCacheManagerImpl#ttlFor` |
| 缓存雪崩 | TTL 随机抖动 ±20% | `ShortLinkCacheManagerImpl#jitter` |
| 缓存一致性 | 禁用短链时主动失效缓存 | `ShortLinkServiceImpl#disable` |
| 发号 | Redis `INCR`（按天分 key）+ Base62；DB 唯一索引 + 5 次重试兜底 | `RedisSeqShortCodeGenerator` |
| 限流 | Redis + Lua 原子固定窗口（IP 维度）+ 单日创建量限制 + Sentinel 接口级兜底 | `RateLimitInterceptor` / `SentinelConfig` |
| 统计削峰 | 跳转只投 MQ；消费者内存攒批 500 条 / 2 秒，**刷盘成功才 ack** | `LinkAccessConsumer` |
| 跳转响应 | 302 + `no-store`，命中缓存时全程无 DB 访问 | `RedirectController` |
| 降级 | 缓存/布隆过滤器故障 → 回源 DB；限流故障 → fail-open；MQ 故障 → 丢统计不丢跳转 | 各组件 try/catch |

---

## 快速开始

### 前置要求

- Docker 24+ 与 Docker Compose v2
- 8080 / 80 / 3306 / 6379 / 5672 / 15672 端口未被占用
- （可选，本地裸机开发）JDK 17、Maven 3.9+、Node 18+

### 一键启动（推荐）

```bash
# 1. 准备环境变量
cp .env.example .env

# 2. 启动全部服务（MySQL / Redis / RabbitMQ / 后端 / 前端）
docker compose up -d --build

# 3. 查看状态（等所有服务 healthy）
docker compose ps
```

启动完成后：

| 入口 | 地址 | 凭据 |
| --- | --- | --- |
| 管理后台 | http://localhost | `admin` / `admin123` |
| 后端 API | http://localhost:8080 | — |
| Swagger UI | http://localhost:8080/swagger-ui.html | — |
| RabbitMQ 管理台 | http://localhost:15672 | `shortlink` / `shortlink123` |
| 健康检查 | http://localhost:8080/actuator/health | — |

> ⚠️ `admin / admin123` 由 Flyway `V2__seed_admin_user.sql` 写入，**生产环境请立即修改**。
> 密码使用 PBKDF2-HMAC-SHA256（210000 次迭代）存储，不是明文，也不是 bcrypt（选型理由见 `docs/progress.md`）。

### 仅启动中间件 + 本地 IDE 调试

```bash
docker compose up -d mysql redis rabbitmq

# 后端（IDEA 里直接运行 ShortLinkCloudApplication 亦可）
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 前端
cd frontend
npm install
npm run dev     # http://localhost:5173
```

### 冒烟测试（一条命令验证全链路）

```bash
# 1. 创建短链
CODE=$(curl -s -X POST http://localhost:8080/api/link/create \
  -H 'Content-Type: application/json' \
  -d '{"originalUrl":"https://example.com/hello","title":"冒烟"}' \
  | grep -o '"shortCode":"[^"]*"' | cut -d'"' -f4)
echo "shortCode=${CODE}"

# 2. 跳转应返回 302 + Location
curl -sI "http://localhost:8080/${CODE}" | head -3

# 3. 等 2 秒让 MQ 消费者落库，再查统计
sleep 3
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' \
  | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
curl -s "http://localhost:8080/api/stats/${CODE}" -H "Authorization: Bearer ${TOKEN}"
```

### 常用运维命令

```bash
docker compose logs -f backend      # 跟踪后端日志
docker compose restart backend      # 重启后端
docker compose down                 # 停止（保留数据卷）
docker compose down -v              # 停止并清空数据
```

---

## 接口一览

统一响应结构：

```json
{
  "code": 0,
  "message": "success",
  "data": {},
  "traceId": "8f3c1a2b9d4e4f10",
  "timestamp": 1735689600000
}
```

`code = 0` 表示成功，非 0 为业务错误码（见 `ErrorCode`）：`1xxxx` 参数、`2xxxx` 资源、
`3xxxx` 鉴权、`4xxxx` 限流、`5xxxx` 系统。

### 短链

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| `POST` | `/api/link/create` | 创建短链（支持自定义短码 / 有效期） | 可选（匿名可创建） |
| `GET` | `/{shortCode}` | 跳转（302） | 无 |
| `GET` | `/api/link/page` | 分页查询短链 | 需要 |
| `PUT` | `/api/link/disable/{id}` | 禁用短链 | 需要 |

### 统计

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| `GET` | `/api/stats/{shortCode}?days=7` | 单条短链汇总 + 按天趋势 | 需要 |
| `GET` | `/api/stats/trend?days=7` | 趋势；不传 `shortCode` 时返回全部合计 | 需要 |

### 认证

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/auth/login` | 登录换取 JWT |
| `GET` | `/api/auth/me` | 当前登录用户 |

### 示例

```bash
# 创建短链（带有效期与自定义短码）
curl -X POST http://localhost:8080/api/link/create \
  -H 'Content-Type: application/json' \
  -d '{"originalUrl":"https://example.com/very/long/path","title":"示例","expireDays":30,"customCode":"mysite"}'

# 跳转（只看响应头）
curl -I http://localhost:8080/mysite

# 分页查询
curl 'http://localhost:8080/api/link/page?current=1&size=10&status=1' \
  -H "Authorization: Bearer $TOKEN"

# 统计与趋势
curl "http://localhost:8080/api/stats/mysite?days=7" -H "Authorization: Bearer $TOKEN"
curl "http://localhost:8080/api/stats/trend?days=30" -H "Authorization: Bearer $TOKEN"
```

### 跳转接口的响应头

| 响应头 | 说明 |
| --- | --- |
| `Location` | 302 时的目标地址 |
| `Cache-Control: no-store` | 禁止浏览器缓存跳转，保证统计真实 |
| `X-RateLimit-Limit` / `X-RateLimit-Remaining` | 当前 IP 的配额与剩余 |
| `Retry-After` | 被限流（429）时建议的重试间隔 |

状态码约定：`302` 正常跳转 / `404` 短码不存在 / `410` 已禁用或已过期 / `429` 触发限流。

---

## 项目结构

```
shortlink-cloud/
├── backend/                     # Spring Boot 后端
│   ├── src/main/java/com/shortlink/cloud/
│   │   ├── common/              # 统一响应、错误码、全局异常、traceId、用户上下文
│   │   ├── config/              # Redis/Redisson · MQ · MyBatis-Plus · Sentinel · 鉴权与限流拦截器
│   │   ├── controller/          # 跳转 / 短链 / 统计 / 认证
│   │   ├── dto/                 # 请求与响应对象
│   │   ├── entity/              # 数据库实体
│   │   ├── mapper/              # MyBatis-Plus Mapper
│   │   ├── mq/                  # 生产者 / 消费者 / 批量写入器 / 消息体
│   │   ├── service/             # 业务逻辑（含缓存、限流、发号、统计实现）
│   │   └── util/                # Base62 · URL 校验 · PBKDF2 · JWT · IP 工具
│   ├── src/main/resources/
│   │   ├── application*.yml     # 分环境配置
│   │   ├── db/migration/        # Flyway 迁移（V1 建表 / V2 管理员 / V3 统计）
│   │   └── lua/                 # Redis Lua 脚本
│   ├── src/test/java/           # 单元测试（10 个测试类）
│   └── Dockerfile
├── frontend/                    # Vue3 + TS + Element Plus + ECharts
│   ├── src/{api,components,layout,router,store,views}
│   ├── nginx/default.conf
│   └── Dockerfile
├── docker/mysql/init/           # 中间件初始化脚本
├── loadtest/                    # wrk / JMeter / Postman 压测与调试脚本
├── docs/                        # progress.md（进度与验收）· benchmark.md（压测报告）
├── docker-compose.yml
├── .env.example
└── README.md
```

### 数据库表

| 表 | 用途 |
| --- | --- |
| `t_user` | 平台用户（PBKDF2 密码、角色、状态） |
| `t_short_link` | 短链映射（短码唯一、逻辑删除、PV/UV 冗余、过期时间） |
| `t_link_access_log` | 访问明细（时间冗余出 `access_date` / `hour`，报表走索引） |
| `t_link_stats` | 按天聚合（`(short_code, stat_date)` 唯一，幂等 upsert） |
| `t_link_uv_log` | 每日 UV 去重（`(short_code, stat_date, ip_hash)` 唯一 + `INSERT IGNORE`） |

---

## 验证与测试

> ⚠️ **重要前提**：本仓库的代码由 AI 在**无 Docker、无 HTTPS 出网**的沙箱中编写。
> 因此 `mvn test`、`docker compose up`、`npm run build`、`wrk` 压测
> **在交付时一次都没有执行过**。下面的方式是把"未验证"变成"已验证"的路径。

### 方式一：GitHub Actions（推荐，无需本地装 Docker）

推送到 GitHub 后，[`.github/workflows/ci.yml`](.github/workflows/ci.yml) 会自动执行：

| Job | 内容 |
| --- | --- |
| `backend-unit` | 编译 + 10 个单元测试类 |
| `backend-integration` | Testcontainers 启动真实 MySQL/Redis/RabbitMQ 跑集成测试 |
| `backend-package` | 打包并校验 jar 产物 |
| `frontend` | TypeScript 类型检查 + 生产构建 |
| `docker-build` | 两个镜像构建 + compose 语法校验 |

也可以不推送，直接在 GitHub 网页上手动触发（workflow 已配 `workflow_dispatch`）。

### 方式二：本地一键验证脚本

```powershell
# Windows：完整验证（需要 JDK 17 + Maven + Node + Docker Desktop）
pwsh -File scripts/verify.ps1

# 没有 Docker 时跳过容器相关步骤
pwsh -File scripts/verify.ps1 -SkipDocker
```

脚本逐步执行「工具链检查 → 后端单元测试 → 打包 → 前端类型检查与构建 →
集成测试 → 端到端冒烟」，任一步失败即停止并给出排查方向。

### 手动执行

```bash
# 单元测试（不需要中间件，最快）
cd backend && mvn test

# 集成测试（需要 Docker，会启动真实中间件容器）
cd backend && mvn -Pintegration test

# 前端类型检查与构建
cd frontend && npm install && npm run type-check && npm run build
```

### 测试分层说明

| 层级 | 数量 | 依赖 | 覆盖什么 |
| --- | --- | --- | --- |
| 单元测试 | 10 个类 | 无（全 mock） | 业务编排：发号重试、限流边界、UV 口径、缓存降级、攒批 ack 时机 |
| 集成测试 | 2 个类 | Docker | SQL 语法、Flyway 迁移可执行性、JSON 序列化、唯一索引、真实 PV/UV 聚合 |

**为什么两层都要**：单元测试验证不了 SQL 和迁移；集成测试跑得慢、不适合每次提交都跑。

---

## 压测报告

> 方法论、采集口径与实测结果见 [`docs/benchmark.md`](docs/benchmark.md)。

压测脚本：

```bash
# wrk（Linux/macOS）
./loadtest/wrk/redirect.sh ${SHORT_CODE} 30s 100 4

# JMeter（跨平台，Windows 也可用）
jmeter -n -t loadtest/jmeter/shortlink-redirect.jmx \
       -Jhost=localhost -Jport=8080 -Jcode=${SHORT_CODE} \
       -Jthreads=100 -Jduration=30 \
       -l loadtest/results/redirect.jtl -e -o loadtest/reports/redirect
```

**目标值**：QPS ≥ 3000，P99 < 50ms，错误率 < 0.1%，缓存命中率 ≥ 95%。

**实测值**：**尚未测得**。本项目代码由 AI 在无 Docker、无 HTTPS 出网的沙箱中编写，
`mvn package` / `docker compose up` / `wrk` 均无法执行，因此
[`docs/benchmark.md`](docs/benchmark.md) 的 §4 实测表格留空——
**不填任何推测数字**。请在有 Docker 与网络的环境按该文档 §3 执行后填入。

> ⚠️ 压测前请把 `.env` 中 `RATE_LIMIT_PERMIT_PER_SECOND` 调大，否则会被自己的限流挡住。

---

## 开发进度

分阶段任务、验收命令与**真实执行结果**记录在 [`docs/progress.md`](docs/progress.md)。
每个阶段的验收命令是否真的跑通、哪些跑不通、原因是什么，都在那里如实标注，
包括与任务书的技术选型偏差及其理由。

## 面试与学习

如果你用这个项目准备面试，[`docs/interview-guide.md`](docs/interview-guide.md) 把代码里的
关键取舍翻译成了可以直接讲出来的答案，包括：

- 9 个设计决策点的「怎么答」（302 vs 301、布隆过滤器预热、ack 时机、UV 去重、fail-open 等）
- 被问「这个项目有什么不足」时的答法
- 高频技术细节速查表
- 现场演示的建议顺序

---

## 环境说明

- 默认端口：`80`(前端) / `8080`(后端) / `3306`(MySQL) / `6379`(Redis) / `5672`+`15672`(RabbitMQ)
- 端口冲突时修改 `.env` 中对应的 `*_HOST_PORT`
- 生产部署必须替换：`JWT_SECRET`、MySQL/Redis/RabbitMQ 全部默认密码、
  管理员初始密码；并按需关闭 `springdoc.swagger-ui.enabled`
- 跳转统计由 MQ 异步写入，跳转后约 2 秒（消费者攒批周期）才能在统计接口看到数据

---

## License

MIT
