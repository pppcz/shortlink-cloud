package com.shortlink.cloud.config;

import com.shortlink.cloud.common.CurrentUser;
import com.shortlink.cloud.common.ErrorCode;
import com.shortlink.cloud.common.RequireLogin;
import com.shortlink.cloud.common.Result;
import com.shortlink.cloud.common.UserContext;
import com.shortlink.cloud.util.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * JWT 鉴权拦截器。
 *
 * <p>只拦截标注了 {@link RequireLogin} 的处理器；其余接口（短链跳转、登录、
 * Swagger）直接放行，避免鉴权逻辑污染跳转热路径。
 *
 * @author shortlink-cloud
 */
@Component
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequireLogin requireLogin = resolveAnnotation(handlerMethod);
        if (requireLogin == null) {
            return true;
        }

        // CORS 预检请求不带 Authorization，直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String token = jwtTokenProvider.resolveToken(
                request.getHeader("Authorization"));
        Claims claims = jwtTokenProvider.parseToken(token);
        if (claims == null) {
            writeError(response, ErrorCode.UNAUTHORIZED);
            return false;
        }

        CurrentUser user = new CurrentUser(
                jwtTokenProvider.getUserId(claims),
                jwtTokenProvider.getUsername(claims),
                jwtTokenProvider.getRole(claims));
        if (user.userId() == null) {
            writeError(response, ErrorCode.UNAUTHORIZED);
            return false;
        }
        if (requireLogin.admin() && !user.isAdmin()) {
            writeError(response, ErrorCode.FORBIDDEN);
            return false;
        }
        UserContext.set(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 线程复用场景下必须清理，否则下一个请求会读到上一个用户的身份
        UserContext.clear();
    }

    private RequireLogin resolveAnnotation(HandlerMethod handlerMethod) {
        RequireLogin onMethod = handlerMethod.getMethodAnnotation(RequireLogin.class);
        if (onMethod != null) {
            return onMethod;
        }
        return handlerMethod.getBeanType().getAnnotation(RequireLogin.class);
    }

    private void writeError(HttpServletResponse response, ErrorCode errorCode) throws Exception {
        response.setStatus(errorCode.getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Result<Void> body = Result.error(errorCode);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
