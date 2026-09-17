package com.shortlink.cloud.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * PV / UV 汇总值对象（Mapper 直接映射，勿改字段名）。
 *
 * @author shortlink-cloud
 */
@Data
@Schema(description = "PV/UV 汇总")
public class StatsSummaryVO implements Serializable {

    @Schema(description = "访问量")
    private Long pv;

    @Schema(description = "独立访客")
    private Long uv;

    /** 空值安全的 pv。 */
    public long safePv() {
        return pv == null ? 0L : pv;
    }

    /** 空值安全的 uv。 */
    public long safeUv() {
        return uv == null ? 0L : uv;
    }
}
