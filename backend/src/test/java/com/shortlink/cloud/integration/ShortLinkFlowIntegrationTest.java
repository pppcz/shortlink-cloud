package com.shortlink.cloud.integration;

import com.shortlink.cloud.common.BizException;
import com.shortlink.cloud.common.Constants;
import com.shortlink.cloud.common.ErrorCode;
import com.shortlink.cloud.dto.CreateLinkRequest;
import com.shortlink.cloud.dto.CreateLinkResponse;
import com.shortlink.cloud.dto.LinkPageQuery;
import com.shortlink.cloud.dto.LinkStatsVO;
import com.shortlink.cloud.dto.StatsSummaryVO;
import com.shortlink.cloud.entity.ShortLink;
import com.shortlink.cloud.mapper.LinkStatsMapper;
import com.shortlink.cloud.mapper.ShortLinkMapper;
import com.shortlink.cloud.mq.LinkAccessConsumer;
import com.shortlink.cloud.mq.LinkAccessProducer;
import com.shortlink.cloud.service.LinkQueryService;
import com.shortlink.cloud.service.RateLimitService;
import com.shortlink.cloud.service.RedirectResult;
import com.shortlink.cloud.service.ShortLinkCacheManager;
import com.shortlink.cloud.service.ShortLinkService;
import com.shortlink.cloud.service.StatsService;
import com.shortlink.cloud.util.IpHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 端到端集成测试：真实 MySQL + Redis + RabbitMQ。
 *
 * <p>这是本项目**唯一能验证以下事情**的测试（单元测试全是 mock，覆盖不到）：
 * <ol>
 *   <li>Flyway 迁移能否在真实 MySQL 8 上从头执行成功</li>
 *   <li>MyBatis-Plus 的 SQL（含文本块 {@code @Select}）语法是否正确</li>
 *   <li>缓存实体经 Jackson JSON 序列化 / 反序列化后字段是否完整（尤其 LocalDateTime）</li>
 *   <li>访问日志消息能否被消费端正确反序列化并落库</li>
 *   <li>PV/UV 的聚合与去重口径在真实数据库唯一约束下是否成立</li>
 * </ol>
 *
 * @author shortlink-cloud
 */
@Tag("integration")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ShortLinkFlowIntegrationTest extends AbstractIntegrationTest {

    private static final long ADMIN_ID = 1L;
    private static final String CLIENT_IP = "1.2.3.4";

    @Autowired
    private ShortLinkService shortLinkService;

    @Autowired
    private ShortLinkCacheManager cacheManager;

    @Autowired
    private LinkAccessProducer accessProducer;

    @Autowired
    private LinkAccessConsumer accessConsumer;

    @Autowired
    private StatsService statsService;

    @Autowired
    private LinkQueryService linkQueryService;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private ShortLinkMapper shortLinkMapper;

    @Autowired
    private LinkStatsMapper statsMapper;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 每个用例前清空业务表，保证用例之间互不影响。 */
    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("DELETE FROM t_link_uv_log");
        jdbcTemplate.execute("DELETE FROM t_link_stats");
        jdbcTemplate.execute("DELETE FROM t_link_access_log");
        jdbcTemplate.execute("DELETE FROM t_short_link");
    }

    // ------------------------------------------------------------------
    // 迁移与表结构
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Flyway 迁移在真实 MySQL 8 上执行成功，五张业务表齐备")
    void shouldApplyMigrationsOnRealMysql() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class);

        assertThat(tables)
                .as("V1/V2/V3 迁移应创建全部业务表")
                .contains("t_user", "t_short_link", "t_link_access_log", "t_link_stats", "t_link_uv_log");

        Integer failed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", Integer.class);
        assertThat(failed).as("不应存在失败的迁移").isZero();

        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class);
        assertThat(applied).as("至少应执行 V1/V2/V3 三条迁移").isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("种子管理员存在，且密码以 PBKDF2 格式存储而非明文")
    void shouldSeedAdminUser() {
        String password = jdbcTemplate.queryForObject(
                "SELECT password FROM t_user WHERE username = 'admin'", String.class);

        assertThat(password).startsWith("pbkdf2-sha256$");
        assertThat(password).doesNotContain("admin123");
    }

    @Test
    @DisplayName("唯一索引真实生效：同一短码插入两次应被数据库拒绝")
    void shouldEnforceUniqueShortCodeConstraint() {
        shortLinkMapper.insert(rawLink(9001L, "dupcode", "https://example.com/1"));

        assertThatThrownBy(() -> shortLinkMapper.insert(rawLink(9002L, "dupcode", "https://example.com/2")))
                .as("uk_short_code 唯一索引应拦住重复短码")
                .isInstanceOf(DuplicateKeyException.class);
    }

    // ------------------------------------------------------------------
    // 创建与跳转主链路
    // ------------------------------------------------------------------

    @Test
    @DisplayName("创建短链：落库成功、返回完整短链、短码已进布隆过滤器")
    void shouldCreateLink() {
        CreateLinkResponse response = shortLinkService.createLink(
                request("https://example.com/hello"), ADMIN_ID, CLIENT_IP);

        assertThat(response.getShortCode()).matches("[0-9A-Za-z]+");
        assertThat(response.getShortUrl()).endsWith("/" + response.getShortCode());

        ShortLink persisted = shortLinkMapper.selectById(response.getId());
        assertThat(persisted).isNotNull();
        assertThat(persisted.getOriginalUrl()).isEqualTo("https://example.com/hello");
        assertThat(persisted.getStatus()).isEqualTo(1);
        assertThat(persisted.getCreatorId()).isEqualTo(ADMIN_ID);
        // createTime 由 MetaObjectHandler 填充，能验出自动填充是否生效
        assertThat(persisted.getCreateTime()).isNotNull();

        assertThat(cacheManager.mightContain(response.getShortCode()))
                .as("新短码必须立刻进入布隆过滤器，否则下次访问会被误判为不存在")
                .isTrue();
    }

    @Test
    @DisplayName("跳转主链路：回源查库 → 302 → 回写缓存 → 缓存字段完整")
    void shouldResolveAndCache() {
        CreateLinkResponse created = shortLinkService.createLink(
                request("https://example.com/target"), ADMIN_ID, CLIENT_IP);
        String code = created.getShortCode();

        assertThat(cacheManager.get(code)).as("首次访问前缓存应为空").isNull();

        RedirectResult first = shortLinkService.resolve(code, CLIENT_IP, "curl/8.0", null);
        assertThat(first.isFound()).isTrue();
        assertThat(first.httpStatus()).isEqualTo(302);
        assertThat(first.targetUrl()).isEqualTo("https://example.com/target");

        ShortLink cached = cacheManager.get(code);
        assertThat(cached).as("回源后应回写缓存").isNotNull();
        assertThat(cached.getShortCode()).isEqualTo(code);
        assertThat(cached.getOriginalUrl()).isEqualTo("https://example.com/target");
        assertThat(cached.getCreateTime())
                .as("LocalDateTime 经 JSON 序列化往返后不应丢失")
                .isNotNull();

        RedirectResult second = shortLinkService.resolve(code, CLIENT_IP, "curl/8.0", null);
        assertThat(second.isFound()).isTrue();
        assertThat(second.targetUrl()).isEqualTo("https://example.com/target");
    }

    @Test
    @DisplayName("不存在的短码返回 404，并写入空值标记防穿透")
    void shouldReturn404AndCacheNull() {
        RedirectResult result = shortLinkService.resolve("nosuch1", CLIENT_IP, "curl/8.0", null);
        assertThat(result.httpStatus()).isEqualTo(404);
        assertThat(result.targetUrl()).isNull();

        // 重复请求应走空值标记而不是又打一次数据库
        RedirectResult again = shortLinkService.resolve("nosuch1", CLIENT_IP, "curl/8.0", null);
        assertThat(again.httpStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("禁用短链返回 410，且缓存被立即失效（不会跳到 TTL 到期）")
    void shouldDisableAndInvalidateCache() {
        CreateLinkResponse created = shortLinkService.createLink(
                request("https://example.com/disable-me"), ADMIN_ID, CLIENT_IP);
        String code = created.getShortCode();

        shortLinkService.resolve(code, CLIENT_IP, "curl/8.0", null);
        assertThat(cacheManager.get(code)).as("访问后缓存应有值").isNotNull();

        shortLinkService.disable(created.getId());

        assertThat(cacheManager.get(code)).as("禁用后缓存必须被清掉").isNull();
        assertThat(shortLinkService.resolve(code, CLIENT_IP, "curl/8.0", null).httpStatus())
                .isEqualTo(410);
    }

    @Test
    @DisplayName("已过期短链返回 410")
    void shouldReturn410ForExpiredLink() {
        CreateLinkResponse created = shortLinkService.createLink(
                request("https://example.com/expire"), ADMIN_ID, CLIENT_IP);

        // 直接把过期时间改到过去，比等待更可靠
        jdbcTemplate.update("UPDATE t_short_link SET expire_time = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(1)), created.getId());
        cacheManager.invalidate(created.getShortCode());

        assertThat(shortLinkService.resolve(created.getShortCode(), CLIENT_IP, "curl/8.0", null).httpStatus())
                .isEqualTo(410);
    }

    @Test
    @DisplayName("非法链接被拒绝，不写库")
    void shouldRejectInvalidUrl() {
        CreateLinkRequest bad = new CreateLinkRequest();
        bad.setOriginalUrl("javascript:alert(1)");

        assertThatThrownBy(() -> shortLinkService.createLink(bad, ADMIN_ID, CLIENT_IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.URL_INVALID);

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_short_link", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("分页查询能从真实数据库取回数据")
    void shouldPageLinks() {
        for (int i = 0; i < 3; i++) {
            shortLinkService.createLink(request("https://example.com/page/" + i), ADMIN_ID, CLIENT_IP);
        }

        LinkPageQuery query = new LinkPageQuery();
        query.setCurrent(1);
        query.setSize(10);

        var page = linkQueryService.page(query);
        assertThat(page.getTotal()).isEqualTo(3);
        assertThat(page.getRecords()).hasSize(3);
        assertThat(page.getRecords().get(0).getShortUrl()).isNotBlank();
    }

    // ------------------------------------------------------------------
    // MQ 异步统计
    // ------------------------------------------------------------------

    @Test
    @DisplayName("访问日志经 MQ 落库：PV 累加正确、UV 按 IP 去重")
    void shouldRecordStatsThroughMq() {
        CreateLinkResponse created = shortLinkService.createLink(
                request("https://example.com/stats"), ADMIN_ID, CLIENT_IP);
        ShortLink link = shortLinkMapper.selectById(created.getId());

        String ipA = "10.1.1.1";
        String ipB = "10.1.1.2";
        // 同一 IP 访问两次 + 另一 IP 一次 → PV=3，UV=2
        accessProducer.publish(link, ipA, IpHasher.hash(ipA), "curl/8.0", null);
        accessProducer.publish(link, ipA, IpHasher.hash(ipA), "curl/8.0", null);
        accessProducer.publish(link, ipB, IpHasher.hash(ipB), "curl/8.0", null);

        // 消费者按 2 秒周期攒批刷盘；这里在断言里主动 flush，避免纯等时间
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    accessConsumer.flush();
                    Integer logs = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM t_link_access_log WHERE short_code = ?",
                            Integer.class, created.getShortCode());
                    assertThat(logs).as("三条访问明细都应落库").isEqualTo(3);
                });

        StatsSummaryVO summary = statsMapper.sumByCode(created.getShortCode());
        assertThat(summary).isNotNull();
        assertThat(summary.safePv()).isEqualTo(3L);
        assertThat(summary.safeUv()).as("同一 IP 重复访问不应重复计 UV").isEqualTo(2L);

        // 明细的冗余列必须被正确填充，否则报表查询走不了索引
        Date accessDate = jdbcTemplate.queryForObject(
                "SELECT access_date FROM t_link_access_log WHERE short_code = ? LIMIT 1",
                Date.class, created.getShortCode());
        assertThat(accessDate).isNotNull();
        Integer hour = jdbcTemplate.queryForObject(
                "SELECT hour FROM t_link_access_log WHERE short_code = ? LIMIT 1",
                Integer.class, created.getShortCode());
        assertThat(hour).isBetween(0, 23);
    }

    @Test
    @DisplayName("统计查询接口能从真实聚合表返回趋势")
    void shouldQueryStatsFromDatabase() {
        CreateLinkResponse created = shortLinkService.createLink(
                request("https://example.com/trend"), ADMIN_ID, CLIENT_IP);
        ShortLink link = shortLinkMapper.selectById(created.getId());

        accessProducer.publish(link, "10.2.2.2", IpHasher.hash("10.2.2.2"), "curl/8.0", null);

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    accessConsumer.flush();
                    LinkStatsVO stats = statsService.statsOf(created.getShortCode(), 7);
                    assertThat(stats.getStatsPvSum()).isEqualTo(1L);
                    assertThat(stats.getStatsUvSum()).isEqualTo(1L);
                });

        LinkStatsVO stats = statsService.statsOf(created.getShortCode(), 7);
        assertThat(stats.getShortCode()).isEqualTo(created.getShortCode());
        assertThat(stats.getOriginalUrl()).isEqualTo("https://example.com/trend");
        assertThat(stats.getTrend()).isNotEmpty();
        assertThat(stats.getTrend().get(0).getPv()).isEqualTo(1L);
    }

    @Test
    @DisplayName("查询不存在的短码统计抛 404")
    void shouldThrowWhenStatsCodeMissing() {
        assertThatThrownBy(() -> statsService.statsOf("nope404", 7))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.LINK_NOT_FOUND);
    }

    // ------------------------------------------------------------------
    // Redis Lua 限流
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Lua 限流脚本在真实 Redis 上按阈值放行/拒绝")
    void shouldRateLimitWithLuaScript() {
        String key = "sl:rl:itest:" + System.nanoTime();

        for (int i = 1; i <= 3; i++) {
            RateLimitService.RateLimitResult result = rateLimitService.tryAcquire(key, 60, 3);
            assertThat(result.allowed()).as("第 %d 次应放行", i).isTrue();
            assertThat(result.current()).isEqualTo(i);
        }

        RateLimitService.RateLimitResult rejected = rateLimitService.tryAcquire(key, 60, 3);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.remaining()).isZero();
        assertThat(rejected.current()).isEqualTo(4L);
    }

    @Test
    @DisplayName("限流 key 带 TTL，验证 Lua 里的 EXPIRE 真的生效（否则计数永久残留）")
    void shouldSetTtlOnRateLimitKey() {
        String key = "sl:rl:ttl:" + System.nanoTime();
        rateLimitService.tryAcquire(key, 120, 10);

        long ttlMillis = redissonClient.getBucket(key).remainTimeToLive();

        assertThat(ttlMillis)
                .as("INCR 之后必须设置过期，否则 key 永不过期会一直拒绝该 IP")
                .isGreaterThan(0L);
        assertThat(ttlMillis).isLessThanOrEqualTo(120_000L);
    }

    // ------------------------------------------------------------------
    // 布隆过滤器
    // ------------------------------------------------------------------

    @Test
    @DisplayName("布隆过滤器预热能载入库中已有短码，否则存量短链会被误判为不存在")
    void shouldWarmUpBloomFilter() {
        // 先清空位图，模拟进程重启后内存位图丢失、Redis 中也没有留存的情况
        redissonClient.getBloomFilter(Constants.KEY_BLOOM_FILTER).delete();

        // 绕过 service 直接写库，确保这个短码"只存在于数据库里"，
        // 否则 service.createLink 会顺手把它加进布隆过滤器而使断言失去意义
        shortLinkMapper.insert(rawLink(9100L, "warm001", "https://example.com/warmup"));

        assertThat(cacheManager.mightContain("warm001"))
                .as("位图被清掉后，库里的存量短码应被判定为不存在")
                .isFalse();

        cacheManager.warmUpBloomFilter();

        assertThat(cacheManager.mightContain("warm001"))
                .as("预热后存量短码必须被布隆过滤器识别，否则线上会全站 404")
                .isTrue();
    }

    @Test
    @DisplayName("布隆过滤器对随机短码的误判率处于可接受范围")
    void shouldHaveLowFalsePositiveRate() {
        cacheManager.warmUpBloomFilter();

        int samples = 1000;
        int falsePositives = 0;
        for (int i = 0; i < samples; i++) {
            if (cacheManager.mightContain("zz" + Integer.toHexString(0x100000 + i))) {
                falsePositives++;
            }
        }
        // 配置误判率 0.001；预热阶段元素很少，实际应远低于此，给足裕量
        assertThat(falsePositives)
                .as("1000 个随机短码的误判数应远小于 50")
                .isLessThan(50);
    }

    // ------------------------------------------------------------------

    private CreateLinkRequest request(String url) {
        CreateLinkRequest request = new CreateLinkRequest();
        request.setOriginalUrl(url);
        request.setTitle("集成测试");
        return request;
    }

    private ShortLink rawLink(Long id, String code, String url) {
        ShortLink link = new ShortLink();
        link.setId(id);
        link.setShortCode(code);
        link.setOriginalUrl(url);
        link.setStatus(1);
        link.setPv(0L);
        link.setUv(0L);
        return link;
    }
}
