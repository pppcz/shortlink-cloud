package com.shortlink.cloud.service;

import com.shortlink.cloud.util.Base62;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis {@code INCR} 的发号器。
 *
 * <p>每次取一个按天的自增序列，与当前日期拼装后再 Base62 编码。
 * 单机 Redis 的 INCR 是原子的，因此多实例部署也不会产生重复短码。
 *
 * <p>Redis 不可用时 {@link #nextCode()} 会抛出异常，由
 * {@code ShortLinkServiceImpl} 捕获并退化为本地兜底生成器。
 *
 * @author shortlink-cloud
 */
@Primary
@Component
public class RedisSeqShortCodeGenerator implements ShortCodeGenerator {

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyMMdd");
    private static final String KEY_FORMAT = "sl:seq:link:%s";
    /** key 保留 3 天，跨天后自然重建。 */
    private static final long KEY_TTL_DAYS = 3L;

    private final StringRedisTemplate redisTemplate;

    public RedisSeqShortCodeGenerator(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public String nextCode() {
        String day = LocalDate.now().format(DAY_FORMAT);
        String key = String.format(KEY_FORMAT, day);
        Long seq = redisTemplate.opsForValue().increment(key);
        if (seq == null) {
            throw new IllegalStateException("Redis INCR 返回空值");
        }
        if (seq == 1L) {
            redisTemplate.expire(key, KEY_TTL_DAYS, TimeUnit.DAYS);
        }
        // 日期前缀 + 当日序列，既保证唯一又便于人工排查
        long value = Long.parseLong(day) * 10_000_000L + seq;
        return Base62.encode(value);
    }

    @Override
    public String name() {
        return "redis-incr";
    }
}
