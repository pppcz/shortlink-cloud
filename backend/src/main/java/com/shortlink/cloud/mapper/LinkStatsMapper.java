package com.shortlink.cloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shortlink.cloud.dto.StatsSummaryVO;
import com.shortlink.cloud.dto.TrendPointVO;
import com.shortlink.cloud.entity.LinkStats;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 统计 Mapper。
 *
 * @author shortlink-cloud
 */
@Mapper
public interface LinkStatsMapper extends BaseMapper<LinkStats> {

    /**
     * 幂等累加当日统计。
     *
     * <p>{@code ON DUPLICATE KEY UPDATE} + {@code pv = pv + VALUES(pv)}：
     * 同一批消息重复投递时 PV 会被重复累加，因此调用方必须保证
     * 「按 (code, date) 聚合后再累加」，而不是逐条累加。
     *
     * @param shortCode 短码
     * @param linkId    短链 ID
     * @param statDate  统计日期
     * @param pv        当日新增 PV
     * @param uv        当日新增 UV
     * @return 受影响行数（插入为 1，更新为 2）
     */
    @Select("""
            INSERT INTO t_link_stats (id, short_code, link_id, stat_date, pv, uv)
            VALUES (#{id}, #{shortCode}, #{linkId}, #{statDate}, #{pv}, #{uv})
            ON DUPLICATE KEY UPDATE
                pv = pv + VALUES(pv),
                uv = uv + VALUES(uv),
                link_id = VALUES(link_id)
            """)
    int upsertStats(@Param("id") Long id,
                    @Param("shortCode") String shortCode,
                    @Param("linkId") Long linkId,
                    @Param("statDate") LocalDate statDate,
                    @Param("pv") long pv,
                    @Param("uv") long uv);

    /**
     * 汇总某个短码的历史总量。
     *
     * @param shortCode 短码
     * @return 汇总结果；无数据时 pv / uv 为 0
     */
    @Select("""
            SELECT COALESCE(SUM(pv), 0) AS pv, COALESCE(SUM(uv), 0) AS uv
            FROM t_link_stats
            WHERE short_code = #{shortCode}
            """)
    StatsSummaryVO sumByCode(@Param("shortCode") String shortCode);

    /**
     * 查询某个短码最近 N 天的趋势（按日期升序，便于前端直接画折线）。
     *
     * @param shortCode 短码
     * @param startDate 起始日期（含）
     * @return 趋势点列表
     */
    @Select("""
            SELECT stat_date AS statDate, COALESCE(pv, 0) AS pv, COALESCE(uv, 0) AS uv
            FROM t_link_stats
            WHERE short_code = #{shortCode}
              AND stat_date >= #{startDate}
            ORDER BY stat_date ASC
            """)
    List<TrendPointVO> selectTrend(@Param("shortCode") String shortCode,
                                   @Param("startDate") LocalDate startDate);

    /**
     * 查询全部短码最近 N 天的趋势合计（管理后台总览用）。
     *
     * @param startDate 起始日期（含）
     * @return 趋势点列表
     */
    @Select("""
            SELECT stat_date AS statDate,
                   COALESCE(SUM(pv), 0) AS pv,
                   COALESCE(SUM(uv), 0) AS uv
            FROM t_link_stats
            WHERE stat_date >= #{startDate}
            GROUP BY stat_date
            ORDER BY stat_date ASC
            """)
    List<TrendPointVO> selectTrendAll(@Param("startDate") LocalDate startDate);
}
