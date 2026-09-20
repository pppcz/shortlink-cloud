package com.shortlink.cloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shortlink.cloud.entity.LinkUvLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/**
 * 每日 UV 去重 Mapper。
 *
 * @author shortlink-cloud
 */
@Mapper
public interface LinkUvLogMapper extends BaseMapper<LinkUvLog> {

    /**
     * 尝试登记一次「首次访问」。
     *
     * <p>用 {@code INSERT IGNORE} 而不是先查后插：并发下「查不到就插入」会重复插入，
     * 依赖唯一键 + IGNORE 才能保证原子性。返回 1 表示本次是该访客当天第一次访问，
     * 返回 0 表示已存在（重复访问，不计 UV）。
     *
     * @param id        主键
     * @param shortCode 短码
     * @param statDate  统计日期
     * @param ipHash    IP 的 MD5
     * @return 1 表示首次访问；0 表示已记录过
     */
    @Select("""
            INSERT IGNORE INTO t_link_uv_log (id, short_code, stat_date, ip_hash)
            VALUES (#{id}, #{shortCode}, #{statDate}, #{ipHash})
            """)
    int tryInsertUv(@Param("id") Long id,
                    @Param("shortCode") String shortCode,
                    @Param("statDate") LocalDate statDate,
                    @Param("ipHash") String ipHash);

    /**
     * 分批删除指定日期之前的 UV 去重记录。
     *
     * <p>这张表只服务于「当天是否首次访问」的判定，跨天后查询价值急剧下降，
     * 但不清会无限增长——它是本项目里最容易失控的一张表。
     *
     * @param beforeDate 早于该日期（不含）的记录会被删除
     * @param limit      单批删除上限
     * @return 实际删除行数
     */
    @Delete("DELETE FROM t_link_uv_log WHERE stat_date < #{beforeDate} LIMIT #{limit}")
    int deleteBeforeDate(@Param("beforeDate") LocalDate beforeDate, @Param("limit") int limit);
}
