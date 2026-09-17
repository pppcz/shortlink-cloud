package com.shortlink.cloud.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 趋势图数据点。
 *
 * @author shortlink-cloud
 */
@Data
@Schema(description = "趋势数据点")
public class TrendPointVO implements Serializable {

    @Schema(description = "日期 yyyy-MM-dd", example = "2026-02-14")
    private String statDate;

    @Schema(description = "当日访问量")
    private Long pv;

    @Schema(description = "当日独立访客")
    private Long uv;
}
