package com.shortlink.cloud.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 登录响应。
 *
 * @author shortlink-cloud
 */
@Data
@Schema(description = "登录响应")
public class LoginResponse implements Serializable {

    @Schema(description = "JWT token")
    private String token;

    @Schema(description = "token 类型")
    private String tokenType = "Bearer";

    @Schema(description = "有效期（秒）")
    private long expiresIn;

    @Schema(description = "用户信息")
    private UserVO user;
}
