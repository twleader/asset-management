package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * BFF 自有的 RFC 8785 JCS 子集（Requirement 163／Task 454）。與 backend {@code SrppJcs} 同規格但刻意不共用
 * jar：object key 依 {@link String#compareTo}（UTF-16 code unit）排序、字串依 RFC 8785 逸出、無空白；
 * number 只允許絕對值 ≤ 2^53−1 的整數，其餘（浮點、超界整數）一律拒絕，因 SRPP context 的金額全部是
 * canonical Decimal 字串。
 */
public final class SrppJcs {

    private static final BigInteger MAX_SAFE_INTEGER = BigInteger.valueOf(9_007_199_254_740_991L);

    private SrppJcs() {}

    /** 將 JSON tree 轉成 JCS 字串；遇到不允許的 node 型別拋 {@link IllegalArgumentException}。 */
    public static String canonicalize(JsonNode node) {
        StringBuilder out = new StringBuilder();
        write(node, out);
        return out.toString();
    }

    /** UTF-8 SHA-256，小寫 hex。 */
    public static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node == null || node.isMissingNode()) {
            throw new IllegalArgumentException("JCS 不接受 missing node");
        }
        if (node.isObject()) {
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.sort(String::compareTo);
            out.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) out.append(',');
                writeString(keys.get(i), out);
                out.append(':');
                write(node.get(keys.get(i)), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) out.append(',');
                write(node.get(i), out);
            }
            out.append(']');
        } else if (node.isTextual()) {
            writeString(node.textValue(), out);
        } else if (node.isBoolean()) {
            out.append(node.booleanValue() ? "true" : "false");
        } else if (node.isNull()) {
            out.append("null");
        } else if (node.isIntegralNumber()) {
            BigInteger value = node.bigIntegerValue();
            if (value.abs().compareTo(MAX_SAFE_INTEGER) > 0) {
                throw new IllegalArgumentException("JCS 整數超出 2^53-1");
            }
            out.append(value);
        } else {
            throw new IllegalArgumentException("JCS 只接受整數 number，拒絕 " + node.getNodeType());
        }
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
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
