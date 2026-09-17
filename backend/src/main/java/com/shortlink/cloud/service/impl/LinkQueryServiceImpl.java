package com.shortlink.cloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shortlink.cloud.dto.LinkPageQuery;
import com.shortlink.cloud.dto.ShortLinkVO;
import com.shortlink.cloud.entity.ShortLink;
import com.shortlink.cloud.mapper.ShortLinkMapper;
import com.shortlink.cloud.service.LinkConverter;
import com.shortlink.cloud.service.LinkQueryService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 短链分页查询实现。
 *
 * <p>查询条件全部使用 Lambda 条件构造器，不走字符串拼接，天然避免 SQL 注入。
 *
 * @author shortlink-cloud
 */
@Service
@RequiredArgsConstructor
public class LinkQueryServiceImpl implements LinkQueryService {

    private final ShortLinkMapper shortLinkMapper;
    private final LinkConverter linkConverter;

    @Override
    public IPage<ShortLinkVO> page(LinkPageQuery query) {
        LambdaQueryWrapper<ShortLink> wrapper = Wrappers.<ShortLink>lambdaQuery()
                .eq(StringUtils.isNotBlank(query.getShortCode()), ShortLink::getShortCode, query.getShortCode())
                .like(StringUtils.isNotBlank(query.getOriginalUrl()), ShortLink::getOriginalUrl, query.getOriginalUrl())
                .like(StringUtils.isNotBlank(query.getTitle()), ShortLink::getTitle, query.getTitle())
                .eq(query.getStatus() != null, ShortLink::getStatus, query.getStatus())
                .eq(query.getCreatorId() != null, ShortLink::getCreatorId, query.getCreatorId())
                .orderByDesc(ShortLink::getCreateTime);

        Page<ShortLink> page = Page.of(query.getCurrent(), query.getSize());
        IPage<ShortLink> entityPage = shortLinkMapper.selectPage(page, wrapper);

        IPage<ShortLinkVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        List<ShortLinkVO> records = entityPage.getRecords().stream()
                .map(linkConverter::toVO)
                .toList();
        voPage.setRecords(records);
        return voPage;
    }
}
