package com.steven.assets.service;

import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.repository.StockDividendHistoryRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DividendHistoryServiceTest {

    private final StockDividendHistoryRepository repo = mock(StockDividendHistoryRepository.class);
    private final DividendCurrentStateProjectionService projection =
            mock(DividendCurrentStateProjectionService.class);

    private HttpServer syncServer;
    private AtomicInteger syncCalls;
    private DividendHistoryService service;

    @BeforeEach
    void setUp() throws IOException {
        syncCalls = new AtomicInteger();
        syncServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        syncServer.createContext("/internal/dividend/sync", exchange -> {
            syncCalls.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        syncServer.start();
        service = new DividendHistoryService(repo, projection,
                "http://127.0.0.1:" + syncServer.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        if (syncServer != null) syncServer.stop(0);
    }

    @Test
    void pureReadNeverEnrichesOrProjectsAndFailuresStayIndependent() {
        DividendCashEnrichmentService enrichment = mock(DividendCashEnrichmentService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "enrichmentService", enrichment);
        when(repo.findByStockSinceYear(eq("0056"), eq("台股"), anyInt()))
                .thenReturn(List.of(history("0056", "台股", "TWSE")));
        service.findFromDbReadOnly("0056", "台股", 10);
        org.mockito.Mockito.verifyNoInteractions(enrichment, projection);
        assertThat(syncCalls).hasValue(0);
        when(projection.projectOne(eq("0056"), eq("台股"), any(Instant.class)))
                .thenThrow(new IllegalStateException("projection fixture"));
        org.mockito.Mockito.doThrow(new IllegalStateException("enrichment fixture"))
                .when(enrichment).enrich(eq("0056"), eq("台股"), any(Instant.class));
        assertThat(service.findFromDb("0056", "台股", 10).rows()).hasSize(1);
        verify(enrichment).enrich(eq("0056"), eq("台股"), any(Instant.class));
        assertThat(syncCalls).hasValue(0);
    }

    @Test
    void firstProjectionFailureFallsBackToExistingHistoryRows() {
        StockDividendHistory existing = history("0056", "台股", "TWSE");
        when(projection.projectOne(eq("0056"), eq("台股"), any(Instant.class)))
                .thenThrow(new IllegalStateException("projection unavailable"));
        when(repo.findByStockSinceYear(eq("0056"), eq("台股"), anyInt()))
                .thenReturn(List.of(existing));

        var result = service.findFromDb("0056", "台股", 10);

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().getFirst().cashDividend()).isEqualByComparingTo("1.25");
        assertThat(result.source()).isEqualTo("TWSE");
        assertThat(result.message()).isNull();
        assertThat(syncCalls).hasValue(0);
        verify(projection, times(1)).projectOne(
                eq("0056"), eq("台股"), any(Instant.class));
        verify(repo, times(1)).findByStockSinceYear(eq("0056"), eq("台股"), anyInt());
    }

    @Test
    void historyRepositoryFailureAfterSyncPropagatesInsteadOfBecomingAnEmptyResult() {
        var failure = new DataAccessResourceFailureException("history unavailable");
        when(repo.findByStockSinceYear(eq("0056"), eq("台股"), anyInt()))
                .thenReturn(List.of())
                .thenThrow(failure);

        assertThatThrownBy(() -> service.findFromDb("0056", "台股", 10))
                .isSameAs(failure);

        assertThat(syncCalls).hasValue(1);
        verify(projection, times(2)).projectOne(
                eq("0056"), eq("台股"), any(Instant.class));
        verify(repo, times(2)).findByStockSinceYear(eq("0056"), eq("台股"), anyInt());
    }

    @Test
    void emptyHistoryAfterSuccessfulSyncAndSecondProjectionReturnsSuccessfulEmptyResult() {
        when(repo.findByStockSinceYear(eq("COIN"), eq("美股"), anyInt()))
                .thenReturn(List.of())
                .thenReturn(List.of());

        var result = service.findFromDb("COIN", "美股", 10);

        assertThat(result.stockCode()).isEqualTo("COIN");
        assertThat(result.market()).isEqualTo("美股");
        assertThat(result.rows()).isEmpty();
        assertThat(result.message()).isEqualTo("查無資料");
        assertThat(result.source()).isNull();
        assertThat(syncCalls).hasValue(1);
        verify(projection, times(2)).projectOne(
                eq("COIN"), eq("美股"), any(Instant.class));
        verify(repo, times(2)).findByStockSinceYear(eq("COIN"), eq("美股"), anyInt());
    }

    @Test
    void secondProjectionFailureStillFallsBackToTheSecondHistoryQuery() {
        StockDividendHistory synced = history("0056", "台股", "FinMind");
        when(projection.projectOne(eq("0056"), eq("台股"), any(Instant.class)))
                .thenReturn(false)
                .thenThrow(new IllegalStateException("second projection unavailable"));
        when(repo.findByStockSinceYear(eq("0056"), eq("台股"), anyInt()))
                .thenReturn(List.of())
                .thenReturn(List.of(synced));

        var result = service.findFromDb("0056", "台股", 10);

        assertThat(result.rows()).hasSize(1);
        assertThat(result.source()).isEqualTo("FinMind");
        assertThat(result.message()).isNull();
        assertThat(syncCalls).hasValue(1);
        verify(projection, times(2)).projectOne(
                eq("0056"), eq("台股"), any(Instant.class));
        verify(repo, times(2)).findByStockSinceYear(eq("0056"), eq("台股"), anyInt());
    }

    private static StockDividendHistory history(String code, String market, String source) {
        return StockDividendHistory.builder()
                .stockCode(code)
                .market(market)
                .year(2026)
                .cashDividend(new BigDecimal("1.25"))
                .stockDividend(BigDecimal.ZERO)
                .exDividendDate(LocalDate.of(2026, 7, 21))
                .source(source)
                .eventStatus("ACTIVE")
                .build();
    }
}
