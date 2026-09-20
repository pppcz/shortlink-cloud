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
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 基于 Redisson 的短链缓存实现。
 *
 * <p>存储模型选型（这里踩过一次坑，值得记录）：
 * 最初用 {@code RMap}（一个 Hash 装所有短码），编译时发现
 * <b>Redisson 的 {@code RMap#put} 没有带 TTL 的重载</b>——
 * 因为 Redis Hash 的过期时间只能设在 key 上，没法给单个 field 设 TTL。
 * 要按 field 设 TTL 得换 {@code RMapCache}，但它为每个 field 维护独立的
 * 过期字典，对本场景是过度设计。
 *
 * <p>所以改成<b>每个短码一个 {@link RBucket} key</b>，key 形如
 * {@code sl:link:{shortCode}}。这样：
 * <ul>
 *   <li>每条映射天然拥有独立的 TTL，正符合"TTL 加随机抖动防雪崩"的需求</li>
 *   <li>读写是单 key 操作，没有 Hash 大 key 与热 key 问题</li>
 *   <li>空值标记用同一个 key、值为空串表示，不需要第二个 key 空间</li>
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

    /** 空值标记：key 存在但值为空串，表示"确认不存在"。 */
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
            String json = bucket(shortCode).get();
            // key 不存在（缓存未命中）或值为空串（空值标记）都返回 null。
            // 两者语义不同，但对调用方而言都是"需要回源或直接 404"，
            // 真正的区分由 resolve() 里布隆过滤器的判定负责。
            if (StringUtils.isEmpty(json)) {
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
            bucket(link.getShortCode()).set(json, Duration.ofSeconds(ttlFor(link)));
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
            long ttl = jitter(properties.getCache().getNullTtlSeconds());
            bucket(shortCode).set(NULL_MARKER, Duration.ofSeconds(ttl));
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
            // 布隆过滤器不可用时不能阻断正常流量，保守返回"可能存在"
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
            bucket(shortCode).delete();
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

    /**
     * 取短码对应的缓存桶。
     *
     * <p>显式指定 {@link StringCodec}：值本身就是 JSON 字符串，
     * 用默认的 Kryo/JSON 二进制 codec 会多一层无意义的包装，
     * 直接在 redis-cli 里 get 出来也会是乱码，不利于排障。
     *
     * @param shortCode 短码
     * @return 缓存桶
     */
    private RBucket<String> bucket(String shortCode) {
        return redissonClient.getBucket(Constants.KEY_LINK_PREFIX + shortCode, StringCodec.INSTANCE);
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
