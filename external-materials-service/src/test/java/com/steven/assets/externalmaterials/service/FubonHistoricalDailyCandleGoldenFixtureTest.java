package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.externalmaterials.client.FubonMarketJson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Cross-language protocol fixture: the immutable fact hash must not depend on database receipt fields. */
class FubonHistoricalDailyCandleGoldenFixtureTest {
    @Test void dailyCandleHashUsesDeclaredLiteralUtf8Bytes() throws Exception {
        JsonNode fixture = FubonMarketJson.MAPPER.readTree(Files.readString(fixturePath(), StandardCharsets.UTF_8));
        Map<String, Object> document = FubonMarketJson.MAPPER.convertValue(fixture,
                new TypeReference<LinkedHashMap<String, Object>>() {});
        document.keySet().removeIf(key -> key.startsWith("canonicalInput") || "sha256".equals(key));
        byte[] literal = fixture.path("canonicalInputUtf8").asText().getBytes(StandardCharsets.UTF_8);
        assertThat(HexFormat.of().formatHex(literal)).isEqualTo(fixture.path("canonicalInputUtf8Hex").asText());
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(literal))).isEqualTo(fixture.path("sha256").asText());
        assertThat(FubonCanonicalHash.dailyCandleInputBytes(document)).isEqualTo(literal);
        assertThat(FubonCanonicalHash.dailyCandle(document)).isEqualTo(fixture.path("sha256").asText());
    }
    private static Path fixturePath() {
        for (Path current = Path.of("").toAbsolutePath(); current != null; current = current.getParent()) {
            Path candidate = current.resolve("spec/fixtures/fubon-historical-daily-candle-fact-v1-golden.json");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException("Task425 daily fact golden fixture is missing");
    }
}
