package com.shortlink.cloud.controller;

import com.shortlink.cloud.service.RedirectResult;
import com.shortlink.cloud.service.ShortLinkService;
import com.shortlink.cloud.util.IpUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 短链跳转入口：{@code GET /{shortCode}}。
 *
 * <p>这是全站唯一的热路径，设计上刻意保持极简：
 * <ul>
 *   <li>不经过鉴权拦截器（拦截器只挂 /api/**）</li>
 *   <li>返回 302 而非 301，避免浏览器把跳转永久缓存导致统计失真</li>
 *   <li>显式设置 no-store，保证每次访问都真实回源</li>
 * </ul>
 *
 * @author shortlink-cloud
 */
@Slf4j
@Controller
@RequiredArgsConstructor
@Tag(name = "跳转", description = "短链跳转入口")
public class RedirectController {

    /**
     * 预留给框架与静态资源的路径，不能被当成短码解析。
     * 这些路径本身有对应处理器，但显式列出来可以让意图更清晰、也更省一次查库。
     */
    private static final Set<String> RESERVED_PATHS = Set.of(
            "api", "actuator", "swagger-ui", "swagger-ui.html", "v3",
            "error", "favicon.ico", "robots.txt", "assets", "index.html", "health");

    private static final String NOT_FOUND_HTML = """
            <!doctype html>
            <html lang="zh-CN"><head><meta charset="utf-8">
            <title>短链不存在</title>
            <style>body{font-family:-apple-system,"PingFang SC",sans-serif;display:flex;
            align-items:center;justify-content:center;height:100vh;margin:0;
            background:#f5f7fa;color:#303133}
            .box{text-align:center}.code{font-size:56px;color:#409eff;margin-bottom:8px}
            p{color:#909399}</style></head>
            <body><div class="box"><div class="code">404</div>
            <h2>短链不存在或已被删除</h2>
            <p>请确认链接是否完整，或联系创建者重新生成。</p></div></body></html>
            """;

    private static final String GONE_HTML = """
            <!doctype html>
            <html lang="zh-CN"><head><meta charset="utf-8">
            <title>短链已失效</title>
            <style>body{font-family:-apple-system,"PingFang SC",sans-serif;display:flex;
            align-items:center;justify-content:center;height:100vh;margin:0;
            background:#f5f7fa;color:#303133}
            .box{text-align:center}.code{font-size:56px;color:#e6a23c;margin-bottom:8px}
            p{color:#909399}</style></head>
            <body><div class="box"><div class="code">410</div>
            <h2>该短链已被禁用或已过期</h2>
            <p>如需继续访问，请联系创建者。</p></div></body></html>
            """;

    private final ShortLinkService shortLinkService;

    @GetMapping("/{shortCode:[0-9a-zA-Z]{1,32}}")
    @Operation(summary = "短链跳转", description = "解析短码并 302 跳转到原始链接")
    public void redirect(@Parameter(description = "短码") @PathVariable String shortCode,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {

        if (RESERVED_PATHS.contains(shortCode.toLowerCase())) {
            writeHtml(response, HttpStatus.NOT_FOUND, NOT_FOUND_HTML);
            return;
        }

        String clientIp = IpUtils.getClientIp(request);
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        String referer = request.getHeader(HttpHeaders.REFERER);

        RedirectResult result = shortLinkService.resolve(shortCode, clientIp, userAgent, referer);
        if (result.isFound()) {
            // 302 + no-store：既保证统计准确，也避免短链被浏览器长期缓存
            response.setStatus(HttpStatus.FOUND.value());
            response.setHeader(HttpHeaders.LOCATION, result.targetUrl());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate");
            response.setHeader(HttpHeaders.PRAGMA, "no-cache");
            return;
        }
        if (result.httpStatus() == RedirectResult.STATUS_GONE) {
            writeHtml(response, HttpStatus.GONE, GONE_HTML);
            return;
        }
        writeHtml(response, HttpStatus.NOT_FOUND, NOT_FOUND_HTML);
    }

    private void writeHtml(HttpServletResponse response, HttpStatus status, String html) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter().write(html);
    }
}
