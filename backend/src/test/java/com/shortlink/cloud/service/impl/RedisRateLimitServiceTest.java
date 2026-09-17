package com.shortlink.cloud.service.impl;

import com.shortlink.cloud.service.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@link RedisRateLimitService} 单元测试。
 *
 * <p>重点是「Redis 故障时是否 fail-open」——这决定了限流组件本身
 * 会不会成为整个跳转链路的单点故障。
 *
 * @author shortlink-cloud
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisRateLimitServiceTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RScript script;

    private RedisRateLimitService service;

    @BeforeEach
    void setUp() {
        when(redissonClient.getScript(any())).thenReturn(script);
        when(script.scriptLoad(anyString())).thenReturn("sha-1");
        service = new RedisRateLimitService(redissonClient);
    }

    @Test
    @DisplayName("计数未超阈值时放行")
    void shouldAllowUnderLimit() {
        when(script.evalSha(any(), anyString(), any(), anyList(), any()))
                .thenReturn(10L);

        RateLimitService.RateLimitResult result = service.tryAcquire("sl:rl:1.2.3.4:1", 2, 50);

        assertThat(result.allowed()).isTrue();
        assertThat(result.current()).isEqualTo(10L);
        assertThat(result.remaining()).isEqualTo(40L);
    }

    @Test
    @DisplayName("计数等于阈值时仍然放行（边界包含）")
    void shouldAllowExactlyAtLimit() {
        when(script.evalSha(any(), anyString(), any(), anyList(), any()))
                .thenReturn(50L);

        RateLimitService.RateLimitResult result = service.tryAcquire("sl:rl:1.2.3.4:1", 2, 50);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isZero();
    }

    @Test
    @DisplayName("计数超过阈值时拒绝")
    void shouldRejectOverLimit() {
        when(script.evalSha(any(), anyString(), any(), anyList(), any()))
                .thenReturn(51L);

        RateLimitService.RateLimitResult result = service.tryAcquire("sl:rl:1.2.3.4:1", 2, 50);

        assertThat(result.allowed()).isFalse();
        assertThat(result.remaining()).isZero();
    }

    @Test
    @DisplayName("Redis 异常时 fail-open 放行，不把限流组件变成单点故障")
    void shouldFailOpenOnRedisError() {
        when(script.evalSha(any(), anyString(), any(), anyList(), any()))
                .thenThrow(new RuntimeException("connection refused"));
        when(script.scriptLoad(anyString()))
                .thenThrow(new RuntimeException("connection refused"));

        RateLimitService.RateLimitResult result = service.tryAcquire("sl:rl:1.2.3.4:1", 2, 50);

        assertThat(result.allowed()).isTrue();
        assertThat(result.current()).isEqualTo(-1L);
    }

    @Test
    @DisplayName("脚本返回 null 时 fail-open 放行")
    void shouldFailOpenOnNullResult() {
        when(script.evalSha(any(), anyString(), any(), anyList(), any()))
                .thenReturn(null);

        assertThat(service.tryAcquire("sl:rl:1.2.3.4:1", 2, 50).allowed()).isTrue();
    }

    @Test
    @DisplayName("阈值非正数时直接放行，不访问 Redis")
    void shouldPassWhenLimitNotPositive() {
        assertThat(service.tryAcquire("k", 0, 50).allowed()).isTrue();
        assertThat(service.tryAcquire("k", 2, 0).allowed()).isTrue();
    }

    @Test
    @DisplayName("EVALSHA 报 NOSCRIPT 时自动重载脚本并重试一次")
    void shouldReloadScriptOnNoscript() {
        // 第一次 evalSha 抛 NOSCRIPT，重新 scriptLoad 后第二次成功
        when(script.evalSha(any(), anyString(), any(), anyList(), any()))
                .thenThrow(new RuntimeException("NOSCRIPT No matching script"))
                .thenReturn(7L);
        when(script.scriptLoad(anyString())).thenReturn("sha-reloaded");

        RateLimitService.RateLimitResult result = service.tryAcquire("sl:rl:x:1", 2, 50);

        assertThat(result.allowed()).isTrue();
        assertThat(result.current()).isEqualTo(7L);
    }
}
