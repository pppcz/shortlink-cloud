package com.shortlink.cloud.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日 UV 去重记录。
 *
 * <p>聚合表里只有一个「当天 UV 数字」，无法判断某个访客今天是否已经来过；
 * 本表靠唯一键 {@code (short_code, stat_date, ip_hash)} 做「首次访问」判定，
 * 只有插入成功才给 UV +1。
 *
 * @author shortlink-cloud
 */
@Data
@TableName("t_link_uv_log")
public class LinkUvLog implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String shortCode;

    private LocalDate statDate;

    /** IP 的 MD5，不落明文。 */
    private String ipHash;

    private LocalDateTime createTime;
}
