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

    /**
     * Task 279：來源對「當日無整股成交」不發布 OHLC，FinMind 序列化為 {@code close: 0.0}。
     * 若這裡放行，ClosePersister 會把 0 經 PriceCacheWriter.writeVerifiedClose 寫進 Redis
     * live cache 並推播，前端就會看到股價 0。
     */
    @Test
    void zeroClose_isRejected() throws Exception {
        JsonNode row = MAPPER.readTree("""
                {"date":"2026-07-23","close":0.0,"open":0.0,"max":0.0,"min":0.0,
                 "spread":0.0,"Trading_Volume":0,"Trading_turnover":0}
                """);
        assertThat(PriceFetchClient.parseTwClosingRow("2885", row, EXPECTED)).isEmpty();
    }

    @Test
    void negativeClose_isRejected() throws Exception {
        JsonNode row = MAPPER.readTree("""
                {"date":"2026-07-23","close":-1.5,"open":61.4,"max":63.2,"min":61.3,
                 "spread":1.8,"Trading_Volume":1000}
                """);
        assertThat(PriceFetchClient.parseTwClosingRow("2885", row, EXPECTED)).isEmpty();
    }

    private JsonNode row(String date) throws Exception {
        return MAPPER.readTree("""
                {"date":"%s","close":63.1,"open":61.4,"max":63.2,"min":61.3,
                 "spread":1.8,"Trading_Volume":1000}
                """.formatted(date));
    }
}
