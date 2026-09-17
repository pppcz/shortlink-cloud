package com.shortlink.cloud.service;

import com.shortlink.cloud.config.ShortLinkProperties;
import com.shortlink.cloud.dto.CreateLinkRequest;
import com.shortlink.cloud.dto.CreateLinkResponse;
import com.shortlink.cloud.dto.ShortLinkVO;
import com.shortlink.cloud.entity.ShortLink;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 短链实体与 DTO 的转换。
 *
 * @author shortlink-cloud
 */
@Component
@RequiredArgsConstructor
public class LinkConverter {

    private final ShortLinkProperties properties;

    /**
     * 拼装完整短链地址。
     *
     * @param shortCode 短码
     * @return 形如 http://host:port/abc1234
     */
    public String buildShortUrl(String shortCode) {
        String domain = properties.getDomain();
        if (domain == null || domain.isEmpty()) {
            return "/" + shortCode;
        }
        return domain.endsWith("/") ? domain + shortCode : domain + "/" + shortCode;
    }

    /**
     * 实体转创建响应。
     *
     * @param entity 短链实体
     * @return 创建响应
     */
    public CreateLinkResponse toCreateResponse(ShortLink entity) {
        CreateLinkResponse response = new CreateLinkResponse();
        response.setId(entity.getId());
        response.setShortCode(entity.getShortCode());
        response.setShortUrl(buildShortUrl(entity.getShortCode()));
        response.setOriginalUrl(entity.getOriginalUrl());
        response.setTitle(entity.getTitle());
        response.setExpireTime(entity.getExpireTime());
        response.setCreateTime(entity.getCreateTime());
        return response;
    }

    /**
     * 实体转列表项。
     *
     * @param entity 短链实体
     * @return 列表项
     */
    public ShortLinkVO toVO(ShortLink entity) {
        ShortLinkVO vo = new ShortLinkVO();
        vo.setId(entity.getId());
        vo.setShortCode(entity.getShortCode());
        vo.setShortUrl(buildShortUrl(entity.getShortCode()));
        vo.setOriginalUrl(entity.getOriginalUrl());
        vo.setTitle(entity.getTitle());
        vo.setStatus(entity.getStatus());
        vo.setExpireTime(entity.getExpireTime());
        vo.setPv(entity.getPv() == null ? 0L : entity.getPv());
        vo.setUv(entity.getUv() == null ? 0L : entity.getUv());
        vo.setLastAccess(entity.getLastAccess());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }

    /**
     * 依据请求构造实体（不含 ID 与时间，交由 MyBatis-Plus 填充）。
     *
     * @param request   创建请求
     * @param shortCode 已确定的短码
     * @param creatorId 创建人 ID，可为 null
     * @param creatorIp 创建人 IP
     * @return 短链实体
     */
    public ShortLink toEntity(CreateLinkRequest request, String shortCode, Long creatorId, String creatorIp) {
        ShortLink entity = new ShortLink();
        entity.setShortCode(shortCode);
        entity.setOriginalUrl(request.getOriginalUrl());
        entity.setTitle(request.getTitle());
        entity.setCreatorId(creatorId);
        entity.setCreatorIp(creatorIp);
        entity.setStatus(1);
        entity.setPv(0L);
        entity.setUv(0L);
        if (request.getExpireDays() != null && request.getExpireDays() > 0) {
            entity.setExpireTime(java.time.LocalDateTime.now().plusDays(request.getExpireDays()));
        }
        return entity;
    }
}
