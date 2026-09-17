package com.shortlink.cloud.service;

import com.shortlink.cloud.dto.LoginRequest;
import com.shortlink.cloud.dto.LoginResponse;
import com.shortlink.cloud.dto.UserVO;

/**
 * 认证服务。
 *
 * @author shortlink-cloud
 */
public interface AuthService {

    /**
     * 登录并签发 JWT。
     *
     * @param request 登录请求
     * @return 登录响应（含 token）
     */
    LoginResponse login(LoginRequest request);

    /**
     * 按 ID 查询当前用户信息。
     *
     * @param userId 用户 ID
     * @return 用户信息
     */
    UserVO currentUser(Long userId);
}
