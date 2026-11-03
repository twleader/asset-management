package com.steven.assets.service.srpp.us;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.srpp.SrppJcs;
import java.math.BigDecimal;
import java.util.*;

/** Python json.dumps(sort_keys=True, ensure_ascii=False, allow_nan=False).
 * This legacy observation digest deliberately differs from the wire JCS digest. */
public final class UsPythonJson {
    private UsPythonJson() {}
    public static String hash(JsonNode value) { return SrppJcs.sha256Hex(write(value)); }
    public static String write(JsonNode value) {
        if (value == null || value.isNull()) return "null";
        if (value.isObject()) {
            List<String> keys = new ArrayList<>(); value.fieldNames().forEachRemaining(keys::add); keys.sort(String::compareTo);
            StringJoiner out = new StringJoiner(", ", "{", "}");
            for (String key : keys) out.add(quote(key) + ": " + write(value.get(key)));
            return out.toString();
        }
        if (value.isArray()) { StringJoiner out = new StringJoiner(", ", "[", "]"); for (JsonNode child : value) out.add(write(child)); return out.toString(); }
        if (value.isTextual()) return quote(value.asText());
        if (value.isBoolean() || value.isIntegralNumber()) return value.toString();
        if (value.isFloatingPointNumber()) {
            double number = value.doubleValue();
            if (!Double.isFinite(number)) throw new IllegalArgumentException("PYTHON_JSON_NONFINITE");
            if (number == 0) return Double.doubleToRawLongBits(number) < 0 ? "-0.0" : "0.0";
            String raw = Double.toString(number).toLowerCase(Locale.ROOT);
            BigDecimal decimal = BigDecimal.valueOf(number);
            double magnitude = Math.abs(number);
            if (magnitude >= 1e-4 && magnitude < 1e16) {
                String plain = decimal.stripTrailingZeros().toPlainString();
                return plain.indexOf('.') < 0 ? plain + ".0" : plain;
            }
            int exponent = decimal.precision() - decimal.scale() - 1;
            String mantissa = decimal.movePointLeft(exponent).stripTrailingZeros().toPlainString();
            return mantissa + "e" + (exponent < 0 ? "-" : "+") + String.format(Locale.ROOT, "%02d", Math.abs(exponent));
        }
        throw new IllegalArgumentException("PYTHON_JSON_UNSUPPORTED");
    }
    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b"); case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> { if (c < 32) out.append(String.format(Locale.ROOT, "\\u%04x", (int)c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }
}
