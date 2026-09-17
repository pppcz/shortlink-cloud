package com.shortlink.cloud.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serializable;

/**
 * 分页查询短链请求。
 *
 * @author shortlink-cloud
 */
@Data
@Schema(description = "短链分页查询条件")
public class LinkPageQuery implements Serializable {

    @Schema(description = "页码，从 1 开始", example = "1")
    @Min(value = 1, message = "页码不能小于 1")
    private Integer current = 1;

    @Schema(description = "每页条数，最大 200", example = "10")
    @Min(value = 1, message = "每页条数不能小于 1")
    @Max(value = 200, message = "每页条数不能超过 200")
    private Integer size = 10;

    @Schema(description = "按短码精确匹配")
    private String shortCode;

    @Schema(description = "按原始链接模糊匹配")
    private String originalUrl;

    @Schema(description = "按标题模糊匹配")
    private String title;

    @Schema(description = "状态过滤：0-禁用 1-启用")
    private Integer status;

    @Schema(description = "创建人 ID 过滤")
    private Long creatorId;
}
