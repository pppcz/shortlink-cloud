package com.shortlink.cloud.service;

import com.shortlink.cloud.dto.CreateLinkRequest;
import com.shortlink.cloud.dto.CreateLinkResponse;
import com.shortlink.cloud.entity.ShortLink;

/**
 * 短链核心服务：创建与跳转解析。
 *
 * @author shortlink-cloud
 */
public interface ShortLinkService {

    /**
     * 创建短链。
     *
     * @param request  创建请求
     * @param creatorId 创建人 ID，匿名创建传 null
     * @param creatorIp 创建人 IP
     * @return 创建结果
     */
    CreateLinkResponse createLink(CreateLinkRequest request, Long creatorId, String creatorIp);

    /**
     * 解析短码并返回跳转结果。
     *
     * @param shortCode 短码
     * @param clientIp  访问者 IP，用于 UV 统计
     * @param userAgent User-Agent，用于 UA 维度统计
     * @return 跳转结果，永不返回 null
     */
    RedirectResult resolve(String shortCode, String clientIp, String userAgent);

    /**
     * 按 ID 禁用短链。
     *
     * @param id 短链 ID
     */
    void disable(Long id);

    /**
     * 按 ID 查询短链（不存在时抛业务异常）。
     *
     * @param id 短链 ID
     * @return 短链实体
     */
    ShortLink getByIdOrThrow(Long id);
}
