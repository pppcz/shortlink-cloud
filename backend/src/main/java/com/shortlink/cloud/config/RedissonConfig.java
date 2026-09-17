package com.shortlink.cloud.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Redisson 客户端配置。
 *
 * <p>连接参数直接复用 Spring Boot 的 {@code spring.data.redis.*}，
 * 避免同一份 Redis 地址在配置里出现两遍而写歪。
 *
 * <p>用途：布隆过滤器（防缓存穿透）与分布式锁（防缓存击穿）。
 *
 * @author shortlink-cloud
 */
@Slf4j
@Configuration
public class RedissonConfig {

    /**
     * 单机模式 RedissonClient。
     *
     * <p>注意：返回的是延迟连接的对象，Redis 未启动时这里不会立刻失败，
     * 真正的连接错误会在首次使用时抛出，因此应用仍可启动并降级运行。
     *
     * @param redisProperties Spring Boot 的 Redis 连接配置
     * @return RedissonClient
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisProperties redisProperties) {
        Config config = new Config();
        String address = "redis://" + redisProperties.getHost() + ":" + redisProperties.getPort();

        SingleServerConfig single = config.useSingleServer()
                .setAddress(address)
                .setDatabase(redisProperties.getDatabase())
                .setConnectionMinimumIdleSize(10)
                .setConnectionPoolSize(64)
                .setTimeout(3000)
                .setRetryAttempts(3)
                .setConnectTimeout(3000);

        if (StringUtils.hasText(redisProperties.getPassword())) {
            single.setPassword(redisProperties.getPassword());
        }
        log.info("Redisson 初始化: address={} database={}", address, redisProperties.getDatabase());
        return Redisson.create(config);
    }
}
