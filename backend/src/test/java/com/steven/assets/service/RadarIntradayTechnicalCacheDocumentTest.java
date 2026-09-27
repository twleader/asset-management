package com.steven.assets.service;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RadarIntradayTechnicalCacheDocumentTest {
    private static final String DOCUMENT = """
            {"schemaVersion":1,"symbol":"2330","market":"台股","provider":"FUBON_SDK",
             "observedAt":"2026-09-25T02:20:00Z",
             "oneMinute":{"timeframe":"1","sourceDate":"2026-09-25","sourceTimestamp":"2026-09-25T02:20:00Z",
              "observedAt":"2026-09-25T02:20:00Z","kdj":{"k":"51.2","d":"48.9","j":"55.8"},
              "macd":{"macdLine":"0.4","signalLine":"0.3"},"bollinger":{"upper":"1015","middle":"1004.5","lower":"994"}},
             "fiveMinute":{"timeframe":"5","sourceDate":"2026-09-25","sourceTimestamp":"2026-09-25T02:20:00Z",
              "observedAt":"2026-09-25T02:20:00Z","kdj":{"k":"50.1","d":"47.2","j":"55.9"},
              "macd":{"macdLine":"0.5","signalLine":"0.2"},"bollinger":{"upper":"1018","middle":"1005","lower":"992"}}}
            """;

    @Test
    void usesAvailableStaleAndTtlBoundariesWithoutChangingValues() {
        var available = RadarIntradayTechnicalCacheDocument.resolve(DOCUMENT, "2330", Instant.parse("2026-09-25T02:27:00Z"));
        assertThat(available.status()).isEqualTo("AVAILABLE");
        assertThat(available.ageSeconds()).isEqualTo(420L);
        assertThat(available.oneMinute().sourceTimestamp()).isEqualTo("2026-09-25T02:20:00Z");
        assertThat(available.oneMinute().kdj().j()).isEqualByComparingTo("55.8");

        var stale = RadarIntradayTechnicalCacheDocument.resolve(DOCUMENT, "2330", Instant.parse("2026-09-25T02:27:01Z"));
        assertThat(stale.status()).isEqualTo("STALE");
        assertThat(stale.fiveMinute().status()).isEqualTo("STALE");

        var expired = RadarIntradayTechnicalCacheDocument.resolve(DOCUMENT, "2330", Instant.parse("2026-09-25T02:32:01Z"));
        assertThat(expired.status()).isEqualTo("UNAVAILABLE");
        assertThat(expired.oneMinute()).isNull();
    }

    @Test
    void sourceBarAgeCanMakeFreshlyObservedDataStale() {
        String oldOneMinuteBar = DOCUMENT.replaceFirst("sourceTimestamp\":\"2026-09-25T02:20:00Z\"",
                "sourceTimestamp\":\"2026-09-25T02:00:00Z\"");
        var result = RadarIntradayTechnicalCacheDocument.resolve(oldOneMinuteBar, "2330",
                Instant.parse("2026-09-25T02:20:30Z"));

        assertThat(result.status()).isEqualTo("STALE");
        assertThat(result.ageSeconds()).isEqualTo(30L);
        assertThat(result.oneMinute().status()).isEqualTo("STALE");
        assertThat(result.fiveMinute().status()).isEqualTo("AVAILABLE");
    }

    @Test
    void rejectsWrongIdentityFutureCorruptAndMissingDocuments() {
        Instant now = Instant.parse("2026-09-25T02:13:30Z");
        assertThat(RadarIntradayTechnicalCacheDocument.resolve(DOCUMENT, "2317", now).status()).isEqualTo("UNAVAILABLE");
        assertThat(RadarIntradayTechnicalCacheDocument.resolve(DOCUMENT.replace("\"symbol\":\"2330\"", "\"symbol\":\"2330\",\"extra\":1"), "2330", now)
                .status()).isEqualTo("UNAVAILABLE");
        assertThat(RadarIntradayTechnicalCacheDocument.resolve(DOCUMENT, "2330", null).status()).isEqualTo("UNAVAILABLE");
        assertThat(RadarIntradayTechnicalCacheDocument.resolve(null, "2330", now).status()).isEqualTo("UNAVAILABLE");
    }
}
