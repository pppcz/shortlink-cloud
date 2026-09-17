package com.shortlink.cloud.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 创建短链请求。
 *
 * @author shortlink-cloud
 */
@Data
@Schema(description = "创建短链请求")
public class CreateLinkRequest implements Serializable {

    @Schema(description = "原始长链接，支持不带协议（自动补 https）",
            example = "https://example.com/very/long/path", requiredMode = Schema.RequiredMode.REQUIRED)
    @Size(max = 2048, message = "原始链接长度不能超过 2048")
    private String originalUrl;

    @Schema(description = "标题 / 备注", example = "示例站点")
    @Size(max = 256, message = "标题长度不能超过 256")
    private String title;

    @Schema(description = "有效期（天）。不传或传 0 表示永久有效", example = "30")
    @Min(value = 0, message = "有效期不能为负数")
    @Max(value = 3650, message = "有效期不能超过 3650 天")
    private Integer expireDays;

    @Schema(description = "自定义短码（仅允许 0-9a-zA-Z，长度 4-16）。不传则自动生成",
            example = "mysite")
    @Size(min = 4, max = 16, message = "自定义短码长度必须在 4-16 之间")
    private String customCode;
}
