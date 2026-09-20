package com.shortlink.cloud.service.impl;

import com.shortlink.cloud.config.RetentionProperties;
import com.shortlink.cloud.mapper.LinkAccessLogMapper;
import com.shortlink.cloud.mapper.LinkUvLogMapper;
import com.shortlink.cloud.service.DataRetentionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据保留与清理实现。
 *
 * @author shortlink-cloud
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataRetentionServiceImpl implements DataRetentionService {

    /**
     * 防重入标志。
     *
     * <p>为什么不直接用 Redisson 分布式锁：清理任务本身是幂等的
     * （删的是"早于某日期"的数据，跑两遍结果一样），
     * 多实例同时跑最多是浪费一点 IO，不会造成数据错误。
     * 用一把本地标志避免同一实例内任务堆叠就够了，
     * 没必要为此引入分布式锁的复杂度和故障面。
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final LinkAccessLogMapper accessLogMapper;
    private final LinkUvLogMapper uvLogMapper;
    private final RetentionProperties properties;

    /**
     * 每天 03:30 执行清理。
     *
     * <p>选凌晨低峰期：批量 DELETE 会占用 IO 与 undo 空间，
     * 放在业务高峰会与正常读写抢资源。
     */
    @Scheduled(cron = "${shortlink.retention.cron:0 30 3 * * ?}")
    public void scheduledPurge() {
        if (!properties.isEnabled()) {
            log.debug("数据清理任务已关闭（shortlink.retention.enabled=false）");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("上一轮数据清理尚未结束，跳过本次调度");
            return;
        }
        long start = System.currentTimeMillis();
        try {
            long accessLogs = purgeAccessLogs();
            long uvLogs = purgeUvLogs();
            log.info("数据清理完成 accessLog={} uvLog={} 耗时={}ms",
                    accessLogs, uvLogs, System.currentTimeMillis() - start);
        } catch (Exception ex) {
            // 清理失败只告警，不能影响主流程（这里已经是独立线程，不会打断请求处理）
            log.error("数据清理失败", ex);
        } finally {
            running.set(false);
        }
    }

    @Override
    public long purgeAccessLogs() {
        LocalDate before = LocalDate.now().minusDays(properties.getAccessLogDays());
        return purgeInBatches(before, "访问明细日志", accessLogMapper::deleteBeforeDate);
    }

    @Override
    public long purgeUvLogs() {
        LocalDate before = LocalDate.now().minusDays(properties.getUvLogDays());
        return purgeInBatches(before, "UV 去重记录", uvLogMapper::deleteBeforeDate);
    }

    /**
     * 循环分批删除。
     *
     * @param before  截止日期
     * @param label   日志标签
     * @param deleter 单批删除函数
     * @return 累计删除行数
     */
    private long purgeInBatches(LocalDate before, String label, BatchDeleter deleter) {
        int batchSize = properties.getBatchSize();
        int maxBatches = properties.getMaxBatchesPerRun();
        long total = 0L;

        for (int i = 0; i < maxBatches; i++) {
            int deleted = deleter.delete(before, batchSize);
            total += deleted;
            // 不足一批说明已经删完，提前结束
            if (deleted < batchSize) {
                break;
            }
            if (i == maxBatches - 1) {
                log.warn("{}清理达到单次上限 {} 批，剩余数据将在下次调度继续", label, maxBatches);
            }
        }
        if (total > 0) {
            log.info("{}清理完成: 删除 {} 行，保留 {} 天", label, total, before);
        }
        return total;
    }

    /** 单批删除函数式接口。 */
    @FunctionalInterface
    private interface BatchDeleter {
        int delete(LocalDate before, int limit);
    }
}
