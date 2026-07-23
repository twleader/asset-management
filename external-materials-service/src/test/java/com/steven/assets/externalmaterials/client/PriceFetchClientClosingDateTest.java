package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PriceFetchClientClosingDateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LocalDate EXPECTED = LocalDate.of(2026, 7, 23);

    @Test
    void matchingDate_isAccepted() throws Exception {
        assertThat(PriceFetchClient.parseTwClosingRow(
                "2885", row("2026-07-23"), EXPECTED)).isPresent();
    }

    @Test
    void previousDate_isRejected() throws Exception {
        assertThat(PriceFetchClient.parseTwClosingRow(
                "2885", row("2026-07-22"), EXPECTED)).isEmpty();
    }

    @Test
    void missingDate_isRejected() throws Exception {
        JsonNode row = MAPPER.readTree("""
                {"close":63.1,"open":61.4,"max":63.2,"min":61.3,"spread":1.8}
                """);
        assertThat(PriceFetchClient.parseTwClosingRow("2885", row, EXPECTED)).isEmpty();
    }

    @Test
    void malformedDate_isRejected() throws Exception {
        assertThat(PriceFetchClient.parseTwClosingRow(
                "2885", row("2026/07/23"), EXPECTED)).isEmpty();
    }

    private JsonNode row(String date) throws Exception {
        return MAPPER.readTree("""
                {"date":"%s","close":63.1,"open":61.4,"max":63.2,"min":61.3,
                 "spread":1.8,"Trading_Volume":1000}
                """.formatted(date));
    }
}
