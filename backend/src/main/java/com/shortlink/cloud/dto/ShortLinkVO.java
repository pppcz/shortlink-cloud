package com.shortlink.cloud.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 短链列表 / 详情项（不含敏感信息）。
 *
 * @author shortlink-cloud
 */
@Data
@Schema(description = "短链信息")
public class ShortLinkVO implements Serializable {

    @Schema(description = "短链 ID")
    private Long id;

    @Schema(description = "短码")
    private String shortCode;

    @Schema(description = "完整短链")
    private String shortUrl;

    @Schema(description = "原始长链接")
    private String originalUrl;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "状态：0-禁用 1-启用")
    private Integer status;

    @Schema(description = "过期时间，null 表示永久")
    private LocalDateTime expireTime;

    @Schema(description = "累计访问次数")
    private Long pv;

    @Schema(description = "累计独立访客")
    private Long uv;

    @Schema(description = "最近访问时间")
    private LocalDateTime lastAccess;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
