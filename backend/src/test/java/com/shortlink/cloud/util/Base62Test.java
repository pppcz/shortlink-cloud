package com.shortlink.cloud.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Base62} 单元测试。
 *
 * @author shortlink-cloud
 */
class Base62Test {

    @Test
    @DisplayName("0 编码为 \"0\"，可原样解码")
    void shouldEncodeZero() {
        assertThat(Base62.encode(0)).isEqualTo("0");
        assertThat(Base62.decode("0")).isZero();
    }

    @ParameterizedTest(name = "round-trip {0}")
    @ValueSource(longs = {1, 61, 62, 63, 3843, 3844, 1_000_000, 123_456_789_012L, Long.MAX_VALUE})
    @DisplayName("编码后能无损解码回原值")
    void shouldRoundTrip(long value) {
        String encoded = Base62.encode(value);
        assertThat(Base62.decode(encoded)).isEqualTo(value);
        assertThat(encoded).matches("[0-9A-Za-z]+");
    }

    @Test
    @DisplayName("字符集顺序为 0-9A-Za-z，62 进一位")
    void shouldUseExpectedAlphabet() {
        assertThat(Base62.encode(10)).isEqualTo("A");
        assertThat(Base62.encode(35)).isEqualTo("Z");
        assertThat(Base62.encode(36)).isEqualTo("a");
        assertThat(Base62.encode(61)).isEqualTo("z");
        assertThat(Base62.encode(62)).isEqualTo("10");
    }

    @Test
    @DisplayName("encodeFixed 左补 '0' 至指定长度")
    void shouldPadToFixedLength() {
        assertThat(Base62.encodeFixed(1, 7)).isEqualTo("0000001");
        assertThat(Base62.encodeFixed(0, 4)).isEqualTo("0000");
        // 超过目标长度时不做截断，保证不丢信息
        assertThat(Base62.encodeFixed(123_456_789L, 2)).isEqualTo(Base62.encode(123_456_789L));
    }

    @Test
    @DisplayName("负数与非 Base62 字符应抛异常")
    void shouldRejectInvalidInput() {
        assertThatThrownBy(() -> Base62.encode(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非负整数");
        assertThatThrownBy(() -> Base62.decode("abc!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法 Base62 字符");
        assertThatThrownBy(() -> Base62.decode(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }
}
