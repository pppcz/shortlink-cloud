package com.shortlink.cloud.common;

/**
 * 全局常量。
 *
 * @author shortlink-cloud
 */
public final class Constants {

    private Constants() {
    }

    /** 链路追踪 ID 在 MDC / 响应头中的键名。 */
    public static final String TRACE_ID = "traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_ID_MDC_KEY = "traceId";

    /** 短链状态。 */
    public static final int STATUS_DISABLED = 0;
    public static final int STATUS_ENABLED = 1;

    /** 逻辑删除标记。 */
    public static final int NOT_DELETED = 0;
    public static final int DELETED = 1;

    /** Redis key 前缀。 */
    public static final String KEY_LINK_PREFIX = "sl:link:";
    public static final String KEY_LINK_NULL_PREFIX = "sl:link:null:";
    public static final String KEY_SEQ_PREFIX = "sl:seq:";
    public static final String KEY_RATE_LIMIT_PREFIX = "sl:rl:";
    public static final String KEY_CREATE_LIMIT_PREFIX = "sl:create:";
    public static final String KEY_BLOOM_FILTER = "sl:bloom:link";

    /** 短码冲突时的最大重试次数。 */
    public static final int SHORT_CODE_MAX_RETRY = 5;

    /** Base62 字符集。 */
    public static final String BASE62_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
}
