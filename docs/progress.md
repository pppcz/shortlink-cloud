# shortlink-cloud 开发进度与验收记录

> 本文件如实记录每个阶段交付了什么、验收命令**实际执行结果**是什么。
> 跑不通的命令不会被标记为通过，原因与证据一并记录。

---

## 0. 执行环境基线（重要）

本次构建所在沙箱环境的实测能力如下，**所有结论均来自实际命令输出**：

| 能力 | 状态 | 证据 |
| --- | --- | --- |
| JDK | ✅ 17.0.11 | `java -version` → `java version "17.0.11" 2024-04-16 LTS` |
| Maven | ✅ 3.9.4 | `mvn -v` → `Apache Maven 3.9.4` |
| Node / npm | ✅ 24.14.0 / 11.9.0 | `node -v`、`npm.cmd -v` |
| Git | ✅ 2.55.0 | `git --version` |
| **Docker** | ❌ 未安装 | `Get-Command docker` → MISSING；无 `com.docker.service` |
| **HTTPS 出网** | ❌ 被阻断 | `curl https://repo.maven.apache.org/maven2/` → `curl: (35) schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS`；`Invoke-WebRequest` → `基础连接已经关闭`。TCP 443 可连通但 TLS 握手失败 |
| **Maven 依赖下载** | ❌ 不可用 | 镜像为 `http://maven.aliyun.com/...`（settings.xml 第 163 行），无法访问 |
| **Maven 本地仓库可写** | ❌ 拒绝 | 本地仓库为 `D:\Maven\apache-maven-3.9.4-bin\apache-maven-3.9.4\mvn_repo`，在工作区之外 → `java.nio.file.AccessDeniedException` |
| **npm 安装** | ❌ 不可用 | `npm install --offline` → `npm error code ENOTCACHED ... no cached response is available` |

### 缺失的关键依赖

本地两个 Maven 仓库（`~/.m2/repository` 711 个 jar、`mvn_repo` 445 个 jar）中**均不存在**：

```
com.baomidou:mybatis-plus-spring-boot3-starter   ❌
org.flywaydb:flyway-core / flyway-mysql          ❌
org.springdoc:springdoc-openapi-starter-webmvc-ui ❌
org.redisson:redisson-spring-boot-starter        ❌
io.lettuce:lettuce-core                          ❌
org.springframework.amqp:spring-rabbit           ❌
com.alibaba.csp:sentinel-core                    ❌
org.springframework.data:spring-data-redis       ❌
```

**影响**：`mvn package` / `mvn test` / `docker compose up` / `npm run build` / `wrk` 压测
在本沙箱内**均无法执行**。仓库代码按任务书指定的技术栈如实编写，
由使用者在具备网络与 Docker 的机器上执行验收。

### 关于推送到 GitHub（补充实测）

本项目当前**只存在于本地仓库**，没有任何 remote：

```
$ git remote -v          # 输出为空
```

尝试推送到 GitHub 时实测到以下阻断，因此**代码尚未上传到任何远端**：

| 检查 | 实测结果 |
| --- | --- |
| `Get-Command gh` | MISSING —— 未安装 GitHub CLI，无法用 CLI 建仓 |
| `git ls-remote https://github.com/octocat/Hello-World` | `Failed to connect to github.com:443 after 21088 ms: Could not connect to server` |
| TCP 443 连通性 | `Test-NetConnection github.com -Port 443` → True（TCP 能连，但 TLS/应用层被阻断） |
| SSH 方式（`git@github.com:...`） | `sh.exe: *** fatal error - couldn't create signal pipe, Win32 error 5` —— 沙箱禁止创建命名管道，git 无法启动 ssh 子进程 |
| `~/.git-credentials` | 不存在 |
| `~/.ssh` 下的私钥 | 不存在（只有 `known_hosts`） |
| HTTPS 代理 | 未配置 |

结论：**既无法建仓，也无法认证，更无法建立到 GitHub 的可用连接。**
本仓库已通过 `git bundle` 导出为单文件（见 `docs/github-push.md`），
使用者可在有网络的机器上一条命令推送。

---

## 阶段 0：仓库初始化

**状态**：✅ 已完成（代码层）

### 交付物

| 文件/目录 | 说明 |
| --- | --- |
| `backend/pom.xml` | Spring Boot 3.3.4 + MyBatis-Plus 3.5.7 + MySQL + Redis(Redisson) + RabbitMQ + Flyway + springdoc + Sentinel + JJWT |
| `frontend/` | Vite 5 + Vue 3.5 + TypeScript 5.6 工程骨架（等价于 `npm create vite@latest frontend -- --template vue-ts` 的产出，另加 router/pinia/element-plus/echarts 依赖） |
| `docker-compose.yml` | mysql:8.0.39 / redis:7.2.5-alpine / rabbitmq:3.13.7-management-alpine + backend + frontend，含健康检查与依赖顺序 |
| `docker/mysql/init/01-init.sql` | 数据库与账号初始化 |
| `.env.example` | 全部可配置项 |
| `.gitignore` / `.gitattributes` | 忽略规则与换行符规范化 |
| `README.md` | 架构图、启动方式、接口示例 |
| `docs/progress.md` | 本文件 |
| Git | `git init` + 首次提交 |

### 与任务书的偏差（如实记录）

1. **Spring Boot 3.3.4**（任务书写「3.3」）：取 3.3.x 最新补丁版。
2. **前端工程为手写等价产出**：`npm create vite@latest` 需要联网，沙箱无出网，
   故按该模板的标准文件集合与依赖版本手写 `package.json` / `vite.config.ts` /
   `tsconfig*.json` / `index.html` / `src/main.ts`。
   使用者本地执行 `npm install` 后即可得到与脚手架一致的工程。
3. **Docker 相关文件无法验证**：沙箱无 Docker，`docker compose config` 未执行。

### 验收命令结果

```bash
mvn -q -DskipTests package     # ❌ 未执行成功：依赖无法下载（见环境基线）
docker compose up -d           # ❌ 未执行：docker 未安装
docker compose ps              # ❌ 未执行：docker 未安装
```

**实测输出（`mvn` 探针）**：

```
[INFO] Downloading from alimaven: http://maven.aliyun.com/nexus/content/groups/public/org/springframework/boot/spring-boot-starter-parent/3.3.4/spring-boot-starter-parent-3.3.4.pom
[WARNING] Failed to create tracking file parent 'D:\Maven\...\mvn_repo\...\spring-boot-starter-parent-3.3.4.pom.lastUpdated'
java.nio.file.AccessDeniedException: D:\Maven\...\mvn_repo\org\springframework\boot\spring-boot-starter-parent\3.3.4
[ERROR] Internal error: ... MavenProjectBuildingException
```

即：**既下不动，也写不进去**，属于环境限制而非代码问题。

---

## 阶段 1：短链生成与跳转

**状态**：✅ 代码完成（构建/测试无法在本沙箱执行）

### 交付物

| 文件 | 说明 |
| --- | --- |
| `db/migration/V1__init_short_link.sql` | `t_user`、`t_short_link` 建表 |
| `db/migration/V2__seed_admin_user.sql` | 默认管理员 admin / admin123 |
| `entity/ShortLink.java`、`entity/User.java` | MyBatis-Plus 实体（逻辑删除、自动填充时间） |
| `mapper/ShortLinkMapper.java`、`mapper/UserMapper.java` | BaseMapper + 原子累加 / 状态切换 SQL |
| `service/ShortLinkService(+Impl)` | 创建、跳转解析、禁用 |
| `service/LinkQueryService(+Impl)` | 分页查询（Lambda 条件构造器，无字符串拼接） |
| `service/RedisSeqShortCodeGenerator` | Redis `INCR` 发号（`@Primary`） |
| `service/LocalSequenceShortCodeGenerator` | Redis 不可用时的本地降级发号 |
| `service/LinkConverter` | 实体 ↔ DTO 转换、短链 URL 拼装 |
| `controller/LinkController` | `POST /api/link/create`、`GET /api/link/page`、`PUT /api/link/disable/{id}` |
| `controller/RedirectController` | `GET /{shortCode}`，302 跳转 / 404 / 410 页面 |
| `controller/AuthController` | `POST /api/auth/login`、`GET /api/auth/me` |
| `util/Base62`、`util/UrlValidator`、`util/Pbkdf2PasswordEncoder`、`util/JwtTokenProvider`、`util/IpUtils` | 工具层 |
| `common/*` | 统一响应、错误码、全局异常、traceId、ThreadLocal 用户上下文 |
| 测试 5 个类 | 见下表 |

### 关键设计决策

1. **短码生成**：Redis `INCR`（按天分 key，值 = 日期 × 10^7 + 序列）→ Base62。
   `INCR` 原子，多实例下天然不重复。Redis 不可用时降级为「毫秒时间戳 + 自增序列」，
   由 `t_short_link.uk_short_code` 唯一索引 + 最多 5 次重试兜底。
2. **跳转返回 302 而非 301**，并显式带 `Cache-Control: no-store`：
   301 会被浏览器长期缓存，导致后续访问不回源、统计严重失真。
3. **唯一索引不含 `deleted`**：短码一经分配永久占用，避免逻辑删除后短码被复用造成串链。
4. **拒绝内网/回环地址**作为短链目标，防 SSRF 与自引用环。
5. **`UrlValidator` 先判协议再补 https**：否则 `javascript:alert(1)` 会被补成
   `https://javascript:alert(1)` 而被误判为合法链接（`java.net.URI` 会把
   `javascript` 当 host、`alert(1)` 当非法 port 后静默丢弃）。

### 验收命令结果

```bash
curl -X POST localhost:8080/api/link/create -H 'Content-Type: application/json' \
     -d '{"originalUrl":"https://example.com"}'     # ❌ 未执行：依赖无法下载，服务起不来
curl -I localhost:8080/{shortCode}                  # ❌ 同上
mvn test                                            # ❌ 同上（依赖无法下载 + 本地仓库不可写）
```

### 已做的静态校验（替代方案）

无法编译，因此改用**逐文件人工复核**替代，重点核对：

| 检查项 | 结果 |
| --- | --- |
| 全部 Java 文件包名与目录结构一致 | ✅ 52 个文件逐一核对 |
| import 的类与依赖声明匹配（`Wrapper` 来自 `core.conditions`，`Wrappers` 来自 `core.toolkit`） | ✅ |
| `@Primary` 解决 `ShortCodeGenerator` 两个实现的选择歧义 | ✅ `RedisSeq` 标 `@Primary` |
| 构造器注入字段与 `final` 修饰一致（`@RequiredArgsConstructor`） | ✅ |
| 实体内 `@TableField(fill=...)` 有对应 `MetaObjectHandler` | ✅ |
| `@RequireLogin` 标注与 `JwtAuthInterceptor` 判定逻辑一致 | ✅ 3 处标注 |
| `RedirectController` 路径变量正则与 `WebMvcConfig` 使用的 `PathPatternParser` 兼容 | ✅ |
| Flyway 脚本命名符合 `V{n}__{desc}.sql` | ✅ |
| 单元测试中的断言与实现行为一一对应（含边界：0 天、超长 URL、私有网段边界） | ✅ |

> ⚠️ 这份「静态校验」不能替代 `mvn test`。测试是否真的通过，必须在有网络的机器上跑一次。

### 测试清单（已编写，未执行）

| 测试类 | 覆盖内容 |
| --- | --- |
| `Base62Test` | 编码/解码往返、字符集顺序、定长补位、非法输入 |
| `UrlValidatorTest` | 协议白名单、危险协议拦截、私有网段边界（172.15/172.32 不误伤）、超长、空值 |
| `Pbkdf2PasswordEncoderTest` | **种子管理员哈希可验签**、错误密码拒绝、格式解析、随机盐 |
| `LocalSequenceShortCodeGeneratorTest` | 2 万次生成不重复、合法字符集、可解码 |
| `ShortLinkServiceImplTest` | 创建（自动码/自定义码/有效期/降级）、冲突重试与耗尽、跳转 302/404/410、统计失败不影响跳转、禁用 |

---

## 阶段 2：Redis 缓存、布隆过滤器、限流

**状态**：✅ 代码完成（构建/压测无法在本沙箱执行）

### 交付物

| 文件 | 说明 |
| --- | --- |
| `service/ShortLinkCacheManager(+Impl)` | Redisson 缓存读写、空值标记、布隆过滤器、TTL 抖动、主动失效、启动预热 |
| `service/RateLimitService(+RedisImpl)` | Redis + Lua 固定窗口限流，脚本 SHA 缓存 + NOSCRIPT 自动重载 |
| `service/CreateQuotaService(+RedisImpl)` | 按 IP 单日创建量限制（防「慢速刷」） |
| `config/RateLimitInterceptor` | 跳转请求 IP 限流，命中即 429，回写 `X-RateLimit-*` 头 |
| `config/BloomFilterWarmUpRunner` | `ApplicationReadyEvent` 时预热布隆过滤器 |
| `config/SentinelConfig` | 代码方式声明 QPS 流控规则 + 注册 `SentinelResourceAspect` |
| `config/RedissonConfig` | 复用 `spring.data.redis.*` 构造 `RedissonClient` |
| `resources/lua/rate_limit.lua` | 原子 INCR + EXPIRE 脚本 |
| 测试 3 个类 | 见下表 |

### 跳转链路（阶段 2 起）

```
请求 GET /{code}
  ↓
RateLimitInterceptor：IP 维度固定窗口（Redis+Lua，1 秒桶）
  ↓ 放行
布隆过滤器：判定「一定不存在」→ 直接 404（挡住绝大多数无效/恶意请求，不碰 Redis 数据面）
  ↓ 可能存在
Redis 缓存：命中 → 校验状态/过期 → 302
           命中空值标记 → 直接 404（缓存穿透防护）
  ↓ 未命中
MySQL 回源 → 回写缓存（TTL = min(1h, 距过期秒数) × ±20% 抖动）
```

### 缓存三大问题的具体落点

| 问题 | 方案 | 代码位置 |
| --- | --- | --- |
| 缓存穿透 | Redisson 布隆过滤器预判 + 空值标记（短 TTL，默认 60s） | `mightContain` / `putNull` |
| 缓存雪崩 | TTL 施加 ±20% 随机抖动 | `jitter` |
| 缓存击穿 | TTL 取 `min(配置 TTL, 距短链过期秒数)`，避免大量 key 同时失效；热点重建依赖 Redis 单线程 + 短 TTL | `ttlFor` |
| 缓存一致性 | 禁用短链时主动 `invalidate`，否则禁用后仍会跳到 TTL 到期 | `ShortLinkServiceImpl#disable` |

### 关键设计决策

1. **布隆过滤器必须预热**：布隆过滤器只能加不能删，进程重启后内存位图丢失，
   不预热会让**全部存量短链被误判为不存在**（这是最容易踩的坑）。
   放在 `ApplicationReadyEvent` 而非 `@PostConstruct`，因为此时数据源与 Flyway 迁移才就绪。
2. **布隆过滤器显式用二进制 codec**：`RBloomFilter` 底层是位图，
   若被 JSON codec 包一层会导致位运算数据损坏。
3. **一切缓存/限流故障都降级而非报错**：缓存读失败 → 回源 DB；
   布隆过滤器失败 → 保守返回「可能存在」；限流组件故障 → **fail-open 放行**。
   取舍理由：防护组件不应成为跳转链路的单点故障。
4. **同时用 Redis 限流与 Sentinel**：前者按 IP 维度防刷（跨实例共享计数），
   后者做接口级总量兜底（本地快速失败，不需要 Redis 往返）。
5. **Lua 脚本用 EVALSHA + NOSCRIPT 重载**：避免每次请求传输脚本文本；
   Redis 重启后脚本缓存丢失能自动恢复，而不是永久退化为 500。

### 验收命令结果

```bash
wrk -t4 -c100 -d30s http://localhost:8080/{shortCode}
# 查看缓存命中率、QPS、P99
```

❌ **未执行**：沙箱内 `wrk` 不存在，后端也无法启动（依赖无法下载 + 无 Docker）。
因此**缓存命中率、QPS、P99 三项指标本阶段没有任何实测数据**，
不会用任何推测值代替。压测方法与指标采集口径见 `docs/benchmark.md`（阶段 4 补齐），
实测数据需在有 Docker 与网络的机器上跑出后填入。

### 测试清单（已编写，未执行）

| 测试类 | 覆盖内容 |
| --- | --- |
| `ShortLinkServiceImplTest`（阶段 2 扩充） | 布隆过滤器短路、缓存命中不回源、未命中回源并回写、空值穿透防护、禁用后失效缓存 |
| `RateLimitInterceptorTest` | 配额内放行 + 响应头、超限 429 + 统一错误体、非 GET/多段路径/根路径跳过、阈值 0 关闭 |
| `RedisRateLimitServiceTest` | 阈值边界（等于阈值放行）、超限拒绝、**Redis 异常 fail-open**、NOSCRIPT 自动重载 |
| `CacheTtlJitterTest` | 抖动区间 ±20%、确实打散、TTL 下限 1 秒、关闭抖动的分支 |

---

## 阶段 3：MQ 异步统计与管理后台 API

**状态**：✅ 代码完成（构建与联调无法在本沙箱执行）

### 交付物

| 文件 | 说明 |
| --- | --- |
| `db/migration/V3__init_stats.sql` | `t_link_access_log`、`t_link_stats`、`t_link_uv_log` |
| `mq/LinkAccessMessage` | 访问日志消息体 |
| `mq/LinkAccessProducer` | 跳转时投递（**投递失败吞掉，不影响跳转**） |
| `mq/LinkAccessConsumer` | 内存攒批（500 条 / 2 秒）+ 刷盘成功才 ack |
| `mq/AccessLogBatchWriter` | MyBatis BATCH 执行器批量写明细 |
| `config/RabbitMqConfig` | 交换机/队列/死信拓扑 + JSON 转换器（含受信任包白名单） |
| `service/StatsService(+Impl)` | 访问日志落库、统计查询、趋势查询 |
| `controller/StatsController` | `GET /api/stats/{shortCode}`、`GET /api/stats/trend` |
| 测试 2 个类 | `StatsServiceImplTest`、`LinkAccessConsumerTest` |

### 消息拓扑

```
shortlink.access.exchange (direct, routing key "link.access")
   └─ shortlink.access.queue
        消费失败 → shortlink.access.dlx.exchange → shortlink.access.dlx.queue
```

### 统计口径（重要，避免"看起来对但算错"）

| 指标 | 口径 | 如何保证正确 |
| --- | --- | --- |
| PV | 访问次数 | 按 `(短码, 日期)` 聚合后**一次性** `ON DUPLICATE KEY UPDATE pv = pv + VALUES(pv)` |
| UV | 当天独立访客 | `t_link_uv_log` 唯一键 `(short_code, stat_date, ip_hash)` + `INSERT IGNORE`，**返回 1 才算新访客** |
| 明细 | 每次访问一行 | BATCH 执行器批量插入，时间冗余出 `access_date` / `hour` 让报表走索引 |

**为什么 UV 要单开一张表**：聚合表里只有一个当天 UV 数字，无法回答
「这个 IP 今天来过没有」。靠唯一键 + `INSERT IGNORE` 的返回值做首次访问判定，
是唯一不引入额外存储、又能原子保证的做法。

### 关键设计决策

1. **刷盘成功才 ack**：先 ack 后写库，写库失败消息就永久丢了。
   这里选择"最多重复消费"而不是"可能丢数"：UV 有唯一键天然去重，
   PV 按批聚合，重复消费的代价远小于丢数。**这是本阶段最重要的正确性保证**，
   已由 `LinkAccessConsumerTest#shouldNackWhenFlushFails` 覆盖。
2. **跳转热路径彻底不写库**：阶段 2 还保留 `updateLastAccess`，
   阶段 3 起改为投递 MQ，PV/UV/最近访问全部由消费者累加。
3. **批量落库而非逐条**：峰值下逐条 INSERT 会打满数据库。
   用 MyBatis BATCH 执行器而不是拼多值 INSERT——后者受 `max_allowed_packet` 限制。
4. **Redis 只用于发布/消费通路，不用 Redis 做 PV 计数器**：
   计数器需要定期回写且故障时会丢，直接用 MQ + 数据库更简单可靠。
5. **`AccessLogBatchWriter` 单独抽层**：把 `SqlSessionFactory`/BATCH 这类基础设施
   细节隔离在业务逻辑之外，`StatsServiceImpl` 才能被纯单元测试覆盖。
6. **JSON 消息显式声明受信任包**：不依赖默认值，防反序列化攻击。

### 验收命令结果

```bash
curl localhost:8080/api/stats/{shortCode}      # ❌ 未执行：服务无法启动
curl localhost:8080/api/stats/trend            # ❌ 未执行
```

❌ **未执行**，原因同前（无网络下载依赖、本地 Maven 仓库不可写、无 Docker）。
接口的请求/响应结构、状态码、鉴权要求均已按任务书实现并在下方静态复核。

### 静态校验

| 检查项 | 结果 |
| --- | --- |
| `@RabbitListener` 用 `@Header(AmqpHeaders.CHANNEL)` 注入 Channel（不标注则注入失败） | ✅ |
| 手动 ack 模式与 `spring.rabbitmq.listener.simple.acknowledge-mode=manual` 一致 | ✅ |
| `@Scheduled` 生效需 `@EnableScheduling`，启动类已标注 | ✅ |
| 三张新表的列名与实体字段驼峰映射一致 | ✅ |
| `upsertStats` 的 `ON DUPLICATE KEY UPDATE` 依赖 `uk_code_date` 唯一键存在 | ✅ V3 已建 |
| `tryInsertUv` 的 `INSERT IGNORE` 依赖 `uk_code_date_ip` 唯一键存在 | ✅ V3 已建 |
| `StatsServiceImpl` 构造器参数与 `@InjectMocks` 注入的 mock 数量一致 | ✅ 4 个 |

### 测试清单（已编写，未执行）

| 测试类 | 覆盖内容 |
| --- | --- |
| `StatsServiceImplTest` | 空批次跳过、同码同日聚合为一次 upsert、**UV 去重（重复 IP 返回 0 不计数）**、缺 ipHash 不计 UV、多组分组、冗余列填充、超长字段截断、畸形消息跳过、linkId 缺失降级、统计详情交叉校验、天数钳制 1-90、短码不存在抛 404 |
| `LinkAccessConsumerTest` | 先入缓冲不立即落库、**成功后 ack**、**失败 nack+requeue 绝不 ack**、整批传递、空缓冲空操作、毒消息丢弃、ack 失败不外抛 |

---

## 阶段 4：前端、Docker、压测、README

**状态**：✅ 代码完成（构建、镜像、压测均无法在本沙箱执行）

### 交付物

| 文件 | 说明 |
| --- | --- |
| `frontend/src/api/{http,link,stats,auth}.ts` | axios 封装（JWT 注入、统一解包 Result、401/429 处理）与接口定义 |
| `frontend/src/store/auth.ts` | Pinia 登录状态（token 持久化 + 刷新后拉取用户） |
| `frontend/src/router/index.ts` | 路由表 + 全局守卫（未登录跳登录，已登录不进登录页） |
| `frontend/src/layout/AdminLayout.vue` | 侧边栏 + 顶栏 + 退出登录 |
| `frontend/src/components/ChartPanel.vue` | ECharts 封装（响应式、resize、loading、卸载 dispose） |
| `frontend/src/views/LoginView.vue` | 登录页（表单校验、默认账号提示） |
| `frontend/src/views/DashboardView.vue` | 概览：4 个指标卡 + 趋势折线 + 最近创建 |
| `frontend/src/views/LinkListView.vue` | 短链管理：检索、分页、创建弹窗、复制、禁用、跳统计 |
| `frontend/src/views/StatsView.vue` | 统计：指标卡 + PV/UV 折线 + 每日柱状 + 明细表 |
| `frontend/src/views/NotFoundView.vue` | 404 页 |
| `frontend/Dockerfile` + `frontend/nginx/default.conf` | 多阶段构建 + SPA 回退 + /api 反代 + 静态资源长缓存 |
| `backend/Dockerfile` | 多阶段构建 + Spring Boot 分层 + 非 root 运行 |
| `loadtest/wrk/redirect.sh` | wrk 压测脚本（含预热与结果落盘） |
| `loadtest/jmeter/shortlink-redirect.jmx` | JMeter 压测计划（参数化 host/port/code/threads/duration） |
| `loadtest/postman/*.json` | Postman 集合（登录自动写 token、创建自动写 shortCode） |
| `docs/benchmark.md` | 压测方法论、采集口径、实测表格（留空待填） |
| `README.md` | 架构图、一键启动、接口一览、冒烟脚本、压测说明、诚实声明 |

### 前端关键实现点

1. **token 存 localStorage，用户信息不存**：刷新后通过 `/api/auth/me` 重新拉取，
   这样后台改了用户信息能及时反映，也避免把过期身份缓存在本地。
2. **axios 响应拦截器统一解包 `Result`**：非 0 业务码直接 reject 并弹提示，
   页面里只处理成功分支，减少重复代码。
3. **ECharts 用 `shallowRef` 持有**：ECharts 实例是庞大的非响应式对象，
   用 `ref` 会带来无谓的深度代理开销；同时在 `onBeforeUnmount` 里 `dispose` 防内存泄漏。
4. **复制短链做降级**：`navigator.clipboard` 在非 HTTPS 或旧浏览器不可用，
   降级到 `document.execCommand('copy')`，再失败则提示手动复制。
5. **`index.html` 不缓存、`/assets/*` 长缓存**：Vite 产物带内容 hash，
   壳子不缓存才能保证发版后用户立刻拿到新版本。

### 部署关键实现点

1. **后端镜像用 Spring Boot 分层 `layertools`**：依赖层变化频率低，
   放在镜像层前面，代码改动时不必重传全部依赖。
2. **容器内非 root 运行**：`useradd -r shortlink` + `chown`，
   降低容器逃逸后的影响面。
3. **Nginx 透传 `X-RateLimit-*` 与 `Retry-After`**：否则前端读不到剩余配额。
4. **Nginx `try_files ... /index.html`**：history 路由模式下刷新子页面不会 404。
5. **`/actuator/` 限制来源网段**：避免健康与指标端点暴露到公网。

### 验收命令结果

```bash
docker compose up -d --build     # ❌ 未执行：docker 未安装
npm run build                    # ❌ 未执行：npm 依赖无法下载（ENOTCACHED）
# 浏览器访问前端，创建短链，查看统计   # ❌ 未执行：前端构建产物与后端服务都不存在
```

❌ **全部未执行**，原因同前。前端 `npm run build` 会先跑 `vue-tsc -b` 类型检查，
因此**我无法保证前端 TypeScript 零类型错误**——这是本阶段最大的未验证风险点。
为降低风险，代码中刻意避免了容易踩的类型陷阱：

| 易错点 | 处理方式 |
| --- | --- |
| `noUnusedLocals`/`noUnusedParameters` 为 true，未用变量会直接构建失败 | 逐文件检查，未使用的 `props` 变量已移除或实际使用 |
| ECharts option 需要 `EChartsOption` 类型 | 计算属性显式标注 `computed<EChartsOption>(...)` |
| axios 泛型返回 | 统一 `http.get<ApiResult<T>>` 并取 `data.data` |
| `import type` 与值导入混用（`verbatimModuleSyntax`） | 所有纯类型导入均使用 `import type` |
| Element Plus 组件 props | 用官方签名（`v-model:current-page`、`el-radio-button :value`） |

### 静态校验

| 检查项 | 结果 |
| --- | --- |
| `docker-compose.yml` 中 backend/frontend 的 `build.context` 与实际目录一致 | ✅ `./backend`、`./frontend` |
| `frontend/Dockerfile` 期望的 `nginx/default.conf` 路径存在 | ✅ 已创建 |
| 后端 Dockerfile 的 jar 名与 `pom.xml` 的 `finalName` 一致 | ✅ 均为 `shortlink-cloud-backend` |
| `FRONTEND` 健康检查路径 `/healthz` 在 Nginx 配置中有对应 location | ✅ |
| 前端路由路径与后端接口路径前缀一致（`VITE_API_BASE_URL=/api`） | ✅ |
| Postman 集合 JSON 可被解析 | ✅ `json.load` 通过 |
| JMeter jmx XML 结构完整（TestPlan → ThreadGroup → HTTPSamplerProxy → Assertion） | ✅ |
| `loadtest/wrk/redirect.sh` 输出文件目录会被创建（`mkdir -p`） | ✅ |

### 测试清单（已编写，未执行）

前端**没有编写自动化测试**——本阶段未引入 Vitest，属于明确的范围取舍：
任务书要求的是「ECharts 展示 PV/UV/趋势」的可视化界面，没有要求前端测试。
后端共 **10 个测试类**（`Base62Test`、`UrlValidatorTest`、`Pbkdf2PasswordEncoderTest`、
`CacheTtlJitterTest`、`LocalSequenceShortCodeGeneratorTest`、`ShortLinkServiceImplTest`、
`RateLimitInterceptorTest`、`RedisRateLimitServiceTest`、`StatsServiceImplTest`、
`LinkAccessConsumerTest`）覆盖核心逻辑，前端以静态类型检查作为质量门禁。

---

## 汇总：本沙箱内无法验证的事项

| 任务书中的验收动作 | 能否在本沙箱验证 | 原因 |
| --- | --- | --- |
| `mvn -q -DskipTests package` | ❌ | 依赖无法下载 + 本地仓库不可写 |
| `mvn test` | ❌ | 同上。**10 个测试类均未执行过，不能声称通过** |
| `docker compose up -d` / `ps` / `--build` | ❌ | 未安装 Docker |
| `curl` 本地接口 | ❌ | 后端无法启动 |
| `npm run build` | ❌ | npm 无法安装依赖（ENOTCACHED） |
| 前端 TypeScript 类型检查 | ❌ | 同上，`vue-tsc` 无法运行 |
| `wrk` QPS / P99 压测 | ❌ | 无后端、无 wrk、无 Docker |
| 真实 MySQL / Redis / RabbitMQ 联调 | ❌ | 中间件无法启动 |
| Java 源码、SQL、Vue 源码、配置的静态审查 | ✅ | 逐文件复核，结果见各阶段小节 |
| Postman 集合 JSON 可解析 | ✅ | `json.load` 通过 |

### 与任务书的技术选型偏差（全部如实列出）

| 项目 | 任务书 | 实际 | 理由 |
| --- | --- | --- | --- |
| Spring Boot 版本 | 3.3 | 3.3.4 | 取 3.3.x 最新补丁版 |
| 密码存储 | 未指定算法 | **PBKDF2-HMAC-SHA256（210000 次迭代）** | bcrypt 的种子哈希在离线环境下无法验证正确性；PBKDF2 由 JDK 原生提供（零外部依赖），且本仓库已用等价 Python 实现复现完全相同的字节并交叉验证。**这是本项目唯一一处偏离常规做法的选型**，相关测试 `Pbkdf2PasswordEncoderTest#shouldVerifySeedAdminHash` 校验了写死在 V3 前的种子哈希 |
| Redisson 依赖 | `redisson-spring-boot-starter` | `redisson`（手动声明 `RedissonClient`） | starter 会带入与其绑定的 `spring-data-redis` 版本，可能与 Spring Boot 管理的版本冲突 |
| 压测工具 | JMeter 或 wrk | 两个都提供 | 任务书写「或」，两者都给可以覆盖 Windows/Linux |
| 前端脚手架 | `npm create vite@latest` | 手写等价产出 | 脚手架需要联网，沙箱无出网；已按该模板的标准文件集合与依赖版本手写 |

### 建议的验证顺序（在有 Docker 与网络的机器上）

```bash
# 1. 先验证后端能编译、测试能过 —— 这一步会暴露最多问题
cd backend && mvn -DskipTests package && mvn test

# 2. 再验证前端类型检查与构建
cd ../frontend && npm install && npm run build

# 3. 最后起服务做端到端冒烟
cd .. && cp .env.example .env && docker compose up -d --build && docker compose ps
#    然后执行 README「冒烟测试」一节的三条命令

# 4. 一切正常后再跑压测，把结果填进 docs/benchmark.md §4
```

**最可能出问题的地方**（按概率排序，供排查参考）：

1. **前端 `vue-tsc` 类型错误**——完全没跑过类型检查，是最大的未知项。
2. **`mvn test` 断言与实际行为不一致**——测试是按设计意图写的，但没执行过。
3. **Redisson `RScript.evalSha` 签名**与 3.32.0 实际 API 有出入。
4. **MyBatis-Plus 对 `@Select` 文本块（Java 15+ text block）的支持**——
   理论上 MyBatis 3.5.16 会原样传递 SQL 字符串，但未实测。
5. **jackson 反序列化 `LocalDateTime`** 到缓存实体时区问题——
   `application.yml` 已配 `time-zone: Asia/Shanghai`，但未实测。

### 一键验证脚本

上面这些步骤已封装成脚本，按顺序执行并在失败处立即停止：

```powershell
# Windows：完整验证
pwsh -File scripts/verify.ps1

# 跳过需要 Docker 的步骤
pwsh -File scripts/verify.ps1 -SkipDocker
```

脚本会逐步输出「通过 / 失败 / 跳过」，失败时给出排查方向。
⚠️ **该脚本自身也未被执行过**——它是新写的，如遇脚本自身的语法问题请直接反馈。

### 云端 CI（推荐优先使用）

`.github/workflows/ci.yml` 把上述验证搬到 GitHub Actions，推送后自动执行：

| Job | 内容 |
| --- | --- |
| `backend-unit` | 编译 + 10 个单元测试类 |
| `backend-integration` | Testcontainers 起真实 MySQL/Redis/RabbitMQ 跑集成测试 |
| `backend-package` | 打包并校验 jar 产物 |
| `frontend` | TypeScript 类型检查 + 生产构建 |
| `docker-build` | 两个镜像构建验证 + `docker compose config` 语法校验 |

**这是把"未验证代码"变成"已验证"的最省事路径**：推送即触发，无需本地装 Docker。

---

## 附加批次：为「学习/面试」用途补齐的内容

首次交付后用户说明用途是学习与面试，因此补做了以下内容。
**以下全部同样未在沙箱内执行。**

### 1. 数据保留清理（修复只增不删的表）

| 文件 | 说明 |
| --- | --- |
| `config/RetentionProperties.java` | 保留天数、批大小、单次最大批次、cron 均可配 |
| `service/DataRetentionService(+Impl)` | 每天 03:30 分批删除过期明细，带防重入 |
| `mapper/LinkAccessLogMapper#deleteBeforeDate` | `DELETE ... LIMIT`（MySQL 方言） |
| `mapper/LinkUvLogMapper#deleteBeforeDate` | 同上 |

**修掉的问题**：`t_link_access_log` 与 `t_link_uv_log` 原本只增不删。
按日活 10 万、人均 3 次估算，一年约 1 亿行，最终写满磁盘、索引不可维护。

**为什么不用分区表**：分区要求把分区列纳入主键，且新增分区要提前建。
对"每天跑一次清理"的中等规模系统，索引 + 分批 DELETE 的复杂度更低。
真到单表过亿、需要秒级 `DROP` 历史时再上分区。

### 2. 集成测试（填补真实中间件链路的零覆盖）

| 文件 | 覆盖内容 |
| --- | --- |
| `integration/AbstractIntegrationTest.java` | Testcontainers 单例容器基类（MySQL 8.0.39 / Redis 7.2.5 / RabbitMQ 3.13.7，版本与 compose 一致） |
| `integration/ShortLinkFlowIntegrationTest.java` | 迁移可执行性、唯一索引真实生效、创建跳转主链路、缓存回写与 `LocalDateTime` 字段完整性、空值穿透、禁用立即失效缓存、过期 410、MQ 落库与 PV/UV 口径、Lua 限流阈值与 TTL、布隆过滤器预热与误判率 |
| `integration/DataRetentionIntegrationTest.java` | 清理窗口边界、空操作、批次上限约束 |

**为什么必须有这一层**：原有 10 个单元测试类全部使用 mock，
**验证不了 SQL 语法、Flyway 迁移可执行性、JSON 序列化兼容性**。
例如清理用的 `DELETE ... LIMIT` 是 MySQL 方言，H2 与 mock 都测不出来。
因此同时**移除了 H2 依赖**——它给不了真实方言覆盖，留着只会造成"测过了"的错觉。

### 3. 工程化配套

| 文件 | 说明 |
| --- | --- |
| `.github/workflows/ci.yml` | 5 个 job 的完整 CI |
| `scripts/verify.ps1` | 本地一键验证，失败即停 |
| `docs/interview-guide.md` | 面试讲述指南：9 个决策点的"怎么答"、不足之处的答法、技术细节速查 |
| `LICENSE` | MIT |
| `pom.xml` | 新增 `integration` profile；移除 H2；新增 Testcontainers 依赖 |

### 4. 种子密码哈希再次交叉验证（本批次唯一真正验证过的一项）

```
salt length : 16 bytes (Java expects 16)
hash length : 32 bytes (Java expects 256-bit key)
iterations  : 210000
expected: RfAgy57mSojqBpfe9GZYHy5atqpysHk3JOmMagNFkKc=
actual  : RfAgy57mSojqBpfe9GZYHy5atqpysHk3JOmMagNFkKc=
HASH REPRODUCES: True
wrong password rejected: True
wrong iteration rejected: True
```

即 `V2__seed_admin_user.sql` 中的哈希确实等于
`PBKDF2-HMAC-SHA256("admin123", 盐, 210000 次, 256 位)`，
参数与 `Pbkdf2PasswordEncoder` 默认值完全一致。
**管理员的密码校验链路无需等 CI 即可确认正确。**

### 5. 附加批次仍未验证的事项

| 项目 | 状态 |
| --- | --- |
| 新增的 2 个集成测试类 | ❌ 未执行（需 Docker） |
| 新增的清理逻辑（`DELETE ... LIMIT`） | ❌ 未执行 |
| `mvn -Pintegration test` | ❌ 未执行 |
| CI workflow 语法 | ❌ 未校验 |
| `scripts/verify.ps1` | ❌ 未执行（脚本自身未跑过） |
| 前端类型检查 | ❌ 仍未执行 |
| 推送到 GitHub | ❌ 实测仍被阻断（TLS `SEC_E_NO_CREDENTIALS`），代码尚未上传任何远端 |

---

## 第一次 CI 运行结果（真实执行记录）

代码推送到 GitHub 后 CI 自动运行，**第 1 次运行编译失败**。
这是本项目第一次获得真实的执行反馈，记录如下。

### 失败内容

```
[ERROR] ShortLinkCacheManagerImpl.java:[92,22] method put in interface
        org.redisson.api.RMap<K,V> cannot be applied to given types;
  required: java.lang.String,java.lang.String
  found:    java.lang.String,java.lang.String,long,java.util.concurrent.TimeUnit
```

同一处错误出现两次（正常缓存写入与空值标记写入）。

### 根因

我假设 Redisson 的 `RMap#put` 有带 TTL 的重载，实际上**没有**。
原因是 Redis Hash 的过期时间只能设在 key 上，无法给单个 field 设 TTL——
所以 `RMap` 根本不提供这个能力。要用带 TTL 的 field 必须用 `RMapCache`。

**这是一个只有编译才能发现的错误**：单元测试全部 mock 掉 Mapper 与 Redis，
`ShortLinkCacheManagerImpl` 也没有对应单元测试，所以 mock 层面永远测不出来。

### 修复方式

改用 **每个短码一个 `RBucket`**（key 形如 `sl:link:{shortCode}`），
而不是一个 `RMap` 装所有短码。这个模型反而更贴合需求：

| 维度 | RMap（原方案） | RBucket（修复后） |
| --- | --- | --- |
| 每条映射独立 TTL | ❌ 不支持 | ✅ 天然支持 |
| 空值标记 | 需要第二个 key 空间 | ✅ 同一个 key，值为空串 |
| 大 key / 热 key 风险 | 有（所有短码挤一个 Hash） | 无（单 key 操作） |
| 排障可读性 | field 层级，需 hget | `GET sl:link:abc1234` 直接可读 |

已用 `RBucket.set(V, Duration)`，该方法在 Redisson 3.32.0 中确认存在
（对照官方源码 `org/redisson/api/RBucket.java` 核对过签名）。

### 顺带发现并修掉的第二个问题

核对 Testcontainers 1.20.3 的 `RabbitMQContainer` 源码时发现：
`withUser()` / `withVhost()` 是 `@Deprecated` 实现，它们通过容器内执行
`rabbitmqadmin` 来声明用户，而 `rabbitmq:3.13` 镜像**已经不提供
`rabbitmqadmin`**，命令会静默失败（只打 error 日志），
结果是"声明的账号根本不存在，应用连接时报认证失败"。

改为使用容器默认的 `guest/guest`，并在 `@DynamicPropertySource` 里显式覆盖
`spring.rabbitmq.virtual-host`。集成测试的目的是验证消息收发链路，不是验证账号配置。

### 本次修复新增的测试

新增 `DataRetentionServiceImplTest`（8 个用例），覆盖清理任务的分批循环：
满批继续、不足批停止、单次上限约束、保留窗口计算、异常吞掉、开关关闭。
这部分是纯算法，不需要容器；`DELETE ... LIMIT` 的真实语法仍由集成测试验证。

### 仍未验证

第 2 次 CI 运行结果待观察。**前端类型检查、集成测试、镜像构建都还没有成功跑过**。

