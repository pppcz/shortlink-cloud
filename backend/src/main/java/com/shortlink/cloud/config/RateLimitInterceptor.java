package com.shortlink.cloud.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortlink.cloud.common.ErrorCode;
import com.shortlink.cloud.common.Result;
import com.shortlink.cloud.service.RateLimitService;
import com.shortlink.cloud.util.IpUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * 短链跳转的 IP 限流拦截器。
 *
 * <p>为什么放在拦截器而不是 Service：限流是横切关注点，放在最外层可以在
 * 触发限流时立刻返回 429，省掉后续所有缓存 / DB 调用。
 *
 * <p>为什么用固定窗口而非令牌桶：短链跳转是「读多写少」的幂等操作，
 * 固定窗口一次 INCR 即可判定，Redis 往返更少；边界突发对本场景无实质影响。
 *
 * @author shortlink-cloud
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    /** 剩余配额响应头。 */
    public static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    /** 窗口上限响应头。 */
    public static final String HEADER_LIMIT = "X-RateLimit-Limit";
    /** 建议重试间隔响应头。 */
    public static final String HEADER_RETRY_AFTER = "Retry-After";

    private static final String KEY_PREFIX = "sl:rl:redirect:";
    /** 窗口 2 秒：略大于 1 秒桶，给 Redis 留出清理余量。 */
    private static final int WINDOW_SECONDS = 2;

    private final RateLimitService rateLimitService;
    private final ShortLinkProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!isRedirectRequest(request)) {
            return true;
        }
        int limit = properties.getRateLimit().getRedirectPermitPerSecond();
        if (limit <= 0) {
            return true;
        }

        String clientIp = IpUtils.getClientIp(request);
        // 按秒分桶：窗口过期后 key 自然废弃，无需额外的清理任务
        String key = KEY_PREFIX + clientIp + ":" + Instant.now().getEpochSecond();

        RateLimitService.RateLimitResult result = rateLimitService.tryAcquire(key, WINDOW_SECONDS, limit);
        response.setHeader(HEADER_LIMIT, String.valueOf(limit));
        response.setHeader(HEADER_REMAINING, String.valueOf(result.remaining()));

        if (result.allowed()) {
            return true;
        }

        log.warn("跳转限流触发 ip={} current={} limit={}", clientIp, result.current(), limit);
        response.setStatus(ErrorCode.RATE_LIMITED.getHttpStatus());
        response.setHeader(HEADER_RETRY_AFTER, "1");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(ErrorCode.RATE_LIMITED)));
        return false;
    }

    /**
     * 判断是否为短链跳转请求：GET 且路径为单段（排除 /api/** 与静态资源）。
     *
     * @param request 当前请求
     * @return true 表示这是跳转请求
     */
    private boolean isRedirectRequest(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        if (path == null || path.length() < 2) {
            return false;
        }
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        if (trimmed.isEmpty() || trimmed.contains("/")) {
            return false;
        }
        return !"api".equalsIgnoreCase(trimmed);
    }
}
