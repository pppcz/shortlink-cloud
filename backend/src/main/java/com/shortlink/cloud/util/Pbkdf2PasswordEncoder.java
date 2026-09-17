package com.shortlink.cloud.util;

import com.shortlink.cloud.common.BizException;
import com.shortlink.cloud.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * PBKDF2-HMAC-SHA256 密码编码器。
 *
 * <p>存储格式：{@code pbkdf2-sha256$<iterations>$<base64(salt)>$<base64(hash)>}
 *
 * <p>选型说明：PBKDF2 由 JDK 原生提供（{@code javax.crypto.SecretKeyFactory}），
 * 无需引入额外依赖；迭代次数默认 210000，符合 OWASP 对 PBKDF2-HMAC-SHA256 的建议下限。
 * 该实现对同一密码 + 盐可复现完全相同的字节，因此种子管理员的哈希可以在离线环境下
 * 用等价的 Python 实现生成并交叉验证（见 V2__seed_admin_user.sql 注释）。
 *
 * @author shortlink-cloud
 */
@Slf4j
public final class Pbkdf2PasswordEncoder {

    public static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    public static final String PREFIX = "pbkdf2-sha256";
    public static final int DEFAULT_ITERATIONS = 210_000;
    public static final int DEFAULT_SALT_BYTES = 16;
    public static final int DEFAULT_KEY_BITS = 256;

    private static final int SEPARATOR_COUNT = 4;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private Pbkdf2PasswordEncoder() {
    }

    /**
     * 生成新密码哈希（随机盐）。
     *
     * @param rawPassword 明文密码
     * @return 存储用哈希字符串
     */
    public static String encode(String rawPassword) {
        return encode(rawPassword, DEFAULT_ITERATIONS, DEFAULT_SALT_BYTES, DEFAULT_KEY_BITS);
    }

    /**
     * 生成新密码哈希（指定参数，便于测试与种子数据复现）。
     *
     * @param rawPassword 明文密码
     * @param iterations  迭代次数
     * @param saltBytes   盐长度（字节）
     * @param keyBits     派生密钥位数
     * @return 存储用哈希字符串
     */
    public static String encode(String rawPassword, int iterations, int saltBytes, int keyBits) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        byte[] salt = new byte[saltBytes];
        SECURE_RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(rawPassword, salt, iterations, keyBits);
        Base64.Encoder encoder = Base64.getEncoder();
        return PREFIX + "$" + iterations + "$" + encoder.encodeToString(salt) + "$" + encoder.encodeToString(hash);
    }

    /**
     * 校验明文密码与存储哈希是否匹配。
     *
     * @param rawPassword    明文密码
     * @param encodedPassword 存储的哈希字符串
     * @return 是否匹配；参数非法或格式错误一律返回 false（不抛异常，避免泄露信息）
     */
    public static boolean matches(String rawPassword, String encodedPassword) {
        if (rawPassword == null || rawPassword.isEmpty() || encodedPassword == null) {
            return false;
        }
        String[] parts = encodedPassword.split("\\$");
        if (parts.length != SEPARATOR_COUNT || !PREFIX.equals(parts[0])) {
            log.warn("密码哈希格式不合法，无法校验");
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(rawPassword, salt, iterations, expected.length * 8);
            // 固定时间比较，避免时序侧信道
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException ex) {
            log.warn("密码哈希解析失败: {}", ex.getMessage());
            return false;
        }
    }

    private static byte[] pbkdf2(String rawPassword, byte[] salt, int iterations, int keyBits) {
        PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(), salt, iterations, keyBits);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "当前 JDK 不支持 " + ALGORITHM, ex);
        } catch (InvalidKeySpecException ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "密码派生失败", ex);
        } finally {
            spec.clearPassword();
        }
    }
}
