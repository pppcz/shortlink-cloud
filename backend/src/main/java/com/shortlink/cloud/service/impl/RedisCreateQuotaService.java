package com.shortlink.cloud.service.impl;

import com.shortlink.cloud.common.Constants;
import com.shortlink.cloud.service.CreateQuotaService;
import com.shortlink.cloud.service.RateLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 基于 Redis 的按日创建量限制。
 *
 * <p>计数 key 带日期后缀，并把 TTL 设为「距次日零点的秒数 + 缓冲」，
 * 这样跨天后 key 自然过期，不需要额外的清理任务。
 *
 * @author shortlink-cloud
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisCreateQuotaService implements CreateQuotaService {

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 跨天缓冲，避免临界时刻 key 提前消失导致计数被重置。 */
    private static final long TTL_BUFFER_SECONDS = 600L;

    private final RateLimitService rateLimitService;

    @Override
    public boolean tryConsume(String clientIp, int dailyLimit) {
        if (dailyLimit <= 0) {
            return true;
        }
        String key = Constants.KEY_CREATE_LIMIT_PREFIX + clientIp + ":" + LocalDate.now().format(DAY_FORMAT);
        int windowSeconds = (int) windowUntilTomorrow();
        RateLimitService.RateLimitResult result = rateLimitService.tryAcquire(key, windowSeconds, dailyLimit);
        if (!result.allowed()) {
            log.warn("创建量超限 ip={} current={} dailyLimit={}", clientIp, result.current(), dailyLimit);
        }
        return result.allowed();
    }

    /** 距次日零点的秒数（含缓冲），至少 60 秒。 */
    private long windowUntilTomorrow() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime tomorrow = now.toLocalDate().plusDays(1).atStartOfDay();
        long seconds = Duration.between(now, tomorrow).getSeconds() + TTL_BUFFER_SECONDS;
        return Math.max(seconds, 60L);
    }
}
