package com.shortlink.cloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shortlink.cloud.entity.LinkAccessLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 访问日志 Mapper。
 *
 * <p>批量写入使用 MyBatis 的 BATCH 执行器（见 {@code StatsServiceImpl}），
 * 因此这里只需继承 BaseMapper 的 {@code insert}，无需手写动态 SQL，
 * 也避免了动辄上千行的多值 INSERT 语句。
 *
 * @author shortlink-cloud
 */
@Mapper
public interface LinkAccessLogMapper extends BaseMapper<LinkAccessLog> {
}
