package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PriceProducerTimestampTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void yahooLseRequiresPositiveRegularMarketTimeAndUsesLondonDate() throws Exception {
        long epoch = Instant.parse("2026-08-21T15:30:00Z").getEpochSecond();
        JsonNode result = MAPPER.readTree("""
                {"meta":{"regularMarketPrice":101.25,"chartPreviousClose":100.00,
                 "regularMarketTime":%d,"shortName":"Example LSE"},
                 "indicators":{"quote":[{"open":[100.50]}]}}
                """.formatted(epoch));

        PriceFetchClient.PriceResult quote = PriceFetchClient
                .parseYahooLsePrice("CSPX", result).orElseThrow();

        assertThat(quote.freshnessInstant()).isEqualTo(Instant.ofEpochSecond(epoch));
        assertThat(quote.tradingDate()).isEqualTo(LocalDate.of(2026, 8, 21));
        ((com.fasterxml.jackson.databind.node.ObjectNode) result.path("meta")).remove("regularMarketTime");
        assertThat(PriceFetchClient.parseYahooLsePrice("CSPX", result)).isEmpty();
    }

    @Test
    void finMindTwCapturesClockOnceOnlyAfterMatchingRowAccepted() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"data":[{"date":"2026-08-21","close":52.3,"open":52.0,
                 "max":52.5,"min":51.9,"spread":0.4,"Trading_Volume":1000}]}
                """);
        when(http.send(any(HttpRequest.class), any())).thenAnswer(ignored -> response);
        CountingClock clock = new CountingClock(Instant.parse("2026-08-21T08:00:00Z"));

        PriceFetchClient.PriceResult quote = client(http, clock)
                .getTwClosingPriceFromFinMind("0056", LocalDate.of(2026, 8, 21)).orElseThrow();

        assertThat(quote.tradingDate()).isEqualTo(LocalDate.of(2026, 8, 21));
        assertThat(quote.freshnessInstant()).isEqualTo(Instant.parse("2026-08-21T08:00:00Z"));
        assertThat(clock.calls()).isOne();
    }

    @Test
    void finMindUsDateMismatchFailsBeforeClockCapture() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"data":[{"date":"2026-08-20","Close":237.85,"Open":236.0,
                 "High":239.0,"Low":235.0,"Volume":1000}]}
                """);
        when(http.send(any(HttpRequest.class), any())).thenAnswer(ignored -> response);
        CountingClock clock = new CountingClock(Instant.parse("2026-08-21T22:00:00Z"));

        assertThat(client(http, clock)
                .getUsClosingPriceFromFinMind("VOO", LocalDate.of(2026, 8, 21))).isEmpty();
        assertThat(clock.calls()).isZero();
    }

    @Test
    void yahooTaiwanFallbackUsesLatestTradedOneMinuteBarInsteadOfMetadata() throws Exception {
        Instant now = Instant.parse("2026-08-24T02:05:00Z");
        long earlier = Instant.parse("2026-08-24T02:03:00Z").getEpochSecond();
        long latest = Instant.parse("2026-08-24T02:04:00Z").getEpochSecond();
        JsonNode chart = MAPPER.readTree("""
                {"meta":{"regularMarketPrice":999,"regularMarketTime":1,"chartPreviousClose":100,"shortName":"台積電"},
                 "timestamp":[%d,%d],
                 "indicators":{"quote":[{"close":[101,102],"volume":[10,20],
                 "open":[100,101],"high":[101,103],"low":[99,100]}]}}
                """.formatted(earlier, latest));

        PriceFetchClient.PriceResult quote = PriceFetchClient
                .parseYahooTwLiveChart("2330", chart, now).orElseThrow();

        assertThat(quote.price()).isEqualByComparingTo("102");
        assertThat(quote.freshnessInstant()).isEqualTo(Instant.ofEpochSecond(latest));
        assertThat(quote.volume()).isEqualTo(20L);
    }

    @Test
    void yahooTaiwanFallbackRejectsMetadataOnlyFutureAndNoTradeBarsThenAllowsTwoSuffixCandidate() throws Exception {
        Instant now = Instant.parse("2026-08-24T02:05:00Z");
        JsonNode metaOnly = MAPPER.readTree("""
                {"meta":{"regularMarketPrice":101,"regularMarketTime":1787537100},"timestamp":[],"indicators":{"quote":[{}]}}
                """);
        JsonNode future = MAPPER.readTree("""
                {"timestamp":[1787537160],"indicators":{"quote":[{"close":[101],"volume":[10]}]}}
                """);
        JsonNode noTrade = MAPPER.readTree("""
                {"timestamp":[1787537040],"indicators":{"quote":[{"close":[101],"volume":[0]}]}}
                """);
        JsonNode twoCandidate = MAPPER.readTree("""
                {"meta":{"shortName":"櫃買"},"timestamp":[1787537040],
                 "indicators":{"quote":[{"close":[101],"volume":[10],"open":[100]}]}}
                """);

        assertThat(PriceFetchClient.parseYahooTwLiveChart("2330", metaOnly, now)).isEmpty();
        assertThat(PriceFetchClient.parseYahooTwLiveChart("2330", future, now)).isEmpty();
        assertThat(PriceFetchClient.parseYahooTwLiveChart("2330", noTrade, now)).isEmpty();
        assertThat(PriceFetchClient.parseYahooTwLiveChart("2330", twoCandidate, now)).isPresent();
    }

    @Test
    void yahooTaiwanLiveActuallyFallsBackFromTwToTwoWhenTwHasNoTradedBar() {
        Instant now = Instant.parse("2026-08-24T02:05:00Z");
        List<String> suffixes = new ArrayList<>();
        PriceFetchClient client = new PriceFetchClient("", mock(HttpClient.class), Clock.fixed(now, ZoneOffset.UTC), null,
                duration -> { }, System::nanoTime, () -> Executors.newSingleThreadExecutor()) {
            @Override String fetchYahooTwLiveChartBody(String code, String suffix) {
                suffixes.add(suffix);
                return ".TW".equals(suffix)
                        ? "{\"chart\":{\"result\":[{\"meta\":{\"regularMarketPrice\":999},\"timestamp\":[],\"indicators\":{\"quote\":[{}]}}]}}"
                        : "{\"chart\":{\"result\":[{\"timestamp\":[1787537040],\"indicators\":{\"quote\":[{\"close\":[101],\"volume\":[10]}]}}]}}";
            }
        };

        PriceFetchClient.PriceResult quote = client.getYahooTwLivePrice("2330").orElseThrow();

        assertThat(suffixes).containsExactly(".TW", ".TWO");
        assertThat(quote.price()).isEqualByComparingTo("101");
    }

    @Test
    void yahooTaiwanLiveAllowsThirtySecondClockSkewButRejectsThirtyOneSecondsAndOverflowEpoch() throws Exception {
        Instant now = Instant.parse("2026-08-24T02:05:00Z");
        JsonNode thirtySeconds = MAPPER.readTree("""
                {"timestamp":[%d],"indicators":{"quote":[{"close":[101],"volume":[10]}]}}
                """.formatted(now.plusSeconds(30).getEpochSecond()));
        JsonNode thirtyOneSeconds = MAPPER.readTree("""
                {"timestamp":[%d],"indicators":{"quote":[{"close":[101],"volume":[10]}]}}
                """.formatted(now.plusSeconds(31).getEpochSecond()));
        JsonNode overflow = MAPPER.readTree("""
                {"timestamp":[9223372036854775807],"indicators":{"quote":[{"close":[101],"volume":[10]}]}}
                """);

        assertThat(PriceFetchClient.parseYahooTwLiveChart("2330", thirtySeconds, now)).isPresent();
        assertThat(PriceFetchClient.parseYahooTwLiveChart("2330", thirtyOneSeconds, now)).isEmpty();
        assertThat(PriceFetchClient.parseYahooTwLiveChart("2330", overflow, now)).isEmpty();
    }

    private static PriceFetchClient client(HttpClient http, Clock clock) {
        return new PriceFetchClient("", http, clock, null, duration -> { }, System::nanoTime,
                () -> Executors.newFixedThreadPool(PriceFetchClient.MAX_CONCURRENT_CHUNKS,
                        Thread.ofVirtual().factory()));
    }

    private static final class CountingClock extends Clock {
        private final Instant instant;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingClock(Instant instant) { this.instant = instant; }
        int calls() { return calls.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() {
            calls.incrementAndGet();
            return instant;
        }
    }
}
