package com.steven.assets.srpp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Requirement 163／Task 452.3：RFC 8785 JSON Canonicalization Scheme（本 API 用得到的子集）。
 *
 * <p>只接受 object／array／string／boolean／null 與絕對值 ≤ 2^53−1 的整數。RFC 8785 以 IEEE-754 double
 * 序列化數字，超出安全整數範圍或非整數無法保證跨語言一致，因此一律拒絕；本 API 的金額全部以
 * canonical Decimal 字串傳輸（{@link SrppDecimal}）。object key 依 {@link String#compareTo}（UTF-16 code unit）
 * 排序，輸出無空白。
 */
public final class SrppJcs {
    private SrppJcs() {}

    private static final BigInteger MAX_SAFE = BigInteger.valueOf(9_007_199_254_740_991L);

    /** 嚴格解析：重複 key 直接拒絕；浮點字面值解析為 double，由 {@link #canonicalize} 拒絕。 */
    private static final ObjectMapper STRICT = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** 以嚴格規則解析 JSON 文字（重複 key、尾端多餘 token 皆拒絕）。 */
    public static JsonNode parseStrict(String json) {
        if (json == null) throw new IllegalArgumentException("JSON_NULL");
        try {
            JsonNode node = STRICT.readTree(json);
            if (node == null || node.isMissingNode()) throw new IllegalArgumentException("JSON_EMPTY");
            return node;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON_INVALID", e);
        }
    }

    public static String canonicalize(JsonNode node) {
        StringBuilder out = new StringBuilder();
        write(node, out);
        return out.toString();
    }

    public static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** {@code sha256Hex(canonicalize(node))} 的簡寫。 */
    public static String hash(JsonNode node) {
        return sha256Hex(canonicalize(node));
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node == null || node.isMissingNode()) throw new IllegalArgumentException("JCS_MISSING_NODE");
        if (node.isNull()) {
            out.append("null");
        } else if (node.isBoolean()) {
            out.append(node.booleanValue() ? "true" : "false");
        } else if (node.isTextual()) {
            writeString(node.textValue(), out);
        } else if (node.isNumber()) {
            out.append(integerText(node));
        } else if (node.isArray()) {
            out.append('[');
            boolean first = true;
            for (JsonNode item : node) {
                if (!first) out.append(',');
                first = false;
                write(item, out);
            }
            out.append(']');
        } else if (node.isObject()) {
            List<String> keys = new ArrayList<>();
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) keys.add(names.next());
            keys.sort(String::compareTo);
            out.append('{');
            boolean first = true;
            for (String key : keys) {
                if (!first) out.append(',');
                first = false;
                writeString(key, out);
                out.append(':');
                write(node.get(key), out);
            }
            out.append('}');
        } else {
            throw new IllegalArgumentException("JCS_UNSUPPORTED_NODE");
        }
    }

    private static String integerText(JsonNode node) {
        if (node.isFloatingPointNumber() && !node.isBigDecimal()) {
            throw new IllegalArgumentException("JCS_FLOAT_NOT_ALLOWED");
        }
        BigInteger value;
        if (node.isBigDecimal()) {
            BigDecimal decimal = node.decimalValue();
            try {
                value = decimal.toBigIntegerExact();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("JCS_NON_INTEGER_NOT_ALLOWED");
            }
        } else if (node.isIntegralNumber()) {
            value = node.bigIntegerValue();
        } else {
            throw new IllegalArgumentException("JCS_UNSUPPORTED_NUMBER");
        }
        if (value.abs().compareTo(MAX_SAFE) > 0) throw new IllegalArgumentException("JCS_INTEGER_OUT_OF_RANGE");
        return value.toString();
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00").append(Character.forDigit((c >> 4) & 0xF, 16))
                                .append(Character.forDigit(c & 0xF, 16));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
