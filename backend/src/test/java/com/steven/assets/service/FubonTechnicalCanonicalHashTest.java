package com.steven.assets.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks Task408's published LF and ASCII-key-order hash grammar in business code. */
class FubonTechnicalCanonicalHashTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void loadsTheCrossLanguageTechnicalFactGoldenBytesAndHash() throws Exception {
        JsonNode fixture = JSON.readTree(Files.readString(goldenFixture(), StandardCharsets.UTF_8));
        Map<String, Object> parameters = JSON.convertValue(fixture.path("parameters"),
                new TypeReference<LinkedHashMap<String, Object>>() {});
        Map<String, String> payload = JSON.convertValue(fixture.path("payload"),
                new TypeReference<LinkedHashMap<String, String>>() {});
        byte[] literal = fixture.path("canonicalInputUtf8").asText().getBytes(StandardCharsets.UTF_8);

        // Verify the fixture itself before the business helper is allowed to
        // participate: this catches a copy/paste hash or newline drift.
        assertThat(HexFormat.of().formatHex(literal)).isEqualTo(fixture.path("canonicalInputUtf8Hex").asText());
        assertThat(sha256(literal)).isEqualTo(fixture.path("sha256").asText());

        LocalDate sourceDate = LocalDate.parse(fixture.path("sourceDate").asText());
        assertThat(FubonTechnicalCanonicalHash.technicalInputBytes(
                fixture.path("profileId").asText(), sourceDate, parameters, payload)).isEqualTo(literal);
        assertThat(FubonTechnicalCanonicalHash.technical(
                fixture.path("profileId").asText(), sourceDate, parameters, payload))
                .isEqualTo(fixture.path("sha256").asText());
    }

    private static Path goldenFixture() {
        for (Path current = Path.of("").toAbsolutePath(); current != null; current = current.getParent()) {
            Path candidate = current.resolve("spec/fixtures/fubon-technical-fact-v1-golden.json");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException("Task408 technical-fact golden fixture is missing");
    }

    private static String sha256(byte[] input) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
    }
}
