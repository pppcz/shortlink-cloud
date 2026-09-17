package com.shortlink.cloud.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.shortlink.cloud.dto.LinkPageQuery;
import com.shortlink.cloud.dto.ShortLinkVO;

/**
 * 短链查询服务（管理后台）。
 *
 * @author shortlink-cloud
 */
public interface LinkQueryService {

    /**
     * 分页查询短链。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    IPage<ShortLinkVO> page(LinkPageQuery query);
}
