package com.shortlink.cloud.util;

import com.shortlink.cloud.common.Constants;

/**
 * Base62 编解码。
 *
 * <p>短码使用 Base62（0-9A-Za-z），相比 Base64 不含 {@code + / =}，
 * 放进 URL 路径无需再转义。
 *
 * @author shortlink-cloud
 */
public final class Base62 {

    private static final char[] CHARS = Constants.BASE62_CHARS.toCharArray();
    private static final int BASE = CHARS.length;

    /** 反查表，避免每次编码都做 indexOf。 */
    private static final int[] LOOKUP = new int[128];

    static {
        for (int i = 0; i < LOOKUP.length; i++) {
            LOOKUP[i] = -1;
        }
        for (int i = 0; i < BASE; i++) {
            LOOKUP[CHARS[i]] = i;
        }
    }

    private Base62() {
    }

    /**
     * 将非负整数编码为 Base62。
     *
     * @param value 非负整数
     * @return Base62 字符串；value 为 0 时返回 "0"
     */
    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Base62 只支持非负整数");
        }
        if (value == 0) {
            return "0";
        }
        char[] buf = new char[11];
        int pos = buf.length;
        long remaining = value;
        while (remaining > 0) {
            buf[--pos] = CHARS[(int) (remaining % BASE)];
            remaining /= BASE;
        }
        return new String(buf, pos, buf.length - pos);
    }

    /**
     * 将 Base62 字符串解码为 long。
     *
     * @param text Base62 字符串
     * @return 解码结果
     * @throws IllegalArgumentException 含非法字符或溢出时抛出
     */
    public static long decode(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("Base62 字符串不能为空");
        }
        long result = 0L;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int digit = c < 128 ? LOOKUP[c] : -1;
            if (digit < 0) {
                throw new IllegalArgumentException("非法 Base62 字符: " + c);
            }
            long next = result * BASE + digit;
            if (next < result) {
                throw new IllegalArgumentException("Base62 数值溢出: " + text);
            }
            result = next;
        }
        return result;
    }

    /**
     * 编码后不足指定长度时左补字符集第一个字符（'0'）。
     *
     * @param value  非负整数
     * @param length 目标长度
     * @return 定长 Base62 字符串
     */
    public static String encodeFixed(long value, int length) {
        String encoded = encode(value);
        if (encoded.length() >= length) {
            return encoded;
        }
        StringBuilder sb = new StringBuilder(length);
        for (int i = encoded.length(); i < length; i++) {
            sb.append(CHARS[0]);
        }
        return sb.append(encoded).toString();
    }
}
