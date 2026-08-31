package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.repository.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoricalDataIntradaySessionHttpTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void webClientDecodesObjectSessionAndUsesStrictPreviousRawClose() throws Exception {
        String body = "{\"tradingDate\":\"2026-08-18\",\"ticks\":[{\"time\":\"2026-08-18T13:30:00\",\"price\":49.48}],\"sessionReferencePrice\":50.55,\"sessionReferenceDate\":\"2026-08-18\",\"sessionReferenceSource\":\"TWSE_MIS_Y\"}";
        start(body);
        StockPriceHistoryRepository prices = mock(StockPriceHistoryRepository.class);
        StockPriceHistory raw = mock(StockPriceHistory.class);
        when(raw.getClosePrice()).thenReturn(new BigDecimal("55.15"));
        when(prices.findPreviousPriceBefore("00881", "台股", LocalDate.of(2026, 8, 18))).thenReturn(Optional.of(raw));
        HistoricalDataService service = service(prices);

        var session = service.fetchIntradaySession("00881", "台股", null);

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

    private void start(String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/intraday-ticks", exchange -> {
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
                "http://127.0.0.1:" + server.getAddress().getPort());
    }
}
