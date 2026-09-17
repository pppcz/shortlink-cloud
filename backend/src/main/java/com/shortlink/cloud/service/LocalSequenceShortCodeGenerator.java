package com.shortlink.cloud.service;

import com.shortlink.cloud.util.Base62;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 纯本地兜底短码生成器：时间戳毫秒 + 自增序列，再 Base62 编码。
 *
 * <p>仅在 Redis 不可用时使用。多实例部署下不同实例可能产生相同短码，
 * 靠数据库唯一索引 + 上层重试兜底，因此它只是「降级可用」而非首选。
 *
 * @author shortlink-cloud
 */
@Component
public class LocalSequenceShortCodeGenerator implements ShortCodeGenerator {

    /** 以 2024-01-01T00:00:00Z 为纪元，缩短数值长度。 */
    private static final long EPOCH_MILLIS = 1_704_067_200_000L;

    /** 低 16 位作为同毫秒内的序列。 */
    private static final long SEQUENCE_MASK = 0xFFFFL;

    private final AtomicLong counter = new AtomicLong();

    @Override
    public String nextCode() {
        long millis = System.currentTimeMillis() - EPOCH_MILLIS;
        long seq = counter.incrementAndGet() & SEQUENCE_MASK;
        long value = (millis << 16) | seq;
        return Base62.encode(value);
    }

    @Override
    public String name() {
        return "local-sequence";
    }
}
