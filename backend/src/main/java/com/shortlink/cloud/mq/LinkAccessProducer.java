package com.shortlink.cloud.mq;

import com.shortlink.cloud.entity.ShortLink;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 访问日志生产者。
 *
 * <p>关键约定：<b>投递失败绝不向上抛异常</b>。统计是旁路能力，
 * MQ 抖动不应该让用户跳转失败。失败时只记录告警日志，
 * 由监控发现 MQ 可用性问题。
 *
 * @author shortlink-cloud
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LinkAccessProducer {

    private final RabbitTemplate rabbitTemplate;

    /**
     * 投递访问日志。
     *
     * @param link      命中的短链
     * @param clientIp  客户端 IP
     * @param ipHash    IP 的 MD5（UV 去重用）
     * @param userAgent User-Agent，可为 null
     * @param referer   来源页，可为 null
     */
    public void publish(ShortLink link, String clientIp, String ipHash, String userAgent, String referer) {
        try {
            LinkAccessMessage message = new LinkAccessMessage();
            message.setShortCode(link.getShortCode());
            message.setLinkId(link.getId());
            message.setOriginalUrl(link.getOriginalUrl());
            message.setClientIp(clientIp);
            message.setIpHash(ipHash);
            message.setUserAgent(truncate(userAgent, 512));
            message.setReferer(truncate(referer, 1024));
            message.setAccessTime(LocalDateTime.now());

            rabbitTemplate.convertAndSend(
                    MqConstants.ACCESS_EXCHANGE,
                    MqConstants.ACCESS_ROUTING_KEY,
                    message,
                    postProcessor -> {
                        postProcessor.getMessageProperties().setContentEncoding("UTF-8");
                        return postProcessor;
                    });
        } catch (Exception ex) {
            log.warn("访问日志投递失败 code={} err={}", link.getShortCode(), ex.getMessage());
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
