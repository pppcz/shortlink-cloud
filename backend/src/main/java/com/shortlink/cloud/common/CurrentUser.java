package com.shortlink.cloud.common;

/**
 * 当前登录用户（由 {@code JwtAuthInterceptor} 解析 JWT 后写入 ThreadLocal）。
 *
 * @param userId   用户 ID
 * @param username 用户名
 * @param role     角色
 * @author shortlink-cloud
 */
public record CurrentUser(Long userId, String username, String role) {

    /** ADMIN 角色常量。 */
    public static final String ROLE_ADMIN = "ADMIN";

    /** 是否管理员。 */
    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }
}
