package com.shortlink.cloud.service;

/**
 * 数据保留与清理服务。
 *
 * <p>解决的问题：{@code t_link_access_log} 与 {@code t_link_uv_log} 都是**只增不删**的表。
 * 不做清理的话，按日活 10 万、人均访问 3 次估算，一年就是 1 亿行，
 * 最终会把磁盘写满、把索引撑到无法维护。
 *
 * <p>设计取舍：
 * <ul>
 *   <li><b>不用 MySQL 分区表</b>：分区需要把分区列纳入主键，且新增分区要提前建。
 *       对"每天跑一次 DELETE"这种低频维护来说，分区带来的复杂度大于收益，
 *       分区只在单表过亿且需要秒级 DROP 历史数据时才明显划算。</li>
 *   <li><b>分批删除</b>：一次 {@code DELETE ... LIMIT n} 会有长事务与主从延迟问题，
 *       这里循环小批量删除，每批之间让出时间片，避免把数据库压住。</li>
 *   <li><b>清理窗口可配</b>：不同业务对明细日志的留存诉求差别很大，
 *       写死天数是不负责任的。</li>
 * </ul>
 *
 * @author shortlink-cloud
 */
public interface DataRetentionService {

    /**
     * 清理过期的访问明细日志。
     *
     * @return 实际删除的行数
     */
    long purgeAccessLogs();

    /**
     * 清理过期的 UV 去重记录。
     *
     * <p>UV 记录只服务于"当天是否首次访问"的判定，跨天后就没有查询价值，
     * 但仍保留一段时间以便排查历史报表异常。
     *
     * @return 实际删除的行数
     */
    long purgeUvLogs();
}
