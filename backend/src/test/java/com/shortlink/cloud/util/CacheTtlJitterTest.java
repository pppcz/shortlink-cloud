package com.shortlink.cloud.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缓存 TTL 抖动逻辑的行为验证。
 *
 * <p>{@code ShortLinkCacheManagerImpl#jitter} 是私有方法，这里用一个等价的最小实现
 * 验证抖动区间与边界，确保「防缓存雪崩」的策略参数（±20%）确实生效。
 * 真正的 Redis 路径无法在离线环境执行，见 docs/progress.md。
 *
 * @author shortlink-cloud
 */
class CacheTtlJitterTest {

    private static final double RATIO = 0.2D;

    /** 与 ShortLinkCacheManagerImpl#jitter 完全一致的算法。 */
    private static long jitter(long ttlSeconds, double ratio) {
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

    @Test
    @DisplayName("抖动结果落在 ±20% 区间内")
    void shouldStayWithinJitterBand() {
        long base = 3600L;
        long delta = (long) (base * RATIO);
        for (int i = 0; i < 10_000; i++) {
            long value = jitter(base, RATIO);
            assertThat(value).isBetween(base - delta, base + delta);
        }
    }

    @Test
    @DisplayName("抖动确实打散了 TTL（不是恒等于基准值）")
    void shouldActuallySpreadValues() {
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            seen.add(jitter(3600L, RATIO));
        }
        // 若没有抖动，集合大小会是 1
        assertThat(seen).hasSizeGreaterThan(10);
    }

    @Test
    @DisplayName("抖动下限为 1 秒，不会产生 0 或负数 TTL")
    void shouldNeverReturnNonPositiveTtl() {
        assertThat(jitter(0L, RATIO)).isEqualTo(1L);
        assertThat(jitter(1L, RATIO)).isEqualTo(1L);
        assertThat(jitter(2L, RATIO)).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("抖动比例 <= 0 时原样返回")
    void shouldSkipJitterWhenRatioDisabled() {
        assertThat(jitter(3600L, 0D)).isEqualTo(3600L);
        assertThat(jitter(3600L, -0.5D)).isEqualTo(3600L);
    }
}
