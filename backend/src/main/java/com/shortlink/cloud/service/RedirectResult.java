package com.shortlink.cloud.service;

/**
 * 跳转解析结果。
 *
 * @param link        命中的短链（可能为 null，表示未命中）
 * @param targetUrl   最终跳转目标
 * @param httpStatus  HTTP 状态码：302 正常跳转，404 不存在，410 已失效
 * @author shortlink-cloud
 */
public record RedirectResult(com.shortlink.cloud.entity.ShortLink link, String targetUrl, int httpStatus) {

    public static final int STATUS_FOUND = 302;
    public static final int STATUS_NOT_FOUND = 404;
    public static final int STATUS_GONE = 410;

    /** 未找到短码。 */
    public static RedirectResult notFound() {
        return new RedirectResult(null, null, STATUS_NOT_FOUND);
    }

    /** 短链存在但已禁用或过期。 */
    public static RedirectResult gone(com.shortlink.cloud.entity.ShortLink link) {
        return new RedirectResult(link, null, STATUS_GONE);
    }

    /** 正常跳转。 */
    public static RedirectResult found(com.shortlink.cloud.entity.ShortLink link) {
        return new RedirectResult(link, link.getOriginalUrl(), STATUS_FOUND);
    }

    /** 是否命中可跳转的短链。 */
    public boolean isFound() {
        return httpStatus == STATUS_FOUND;
    }
}
