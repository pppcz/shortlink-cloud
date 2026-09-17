package com.shortlink.cloud.service;

/**
 * 短码生成器。
 *
 * <p>实现优先级由 Spring 决定：{@link RedisSeqShortCodeGenerator} 标注
 * {@code @Primary}，正常路径使用 Redis 发号；Redis 不可用时上层退化为
 * {@link LocalSequenceShortCodeGenerator}，保证服务仍可创建短链。
 *
 * @author shortlink-cloud
 */
public interface ShortCodeGenerator {

    /**
     * 生成一个候选短码。
     *
     * @return Base62 短码
     */
    String nextCode();

    /**
     * 生成器名称，用于日志与诊断。
     *
     * @return 名称
     */
    String name();
}
