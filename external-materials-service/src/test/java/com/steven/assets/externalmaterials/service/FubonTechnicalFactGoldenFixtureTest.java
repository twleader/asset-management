package com.steven.assets.externalmaterials.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.externalmaterials.client.FubonMarketJson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The source-fact writer reads the same literal-byte fixture as the Python
 * normalizer and the business-side cache reader.  Do not duplicate its hash
 * here: the fixture itself is the protocol artifact.
 */
class FubonTechnicalFactGoldenFixtureTest {

    @Test
    void sourceFactWriterMatchesCrossLanguageTechnicalFactGoldenBytesAndHash() throws Exception {
        JsonNode fixture = FubonMarketJson.MAPPER.readTree(
                Files.readString(goldenFixture(), StandardCharsets.UTF_8));
        FubonMarketData.TechnicalProfile profile = FubonMarketData.profile(fixture.path("profileId").asText());
        Map<String, Object> parameters = FubonMarketJson.MAPPER.convertValue(fixture.path("parameters"),
                new TypeReference<LinkedHashMap<String, Object>>() {});
        Map<String, String> payload = FubonMarketJson.MAPPER.convertValue(fixture.path("payload"),
                new TypeReference<LinkedHashMap<String, String>>() {});
        byte[] literal = fixture.path("canonicalInputUtf8").asText().getBytes(StandardCharsets.UTF_8);

        // This SHA-256 is intentionally direct, before FubonCanonicalHash is
        // involved, so it establishes an independently checkable fixture.
        assertThat(HexFormat.of().formatHex(literal)).isEqualTo(fixture.path("canonicalInputUtf8Hex").asText());
        assertThat(sha256(literal)).isEqualTo(fixture.path("sha256").asText());
        assertThat(profile.parameters()).isEqualTo(parameters);

        LocalDate sourceDate = LocalDate.parse(fixture.path("sourceDate").asText());
        assertThat(FubonCanonicalHash.technicalInputBytes(profile, sourceDate, payload)).isEqualTo(literal);
        assertThat(FubonCanonicalHash.technical(profile, sourceDate, payload))
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
