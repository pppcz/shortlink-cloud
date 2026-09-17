package com.shortlink.cloud.util;

import com.shortlink.cloud.common.BizException;
import com.shortlink.cloud.common.ErrorCode;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * 原始链接校验与规范化。
 *
 * <p>只允许 http / https，显式拒绝 javascript:、data: 等危险协议，
 * 防止把短链服务变成开放重定向或 XSS 载体。
 *
 * @author shortlink-cloud
 */
public final class UrlValidator {

    /** 原始链接最大长度，与 t_short_link.original_url 列宽保持一致。 */
    public static final int MAX_URL_LENGTH = 2048;

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private UrlValidator() {
    }

    /**
     * 校验并规范化链接。
     *
     * @param rawUrl 用户提交的原始链接
     * @return 规范化后的链接（补全协议、去首尾空白）
     * @throws BizException 链接非法时抛出
     */
    public static String validateAndNormalize(String rawUrl) {
        if (StringUtils.isBlank(rawUrl)) {
            throw new BizException(ErrorCode.URL_INVALID, "原始链接不能为空");
        }
        String url = rawUrl.trim();

        // 先拦截显式的危险协议。这一步必须在「补 https」之前，
        // 否则 javascript:alert(1) 会被补成 https://javascript:alert(1)
        // 而被误判为一个合法链接。
        int colon = url.indexOf(':');
        if (colon > 0) {
            String schemeCandidate = url.substring(0, colon).toLowerCase(Locale.ROOT);
            if (schemeCandidate.chars().allMatch(UrlValidator::isSchemeChar)) {
                if (!ALLOWED_SCHEMES.contains(schemeCandidate)) {
                    throw new BizException(ErrorCode.URL_INVALID, "仅支持 http / https 协议的链接");
                }
            }
        }

        // 未写协议时默认补 https，提升易用性
        if (!url.contains("://")) {
            url = "https://" + url;
        }
        if (url.length() > MAX_URL_LENGTH) {
            throw new BizException(ErrorCode.URL_INVALID,
                    "原始链接长度不能超过 " + MAX_URL_LENGTH + " 个字符");
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException ex) {
            throw new BizException(ErrorCode.URL_INVALID, "原始链接格式不合法");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new BizException(ErrorCode.URL_INVALID, "仅支持 http / https 协议的链接");
        }
        String host = uri.getHost();
        if (StringUtils.isBlank(host)) {
            throw new BizException(ErrorCode.URL_INVALID, "原始链接缺少有效的域名");
        }
        // 拒绝指向本机 / 内网的回环地址，避免 SSRF 与自引用环
        if (isLoopbackOrPrivate(host)) {
            throw new BizException(ErrorCode.URL_BLOCKED, "不允许把内网或本机地址作为短链目标");
        }
        return uri.toString();
    }

    /**
     * 判断 host 是否为回环 / 私有网段。
     *
     * <p>覆盖 localhost、127.0.0.0/8、10/8、172.16/12、192.168/16 以及 IPv6 回环。
     *
     * @param host 域名或 IP
     * @return true 表示属于内网或本机
     */
    public static boolean isLoopbackOrPrivate(String host) {
        if (StringUtils.isBlank(host)) {
            return true;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(h) || h.endsWith(".localhost") || "::1".equals(h) || "0:0:0:0:0:0:0:1".equals(h)) {
            return true;
        }
        if ("[::1]".equals(h)) {
            return true;
        }
        // 形如 127.x.x.x / 10.x.x.x / 192.168.x.x / 172.16-31.x.x
        String[] parts = h.split("\\.");
        if (parts.length == 4 && isNumeric(parts)) {
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            if (first == 127 || first == 10 || first == 0) {
                return true;
            }
            if (first == 192 && second == 168) {
                return true;
            }
            return first == 172 && second >= 16 && second <= 31;
        }
        return false;
    }

    private static boolean isSchemeChar(int c) {
        return Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.';
    }

    private static boolean isNumeric(String[] parts) {
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }
}
