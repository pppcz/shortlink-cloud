package com.shortlink.cloud.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 业务配置，对应 application.yml 中的 {@code shortlink.*}。
 *
 * @author shortlink-cloud
 */
@Data
@Component
@ConfigurationProperties(prefix = "shortlink")
public class ShortLinkProperties {

    /** 短链域名前缀，用于拼装返回给前端的完整短链。 */
    private String domain = "http://localhost:8080";

    /** 短码长度。 */
    private int codeLength = 7;

    private Cache cache = new Cache();

    private RateLimit rateLimit = new RateLimit();

    private Jwt jwt = new Jwt();

    @Data
    public static class Cache {
        /** 正常映射缓存秒数。 */
        private long linkTtlSeconds = 3600;
        /** 空值缓存秒数，用于防缓存穿透。 */
        private long nullTtlSeconds = 60;
        /** TTL 随机抖动比例，0.2 表示 ±20%，用于防缓存雪崩。 */
        private double ttlJitterRatio = 0.2;
        /** 布隆过滤器预期插入量。 */
        private long bloomExpectedInsertions = 10_000_000L;
        /** 布隆过滤器误判率。 */
        private double bloomFalsePositiveProbability = 0.001;
    }

    @Data
    public static class RateLimit {
        /** 单 IP 每秒允许的跳转请求数。 */
        private int redirectPermitPerSecond = 50;
        /** 单 IP 每天允许创建的短链数。 */
        private int createPerDay = 200;
    }

    @Data
    public static class Jwt {
        /** 签名密钥，生产必须替换。 */
        private String secret = "shortlink-cloud-jwt-secret-please-change-me-in-production";
        /** 过期时间（秒）。 */
        private long expireSeconds = 86400L;
        /** 承载 token 的请求头。 */
        private String header = "Authorization";
        /** token 前缀。 */
        private String prefix = "Bearer ";
    }
}
