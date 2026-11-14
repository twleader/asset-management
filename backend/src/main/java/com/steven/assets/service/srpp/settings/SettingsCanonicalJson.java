package com.steven.assets.service.srpp.settings;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.erdtman.jcs.JsonCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Full RFC 8785 for settings only; historical SRPP's integer-only JCS is unchanged. */
public final class SettingsCanonicalJson {
    private SettingsCanonicalJson() {}
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    public static JsonNode parse(String raw) {
        if (raw == null || raw.length() > 4_000_000) throw new IllegalArgumentException("JSON_INVALID");
        try {
            JsonNode n = JSON.readTree(raw);
            validate(n);
            return n;
        } catch (Exception e) { throw new IllegalArgumentException("JSON_INVALID", e); }
    }
    public static String canonical(JsonNode n) {
        validate(n);
        try {
            // This library accepts object roots. A single-property envelope permits every
            // RFC 8785 JSON value without implementing a second number/string serializer.
            String encoded=new JsonCanonicalizer("{\"v\":"+JSON.writeValueAsString(n)+"}").getEncodedString();
            return encoded.substring(5,encoded.length()-1);
        }
        catch (Exception e) { throw new IllegalArgumentException("JSON_INVALID", e); }
    }
    public static <T> T bind(JsonNode node,Class<T> type) {
        try{return JSON.treeToValue(node,type);}catch(Exception invalid){throw new IllegalArgumentException("JSON_DTO_INVALID",invalid);}
    }
    public static JsonNode tree(Object value){return JSON.valueToTree(value);}
    public static String hash(JsonNode n) { return bytesHash(canonical(n).getBytes(StandardCharsets.UTF_8)); }
    public static String bytesHash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static void validate(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isBinary() || n.isPojo()) throw new IllegalArgumentException("JSON_INVALID");
        if (n.isNumber() && (!Double.isFinite(n.doubleValue()) || n.isIntegralNumber() && n.bigIntegerValue().abs().compareTo(java.math.BigInteger.valueOf(9_007_199_254_740_991L)) > 0)) throw new IllegalArgumentException("JSON_NUMBER_INVALID");
        if (n.isTextual()) unicode(n.textValue());
        if (n.isObject()) n.fieldNames().forEachRemaining(SettingsCanonicalJson::unicode);
        if (n.isContainerNode()) n.forEach(SettingsCanonicalJson::validate);
    }
    private static void unicode(String s) {
        for (int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i>=s.length() || !Character.isLowSurrogate(s.charAt(i))) throw new IllegalArgumentException("JSON_UNICODE_INVALID");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("JSON_UNICODE_INVALID");
        }
    }
}
