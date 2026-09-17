package com.shortlink.cloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shortlink.cloud.entity.ShortLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 短链 Mapper。
 *
 * @author shortlink-cloud
 */
@Mapper
public interface ShortLinkMapper extends BaseMapper<ShortLink> {

    /**
     * 原子累加访问统计。
     *
     * <p>跳转接口本身不写库，本方法由统计消费者调用。使用 {@code pv = pv + #{pv}}
     * 而不是「读-改-写」，避免并发丢更新。
     *
     * @param id         短链 ID
     * @param pv         本次新增访问量
     * @param uv         本次新增独立访客
     * @param lastAccess 最近访问时间
     * @return 受影响行数
     */
    @Update("UPDATE t_short_link SET pv = pv + #{pv}, uv = uv + #{uv}, "
            + "last_access = #{lastAccess}, update_time = NOW() "
            + "WHERE id = #{id} AND deleted = 0")
    int accumulateStats(@Param("id") Long id,
                        @Param("pv") long pv,
                        @Param("uv") long uv,
                        @Param("lastAccess") LocalDateTime lastAccess);

    /**
     * 切换启用 / 禁用状态。
     *
     * @param id     短链 ID
     * @param status 目标状态
     * @return 受影响行数
     */
    @Update("UPDATE t_short_link SET status = #{status}, update_time = NOW() "
            + "WHERE id = #{id} AND deleted = 0")
    int updateStatus(@Param("id") Long id, @Param("status") int status);

    /**
     * 记录一次访问时间。
     *
     * <p>阶段 1 的跳转热路径使用；阶段 3 引入 MQ 异步统计后，PV/UV 的累加
     * 改由消费者批量完成，本方法仅保留为兜底。
     *
     * @param id         短链 ID
     * @param lastAccess 访问时间
     * @return 受影响行数
     */
    @Update("UPDATE t_short_link SET last_access = #{lastAccess} "
            + "WHERE id = #{id} AND deleted = 0")
    int updateLastAccess(@Param("id") Long id, @Param("lastAccess") LocalDateTime lastAccess);
}
