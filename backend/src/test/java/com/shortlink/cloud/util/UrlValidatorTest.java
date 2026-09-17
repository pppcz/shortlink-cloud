package com.shortlink.cloud.util;

import com.shortlink.cloud.common.BizException;
import com.shortlink.cloud.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link UrlValidator} 单元测试。
 *
 * @author shortlink-cloud
 */
class UrlValidatorTest {

    @Test
    @DisplayName("正常 http/https 链接原样通过")
    void shouldAcceptHttpAndHttps() {
        assertThat(UrlValidator.validateAndNormalize("https://example.com/a/b?c=1"))
                .isEqualTo("https://example.com/a/b?c=1");
        assertThat(UrlValidator.validateAndNormalize("http://example.com"))
                .isEqualTo("http://example.com");
    }

    @Test
    @DisplayName("缺少协议时自动补 https")
    void shouldPrependScheme() {
        assertThat(UrlValidator.validateAndNormalize("example.com/path"))
                .isEqualTo("https://example.com/path");
    }

    @Test
    @DisplayName("首尾空白被裁剪")
    void shouldTrimWhitespace() {
        assertThat(UrlValidator.validateAndNormalize("  https://example.com  "))
                .isEqualTo("https://example.com");
    }

    @ParameterizedTest(name = "拒绝危险协议: {0}")
    @ValueSource(strings = {
            "javascript:alert(1)",
            "data:text/html;base64,PHNjcmlwdD4=",
            "file:///etc/passwd",
            "ftp://example.com"
    })
    @DisplayName("非 http/https 协议一律拒绝")
    void shouldRejectDangerousSchemes(String url) {
        assertThatThrownBy(() -> UrlValidator.validateAndNormalize(url))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.URL_INVALID);
    }

    @ParameterizedTest(name = "拒绝内网地址: {0}")
    @ValueSource(strings = {
            "http://localhost/admin",
            "http://127.0.0.1:8080/x",
            "http://10.0.0.5/x",
            "http://192.168.1.1/x",
            "http://172.16.0.1/x",
            "http://172.31.255.255/x",
            "http://0.0.0.0/x",
            "http://[::1]/x"
    })
    @DisplayName("回环 / 私有网段地址拒绝，防 SSRF 与自引用环")
    void shouldRejectLoopbackAndPrivate(String url) {
        assertThatThrownBy(() -> UrlValidator.validateAndNormalize(url))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.URL_BLOCKED);
    }

    @ParameterizedTest(name = "允许公网地址: {0}")
    @ValueSource(strings = {
            "http://8.8.8.8/x",
            "http://172.32.0.1/x",
            "http://172.15.0.1/x",
            "http://192.169.0.1/x"
    })
    @DisplayName("公网地址正常通过（私有网段边界不能误伤）")
    void shouldAcceptPublicAddresses(String url) {
        assertThat(UrlValidator.validateAndNormalize(url)).isEqualTo(url);
    }

    @Test
    @DisplayName("空链接抛参数异常")
    void shouldRejectBlank() {
        assertThatThrownBy(() -> UrlValidator.validateAndNormalize("   "))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.URL_INVALID);
    }

    @Test
    @DisplayName("超长链接抛参数异常")
    void shouldRejectTooLong() {
        String longUrl = "https://example.com/" + "a".repeat(UrlValidator.MAX_URL_LENGTH);
        assertThatThrownBy(() -> UrlValidator.validateAndNormalize(longUrl))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("长度");
    }

    @Test
    @DisplayName("无有效域名抛参数异常")
    void shouldRejectHostless() {
        assertThatThrownBy(() -> UrlValidator.validateAndNormalize("https://"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.URL_INVALID);
    }
}
