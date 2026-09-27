package com.steven.assets.externalmaterials.client;

import com.steven.assets.externalmaterials.service.FubonIntradayTechnical;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FubonIntradayTechnicalJsonTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 25);
    private static final Instant NOW = Instant.parse("2026-09-25T02:13:30Z");

    @Test
    void acceptsFixtureWithBothTimeframesAndDateOnlySourceTime() {
        FubonIntradayTechnical.Bundle bundle = FubonIntradayTechnicalJson.parse(
                FubonMarketJson.parse(fixture(null, null)), "2330", TODAY, NOW);

        assertThat(bundle.schemaVersion()).isEqualTo(1);
        assertThat(bundle.symbol()).isEqualTo("2330");
        assertThat(bundle.oneMinute().timeframe()).isEqualTo("1");
        assertThat(bundle.oneMinute().sourceTimestamp()).isNull();
        assertThat(bundle.oneMinute().kdj().k()).isEqualByComparingTo("51.2");
        assertThat(bundle.fiveMinute().timeframe()).isEqualTo("5");
        assertThat(bundle.fiveMinute().sourceTimestamp()).isNull();
        assertThat(bundle.fiveMinute().bollinger().upper()).isEqualByComparingTo("1018");
    }

    @Test
    void acceptsTimestampOnlyWhenItBelongsToTheSourceDateAndPrecedesObservation() {
        FubonIntradayTechnical.Bundle bundle = FubonIntradayTechnicalJson.parse(
                FubonMarketJson.parse(fixture("2026-09-25T02:12:00Z", "2026-09-25T02:10:00Z")),
                "2330", TODAY, NOW);
        assertThat(bundle.oneMinute().sourceTimestamp()).isEqualTo(Instant.parse("2026-09-25T02:12:00Z"));
        assertThat(bundle.fiveMinute().sourceTimestamp()).isEqualTo(Instant.parse("2026-09-25T02:10:00Z"));
    }

    @Test
    void rejectsWrongIdentityFutureTimesAndNonCanonicalDecimals() {
        assertThatThrownBy(() -> FubonIntradayTechnicalJson.parse(
                FubonMarketJson.parse(fixture(null, null)), "2317", TODAY, NOW)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> FubonIntradayTechnicalJson.parse(
                FubonMarketJson.parse(fixture("2026-09-25T02:14:00Z", null)), "2330", TODAY, NOW))
                .isInstanceOf(RuntimeException.class);
        String nonCanonical = fixture(null, null).replace("\"k\":\"51.2\"", "\"k\":\"51.20\"");
        assertThatThrownBy(() -> FubonIntradayTechnicalJson.parse(
                FubonMarketJson.parse(nonCanonical), "2330", TODAY, NOW)).isInstanceOf(RuntimeException.class);
    }

    private static String fixture(String oneMinuteSourceTime, String fiveMinuteSourceTime) {
        return """
                {
                  "schemaVersion":1,"symbol":"2330","market":"台股","provider":"FUBON_SDK",
                  "observedAt":"2026-09-25T02:13:00Z",
                  "oneMinute":{"timeframe":"1","sourceDate":"2026-09-25","sourceTimestamp":%s,
                    "observedAt":"2026-09-25T02:13:00Z","kdj":{"k":"51.2","d":"48.9","j":"55.8"},
                    "macd":{"macdLine":"0.4","signalLine":"0.3"},
                    "bollinger":{"upper":"1015","middle":"1004.5","lower":"994"}},
                  "fiveMinute":{"timeframe":"5","sourceDate":"2026-09-25","sourceTimestamp":%s,
                    "observedAt":"2026-09-25T02:13:00Z","kdj":{"k":"50.1","d":"47.2","j":"55.9"},
                    "macd":{"macdLine":"0.5","signalLine":"0.2"},
                    "bollinger":{"upper":"1018","middle":"1005","lower":"992"}}
                }
                """.formatted(json(oneMinuteSourceTime), json(fiveMinuteSourceTime));
    }

    private static String json(String value) { return value == null ? "null" : "\"" + value + "\""; }
}
