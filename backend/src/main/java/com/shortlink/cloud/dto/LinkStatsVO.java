package com.shortlink.cloud.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/**
 * 单个短码的统计详情。
 *
 * @author shortlink-cloud
 */
@Data
@Schema(description = "短链统计详情")
public class LinkStatsVO implements Serializable {

    @Schema(description = "短码")
    private String shortCode;

    @Schema(description = "原始长链接")
    private String originalUrl;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "状态：0-禁用 1-启用")
    private Integer status;

    @Schema(description = "创建时间")
    private String createTime;

    @Schema(description = "过期时间，null 表示永久")
    private String expireTime;

    @Schema(description = "累计访问量（来自短链冗余字段）")
    private Long totalPv;

    @Schema(description = "累计独立访客（来自短链冗余字段）")
    private Long totalUv;

    @Schema(description = "历史 PV 合计（来自按天统计表，用于与冗余字段交叉校验）")
    private Long statsPvSum;

    @Schema(description = "历史 UV 合计（来自按天统计表）")
    private Long statsUvSum;

    @Schema(description = "最近访问时间")
    private String lastAccess;

    @Schema(description = "查询的起止日期")
    private LocalDate startDate;

    @Schema(description = "查询的截止日期")
    private LocalDate endDate;

    @Schema(description = "按天趋势")
    private List<TrendPointVO> trend;
}
