package com.shortlink.cloud.service;

import com.shortlink.cloud.entity.ShortLink;

/**
 * 短链缓存管理。
 *
 * <p>缓存四要素的落点：
 * <ul>
 *   <li><b>穿透</b>：Redisson 布隆过滤器预判 + 空值标记（短 TTL）</li>
 *   <li><b>击穿</b>：热点 key 用 Redisson 分布式锁互斥重建</li>
 *   <li><b>雪崩</b>：写入时对 TTL 施加随机抖动（±20%）</li>
 *   <li><b>一致性</b>：禁用短链时主动失效缓存</li>
 * </ul>
 *
 * @author shortlink-cloud
 */
public interface ShortLinkCacheManager {

    /**
     * 读取缓存的短链。
     *
     * @param shortCode 短码
     * @return 命中返回实体；未命中返回 null
     */
    ShortLink get(String shortCode);

    /**
     * 写入短链缓存（TTL 带随机抖动，且不超过短链自身过期时间）。
     *
     * @param link 短链实体
     */
    void put(ShortLink link);

    /**
     * 标记「该短码不存在」，用于防缓存穿透。
     *
     * <p>TTL 很短，避免短码刚被创建却一直被空值挡住。
     *
     * @param shortCode 短码
     */
    void putNull(String shortCode);

    /**
     * 布隆过滤器预判。
     *
     * @param shortCode 短码
     * @return false 表示「一定不存在」；true 表示「可能存在」，需继续查缓存/DB
     */
    boolean mightContain(String shortCode);

    /**
     * 把短码加入布隆过滤器。
     *
     * @param shortCode 短码
     */
    void addToBloom(String shortCode);

    /**
     * 失效指定短码的缓存（禁用、删除时调用）。
     *
     * @param shortCode 短码
     */
    void invalidate(String shortCode);

    /**
     * 应用启动时预热布隆过滤器。
     *
     * <p>布隆过滤器无法持久枚举已加入的元素，进程重启后内存中的位图会丢失，
     * 因此必须在启动时把库中已有短码重新灌入，否则存量短链会被误判为不存在。
     */
    void warmUpBloomFilter();

    /**
     * 布隆过滤器当前已加入的估算元素数量。
     *
     * @return 估算数量
     */
    long bloomCount();
}
