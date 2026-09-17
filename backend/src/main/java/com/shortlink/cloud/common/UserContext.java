package com.shortlink.cloud.common;

/**
 * 请求级用户上下文。
 *
 * <p>必须在请求结束时调用 {@link #clear()}，否则线程复用会串数据
 * （见 {@code JwtAuthInterceptor#afterCompletion}）。
 *
 * @author shortlink-cloud
 */
public final class UserContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    /**
     * 获取当前用户。
     *
     * @return 当前用户；未登录返回 null
     */
    public static CurrentUser get() {
        return HOLDER.get();
    }

    /**
     * 获取当前用户，未登录抛 401。
     *
     * @return 当前用户
     */
    public static CurrentUser require() {
        CurrentUser user = HOLDER.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    /**
     * 获取当前用户 ID。
     *
     * @return 用户 ID；未登录返回 null
     */
    public static Long userId() {
        CurrentUser user = HOLDER.get();
        return user == null ? null : user.userId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
