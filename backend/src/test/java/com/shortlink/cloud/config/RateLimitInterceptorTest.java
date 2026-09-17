package com.shortlink.cloud.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortlink.cloud.common.ErrorCode;
import com.shortlink.cloud.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RateLimitInterceptor} 单元测试。
 *
 * @author shortlink-cloud
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateLimitInterceptorTest {

    private static final int LIMIT = 50;

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private RateLimitInterceptor interceptor;
    private StringWriter responseBody;

    @BeforeEach
    void setUp() throws Exception {
        ShortLinkProperties properties = new ShortLinkProperties();
        properties.getRateLimit().setRedirectPermitPerSecond(LIMIT);

        interceptor = new RateLimitInterceptor(rateLimitService, properties, new ObjectMapper());

        responseBody = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseBody));
    }

    @Test
    @DisplayName("配额充足时放行，并回写 X-RateLimit 响应头")
    void shouldPassWithinLimit() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/abc1234");
        when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4");
        when(rateLimitService.tryAcquire(anyString(), anyInt(), eq(LIMIT)))
                .thenReturn(RateLimitService.RateLimitResult.pass(3, LIMIT));

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        verify(response).setHeader(RateLimitInterceptor.HEADER_LIMIT, String.valueOf(LIMIT));
        verify(response).setHeader(RateLimitInterceptor.HEADER_REMAINING, String.valueOf(LIMIT - 3));
        // 放行时不应改写状态码
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    @DisplayName("超出配额时返回 429 并写出统一错误体")
    void shouldRejectWhenOverLimit() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/abc1234");
        when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4");
        when(rateLimitService.tryAcquire(anyString(), anyInt(), eq(LIMIT)))
                .thenReturn(RateLimitService.RateLimitResult.reject(LIMIT + 1, LIMIT));

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        verify(response).setStatus(ErrorCode.RATE_LIMITED.getHttpStatus());
        verify(response).setHeader(RateLimitInterceptor.HEADER_RETRY_AFTER, "1");
        assertThat(responseBody.toString()).contains("\"code\":40001");
    }

    @Test
    @DisplayName("非 GET 请求不参与跳转限流")
    void shouldSkipNonGetRequests() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/abc1234");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        verify(rateLimitService, never()).tryAcquire(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("/api/** 多段路径不参与跳转限流")
    void shouldSkipApiPaths() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/link/page");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        verify(rateLimitService, never()).tryAcquire(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("根路径不参与跳转限流")
    void shouldSkipRootPath() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/");

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        verify(rateLimitService, never()).tryAcquire(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("限流阈值配置为 0 表示关闭限流")
    void shouldDisableWhenLimitIsZero() throws Exception {
        ShortLinkProperties disabled = new ShortLinkProperties();
        disabled.getRateLimit().setRedirectPermitPerSecond(0);
        RateLimitInterceptor noLimit = new RateLimitInterceptor(rateLimitService, disabled, new ObjectMapper());

        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/abc1234");

        assertThat(noLimit.preHandle(request, response, new Object())).isTrue();
        verify(rateLimitService, never()).tryAcquire(anyString(), anyInt(), anyInt());
    }
}
