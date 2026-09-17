package com.shortlink.cloud.config;

import com.shortlink.cloud.service.ShortLinkCacheManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 应用启动后预热布隆过滤器。
 *
 * <p>必要性：布隆过滤器只能添加不能删除，进程重启后内存中的位图会丢失；
 * 若不预热，存量短链会被预判为「一定不存在」而全部返回 404。
 *
 * <p>放在 {@link ApplicationReadyEvent} 而不是 {@code @PostConstruct}：
 * 此时数据源、Flyway 迁移都已完成，可以安全读库。
 *
 * @author shortlink-cloud
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BloomFilterWarmUpRunner {

    private final ShortLinkCacheManager cacheManager;

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        try {
            cacheManager.warmUpBloomFilter();
            log.info("布隆过滤器预热流程结束，估算元素数={}", cacheManager.bloomCount());
        } catch (Exception ex) {
            // 预热失败不应导致启动失败：读请求会退化为「全部回源」，
            // 功能仍可用，只是失去防穿透保护。
            log.error("布隆过滤器预热异常，服务继续启动（读路径将退化为回源）", ex);
        }
    }
}
