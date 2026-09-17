package com.shortlink.cloud.util;

import com.shortlink.cloud.config.ShortLinkProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 签发与校验。
 *
 * <p>HS256 对称签名；密钥长度不足 32 字节时 jjwt 会直接拒绝，
 * 因此这里在初始化时补齐校验，避免运行期才报错。
 *
 * @author shortlink-cloud
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_USER_ID = "uid";
    private static final int MIN_SECRET_BYTES = 32;

    private final ShortLinkProperties properties;

    private volatile SecretKey cachedKey;

    /**
     * 签发 token。
     *
     * @param userId   用户 ID
     * @param username 用户名
     * @param role     角色
     * @return JWT 字符串
     */
    public String createToken(Long userId, String username, String role) {
        long now = System.currentTimeMillis();
        long expireSeconds = properties.getJwt().getExpireSeconds();
        Map<String, Object> claims = new HashMap<>(4);
        claims.put(CLAIM_USER_ID, userId);
        claims.put(CLAIM_USERNAME, username);
        claims.put(CLAIM_ROLE, role);
        return Jwts.builder()
                .claims(claims)
                .subject(String.valueOf(userId))
                .issuedAt(new Date(now))
                .expiration(new Date(now + expireSeconds * 1000L))
                .signWith(signingKey())
                .compact();
    }

    /**
     * 解析并校验 token。
     *
     * @param token JWT 字符串
     * @return 载荷；token 非法或过期时返回 null
     */
    public Claims parseToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return Jwts.parser()
                    .verifyWith(signingKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("JWT 校验失败: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 从 token 中取出用户 ID。
     *
     * @param claims 载荷
     * @return 用户 ID；缺失时返回 null
     */
    public Long getUserId(Claims claims) {
        if (claims == null) {
            return null;
        }
        Object uid = claims.get(CLAIM_USER_ID);
        if (uid instanceof Number number) {
            return number.longValue();
        }
        String subject = claims.getSubject();
        return subject == null ? null : Long.valueOf(subject);
    }

    /**
     * 从 token 中取出用户名。
     *
     * @param claims 载荷
     * @return 用户名；缺失时返回 null
     */
    public String getUsername(Claims claims) {
        return claims == null ? null : claims.get(CLAIM_USERNAME, String.class);
    }

    /**
     * 从 token 中取出角色。
     *
     * @param claims 载荷
     * @return 角色；缺失时返回 null
     */
    public String getRole(Claims claims) {
        return claims == null ? null : claims.get(CLAIM_ROLE, String.class);
    }

    /**
     * 按配置剥离 {@code Bearer } 前缀。
     *
     * @param headerValue 请求头原文
     * @return 纯 token；为空时返回 null
     */
    public String resolveToken(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        String prefix = properties.getJwt().getPrefix();
        if (prefix != null && !prefix.isEmpty() && headerValue.startsWith(prefix)) {
            return headerValue.substring(prefix.length()).trim();
        }
        return headerValue.trim();
    }

    private SecretKey signingKey() {
        SecretKey key = cachedKey;
        if (key == null) {
            synchronized (this) {
                key = cachedKey;
                if (key == null) {
                    byte[] bytes = properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
                    if (bytes.length < MIN_SECRET_BYTES) {
                        throw new IllegalStateException(
                                "shortlink.jwt.secret 至少需要 " + MIN_SECRET_BYTES + " 字节，当前 " + bytes.length);
                    }
                    key = Keys.hmacShaKeyFor(bytes);
                    cachedKey = key;
                }
            }
        }
        return key;
    }
}
