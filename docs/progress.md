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

**状态**：待开始

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
