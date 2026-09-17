# shortlink-cloud · 高并发短链平台

> 短链生成 / 跳转 / 统计 / 限流 / 防刷 / 管理后台，一站式可一键启动的实现。
>
> **技术栈**：Java 17 · Spring Boot 3.3 · MyBatis-Plus · MySQL 8 · Redis 7 · Redisson · RabbitMQ · Sentinel · Flyway · Vue 3 · Element Plus · ECharts · Docker Compose · Nginx

---

## 目录

- [架构总览](#架构总览)
- [快速开始](#快速开始)
- [接口一览](#接口一览)
- [项目结构](#项目结构)
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
        │  │                    业务层 Service                              │   │
        │  │  ShortLinkService · StatsService · AuthService                 │   │
        │  └──────┬───────────────┬────────────────┬───────────────┬────────┘   │
        │         │               │                │               │            │
        │  ┌──────▼─────┐  ┌──────▼──────┐  ┌──────▼──────┐  ┌─────▼─────────┐  │
        │  │ Redis 缓存  │  │ 布隆过滤器   │  │ Lua 限流     │  │ MQ 生产者      │  │
        │  │ 空值+抖动   │  │ Redisson    │  │ IP + 接口    │  │ 访问日志       │  │
        │  └──────┬─────┘  └──────┬──────┘  └──────┬──────┘  └─────┬─────────┘  │
        └─────────┼───────────────┼────────────────┼───────────────┼────────────┘
                  │               │                │               │
        ┌─────────▼───────────────▼────────────────▼───┐   ┌───────▼─────────────┐
        │              Redis 7 (:6379)                 │   │  RabbitMQ (:5672)   │
        │  url 缓存 · 布隆位图 · 限流计数器 · 发号器    │   │  link.access.log    │
        └──────────────────────────────────────────────┘   │  → 消费者批量落库    │
                                                           └───────┬─────────────┘
        ┌──────────────────────────────────────────────────────────▼─────────────┐
        │                            MySQL 8 (:3306)                             │
        │  t_user · t_short_link · t_link_access_log · t_link_stats              │
        └────────────────────────────────────────────────────────────────────────┘
```

### 高并发设计要点

| 关注点 | 方案 |
| --- | --- |
| 缓存穿透 | Redisson 布隆过滤器预判 + 空值缓存（短 TTL） |
| 缓存击穿 | 热点 key 逻辑过期 / 互斥重建（Redisson 分布式锁） |
| 缓存雪崩 | TTL 随机抖动（±20%） |
| 写扩散 / 发号 | Redis `INCR` + Base62，冲突则二次重试；DB 唯一索引兜底 |
| 限流 | Redis + Lua 原子滑动窗口（IP 维度），Sentinel 兜底 |
| 统计削峰 | 跳转接口只投递 MQ 消息，消费者批量写日志与聚合表 |
| 跳转响应 | 302/301 重定向，全程无阻塞 DB 查询（命中缓存时） |

---

## 快速开始

### 前置要求

- Docker 24+ 与 Docker Compose v2
- 本机 8080 / 80 / 3306 / 6379 / 5672 / 15672 端口未被占用
- （可选，本地裸机开发）JDK 17、Maven 3.9+、Node 18+

### 一键启动（推荐）

```bash
# 1. 准备环境变量
cp .env.example .env

# 2. 启动全部服务（MySQL / Redis / RabbitMQ / 后端 / 前端）
docker compose up -d --build

# 3. 查看状态
docker compose ps
```

启动完成后：

| 入口 | 地址 |
| --- | --- |
| 管理后台 | http://localhost |
| 后端 API | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| RabbitMQ 管理台 | http://localhost:15672 （shortlink / shortlink123） |
| 健康检查 | http://localhost:8080/actuator/health |

> 默认管理员账号见 `README` 的[接口一览](#接口一览) 或 Flyway `V3__seed_admin_user.sql`。

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

`code = 0` 表示成功，非 0 为业务错误码（见 `ErrorCode`）。

### 短链

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| `POST` | `/api/link/create` | 创建短链 | 可选 |
| `GET` | `/{shortCode}` | 跳转（302） | 无 |
| `GET` | `/api/link/page` | 分页查询短链 | 需要 |
| `PUT` | `/api/link/disable/{id}` | 禁用短链 | 需要 |

### 统计

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| `GET` | `/api/stats/{shortCode}` | 单条短链汇总统计 | 需要 |
| `GET` | `/api/stats/trend` | 趋势（按天） | 需要 |

### 认证

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/auth/login` | 登录换取 JWT |
| `GET` | `/api/auth/me` | 当前登录用户 |

### 示例

```bash
# 创建短链
curl -X POST http://localhost:8080/api/link/create \
  -H 'Content-Type: application/json' \
  -d '{"originalUrl":"https://example.com/very/long/path","title":"示例"}'

# 跳转（只看响应头）
curl -I http://localhost:8080/{shortCode}

# 分页查询
curl 'http://localhost:8080/api/link/page?current=1&size=10' \
  -H "Authorization: Bearer $TOKEN"

# 统计
curl http://localhost:8080/api/stats/{shortCode} -H "Authorization: Bearer $TOKEN"
curl 'http://localhost:8080/api/stats/trend?shortCode={shortCode}&days=7' -H "Authorization: Bearer $TOKEN"
```

---

## 项目结构

```
shortlink-cloud/
├── backend/                     # Spring Boot 后端
│   ├── src/main/java/com/shortlink/cloud/
│   │   ├── common/              # 统一响应、异常、常量、工具
│   │   ├── config/              # Redis / MQ / MyBatis-Plus / Redisson / Sentinel 配置
│   │   ├── controller/          # REST 接口
│   │   ├── dto/                 # 请求 / 响应对象
│   │   ├── entity/              # 数据库实体
│   │   ├── mapper/              # MyBatis-Plus Mapper
│   │   ├── mq/                  # 生产者 / 消费者 / 消息体
│   │   ├── service/             # 业务逻辑
│   │   └── util/                # Base62、IP、雪花等
│   ├── src/main/resources/
│   │   ├── application*.yml     # 分环境配置
│   │   ├── db/migration/        # Flyway 迁移脚本
│   │   ├── lua/                 # Redis Lua 脚本
│   │   └── mapper/              # MyBatis XML
│   └── Dockerfile
├── frontend/                    # Vue3 + TS + Element Plus + ECharts
│   ├── src/{api,router,store,views,components,layout,utils}
│   ├── nginx/default.conf
│   └── Dockerfile
├── docker/                      # 中间件初始化脚本
├── loadtest/                    # wrk / JMeter 压测脚本
├── docs/                        # 进度与压测报告
├── docker-compose.yml
├── .env.example
└── README.md
```

---

## 压测报告

> 详细方法与原始数据见 [`docs/benchmark.md`](docs/benchmark.md)。

压测脚本位于 [`loadtest/`](loadtest/)：

```bash
# wrk（推荐，Linux/macOS）
wrk -t4 -c100 -d30s --latency http://localhost:8080/{shortCode}

# JMeter（跨平台）
jmeter -n -t loadtest/jmeter/shortlink-redirect.jmx \
       -l loadtest/results/redirect.jtl \
       -e -o loadtest/reports/redirect
```

**目标值**：QPS 3000+ ，P99 < 50ms。

**实测值**：见 [`docs/benchmark.md`](docs/benchmark.md)（含原始 wrk/JMeter 输出与运行环境）。

> ⚠️ 诚实声明：本仓库的压测结论必须在具备 Docker 与网络的机器上实测后填入。
> 本次交付所处的沙箱环境**无法运行 Docker、无法下载依赖、无 HTTPS 出网**，
> 因此压测数据与构建结果均由使用者在本地复现，具体限制见 [`docs/progress.md`](docs/progress.md)。

---

## 开发进度

分阶段任务、验收命令与**真实执行结果**记录在 [`docs/progress.md`](docs/progress.md)，
每个阶段的验收命令是否真的跑通、哪些跑不通、原因是什么，都在那里如实标注。

---

## 环境说明

- 默认端口：`80`(前端) / `8080`(后端) / `3306`(MySQL) / `6379`(Redis) / `5672`+`15672`(RabbitMQ)
- 端口冲突时修改 `.env` 中对应的 `*_HOST_PORT`
- 生产部署务必替换 `JWT_SECRET`、所有默认密码，并将 `springdoc.swagger-ui.enabled` 按需关闭

---

## License

MIT
