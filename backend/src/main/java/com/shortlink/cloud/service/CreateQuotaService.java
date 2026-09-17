package com.shortlink.cloud.service;

/**
 * 按日创建量限制（防刷）。
 *
 * <p>与 {@link RateLimitService} 的区别：后者限制的是「瞬时速率」（每秒多少次），
 * 这里限制的是「单日总量」，用于挡住「慢慢刷、不触发速率限制」的滥用。
 *
 * @author shortlink-cloud
 */
public interface CreateQuotaService {

    /**
     * 记录一次创建并判断是否超限。
     *
     * @param clientIp 客户端 IP
     * @param dailyLimit 单日上限
     * @return true 表示允许本次创建；false 表示已超限
     */
    boolean tryConsume(String clientIp, int dailyLimit);
}
