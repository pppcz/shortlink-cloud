package com.shortlink.cloud.service;

/**
 * 基于 Redis + Lua 的限流服务。
 *
 * <p>为什么用 Lua：限流是典型的「读-判断-写」复合操作，如果在应用侧分两步做，
 * 高并发下会出现检查通过后并发写导致超发。Lua 在 Redis 内单线程原子执行，
 * 从根上避免竞态。
 *
 * @author shortlink-cloud
 */
public interface RateLimitService {

    /**
     * 固定窗口计数限流。
     *
     * @param key           限流维度 key（如 {@code sl:rl:redirect:1.2.3.4}）
     * @param windowSeconds 窗口秒数
     * @param limit         窗口内允许的最大请求数
     * @return 限流结果
     */
    RateLimitResult tryAcquire(String key, int windowSeconds, int limit);

    /**
     * 限流结果。
     *
     * @param allowed   是否放行
     * @param current   当前窗口已计数
     * @param limit     窗口上限
     * @param remaining 剩余可用次数
     */
    record RateLimitResult(boolean allowed, long current, int limit, long remaining) {

        /** 构建放行结果。 */
        public static RateLimitResult pass(long current, int limit) {
            return new RateLimitResult(true, current, limit, Math.max(0, limit - current));
        }

        /** 构建拒绝结果。 */
        public static RateLimitResult reject(long current, int limit) {
            return new RateLimitResult(false, current, limit, 0);
        }

        /**
         * 限流场景下的兜底结果：Redis 故障时选择放行。
         *
         * <p>取舍说明：限流是为了保护系统而非业务正确性，
         * 因限流组件故障而拒绝全部正常流量，代价大于短暂放行。
         *
         * @param limit 窗口上限
         * @return 放行结果
         */
        public static RateLimitResult failOpen(int limit) {
            return new RateLimitResult(true, -1, limit, limit);
        }
    }
}
