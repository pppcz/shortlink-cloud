package com.shortlink.cloud.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.shortlink.cloud.common.Result;
import com.shortlink.cloud.common.RequireLogin;
import com.shortlink.cloud.common.UserContext;
import com.shortlink.cloud.dto.CreateLinkRequest;
import com.shortlink.cloud.dto.CreateLinkResponse;
import com.shortlink.cloud.dto.LinkPageQuery;
import com.shortlink.cloud.dto.ShortLinkVO;
import com.shortlink.cloud.service.LinkQueryService;
import com.shortlink.cloud.service.ShortLinkService;
import com.shortlink.cloud.util.IpUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
 * @author shortlink-cloud
 */
@Validated
@RestController
@RequestMapping("/api/link")
@RequiredArgsConstructor
@Tag(name = "短链管理", description = "创建、分页查询、禁用短链")
public class LinkController {

    private final ShortLinkService shortLinkService;
    private final LinkQueryService linkQueryService;

    @PostMapping("/create")
    @Operation(summary = "创建短链", description = "支持自定义短码与有效期；未登录也可创建（匿名短链）")
    public Result<CreateLinkResponse> create(@Valid @RequestBody CreateLinkRequest request,
                                             HttpServletRequest httpRequest) {
        // 未登录时 creatorId 为 null，属于允许的匿名创建
        Long creatorId = UserContext.userId();
        String creatorIp = IpUtils.getClientIp(httpRequest);
        return Result.success(shortLinkService.createLink(request, creatorId, creatorIp));
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
