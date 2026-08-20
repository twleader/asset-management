package com.steven.assets.bff.gdptwse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketIndexChartServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void catalogNormalizationAndAllEightyCombinationsAreAccepted() {
        assertThat(MarketIndexChartService.supportedMarkets())
                .extracting(MarketIndexChartDto.Option::value)
                .containsExactly("TWSE", "TPEX", "DJI", "SPX", "IXIC", "SOX", "FTSE", "DAX", "KOSPI", "N225");
        assertThat(MarketIndexChartService.supportedMarkets())
                .extracting(MarketIndexChartDto.Option::label)
                .containsExactly("台股集中市場", "台股櫃買市場", "道瓊工業", "標普 500", "那斯達克綜合", "費城半導體",
                        "英國富時 100", "德國 DAX", "韓國 KOSPI", "日經 225");
        assertThat(MarketIndexChartService.supportedRanges())
                .extracting(MarketIndexChartDto.Option::value)
                .containsExactly("d", "1m", "3m", "6m", "1y", "2y", "5y", "10y");
        assertThat(MarketIndexChartService.supportedRanges())
                .extracting(MarketIndexChartDto.Option::label)
                .containsExactly("當日", "1 個月", "3 個月", "半年", "1 年", "2 年", "5 年", "10 年");

        MarketIndexChartService service = service(request -> jsonResponse(List.of()));
        int validPairs = 0;
        for (MarketIndexChartDto.Option market : MarketIndexChartService.supportedMarkets()) {
            for (MarketIndexChartDto.Option range : MarketIndexChartService.supportedRanges()) {
                assertThat(MarketIndexChartService.normalizeMarket(market.value())).isEqualTo(market.value());
                assertThat(MarketIndexChartService.normalizeRange(range.value())).isEqualTo(range.value());
                MarketIndexChartDto.Response response = service
                        .getPublicChart(market.value().toLowerCase(), range.value().toUpperCase())
                        .block();
                assertThat(response).isNotNull();
                assertThat(response.market()).isEqualTo(market.value());
                assertThat(response.range()).isEqualTo(range.value());
                assertAligned(response, 0);
                validPairs++;
            }
        }
        assertThat(validPairs).isEqualTo(80);

        assertThat(MarketIndexChartService.normalizeMarket(null)).isEqualTo("TWSE");
        assertThat(MarketIndexChartService.normalizeMarket("   ")).isEqualTo("TWSE");
        assertThat(MarketIndexChartService.normalizeMarket("  ixic ")).isEqualTo("IXIC");
        assertThat(MarketIndexChartService.normalizeRange(null)).isEqualTo("1y");
        assertThat(MarketIndexChartService.normalizeRange("   ")).isEqualTo("1y");
        assertThat(MarketIndexChartService.normalizeRange("  1Y ")).isEqualTo("1y");
    }

    @Test
    void unknownMarketAndRangeFailBeforeAnyDownstreamRequest() {
        List<URI> requests = new CopyOnWriteArrayList<>();
        MarketIndexChartService service = service(request -> {
            requests.add(request.url());
            return ClientResponse.create(HttpStatus.OK).body("[]").build();
        });

        assertThatThrownBy(() -> service.getPublicChart("BAD", "1y"))
                .isInstanceOf(PublicMarketIndexRequestException.class)
                .hasMessageContaining("BAD", "TWSE", "N225");
        assertThatThrownBy(() -> service.getPublicChart("TWSE", "bad"))
                .isInstanceOf(PublicMarketIndexRequestException.class)
                .hasMessageContaining("bad", "d", "10y");
        assertThat(requests).isEmpty();
    }

    @Test
    void everyDailyRangeSlicesInLockstepAfterMaturityIsCalculatedOnFullHistory() {
        List<Map<String, Object>> rows = sequentialTwseRows(2500);
        MarketIndexChartService service = service(request -> jsonResponse(rows));

        Map<String, Integer> expectedSizes = new LinkedHashMap<>();
        expectedSizes.put("1m", 21);
        expectedSizes.put("3m", 63);
        expectedSizes.put("6m", 125);
        expectedSizes.put("1y", 250);
        expectedSizes.put("2y", 500);
        expectedSizes.put("5y", 1250);
        expectedSizes.put("10y", 2500);

        expectedSizes.forEach((range, expectedSize) -> {
            MarketIndexChartDto.Response response = service.getPublicChart("TWSE", range).block();
            assertThat(response).isNotNull();
            assertThat(response.mode()).isEqualTo("DAILY");
            assertThat(response.range()).isEqualTo(range);
            assertThat(response.tradingDate()).isNull();
            assertThat(response.previousClose()).isNull();
            assertThat(response.lastClose()).isNull();
            assertThat(response.change()).isNull();
            assertThat(response.changePercent()).isNull();
            assertAligned(response, expectedSize);
        });

        MarketIndexChartDto.Response oneMonth = service.getPublicChart("TWSE", "1m").block();
        assertThat(oneMonth).isNotNull();
        assertThat(oneMonth.ma240().getFirst())
                .as("MA240 必須先在完整歷史成熟，再裁成最後 21 筆")
                .isEqualByComparingTo("2360.50");
        assertThat(oneMonth.hasVolume()).isTrue();

        MarketIndexChartDto.Response insufficient = service(request -> jsonResponse(sequentialTwseRows(100)))
                .getPublicChart("TWSE", "2y")
                .block();
        assertThat(insufficient).isNotNull();
        assertAligned(insufficient, 100);
    }

    @Test
    void intradayUsesLatestDailyMovingAveragesAndNullVolumeLines() {
        List<Map<String, Object>> dailyRows = constantTwseRows(260, "100.00");
        List<Map<String, Object>> intradayRows = List.of(
                intradayRow("2026-08-11T09:00:00", "100.00"),
                intradayRow("2026-08-11T09:05:00", null),
                intradayRow("2026-08-11T13:30:00", "102.00"));

        MarketIndexChartService service = service(request ->
                "/api/index-intraday".equals(request.url().getPath())
                        ? jsonResponse(intradayRows)
                        : jsonResponse(dailyRows));

        MarketIndexChartDto.Response response = service.getPublicChart(" twse ", " D ").block();

        assertThat(response).isNotNull();
        assertThat(response.market()).isEqualTo("TWSE");
        assertThat(response.range()).isEqualTo("d");
        assertThat(response.mode()).isEqualTo("INTRADAY");
        assertThat(response.tradingDate()).isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(response.labels()).containsExactly("09:00", "09:05", "13:30");
        assertThat(response.closes()).containsExactly(
                new BigDecimal("100.00"), null, new BigDecimal("102.00"));
        assertThat(response.previousClose()).isEqualByComparingTo("100.00");
        assertThat(response.lastClose()).isEqualByComparingTo("102.00");
        assertThat(response.change()).isEqualByComparingTo("2.00");
        assertThat(response.changePercent()).isEqualByComparingTo("2.00");
        assertThat(response.ma5()).containsOnly(new BigDecimal("100.00"));
        assertThat(response.ma20()).containsOnly(new BigDecimal("100.00"));
        assertThat(response.ma60()).containsOnly(new BigDecimal("100.00"));
        assertThat(response.ma240()).containsOnly(new BigDecimal("100.00"));
        assertThat(response.volumes()).hasSize(3).containsOnlyNulls();
        assertThat(response.turnovers()).hasSize(3).containsOnlyNulls();
        assertThat(response.hasVolume()).isFalse();
        assertAligned(response, 3);
    }

    @Test
    void unavailableMovingAverageBecomesSameLengthAllNullIntradayLine() {
        List<Map<String, Object>> dailyRows = constantTwseRows(4, "100.00");
        List<Map<String, Object>> intradayRows = List.of(
                intradayRow("2026-08-11T09:00:00", "100.00"),
                intradayRow("2026-08-11T09:05:00", "101.00"));
        MarketIndexChartService service = service(request ->
                "/api/index-intraday".equals(request.url().getPath())
                        ? jsonResponse(intradayRows)
                        : jsonResponse(dailyRows));

        MarketIndexChartDto.Response response = service.getPublicChart("TWSE", "d").block();

        assertThat(response).isNotNull();
        assertThat(response.ma5()).hasSize(2).containsOnlyNulls();
        assertThat(response.ma20()).hasSize(2).containsOnlyNulls();
        assertThat(response.ma60()).hasSize(2).containsOnlyNulls();
        assertThat(response.ma240()).hasSize(2).containsOnlyNulls();
    }

    @Test
    void noDataStillReturnsCompleteMetadataAndEmptyAlignedArrays() {
        MarketIndexChartService service = service(request -> jsonResponse(List.of()));

        MarketIndexChartDto.Response daily = service.getPublicChart(null, null).block();
        MarketIndexChartDto.Response intraday = service.getPublicChart("N225", "d").block();

        assertThat(daily).isNotNull();
        assertThat(daily.market()).isEqualTo("TWSE");
        assertThat(daily.range()).isEqualTo("1y");
        assertThat(daily.supportedMarkets()).hasSize(10);
        assertThat(daily.supportedRanges()).hasSize(8);
        assertAligned(daily, 0);
        assertThat(daily.hasVolume()).isFalse();

        assertThat(intraday).isNotNull();
        assertThat(intraday.market()).isEqualTo("N225");
        assertThat(intraday.mode()).isEqualTo("INTRADAY");
        assertThat(intraday.tradingDate()).isNull();
        assertThat(intraday.previousClose()).isNull();
        assertThat(intraday.lastClose()).isNull();
        assertThat(intraday.change()).isNull();
        assertThat(intraday.changePercent()).isNull();
        assertAligned(intraday, 0);
    }

    @Test
    void legacySelectionAndFailSoftBehaviorRemainInSharedService() {
        List<URI> requests = new CopyOnWriteArrayList<>();
        MarketIndexChartService service = service(request -> {
            requests.add(request.url());
            return jsonResponse(List.of());
        });

        service.getIndexDaily("TWSE", 10).block();
        service.getIndexDaily("TPEX", 3).block();
        service.getIndexIntraday("TPEX").block();

        assertThat(requests).anyMatch(uri -> "/api/twse-daily-index".equals(uri.getPath()));
        assertThat(requests).anyMatch(uri -> "/api/us-daily-index".equals(uri.getPath())
                && uri.getQuery().contains("code=TPEX"));
        assertThat(requests).anyMatch(uri -> "/api/index-intraday".equals(uri.getPath())
                && uri.getQuery().contains("market=TPEX"));

        MarketIndexChartService failing = service(request -> {
            throw new IllegalStateException("downstream unavailable");
        });
        Map<String, Object> daily = failing.getIndexDaily("TWSE", 10).block();
        Map<String, Object> intraday = failing.getIndexIntraday("TWSE").block();
        assertThat(daily).isNotNull();
        assertThat((List<?>) daily.get("dates")).isEmpty();
        assertThat(daily.get("hasVolume")).isEqualTo(false);
        assertThat(intraday).isNotNull();
        assertThat((List<?>) intraday.get("times")).isEmpty();
        assertThat(intraday.get("previousClose")).isNull();
    }

    @Test
    void responseRecordDefensivelyCopiesNullableListsAndExposesNoMutation() {
        List<BigDecimal> closes = new ArrayList<>(Arrays.asList(new BigDecimal("1.00"), null));
        List<Object> volumes = new ArrayList<>(Arrays.asList(null, 10L));
        List<BigDecimal> turnovers = new ArrayList<>(Arrays.asList(null, new BigDecimal("20.00")));
        MarketIndexChartDto.Response response = new MarketIndexChartDto.Response(
                "TWSE", "台股集中市場", "d", "當日", "INTRADAY", LocalDate.of(2026, 8, 11),
                new ArrayList<>(List.of("09:00", "09:05")),
                closes, closes, closes, closes, closes, volumes, turnovers, false,
                null, null, null, null,
                MarketIndexChartService.supportedMarkets(), MarketIndexChartService.supportedRanges());

        closes.set(0, BigDecimal.TEN);
        volumes.set(1, 99L);
        turnovers.set(1, BigDecimal.ZERO);

        assertThat(MarketIndexChartDto.Response.class.isRecord()).isTrue();
        assertThat(MarketIndexChartDto.Option.class.isRecord()).isTrue();
        assertThat(Arrays.stream(MarketIndexChartDto.Response.class.getRecordComponents())
                .filter(component -> component.getName().equals("turnovers"))
                .findFirst()
                .orElseThrow()
                .getGenericType()
                .getTypeName()).isEqualTo("java.util.List<java.math.BigDecimal>");
        assertThat(response.closes()).containsExactly(new BigDecimal("1.00"), null);
        assertThat(response.volumes()).containsExactly(null, 10L);
        assertThat(response.turnovers()).containsExactly(null, new BigDecimal("20.00"));
        assertThatThrownBy(() -> response.closes().add(BigDecimal.ONE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> response.volumes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> response.turnovers().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> response.supportedMarkets().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static void assertAligned(MarketIndexChartDto.Response response, int expectedSize) {
        assertThat(response.labels()).hasSize(expectedSize);
        assertThat(response.closes()).hasSize(expectedSize);
        assertThat(response.ma5()).hasSize(expectedSize);
        assertThat(response.ma20()).hasSize(expectedSize);
        assertThat(response.ma60()).hasSize(expectedSize);
        assertThat(response.ma240()).hasSize(expectedSize);
        assertThat(response.volumes()).hasSize(expectedSize);
        assertThat(response.turnovers()).hasSize(expectedSize);
    }

    private static MarketIndexChartService service(
            Function<org.springframework.web.reactive.function.client.ClientRequest, ClientResponse> responder) {
        WebClient client = WebClient.builder()
                .baseUrl("http://business")
                .exchangeFunction(request -> {
                    try {
                        return reactor.core.publisher.Mono.just(responder.apply(request));
                    } catch (RuntimeException ex) {
                        return reactor.core.publisher.Mono.error(ex);
                    }
                })
                .build();
        return new MarketIndexChartService(client);
    }

    private static ClientResponse jsonResponse(Object body) {
        try {
            return ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(OBJECT_MAPPER.writeValueAsString(body))
                    .build();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static List<Map<String, Object>> sequentialTwseRows(int count) {
        List<Map<String, Object>> rows = new ArrayList<>(count);
        LocalDate first = LocalDate.of(2025, 1, 1);
        for (int i = 0; i < count; i++) {
            rows.add(twseRow(first.plusDays(i).toString(), BigDecimal.valueOf(i + 1), 1_000L + i, 10_000L + i));
        }
        return rows;
    }

    private static List<Map<String, Object>> constantTwseRows(int count, String close) {
        List<Map<String, Object>> rows = new ArrayList<>(count);
        LocalDate first = LocalDate.of(2025, 11, 24);
        for (int i = 0; i < count; i++) {
            rows.add(twseRow(first.plusDays(i).toString(), new BigDecimal(close), 1_000L, 10_000L));
        }
        return rows;
    }

    private static Map<String, Object> twseRow(
            String tradingDate, BigDecimal close, Object tradeVolume, Object tradeValue) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tradingDate", tradingDate);
        row.put("closePoint", close);
        row.put("tradeVolume", tradeVolume);
        row.put("tradeValue", tradeValue);
        return row;
    }

    private static Map<String, Object> intradayRow(String time, Object close) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("time", time);
        row.put("close", close);
        return row;
    }
}
