# 压测报告 · shortlink-cloud

> **状态：等待实测填充。**
>
> 本文件的方法论、命令与采集口径已经完整确定，但**没有任何实测数字**。
> 原因见文末「为什么这里没有数字」——交付本仓库的沙箱环境无法运行 Docker、
> 无法下载依赖、无 HTTPS 出网，因此压测根本跑不起来。
> 请按下面的步骤在具备 Docker 的机器上执行后，把结果填入 §4。

---

## 1. 目标值

| 指标 | 目标 | 说明 |
| --- | --- | --- |
| QPS | ≥ 3000 | 单实例、4 核 / 8G 量级 |
| P99 延迟 | < 50 ms | 302 跳转全链路 |
| 错误率 | < 0.1% | 不含被限流拒绝的 429 |
| 缓存命中率 | ≥ 95% | 预热后、重复访问同一批短码 |

---

## 2. 被测环境（模板，请按实际填写）

| 项目 | 值 |
| --- | --- |
| 压测机 CPU / 内存 | 待填 |
| 被测机 CPU / 内存 | 待填 |
| 操作系统 | 待填 |
| Docker 版本 | 待填 |
| JVM | 待填（默认 `-Xms512m -Xmx1024m -XX:+UseG1GC`） |
| MySQL | 8.0.39，容器内，`innodb_buffer_pool_size=512M` |
| Redis | 7.2.5-alpine，容器内，`maxmemory=512mb` |
| RabbitMQ | 3.13.7-management-alpine，容器内 |
| 网络 | 压测机与被测机同宿主机 / 同局域网（待填） |

**关键前提**：`wrk` 与被测服务不要跑在同一台资源紧张的机器上，否则测到的是压测机瓶颈。

---

## 3. 压测方法

### 3.1 准备

```bash
# 1. 启动全部服务
cp .env.example .env
docker compose up -d --build

# 2. 调大限流阈值，否则会被 429 挡住而测不出真实吞吐
#    编辑 .env：
#      RATE_LIMIT_PERMIT_PER_SECOND=10000000
#      RATE_LIMIT_CREATE_PER_DAY=1000000
docker compose up -d backend      # 让新阈值生效

# 3. 关闭慢 SQL 日志（prod profile 默认已关闭）
```

### 3.2 造数并预热

```bash
# 创建一条短链，拿到 shortCode
curl -s -X POST http://localhost:8080/api/link/create \
  -H 'Content-Type: application/json' \
  -d '{"originalUrl":"https://example.com/benchmark"}' | tee /tmp/create.json

SHORT_CODE=$(python3 -c "import json;print(json.load(open('/tmp/create.json'))['data']['shortCode'])")
echo "shortCode = ${SHORT_CODE}"

# 预热一次，让 Redis 缓存与 JVM JIT 进入稳态
for i in $(seq 1 200); do curl -s -o /dev/null http://localhost:8080/${SHORT_CODE}; done
```

### 3.3 执行

```bash
# wrk（推荐）
./loadtest/wrk/redirect.sh ${SHORT_CODE} 30s 100 4

# JMeter（跨平台，Windows 也可用）
jmeter -n -t loadtest/jmeter/shortlink-redirect.jmx \
       -Jhost=localhost -Jport=8080 -Jcode=${SHORT_CODE} \
       -Jthreads=100 -Jduration=30 \
       -l loadtest/results/redirect.jtl \
       -e -o loadtest/reports/redirect
```

### 3.4 采集配套指标

```bash
# 缓存命中率：Redis 命中/未命中计数
docker exec -it shortlink-redis redis-cli -a redis123 --no-auth-warning info stats \
  | grep -E "keyspace_hits|keyspace_misses"

# QPS 与 P99 的服务端视角（Actuator）
curl -s http://localhost:8080/actuator/metrics/http.server.requests | head -50

# 容器资源占用
docker stats --no-stream

# MySQL 慢查询 / 连接数
docker exec -it shortlink-mysql mysql -uroot -proot123456 \
  -e "SHOW GLOBAL STATUS LIKE 'Threads_connected'; SHOW GLOBAL STATUS LIKE 'Slow_queries';"
```

**命中率计算**：`hits / (hits + misses)`。注意这个比值必须**减去预热阶段之外的新键访问**才有意义，
所以正式压测只压同一个已缓存的 shortCode。

---

## 4. 实测结果

### 4.1 wrk

| 并发 | QPS | 平均延迟 | P99 | 错误数 | 备注 |
| --- | --- | --- | --- | --- | --- |
| 待填 | 待填 | 待填 | 待填 | 待填 | |

粘贴 wrk 原始输出：

```
（待填：./loadtest/results/wrk-redirect-*.txt 的完整内容）
```

### 4.2 配套指标

| 指标 | 值 |
| --- | --- |
| 缓存命中率 | 待填 |
| Redis keyspace_hits / misses | 待填 |
| 容器 CPU 峰值 | 待填 |
| 容器内存峰值 | 待填 |
| MySQL Threads_connected 峰值 | 待填 |
| MySQL Slow_queries | 待填 |

### 4.3 目标达成情况

| 指标 | 目标 | 实测 | 是否达成 |
| --- | --- | --- | --- |
| QPS | ≥ 3000 | 待填 | 待填 |
| P99 | < 50 ms | 待填 | 待填 |
| 错误率 | < 0.1% | 待填 | 待填 |
| 缓存命中率 | ≥ 95% | 待填 | 待填 |

---

## 5. 架构上为高并发做的取舍（供结果解读参考）

| 设计 | 对性能的影响 |
| --- | --- |
| 布隆过滤器前置 | 无效短码不碰 Redis 数据面，恶意扫描时保护后端 |
| Redis 缓存 + TTL 抖动 | 命中率高时跳转不查库；抖动避免集中失效造成尖峰 |
| 跳转不写库，只投 MQ | 热路径无 DB 写，P99 大幅下降；代价是统计有约 2 秒延迟 |
| 302 而非 301 | 每次访问都回源，统计准确；代价是无法利用浏览器缓存 |
| `no-store` 响应头 | 同上 |
| 限流放在拦截器 | 命中限流立刻 429，不进入后续链路 |
| Tomcat `max-threads=400` / `accept-count=1000` | 避免突发流量直接拒绝连接 |

---

## 6. 为什么这里没有数字（诚实声明）

本仓库的代码与脚本由 AI 在沙箱环境中编写，该环境实测存在以下硬性限制：

| 限制 | 实测证据 |
| --- | --- |
| 无 Docker | `Get-Command docker` → MISSING，无 `com.docker.service` |
| 无 HTTPS 出网 | `curl: (35) schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS` |
| Maven 依赖无法下载 | 镜像 `http://maven.aliyun.com/...` 不可达 |
| Maven 本地仓库不可写 | 本地仓库位于工作区之外 → `AccessDeniedException` |
| 无 wrk | 命令不存在 |

因此 `mvn package`、`docker compose up`、`wrk` 全部无法执行，
**任何写在这里的数字都会是编造的**。宁可留空，也不填推测值。

请在有 Docker 与网络的环境执行 §3 后填入 §4，并保留原始输出文件。
