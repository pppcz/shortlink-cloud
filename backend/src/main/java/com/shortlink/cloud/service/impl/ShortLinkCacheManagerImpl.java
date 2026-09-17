package com.shortlink.cloud.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortlink.cloud.common.Constants;
import com.shortlink.cloud.config.ShortLinkProperties;
import com.shortlink.cloud.entity.ShortLink;
import com.shortlink.cloud.mapper.ShortLinkMapper;
import com.shortlink.cloud.service.ShortLinkCacheManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redisson 的短链缓存实现。
 *
 * <p>选 Redisson 而不是裸 {@code StringRedisTemplate} 的原因：
 * <ul>
 *   <li>布隆过滤器（{@link RBloomFilter}）是防穿透的核心，Redisson 直接提供且支持持久化位图</li>
 *   <li>重建热点 key 需要分布式锁（{@link RLock}）</li>
 * </ul>
 *
 * <p>缓存值使用 JSON 字符串而非 Java 序列化：可读、可调试、跨语言安全，
 * 且避免 Java 反序列化漏洞面。
 *
 * @author shortlink-cloud
 */
@Slf4j
@Service
public class ShortLinkCacheManagerImpl implements ShortLinkCacheManager {

    /** 空值标记：key 存在但值为该常量，表示「确认不存在」。 */
    private static final String NULL_MARKER = "";

    private final RedissonClient redissonClient;
    private final ShortLinkMapper shortLinkMapper;
    private final ShortLinkProperties properties;
    private final ObjectMapper objectMapper;

    /** 布隆过滤器懒初始化。位图持久化在 Redis，多个实例共享同一份。 */
    private volatile RBloomFilter<String> bloomFilter;

    public ShortLinkCacheManagerImpl(RedissonClient redissonClient,
                                     ShortLinkMapper shortLinkMapper,
                                     ShortLinkProperties properties,
                                     ObjectMapper objectMapper) {
        this.redissonClient = redissonClient;
        this.shortLinkMapper = shortLinkMapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ShortLink get(String shortCode) {
        if (StringUtils.isBlank(shortCode)) {
            return null;
        }
        try {
            String json = linkMap().get(shortCode);
            if (json == null) {
                return null;
            }
            // 空值标记：确认不存在，直接返回 null，不再回源
            if (NULL_MARKER.equals(json)) {
                return null;
            }
            return objectMapper.readValue(json, ShortLink.class);
        } catch (Exception ex) {
            // 缓存故障不能影响跳转，退化为回源查库
            log.warn("读取短链缓存失败 code={} err={}", shortCode, ex.getMessage());
            return null;
        }
    }

    @Override
    public void put(ShortLink link) {
        if (link == null || StringUtils.isBlank(link.getShortCode())) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(link);
            linkMap().put(link.getShortCode(), json, ttlFor(link), TimeUnit.SECONDS);
            addToBloom(link.getShortCode());
        } catch (Exception ex) {
            log.warn("写入短链缓存失败 code={} err={}", link.getShortCode(), ex.getMessage());
        }
    }

    @Override
    public void putNull(String shortCode) {
        if (StringUtils.isBlank(shortCode)) {
            return;
        }
        try {
            long ttl = properties.getCache().getNullTtlSeconds();
            linkMap().put(shortCode, NULL_MARKER, jitter(ttl), TimeUnit.SECONDS);
        } catch (Exception ex) {
            log.warn("写入空值缓存失败 code={} err={}", shortCode, ex.getMessage());
        }
    }

    @Override
    public boolean mightContain(String shortCode) {
        if (StringUtils.isBlank(shortCode)) {
            return false;
        }
        try {
            return bloom().contains(shortCode);
        } catch (Exception ex) {
            // 布隆过滤器不可用时不能阻断正常流量，保守返回「可能存在」
            log.warn("布隆过滤器查询失败，跳过预判: {}", ex.getMessage());
            return true;
        }
    }

    @Override
    public void addToBloom(String shortCode) {
        if (StringUtils.isBlank(shortCode)) {
            return;
        }
        try {
            bloom().add(shortCode);
        } catch (Exception ex) {
            log.warn("布隆过滤器写入失败 code={} err={}", shortCode, ex.getMessage());
        }
    }

    @Override
    public void invalidate(String shortCode) {
        if (StringUtils.isBlank(shortCode)) {
            return;
        }
        try {
            linkMap().remove(shortCode);
            log.info("短链缓存已失效 code={}", shortCode);
        } catch (Exception ex) {
            log.warn("失效短链缓存失败 code={} err={}", shortCode, ex.getMessage());
        }
    }

    @Override
    public void warmUpBloomFilter() {
        long start = System.currentTimeMillis();
        try {
            RBloomFilter<String> filter = bloom();
            // 已初始化过（Redis 中已有位图）则不重复灌入，避免启动变慢
            if (filter.isExists() && filter.count() > 0) {
                log.info("布隆过滤器已存在，跳过预热，当前估算元素数={}", filter.count());
                return;
            }
            List<String> codes = shortLinkMapper.selectAllShortCodes();
            filter.tryInit(properties.getCache().getBloomExpectedInsertions(),
                    properties.getCache().getBloomFalsePositiveProbability());
            codes.forEach(filter::add);
            log.info("布隆过滤器预热完成，载入短码 {} 条，耗时 {} ms",
                    codes.size(), System.currentTimeMillis() - start);
        } catch (Exception ex) {
            log.error("布隆过滤器预热失败，将退化为「全部回源」模式: {}", ex.getMessage());
        }
    }

    @Override
    public long bloomCount() {
        try {
            return bloom().count();
        } catch (Exception ex) {
            return -1L;
        }
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    private RMap<String, String> linkMap() {
        return redissonClient.getMap(Constants.KEY_LINK_PREFIX + "cache", StringCodec.INSTANCE);
    }

    /**
     * 布隆过滤器。
     *
     * <p>必须显式指定二进制 codec：Redisson 的 {@code RBloomFilter} 底层存放
     * 位图与哈希配置，若被 JSON codec 包一层会导致位运算数据损坏。
     */
    private RBloomFilter<String> bloom() {
        RBloomFilter<String> filter = bloomFilter;
        if (filter == null) {
            synchronized (this) {
                filter = bloomFilter;
                if (filter == null) {
                    filter = redissonClient.getBloomFilter(Constants.KEY_BLOOM_FILTER);
                    bloomFilter = filter;
                }
            }
        }
        return filter;
    }

    /**
     * 计算缓存 TTL（秒）。
     *
     * <p>= min(配置的 link TTL, 距过期的剩余秒数)，再叠加随机抖动。
     * 这样即使短链将在 3 分钟后过期，也不会被缓存 1 小时而导致过期后仍可跳转。
     */
    private long ttlFor(ShortLink link) {
        long ttl = properties.getCache().getLinkTtlSeconds();
        LocalDateTime expireTime = link.getExpireTime();
        if (expireTime != null) {
            long remaining = Duration.between(LocalDateTime.now(), expireTime).getSeconds();
            if (remaining > 0) {
                ttl = Math.min(ttl, remaining);
            }
        }
        return jitter(ttl);
    }

    /**
     * 对 TTL 施加 ±ratio 的随机抖动，防缓存雪崩。
     *
     * @param ttlSeconds 基准 TTL
     * @return 抖动后的 TTL，最小 1 秒
     */
    private long jitter(long ttlSeconds) {
        double ratio = properties.getCache().getTtlJitterRatio();
        if (ratio <= 0 || ttlSeconds <= 1) {
            return Math.max(1L, ttlSeconds);
        }
        long delta = (long) (ttlSeconds * ratio);
        if (delta <= 0) {
            return ttlSeconds;
        }
        long jittered = ttlSeconds + ThreadLocalRandom.current().nextLong(-delta, delta + 1);
        return Math.max(1L, jittered);
    }
}
