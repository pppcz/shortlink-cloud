package com.shortlink.cloud.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 创建短链响应。
 *
 * @author shortlink-cloud
 */
@Data
@Schema(description = "创建短链响应")
public class CreateLinkResponse implements Serializable {

    @Schema(description = "短链 ID")
    private Long id;

    @Schema(description = "短码")
    private String shortCode;

    @Schema(description = "可直接访问的完整短链")
    private String shortUrl;

    @Schema(description = "原始长链接")
    private String originalUrl;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "过期时间，null 表示永久")
    private LocalDateTime expireTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
