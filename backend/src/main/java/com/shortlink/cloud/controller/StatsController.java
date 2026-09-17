package com.shortlink.cloud.controller;

import com.shortlink.cloud.common.RequireLogin;
import com.shortlink.cloud.common.Result;
import com.shortlink.cloud.dto.LinkStatsVO;
import com.shortlink.cloud.dto.TrendPointVO;
import com.shortlink.cloud.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 统计查询接口。
 *
 * <p>数据来源：跳转时投递到 RabbitMQ 的访问日志，由消费者异步落到
 * {@code t_link_access_log} / {@code t_link_stats}，因此这里查到的数据
 * 存在秒级的消费延迟（这正是削峰的代价）。
 *
 * @author shortlink-cloud
 */
@Validated
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
@Tag(name = "统计", description = "短链访问统计与趋势")
public class StatsController {

    private final StatsService statsService;

    @GetMapping("/{shortCode}")
    @RequireLogin
    @Operation(summary = "短链统计详情", description = "返回累计 PV/UV、冗余字段与按天趋势")
    public Result<LinkStatsVO> stats(
            @Parameter(description = "短码") @PathVariable String shortCode,
            @Parameter(description = "趋势天数，1-90，默认 7")
            @RequestParam(defaultValue = "7") @Min(1) @Max(90) int days) {
        return Result.success(statsService.statsOf(shortCode, days));
    }

    @GetMapping("/trend")
    @RequireLogin
    @Operation(summary = "访问趋势",
            description = "传 shortCode 查单条短链趋势；不传则返回全部短链的合计趋势（总览用）")
    public Result<List<TrendPointVO>> trend(
            @Parameter(description = "短码，可不传")
            @RequestParam(required = false) String shortCode,
            @Parameter(description = "趋势天数，1-90，默认 7")
            @RequestParam(defaultValue = "7") @Min(1) @Max(90) int days) {
        if (shortCode == null || shortCode.isBlank()) {
            return Result.success(statsService.trendAll(days));
        }
        return Result.success(statsService.statsOf(shortCode, days).getTrend());
    }
}
