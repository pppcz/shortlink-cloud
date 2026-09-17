package com.shortlink.cloud.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LocalSequenceShortCodeGenerator} 单元测试。
 *
 * @author shortlink-cloud
 */
class LocalSequenceShortCodeGeneratorTest {

    private final LocalSequenceShortCodeGenerator generator = new LocalSequenceShortCodeGenerator();

    @Test
    @DisplayName("批量生成不重复，且全部是合法 Base62")
    void shouldGenerateUniqueBase62Codes() {
        int count = 20_000;
        Set<String> codes = new HashSet<>(count);
        for (int i = 0; i < count; i++) {
            String code = generator.nextCode();
            assertThat(code).matches("[0-9A-Za-z]+");
            codes.add(code);
        }
        assertThat(codes).hasSize(count);
    }

    @Test
    @DisplayName("生成器名称为 local-sequence")
    void shouldExposeName() {
        assertThat(generator.name()).isEqualTo("local-sequence");
    }

    @Test
    @DisplayName("短码可被 Base62 无损解码回数值")
    void shouldBeDecodable() {
        String code = generator.nextCode();
        assertThat(com.shortlink.cloud.util.Base62.decode(code)).isPositive();
    }
}
