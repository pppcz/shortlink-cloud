package com.shortlink.cloud.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Pbkdf2PasswordEncoder} 单元测试。
 *
 * <p>关键固定用例：种子管理员（admin / admin123）的哈希写死在
 * {@code V2__seed_admin_user.sql} 中，这里校验同一组参数能被本实现正确验签，
 * 否则全新部署后管理员将无法登录。
 *
 * @author shortlink-cloud
 */
class Pbkdf2PasswordEncoderTest {

    /**
     * 与 V2__seed_admin_user.sql 中写入的哈希完全一致。
     * 由等价的 Python 实现（hashlib.pbkdf2_hmac）生成并交叉验证。
     */
    private static final String SEED_ADMIN_HASH =
            "pbkdf2-sha256$210000$Wks8LR4PECEyQ1RldoeYqQ==$RfAgy57mSojqBpfe9GZYHy5atqpysHk3JOmMagNFkKc=";

    private static final String SEED_ADMIN_PASSWORD = "admin123";

    @Test
    @DisplayName("种子管理员哈希可被本实现验签通过")
    void shouldVerifySeedAdminHash() {
        assertThat(Pbkdf2PasswordEncoder.matches(SEED_ADMIN_PASSWORD, SEED_ADMIN_HASH)).isTrue();
    }

    @Test
    @DisplayName("错误密码不能通过种子哈希校验")
    void shouldRejectWrongPasswordForSeedHash() {
        assertThat(Pbkdf2PasswordEncoder.matches("admin124", SEED_ADMIN_HASH)).isFalse();
        assertThat(Pbkdf2PasswordEncoder.matches("Admin123", SEED_ADMIN_HASH)).isFalse();
        assertThat(Pbkdf2PasswordEncoder.matches("", SEED_ADMIN_HASH)).isFalse();
    }

    @Test
    @DisplayName("encode 产出的哈希可被 matches 验签，且每次盐不同")
    void shouldEncodeAndMatch() {
        String first = Pbkdf2PasswordEncoder.encode("s3cret-password");
        String second = Pbkdf2PasswordEncoder.encode("s3cret-password");

        assertThat(first).startsWith("pbkdf2-sha256$210000$");
        // 随机盐：两次编码结果必须不同，但都能通过校验
        assertThat(first).isNotEqualTo(second);
        assertThat(Pbkdf2PasswordEncoder.matches("s3cret-password", first)).isTrue();
        assertThat(Pbkdf2PasswordEncoder.matches("s3cret-password", second)).isTrue();
        assertThat(Pbkdf2PasswordEncoder.matches("s3cret-passwore", first)).isFalse();
    }

    @Test
    @DisplayName("哈希格式正确，且满足 JDK 提供的 PBKDF2 参数约定")
    void shouldProduceExpectedFormat() {
        String encoded = Pbkdf2PasswordEncoder.encode("s3cret-password");
        String[] parts = encoded.split("\\$");

        assertThat(parts).hasSize(4);
        assertThat(parts[0]).isEqualTo(Pbkdf2PasswordEncoder.PREFIX);
        assertThat(parts[1]).isEqualTo(String.valueOf(Pbkdf2PasswordEncoder.DEFAULT_ITERATIONS));
        // 16 字节盐 → base64 长度 24（含一个 '='）
        assertThat(java.util.Base64.getDecoder().decode(parts[2])).hasSize(16);
        // 256 位派生密钥 → 32 字节
        assertThat(java.util.Base64.getDecoder().decode(parts[3])).hasSize(32);
    }

    @Test
    @DisplayName("哈希格式非法时返回 false 而不抛异常")
    void shouldReturnFalseOnMalformedHash() {
        assertThat(Pbkdf2PasswordEncoder.matches("x", "not-a-hash")).isFalse();
        assertThat(Pbkdf2PasswordEncoder.matches("x", "bcrypt$10$abc$def")).isFalse();
        assertThat(Pbkdf2PasswordEncoder.matches("x", "pbkdf2-sha256$abc$zz$zz")).isFalse();
        assertThat(Pbkdf2PasswordEncoder.matches("x", null)).isFalse();
        assertThat(Pbkdf2PasswordEncoder.matches(null, SEED_ADMIN_HASH)).isFalse();
    }

    @Test
    @DisplayName("空密码不允许编码")
    void shouldRejectEmptyPasswordOnEncode() {
        assertThatThrownBy(() -> Pbkdf2PasswordEncoder.encode(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
