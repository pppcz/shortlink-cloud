package com.shortlink.cloud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shortlink.cloud.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 用户 Mapper。
 *
 * @author shortlink-cloud
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 记录最近登录时间。
     *
     * @param id        用户 ID
     * @param loginTime 登录时刻
     * @return 受影响行数
     */
    @Update("UPDATE t_user SET last_login = #{loginTime} WHERE id = #{id} AND deleted = 0")
    int updateLastLogin(@Param("id") Long id, @Param("loginTime") LocalDateTime loginTime);
}
