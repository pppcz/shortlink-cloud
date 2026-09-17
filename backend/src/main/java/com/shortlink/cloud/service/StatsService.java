package com.shortlink.cloud.service;

import com.shortlink.cloud.dto.LinkStatsVO;
import com.shortlink.cloud.dto.TrendPointVO;
import com.shortlink.cloud.mq.LinkAccessMessage;

import java.util.List;

/**
 * 统计服务。
 *
 * @author shortlink-cloud
 */
public interface StatsService {

    /**
     * 消费并落库一批访问日志。
     *
     * <p>幂等性说明：消费者采用手动 ack，只有本方法正常返回才会 ack。
     * 若落库过程中抛异常，消息会重投，此时可能出现「同一条访问被记两次」。
     * 为了把重复的影响降到最低：
     * <ul>
     *   <li>UV 依靠唯一键 + INSERT IGNORE 天然去重，重复投递不会虚高</li>
     *   <li>PV 按 (code, date) 聚合后一次性 upsert，单批内不会重复累加</li>
     * </ul>
     *
     * @param messages 消息批次，可为空
     * @return 落库的消息条数
     */
    int recordAccess(List<LinkAccessMessage> messages);

    /**
     * 查询单个短码的统计详情与趋势。
     *
     * @param shortCode 短码
     * @param days      趋势天数（1-90）
     * @return 统计详情
     */
    LinkStatsVO statsOf(String shortCode, int days);

    /**
     * 查询全部短码的合计趋势（管理后台总览）。
     *
     * @param days 趋势天数（1-90）
     * @return 趋势点列表
     */
    List<TrendPointVO> trendAll(int days);
}
