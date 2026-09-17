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

**状态**：待开始

---

## 阶段 3：MQ 异步统计与管理后台 API

**状态**：待开始

---

## 阶段 4：前端、Docker、压测、README

**状态**：待开始

---

## 汇总：本沙箱内无法验证的事项

| 任务书中的验收动作 | 能否在本沙箱验证 | 原因 |
| --- | --- | --- |
| `mvn -q -DskipTests package` | ❌ | 依赖无法下载 + 本地仓库不可写 |
| `mvn test` | ❌ | 同上 |
| `docker compose up -d` / `ps` / `--build` | ❌ | 未安装 Docker |
| `curl` 本地接口 | ❌ | 后端无法启动 |
| `npm run build` | ❌ | npm 无法安装依赖 |
| `wrk` QPS / P99 压测 | ❌ | 无后端、无 wrk、无 Docker |
| 真实 MySQL / Redis / RabbitMQ 联调 | ❌ | 中间件无法启动 |
| **Java 源码、SQL、Vue 源码、配置的静态审查** | ✅ | 逐文件校验完成，结果见各阶段小节 |

后续阶段的验收小节将保持同样的诚实标准。
