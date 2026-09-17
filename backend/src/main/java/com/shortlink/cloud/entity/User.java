package com.shortlink.cloud.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 平台用户。
 *
 * @author shortlink-cloud
 */
@Data
@TableName("t_user")
public class User implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String username;

    /** PBKDF2 哈希，绝不返回给前端。 */
    private String password;

    private String nickname;

    private String email;

    /** ADMIN / USER。 */
    private String role;

    /** 状态：0-禁用 1-启用。 */
    private Integer status;

    private LocalDateTime lastLogin;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

    /** 账号是否可用。 */
    public boolean isEnabled() {
        return status != null && status == 1;
    }
}
