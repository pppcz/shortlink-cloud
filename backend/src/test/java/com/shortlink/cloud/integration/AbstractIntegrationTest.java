package com.shortlink.cloud.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试基类：启动真实 MySQL / Redis / RabbitMQ 容器。
 *
 * <p><b>为什么必须有这一层</b>：项目里 10 个单元测试类全部使用 mock，
 * 它们验证不了「SQL 是否写对」「Flyway 迁移能否在真实 MySQL 上跑通」
 * 「JSON 消息能否被消费端正确反序列化」——而这恰恰是最容易出问题的地方。
 * 本类补上的就是这段零覆盖的链路。
 *
 * <p>容器使用 {@code static} 字段 + {@code @Container}：
 * Testcontainers 的「单例容器」模式，多个测试类共享同一批容器，只启动一次。
 * 用实例字段会导致每个测试方法重启容器，慢到无法接受。
 *
 * <p>版本与 {@code docker-compose.yml} 保持一致，避免"测试通过但生产报错"。
 *
 * @author shortlink-cloud
 */
public abstract class AbstractIntegrationTest {

    /** MySQL：与 docker-compose 同版本。 */
    protected static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0.39"))
                    .withDatabaseName("shortlink")
                    .withUsername("shortlink")
                    .withPassword("shortlink123")
                    .withCommand(
                            "--character-set-server=utf8mb4",
                            "--collation-server=utf8mb4_unicode_ci",
                            "--default-time-zone=+08:00")
                    .withReuse(false);

    /** Redis：alpine 镜像带 redis-server。 */
    @SuppressWarnings("resource")
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.2.5-alpine"))
                    .withExposedPorts(6379)
                    // 用密码启动，顺便验证配置里的 password 传递链路正确
                    .withCommand("redis-server", "--requirepass", "redis123")
                    .withReuse(false);

    /** RabbitMQ：管理版镜像，端口与生产一致。 */
    protected static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13.7-management-alpine"))
                    .withUser("shortlink")
                    .withPassword("shortlink123")
                    .withVhost("/")
                    .withReuse(false);

    static {
        // 显式 start：静态块保证在任何测试类使用之前容器已就绪。
        // 不用 @Testcontainers/@Container 是因为部分测试类可能不需要全部容器，
        // 集中管理更容易看清依赖关系。
        MYSQL.start();
        REDIS.start();
        RABBITMQ.start();
    }

    /**
     * 把容器地址注入 Spring 环境。
     *
     * <p>注意 base 配置 {@code application.yml} 里的 RabbitMQ 账号是
     * {@code shortlink/shortlink123}，与容器设置一致，因此这里只需覆盖 host/port。
     *
     * @param registry Spring 动态属性注册器
     */
    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        // ---- MySQL ----
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);

        // ---- Redis ----
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "redis123");

        // ---- RabbitMQ ----
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);

        // ---- 测试期间放宽业务限制，避免用例之间互相干扰 ----
        // 限流单独在 RateLimitIntegrationTest 里用小阈值验证，其余用例不该被它挡住
        registry.add("shortlink.rate-limit.redirect-permit-per-second", () -> 1_000_000);
        registry.add("shortlink.rate-limit.create-per-day", () -> 1_000_000);
        // 清理任务会删数据，测试里禁掉；其逻辑由单元测试覆盖
        registry.add("shortlink.retention.enabled", () -> false);
    }
}
