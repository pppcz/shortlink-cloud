package com.shortlink.cloud.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 数据保留策略配置，对应 {@code shortlink.retention.*}。
 *
 * @author shortlink-cloud
 */
@Data
@Component
@ConfigurationProperties(prefix = "shortlink.retention")
public class RetentionProperties {

    /** 是否启用定时清理。 */
    private boolean enabled = true;

    /** 访问明细日志保留天数。 */
    private int accessLogDays = 90;

    /** UV 去重记录保留天数。 */
    private int uvLogDays = 180;

    /**
     * 单批删除行数。
     *
     * <p>不设太大：{@code DELETE} 会持有行锁并写入 undo/redo，
     * 单批几十万行会造成明显的主从延迟。一万行是个经验上的安全值。
     */
    private int batchSize = 10_000;

    /**
     * 单次清理任务最多删除的批次数。
     *
     * <p>防止"一次清理刚好赶上积压几亿行"时长时间占用数据库，
     * 宁可分多次调度慢慢清完。
     */
    private int maxBatchesPerRun = 50;

    /**
     * 清理任务 cron 表达式。
     *
     * <p>{@code @Scheduled} 通过 {@code ${shortlink.retention.cron}} 读取本字段，
     * 必须声明为属性，否则 SpEL 占位符无处可解析。
     */
    private String cron = "0 30 3 * * ?";
}
