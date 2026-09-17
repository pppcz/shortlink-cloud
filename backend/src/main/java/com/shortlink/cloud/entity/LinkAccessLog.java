package com.shortlink.cloud.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 短链访问明细日志。
 *
 * <p>{@code accessDate} / {@code hour} 是从 {@code accessTime} 冗余出来的列，
 * 目的是让报表查询能走 {@code (short_code, access_date, hour)} 联合索引，
 * 而不是对时间列做函数运算导致索引失效。
 *
 * @author shortlink-cloud
 */
@Data
@TableName("t_link_access_log")
public class LinkAccessLog implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String shortCode;

    private Long linkId;

    private String originalUrl;

    private String clientIp;

    /** IP 的 MD5，UV 去重用。 */
    private String ipHash;

    private String userAgent;

    private String referer;

    private LocalDateTime accessTime;

    private LocalDate accessDate;

    /** 0-23。 */
    private Integer hour;
}
