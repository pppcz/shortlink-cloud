package com.shortlink.cloud.util;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;

/**
 * 从请求中解析真实客户端 IP。
 *
 * <p>生产环境经过 Nginx 反代，必须按代理头取值；解析顺序遵循
 * X-Forwarded-For → X-Real-IP → Proxy-Client-IP → WL-Proxy-Client-IP → remoteAddr。
 * 注意 X-Forwarded-For 是逗号分隔列表，第一个才是原始客户端。
 *
 * @author shortlink-cloud
 */
public final class IpUtils {

    private static final String UNKNOWN = "unknown";
    private static final String LOCAL_IPV6 = "0:0:0:0:0:0:0:1";
    private static final String LOCAL_IPV4 = "127.0.0.1";

    private static final String[] HEADERS = {
            "X-Forwarded-For",
            "X-Real-IP",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_CLIENT_IP",
            "HTTP_X_FORWARDED_FOR"
    };

    private IpUtils() {
    }

    /**
     * 解析客户端 IP，永不返回 null。
     *
     * @param request 当前请求，可为 null
     * @return 客户端 IP；无法解析时返回 "unknown"
     */
    public static String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        for (String header : HEADERS) {
            String value = request.getHeader(header);
            if (isValid(value)) {
                return normalize(value);
            }
        }
        String remote = request.getRemoteAddr();
        if (StringUtils.isBlank(remote)) {
            return UNKNOWN;
        }
        return normalize(remote);
    }

    private static boolean isValid(String value) {
        return StringUtils.isNotBlank(value) && !UNKNOWN.equalsIgnoreCase(value.trim());
    }

    /** 取 X-Forwarded-For 的第一个地址，并把本机 IPv6 回环统一成 127.0.0.1。 */
    private static String normalize(String value) {
        String ip = value;
        int comma = ip.indexOf(',');
        if (comma > 0) {
            ip = ip.substring(0, comma);
        }
        ip = ip.trim();
        if (LOCAL_IPV6.equals(ip)) {
            return LOCAL_IPV4;
        }
        return ip;
    }
}
