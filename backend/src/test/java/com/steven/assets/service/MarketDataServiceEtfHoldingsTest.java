package com.steven.assets.service;

import com.steven.assets.model.FubonEtfHoldingsSnapshot;
import com.steven.assets.repository.FubonEtfHoldingsSnapshotRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MarketDataServiceEtfHoldingsTest {
    @Mock FubonEtfHoldingsSnapshotRepository repository;
    private static final String PAYLOAD = """
            {"schemaVersion":1,"stockCode":"0050","sourceDate":"2026-08-27","holdings":[
              {"stockCode":"2330","stockName":"台積電","weight":"58.82","shares":"530358242"}]}
            """;

    @Test
    void missingFailedInvalidAndEmptyStatesRemainDistinct() {
        var service = new MarketDataService("http://127.0.0.1:1", null, repository);
        when(repository.findById("0050")).thenReturn(Optional.empty());
        assertThat(service.getEtfHoldings("0050", "台股").message()).isEqualTo("尚無同步資料");
        var failed = snapshot(PAYLOAD); failed.setSuccess(false); failed.setReason("SDK_CALL_SATURATED");
        when(repository.findById("0050")).thenReturn(Optional.of(failed));
        assertThat(service.getEtfHoldings("0050", "台股").message()).isEqualTo("本次同步查詢失敗：SDK_CALL_SATURATED");
        failed.setReason("secret account=123");
        assertThat(service.getEtfHoldings("0050", "台股").message()).isEqualTo("本次同步查詢失敗：ETF_HOLDINGS_FAILED");
        when(repository.findById("0050")).thenReturn(Optional.of(snapshot("{}")));
        assertThat(service.getEtfHoldings("0050", "台股").message()).isEqualTo("同步資料格式不可用");
        when(repository.findById("0050")).thenReturn(Optional.of(snapshot("""
                {"schemaVersion":1,"stockCode":"0050","sourceDate":null,"holdings":[]}
                """)));
        var empty = service.getEtfHoldings("0050", "台股");
        assertThat(empty.supported()).isTrue();
        assertThat(empty.message()).isEqualTo("無股票成分資料");
        assertThat(empty.source()).isEqualTo("Fubon");
        assertThat(empty.asOfDate()).isNull();
        assertThat(empty.holdings()).isEmpty();
    }

    @Test
    void validSnapshotReturnsSourceDateNotLaterFetchDate() {
        when(repository.findById("0050")).thenReturn(Optional.of(snapshot(PAYLOAD)));
        var result = new MarketDataService("http://127.0.0.1:1", null, repository).getEtfHoldings("0050", "台股");
        assertThat(result.supported()).isTrue();
        assertThat(result.source()).isEqualTo("Fubon");
        assertThat(result.asOfDate()).isEqualTo("2026-08-27");
        assertThat(result.message()).isNull();
        assertThat(result.holdings()).hasSize(1);
        assertThat(result.holdings().getFirst().weight()).isEqualByComparingTo("58.82");
    }

    @Test
    void databaseFailureStaysFailSoftAndDoesNotFallback() {
        when(repository.findById("0050")).thenThrow(new IllegalStateException("private db error"));
        var result = new MarketDataService("http://127.0.0.1:1", null, repository).getEtfHoldings("0050", "台股");
        assertThat(result.supported()).isFalse();
        assertThat(result.message()).isEqualTo("同步資料暫時無法讀取");
    }

    @Test
    void taiwanReadStatesSendZeroExternalRequests() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/etf-holdings", exchange -> { calls.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); });
        server.start();
        try {
            var service = new MarketDataService("http://127.0.0.1:" + server.getAddress().getPort(), null, repository);
            when(repository.findById("0050")).thenReturn(Optional.empty(), Optional.of(snapshot(PAYLOAD)), Optional.of(snapshot("{}")));
            service.getEtfHoldings("0050", "台股"); service.getEtfHoldings("0050", "台股"); service.getEtfHoldings("0050", "台股");
            assertThat(calls).hasValue(0);
        } finally { server.stop(0); }
    }

    @Test
    void usMarketStillUsesExternalAndNeverReadsFubonRepository() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/etf-holdings", exchange -> {
            byte[] bytes = "{\"stockCode\":\"QQQ\",\"market\":\"美股\",\"supported\":true,\"source\":\"Yahoo\",\"asOfDate\":\"2026-08-28\",\"message\":null,\"holdings\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var result = new MarketDataService("http://127.0.0.1:" + server.getAddress().getPort(), null, repository).getEtfHoldings("QQQ", "美股");
            assertThat(result.supported()).isTrue();
            assertThat(result.source()).isEqualTo("Yahoo");
            verifyNoInteractions(repository);
        } finally { server.stop(0); }
    }

    @Test
    void unavailableUsServicePreservesExistingFallback() {
        var result = new MarketDataService("http://127.0.0.1:1", null, repository).getEtfHoldings("QQQ", "美股");
        assertThat(result.supported()).isFalse();
        assertThat(result.message()).isEqualTo("external-materials-service 不可用");
        verifyNoInteractions(repository);
    }

    private FubonEtfHoldingsSnapshot snapshot(String json) {
        return FubonEtfHoldingsSnapshot.builder().etfStockCode("0050").market("台股").success(true)
                .rawResponseJson(json).fetchedAt(Instant.parse("2026-08-28T07:30:00Z"))
                .updatedAt(Instant.parse("2026-08-28T07:30:00Z")).build();
    }
}
