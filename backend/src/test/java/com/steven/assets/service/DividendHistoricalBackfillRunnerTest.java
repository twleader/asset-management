package com.steven.assets.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 357／357.3a／357.3c：一次性回補 runner 的分批／逐檔失敗清單／續跑行為。
 * 用本機 {@link HttpServer} 頂替 external-materials-service，驗證 runner 真的先打
 * {@code POST /internal/dividend/sync} 才呼叫 backend 端投影，且單檔失敗不中斷整批。
 */
class DividendHistoricalBackfillRunnerTest {

    private final DividendCurrentStateProjectionService projection =
            mock(DividendCurrentStateProjectionService.class);

    private HttpServer syncServer;
    private ConcurrentLinkedQueue<String> syncRequests;
    private DividendHistoricalBackfillRunner runner;

    @BeforeEach
    void setUp() throws IOException {
        syncRequests = new ConcurrentLinkedQueue<>();
        syncServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        syncServer.createContext("/internal/dividend/sync", exchange -> {
            syncRequests.add(exchange.getRequestURI().getQuery());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        syncServer.start();
        runner = new DividendHistoricalBackfillRunner(
                "http://127.0.0.1:" + syncServer.getAddress().getPort(), projection);
    }

    @AfterEach
    void tearDown() {
        if (syncServer != null) syncServer.stop(0);
    }

    @Test
    void 每一檔都先重抓再投影且成功清單逐檔記錄() {
        when(projection.projectOne(any(), any(), any(Instant.class))).thenReturn(true);

        var result = runner.backfill(List.of(
                new DividendHistoricalBackfillRunner.Target("7556", "台股"),
                new DividendHistoricalBackfillRunner.Target("9933", "台股")));

        assertThat(result.succeeded()).containsExactly("7556/台股", "9933/台股");
        assertThat(result.failed()).isEmpty();
        assertThat(syncRequests).hasSize(2)
                .anyMatch(q -> q.contains("code=7556") && q.contains("market=") )
                .anyMatch(q -> q.contains("code=9933"));
        verify(projection, times(1)).projectOne(eq("7556"), eq("台股"), any(Instant.class));
        verify(projection, times(1)).projectOne(eq("9933"), eq("台股"), any(Instant.class));
    }

    @Test
    void 單檔投影失敗記入失敗清單且不中斷其餘檔位() {
        when(projection.projectOne(eq("2881"), eq("台股"), any(Instant.class)))
                .thenThrow(new IllegalStateException("projection unavailable"));
        when(projection.projectOne(eq("2885"), eq("台股"), any(Instant.class))).thenReturn(true);

        var result = runner.backfill(List.of(
                new DividendHistoricalBackfillRunner.Target("2881", "台股"),
                new DividendHistoricalBackfillRunner.Target("2885", "台股")));

        assertThat(result.succeeded()).containsExactly("2885/台股");
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().getFirst().code()).isEqualTo("2881");
        assertThat(result.failed().getFirst().reason()).contains("projection unavailable");
        // 兩檔都必須被嘗試過重抓，2881 失敗不能阻止 2885 續跑。
        assertThat(syncRequests).hasSize(2);
    }

    @Test
    void projectOne回false視為失敗且揭露原因而非靜默當成功() {
        when(projection.projectOne(eq("2891"), eq("台股"), any(Instant.class))).thenReturn(false);

        var result = runner.backfill(
                List.of(new DividendHistoricalBackfillRunner.Target("2891", "台股")));

        assertThat(result.succeeded()).isEmpty();
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().getFirst().reason()).contains("projectOne 回傳 false");
    }

    @Test
    void 空清單直接回空結果不呼叫任何依賴() {
        var result = runner.backfill(List.of());

        assertThat(result.succeeded()).isEmpty();
        assertThat(result.failed()).isEmpty();
        assertThat(syncRequests).isEmpty();
    }
}
