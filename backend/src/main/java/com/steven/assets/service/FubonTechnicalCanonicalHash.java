package com.steven.assets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

/**
 * The immutable Task408 technical-fact identity shared by PostgreSQL and the
 * v2 Redis projection.
 *
 * <p>This is intentionally owned by the business reader as well as the
 * external writer.  A syntactically plausible {@code contentHash} is not
 * evidence that a Redis document or historical database row still represents
 * the declared profile parameters and payload.  Keep the grammar byte-for-byte
 * aligned with {@code FubonCanonicalHash} in external-materials-service.</p>
 */
final class FubonTechnicalCanonicalHash {
    private static final ObjectMapper CANONICAL_JSON = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private FubonTechnicalCanonicalHash() {}

    static String technical(String profileId, LocalDate sourceDate,
                            Map<String, Object> parameters, Map<String, String> payload) {
        if (profileId == null || sourceDate == null || parameters == null || payload == null) {
            throw new IllegalArgumentException("technical hash inputs");
        }
        return digest(technicalInputBytes(profileId, sourceDate, parameters, payload));
    }

    /** Package-visible for the cross-language golden fixture: these exact bytes are hashed. */
    static byte[] technicalInputBytes(String profileId, LocalDate sourceDate,
                                      Map<String, Object> parameters, Map<String, String> payload) {
        if (profileId == null || sourceDate == null || parameters == null || payload == null) {
            throw new IllegalArgumentException("technical hash inputs");
        }
        String canonical = "FUBON_TECHNICAL_FACT_V1\n" + profileId + "\n" + sourceDate + "\n"
                + json(parameters) + "\n" + json(payload);
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    private static String digest(byte[] canonical) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String json(Map<String, ?> value) {
        try {
            // The accepted Task408 parameter/payload objects are shallow and
            // string-keyed.  TreeMap fixes their ASCII lexical order explicitly
            // rather than inheriting whichever map implementation parsed JSON.
            return CANONICAL_JSON.writeValueAsString(new TreeMap<>(value));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("canonical JSON", invalid);
        }
    }
}
