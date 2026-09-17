package com.shortlink.cloud.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.shortlink.cloud.common.ErrorCode;
import com.shortlink.cloud.common.RequireLogin;
import com.shortlink.cloud.common.Result;
import com.shortlink.cloud.common.UserContext;
import com.shortlink.cloud.config.SentinelConfig;
import com.shortlink.cloud.config.ShortLinkProperties;
import com.shortlink.cloud.dto.CreateLinkRequest;
import com.shortlink.cloud.dto.CreateLinkResponse;
import com.shortlink.cloud.dto.LinkPageQuery;
import com.shortlink.cloud.dto.ShortLinkVO;
import com.shortlink.cloud.service.CreateQuotaService;
import com.shortlink.cloud.service.LinkQueryService;
import com.shortlink.cloud.service.ShortLinkService;
import com.shortlink.cloud.util.IpUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 短链管理接口。
 *
 * <p>创建接口叠加两层防护：
 * <ol>
 *   <li>Sentinel 接口级 QPS 兜底（{@code @SentinelResource}）</li>
 *   <li>Redis 按 IP 单日创建量限制（防「慢速刷」）</li>
 * </ol>
 *
 * @author shortlink-cloud
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/link")
@RequiredArgsConstructor
@Tag(name = "短链管理", description = "创建、分页查询、禁用短链")
public class LinkController {

    private final ShortLinkService shortLinkService;
    private final LinkQueryService linkQueryService;
    private final CreateQuotaService createQuotaService;
    private final ShortLinkProperties properties;

    @PostMapping("/create")
    @SentinelResource(value = SentinelConfig.RESOURCE_LINK_CREATE, fallback = "createFallback")
    @Operation(summary = "创建短链",
            description = "支持自定义短码与有效期；未登录也可创建（匿名短链）。受 QPS 与单日创建量双重限制")
    public Result<CreateLinkResponse> create(@Valid @RequestBody CreateLinkRequest request,
                                             HttpServletRequest httpRequest) {
        // 未登录时 creatorId 为 null，属于允许的匿名创建
        Long creatorId = UserContext.userId();
        String creatorIp = IpUtils.getClientIp(httpRequest);

        if (!createQuotaService.tryConsume(creatorIp, properties.getRateLimit().getCreatePerDay())) {
            return Result.error(ErrorCode.CREATE_LIMITED);
        }
        return Result.success(shortLinkService.createLink(request, creatorId, creatorIp));
    }

    /**
     * Sentinel 限流 / 熔断兜底。
     *
     * <p>方法签名需与原方法一致（末尾可追加异常参数），否则 Sentinel 无法解析。
     *
     * @param request    原请求体
     * @param httpRequest 原请求
     * @param ex         触发兜底的异常
     * @return 限流响应
     */
    public Result<CreateLinkResponse> createFallback(CreateLinkRequest request,
                                                     HttpServletRequest httpRequest,
                                                     Throwable ex) {
        log.warn("创建短链被 Sentinel 限流: {}", ex.toString());
        return Result.error(ErrorCode.RATE_LIMITED);
    }

    @GetMapping("/page")
    @RequireLogin
    @Operation(summary = "分页查询短链", description = "需要登录")
    public Result<IPage<ShortLinkVO>> page(@Valid @ModelAttribute LinkPageQuery query) {
        return Result.success(linkQueryService.page(query));
    }

    @PutMapping("/disable/{id}")
    @RequireLogin
    @Operation(summary = "禁用短链", description = "需要登录；禁用后跳转返回 410")
    public Result<Void> disable(@PathVariable Long id) {
        shortLinkService.disable(id);
        return Result.success();
    }
}
