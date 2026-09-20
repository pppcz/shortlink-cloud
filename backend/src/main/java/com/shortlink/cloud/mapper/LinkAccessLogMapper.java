package com.shortlink.cloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shortlink.cloud.entity.LinkAccessLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

/**
 * 访问日志 Mapper。
 *
 * <p>批量写入使用 MyBatis 的 BATCH 执行器（见 {@code AccessLogBatchWriter}），
 * 因此这里只需继承 BaseMapper 的 {@code insert}，无需手写动态 SQL，
 * 也避免了动辄上千行的多值 INSERT 语句。
 *
 * @author shortlink-cloud
 */
@Mapper
public interface LinkAccessLogMapper extends BaseMapper<LinkAccessLog> {

    /**
     * 分批删除指定日期之前的访问明细。
     *
     * <p>必须带 {@code LIMIT}：一次性删掉几百万行会形成长事务，
     * 阻塞主从复制并可能触发锁等待超时。
     *
     * <p>删除条件本身就等于匹配条件，因此 MySQL JDBC 默认返回「匹配行数」
     * 还是「影响行数」在这里没有差别。
     *
     * @param beforeDate 早于该日期（不含）的记录会被删除
     * @param limit      单批删除上限
     * @return 实际删除行数
     */
    @Delete("DELETE FROM t_link_access_log WHERE access_date < #{beforeDate} LIMIT #{limit}")
    int deleteBeforeDate(@Param("beforeDate") LocalDate beforeDate, @Param("limit") int limit);
}
