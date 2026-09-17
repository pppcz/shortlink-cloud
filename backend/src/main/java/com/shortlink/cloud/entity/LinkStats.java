package com.shortlink.cloud.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 短链按天统计。
 *
 * <p>{@code (short_code, stat_date)} 唯一，配合 {@code ON DUPLICATE KEY UPDATE}
 * 实现幂等累加，消费重复投递的消息也不会把 PV 算重。
 *
 * @author shortlink-cloud
 */
@Data
@TableName("t_link_stats")
public class LinkStats implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String shortCode;

    private Long linkId;

    private LocalDate statDate;

    /** 当日访问量。 */
    private Long pv;

    /** 当日独立访客。 */
    private Long uv;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
