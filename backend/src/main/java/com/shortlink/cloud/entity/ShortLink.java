package com.shortlink.cloud.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 短链映射。
 *
 * @author shortlink-cloud
 */
@Data
@TableName("t_short_link")
public class ShortLink implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 短码（Base62），全局唯一。 */
    private String shortCode;

    /** 原始长链接。 */
    private String originalUrl;

    /** 标题 / 备注。 */
    private String title;

    /** 创建人 ID，匿名创建为 null。 */
    private Long creatorId;

    /** 创建人 IP。 */
    private String creatorIp;

    /** 状态：0-禁用 1-启用。 */
    private Integer status;

    /** 过期时间，null 表示永久有效。 */
    private LocalDateTime expireTime;

    /** 累计访问次数（冗余字段，由统计消费者异步累加）。 */
    private Long pv;

    /** 累计独立访客（冗余字段，由统计消费者异步累加）。 */
    private Long uv;

    /** 最近一次访问时间。 */
    private LocalDateTime lastAccess;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除标记：0-正常 1-删除。 */
    @TableLogic
    private Integer deleted;

    /** 是否处于可跳转状态（启用且未过期）。 */
    public boolean isRedirectable(LocalDateTime now) {
        if (status == null || status != 1) {
            return false;
        }
        return expireTime == null || expireTime.isAfter(now);
    }
}
