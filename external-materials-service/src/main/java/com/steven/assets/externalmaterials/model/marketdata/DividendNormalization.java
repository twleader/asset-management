package com.steven.assets.externalmaterials.model.marketdata;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Exact decimal and upstream-component validation shared by dividend producers and persistence. */
public final class DividendNormalization {
    public static final int VERSION = 2;
    public static final String NOMINAL = "NOMINAL_PER_EX_DATE";
    private DividendNormalization() {}

    public static BigDecimal exact(BigDecimal value) {
        if (value == null) return null;
        BigDecimal normalized = value.stripTrailingZeros();
        if (value.signum() < 0 || Math.max(0, normalized.scale()) > 18
                || Math.max(0, normalized.precision() - normalized.scale()) > 12)
            throw new IllegalArgumentException("DIVIDEND_DECIMAL_RANGE_EXCEEDED");
        return value;
    }

    public static String componentId(String source, String... identity) {
        StringBuilder input = new StringBuilder("DIVIDEND_COMPONENT_V2|");
        for (String token : identity) {
            if (token == null || token.isBlank()) return null;
            input.append(token.length()).append(':').append(token);
        }
        return source + ":" + sha256(input.toString());
    }

    public static boolean completeComponents(List<DividendData.DividendEvent> events) {
        Map<String, String> components = new HashMap<>();
        for (var event : events) {
            if (event.cashDividend() == null || event.stockDividend() == null) return false;
            String id = event.upstreamComponentId();
            if (id == null || id.isBlank() || id.length() > 128) return false;
            String signature = String.join("|", Objects.toString(event.exDividendDate(), "NULL"),
                    Objects.toString(event.exRightsDate(), "NULL"), decimal(event.cashDividend()),
                    decimal(event.stockDividend()), Objects.toString(event.cashPaymentDate(), "NULL"),
                    Objects.toString(event.stockPaymentDate(), "NULL"));
            String previous = components.putIfAbsent(id, signature);
            if (previous != null && !previous.equals(signature)) return false;
        }
        return true;
    }

    public static String decimal(BigDecimal value) {
        return value == null ? "NULL" : value.stripTrailingZeros().toPlainString();
    }

    public static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
