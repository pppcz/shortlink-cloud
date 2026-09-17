package com.shortlink.cloud.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * IP 哈希工具。
 *
 * <p>UV 去重只需要「同一个 IP 得到同一个值」，不需要可逆，
 * 因此聚合表里存 MD5 而不是明文 IP，减少敏感信息沉淀。
 *
 * <p>说明：MD5 在这里只用于去重标识，不承担任何安全职责；
 * 选它是因为输出定长（32 字符）、计算快，适合放在跳转热路径上。
 *
 * @author shortlink-cloud
 */
public final class IpHasher {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private IpHasher() {
    }

    /**
     * 计算 IP 的 MD5 十六进制串。
     *
     * @param ip 客户端 IP，可为 null
     * @return 32 位小写十六进制串；ip 为空时返回 null
     */
    public static String hash(String ip) {
        if (ip == null || ip.isEmpty()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(ip.getBytes(StandardCharsets.UTF_8));
            char[] out = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                int v = bytes[i] & 0xFF;
                out[i * 2] = HEX[v >>> 4];
                out[i * 2 + 1] = HEX[v & 0x0F];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException ex) {
            // JDK 必然提供 MD5，正常不会走到这里
            throw new IllegalStateException("当前 JDK 不支持 MD5", ex);
        }
    }
}
