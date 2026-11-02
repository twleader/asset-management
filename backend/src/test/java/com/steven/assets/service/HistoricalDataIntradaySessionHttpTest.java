package com.steven.assets.service;

import com.steven.assets.integration.marketdata.HistoricalDataHttpAdapter;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.repository.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferLimitException;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoricalDataIntradaySessionHttpTest {
    private HttpServer server;
    private String requestQuery;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void webClientDecodesObjectSessionAndUsesStrictPreviousRawClose(boolean readOnly) throws Exception {
        String body = "{\"tradingDate\":\"2026-08-18\",\"ticks\":[{\"time\":\"2026-08-18T13:30:00\",\"price\":49.48}],\"sessionReferencePrice\":50.55,\"sessionReferenceDate\":\"2026-08-18\",\"sessionReferenceSource\":\"TWSE_MIS_Y\"}";
        start(body);
        StockPriceHistoryRepository prices = mock(StockPriceHistoryRepository.class);
        StockPriceHistory raw = mock(StockPriceHistory.class);
        when(raw.getClosePrice()).thenReturn(new BigDecimal("55.15"));
        when(prices.findPreviousPriceBefore("00881", "台股", LocalDate.of(2026, 8, 18))).thenReturn(Optional.of(raw));
        HistoricalDataService service = service(prices);

        var session = service.fetchIntradaySession("00881", "台股", null, readOnly);
        assertThat(requestQuery.contains("readOnly=true")).isEqualTo(readOnly);

        assertThat(session.tradingDate()).isEqualTo(LocalDate.of(2026, 8, 18));
        assertThat(session.sessionReferencePrice()).isEqualByComparingTo("50.55");
        assertThat(session.sessionReferenceDate()).isEqualTo(LocalDate.of(2026, 8, 18));
        assertThat(session.sessionReferenceSource()).isEqualTo("TWSE_MIS_Y");
        assertThat(session.comparisonKind()).isEqualTo(HistoricalDataService.ComparisonKind.EX_RIGHTS_REFERENCE);
        assertThat(session.change()).isEqualByComparingTo("-1.07");
        verify(prices).findPreviousPriceBefore("00881", "台股", LocalDate.of(2026, 8, 18));
    }

    @Test
    void repositoryFailureKeepsTaiwanTicksAndValidReferenceAsSessionReference() throws Exception {
        start("{\"tradingDate\":\"2026-08-18\",\"ticks\":[{\"time\":\"2026-08-18T13:30:00\",\"price\":49.48}],\"sessionReferencePrice\":50.55,\"sessionReferenceDate\":\"2026-08-18\",\"sessionReferenceSource\":\"TWSE_MIS_Y\"}");
        StockPriceHistoryRepository prices = mock(StockPriceHistoryRepository.class);
        when(prices.findPreviousPriceBefore(anyString(), anyString(), any())).thenThrow(new IllegalStateException("db down"));

        var session = service(prices).fetchIntradaySession("00881", "台股", null);

        assertThat(session.ticks()).hasSize(1);
        assertThat(session.comparisonKind()).isEqualTo(HistoricalDataService.ComparisonKind.SESSION_REFERENCE);
        assertThat(session.sessionReferenceSource()).isEqualTo("TWSE_MIS_Y");
        assertThat(session.change()).isEqualByComparingTo("-1.07");
    }

    @Test
    void repositoryFailureKeepsNonTaiwanTicksButFailsComparisonClosed() throws Exception {
        start("{\"tradingDate\":\"2026-08-18\",\"ticks\":[{\"time\":\"2026-08-18T13:30:00\",\"price\":500.00}],\"sessionReferencePrice\":null,\"sessionReferenceDate\":null,\"sessionReferenceSource\":null}");
        StockPriceHistoryRepository prices = mock(StockPriceHistoryRepository.class);
        when(prices.findPreviousPriceBefore(anyString(), anyString(), any())).thenThrow(new IllegalStateException("db down"));

        var session = service(prices).fetchIntradaySession("VOO", "美股", null);

        assertThat(session.ticks()).hasSize(1);
        assertThat(session.comparisonKind()).isEqualTo(HistoricalDataService.ComparisonKind.UNAVAILABLE);
        assertThat(session.comparisonPrice()).isNull();
        assertThat(session.sessionReferencePrice()).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void denseSessionOverTwoMiBPreservesEveryTickAndComparison(boolean readOnly) throws Exception {
        LocalDate date = LocalDate.of(2026, 11, 2);
        LocalDateTime open = date.atTime(9, 0);
        List<HistoricalDataService.IntradayTick> expected = new ArrayList<>();
        StringBuilder body = new StringBuilder("{\"tradingDate\":\"2026-11-02\",\"ticks\":[");
        for (int i = 0; i < 40_000; i++) {
            String time = open.plusNanos(i * 1_000_000L).toString();
            BigDecimal price = new BigDecimal("103.1234567890").add(BigDecimal.valueOf(i, 10));
            expected.add(new HistoricalDataService.IntradayTick(time, price));
            if (i > 0) body.append(',');
            body.append("{\"time\":\"").append(time).append("\",\"price\":")
                    .append(price.toPlainString()).append('}');
        }
        body.append("],\"sessionReferencePrice\":100.0000000000,\"sessionReferenceDate\":\"2026-11-02\",\"sessionReferenceSource\":\"TWSE_MIS_Y\"}");
        assertThat(body.toString().getBytes(StandardCharsets.UTF_8).length).isGreaterThan(2 * 1024 * 1024);
        start(body.toString());
        StockPriceHistoryRepository prices = mock(StockPriceHistoryRepository.class);
        StockPriceHistory previous = mock(StockPriceHistory.class);
        when(previous.getClosePrice()).thenReturn(new BigDecimal("100.0000000000"));
        when(prices.findPreviousPriceBefore("0050", "台股", date)).thenReturn(Optional.of(previous));

        var session = service(prices).fetchIntradaySession("0050", "台股", date, readOnly);

        assertThat(requestQuery).contains("code=0050", "market=%E5%8F%B0%E8%82%A1", "date=2026-11-02");
        assertThat(requestQuery.contains("readOnly=true")).isEqualTo(readOnly);
        assertThat(session.tradingDate()).isEqualTo(date);
        assertThat(session.ticks()).hasSize(expected.size());
        assertThat(session.ticks()).containsExactlyElementsOf(expected);
        assertThat(session.ticks().getFirst().time()).isEqualTo("2026-11-02T09:00");
        assertThat(session.ticks().getFirst().price()).isEqualTo(new BigDecimal("103.1234567890"));
        assertThat(session.ticks().getLast().time()).isEqualTo("2026-11-02T09:00:39.999");
        assertThat(session.ticks().getLast().price()).isEqualTo(new BigDecimal("103.1234607889"));
        assertThat(session.sessionReferencePrice()).isEqualTo(new BigDecimal("100.0000000000"));
        assertThat(session.sessionReferenceDate()).isEqualTo(date);
        assertThat(session.sessionReferenceSource()).isEqualTo("TWSE_MIS_Y");
        assertThat(session.comparisonPrice()).isEqualTo(new BigDecimal("100.0000000000"));
        assertThat(session.comparisonKind()).isEqualTo(HistoricalDataService.ComparisonKind.PREVIOUS_CLOSE);
        assertThat(session.comparisonSource()).isEqualTo("TWSE_MIS_Y");
        assertThat(session.lastPrice()).isEqualTo(new BigDecimal("103.1234607889"));
        assertThat(session.change()).isEqualTo(new BigDecimal("3.1234607889"));
        assertThat(session.changePercent()).isEqualTo(new BigDecimal("3.123461"));
        verify(prices).findPreviousPriceBefore("0050", "台股", date);
    }

    @Test
    void sessionOverSixteenMiBFailsSoftWithoutPartialTicks() throws Exception {
        // Leading JSON whitespace keeps the response valid while exercising the finite byte cap.
        start(" ".repeat(16 * 1024 * 1024)
                + "{\"tradingDate\":\"2026-11-02\",\"ticks\":[{\"time\":\"2026-11-02T09:00:00\",\"price\":103.12}]}");

        var session = service(mock(StockPriceHistoryRepository.class))
                .fetchIntradaySession("0050", "台股", LocalDate.of(2026, 11, 2), true);

        assertUnavailableSession(session);
    }

    @Test
    void malformedSessionFailsSoftWithoutPartialTicks() throws Exception {
        start("{\"tradingDate\":\"2026-11-02\",\"ticks\":[{\"time\":\"2026-11-02T09:00:00\",\"price\":103.12},");

        var session = service(mock(StockPriceHistoryRepository.class))
                .fetchIntradaySession("0050", "台股", LocalDate.of(2026, 11, 2), true);

        assertUnavailableSession(session);
    }

    @Test
    void nonSessionQueryRetainsDefaultBufferLimit() throws Exception {
        String bar = "{\"time\":\"2026-11-02T09:00:00\",\"open\":103.12,\"high\":103.13,\"low\":103.11,\"close\":103.12}";
        String body = "[" + (bar + ',').repeat(4_000) + bar + ']';
        assertThat(body.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(256 * 1024);
        start("/internal/intraday-5m", body);

        assertThatThrownBy(() -> adapter().intraday5m("0050", "台股", 1))
                .hasRootCauseInstanceOf(DataBufferLimitException.class);
    }

    private static void assertUnavailableSession(HistoricalDataService.IntradaySession session) {
        assertThat(session.tradingDate()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(session.ticks()).isEmpty();
        assertThat(session.comparisonKind()).isEqualTo(HistoricalDataService.ComparisonKind.UNAVAILABLE);
        assertThat(session.sessionReferencePrice()).isNull();
        assertThat(session.sessionReferenceDate()).isNull();
        assertThat(session.sessionReferenceSource()).isNull();
        assertThat(session.comparisonPrice()).isNull();
        assertThat(session.comparisonSource()).isNull();
        assertThat(session.lastPrice()).isNull();
        assertThat(session.change()).isNull();
        assertThat(session.changePercent()).isNull();
    }

    private void start(String body) throws Exception {
        start("/internal/intraday-ticks", body);
    }

    private void start(String path, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(path, exchange -> {
            requestQuery = exchange.getRequestURI().getRawQuery();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
    }

    private HistoricalDataService service(StockPriceHistoryRepository prices) {
        return new HistoricalDataService(prices, mock(PriceQueryService.class),
                mock(ExchangeRateHistoryRepository.class), mock(CommodityPriceHistoryRepository.class),
                mock(FundMasterRepository.class), mock(TwseIndexDailyHistoryRepository.class),
                adapter());
    }

    private HistoricalDataHttpAdapter adapter() {
        return new HistoricalDataHttpAdapter("http://127.0.0.1:" + server.getAddress().getPort());
    }
}
