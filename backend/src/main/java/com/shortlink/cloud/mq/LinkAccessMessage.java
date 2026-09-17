package com.shortlink.cloud.mq;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 访问日志消息体。
 *
 * <p>刻意保持扁平且字段精简：这是跳转热路径上唯一产生的网络负载，
 * 序列化开销直接体现在 P99 上。
 *
 * @author shortlink-cloud
 */
@Data
@Schema(description = "访问日志消息")
public class LinkAccessMessage implements Serializable {

    /** 短码。 */
    private String shortCode;

    /** 短链 ID。 */
    private Long linkId;

    /** 访问时的原始链接快照（便于日志表独立可查）。 */
    private String originalUrl;

    /** 客户端 IP（明文，仅用于排障）。 */
    private String clientIp;

    /** IP 的 MD5，UV 去重用，避免在聚合表落明文 IP。 */
    private String ipHash;

    /** User-Agent。 */
    private String userAgent;

    /** 来源页。 */
    private String referer;

    /** 访问时间。 */
    private LocalDateTime accessTime;
}
