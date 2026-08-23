package com.steven.assets.service;

import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.repository.StockDividendHistoryRepository;
import com.steven.assets.service.DividendBackfillTargetRepository.Target;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 357.3a／357.3b／357.3c：一次性回補 runner 的行為。
 *
 * <p>純單元測試：不啟動 Spring context、不連 DB、不打網路。回補的兩段（ext 重抓 ＋ backend
 * 投影）都以 mock 表示，斷言的是<b>編排</b>：分批、續跑、逐檔失敗、兩段順序、357.3b 修正清單。</p>
 */
class DividendBackfillServiceTest {

    /** 記憶體版 ledger；同時當成「已完成清單」與「稽核帳」的可觀測替身。 */
    private static final class InMemoryLedger implements DividendBackfillLedger {
        private final Set<String> completed = new LinkedHashSet<>();
        private final List<String> lines = new ArrayList<>();

        InMemoryLedger(String... alreadyCompleted) {
            completed.addAll(List.of(alreadyCompleted));
        }

        @Override public Set<String> completedKeys() { return Set.copyOf(completed); }

        @Override public void markCompleted(String key) { completed.add(key); }

        @Override public void appendLine(String line) { lines.add(line); }
    }

    private static final String TW = "台股";

    private static Target tw(String code) { return new Target(code, TW); }

    private static StockDividendHistory row(
            int year, String exDividend, String exRights, String cash, String stock) {
        return StockDividendHistory.builder()
                .year(year)
                .exDividendDate(exDividend == null ? null : LocalDate.parse(exDividend))
                .exRightsDate(exRights == null ? null : LocalDate.parse(exRights))
                .cashDividend(cash == null ? null : new BigDecimal(cash))
                .stockDividend(stock == null ? null : new BigDecimal(stock))
                .build();
    }

    private DividendBackfillService service(
            DividendBackfillTargetRepository targets,
            DividendResyncClient client,
            DividendCurrentStateProjectionService projection,
            StockDividendHistoryRepository history,
            DividendBackfillLedger ledger,
            int batchSize) {
        return new DividendBackfillService(
                targets, client, projection, history, ledger, batchSize, 0L, 2000);
    }

    @Test
    @DisplayName("357.3c：分批切分保留順序，批數為 ceil(N / 批次大小)")
    void partitionsPendingTargetsInOrder() {
        List<Target> pending = List.of(tw("1101"), tw("2330"), tw("2881"),
                tw("2885"), tw("2891"), tw("7556"), tw("9933"));

        List<List<Target>> batches = DividendBackfillService.partition(pending, 3);

        assertThat(batches).hasSize(3);
        assertThat(batches.get(0)).containsExactly(tw("1101"), tw("2330"), tw("2881"));
        assertThat(batches.get(1)).containsExactly(tw("2885"), tw("2891"), tw("7556"));
        assertThat(batches.get(2)).containsExactly(tw("9933"));
        // 攤平後必須逐位還原原清單：分批不得遺漏、重複或改順序。
        assertThat(batches.stream().flatMap(List::stream).toList()).isEqualTo(pending);
    }

    @Test
    @DisplayName("357.3c：批次大小 <= 0 視為不分批，不得無限迴圈或退化成每批一檔")
    void nonPositiveBatchSizeCollapsesToOneBatch() {
        List<Target> pending = List.of(tw("2881"), tw("2885"));

        assertThat(DividendBackfillService.partition(pending, 0)).hasSize(1);
        assertThat(DividendBackfillService.partition(pending, -5)).hasSize(1);
        assertThat(DividendBackfillService.partition(List.of(), 3)).isEmpty();
    }

    @Test
    @DisplayName("357.3a：兩段都會被呼叫，且一定是先 ext 重抓、後 backend 投影")
    void resyncsBeforeProjectingForEachTarget() {
        DividendBackfillTargetRepository targets = mock(DividendBackfillTargetRepository.class);
        DividendResyncClient client = mock(DividendResyncClient.class);
        DividendCurrentStateProjectionService projection =
                mock(DividendCurrentStateProjectionService.class);
        StockDividendHistoryRepository history = mock(StockDividendHistoryRepository.class);
        when(targets.findBackfillTargets()).thenReturn(List.of(tw("2881"), tw("7556")));
        when(history.findByStockSinceYear(anyString(), anyString(), anyInt()))
                .thenReturn(List.of());
        when(client.resyncOne(anyString(), anyString())).thenReturn(5);

        DividendBackfillService.Report report = service(
                targets, client, projection, history, new InMemoryLedger(), 10).run();

        InOrder order = inOrder(client, projection);
        order.verify(client).resyncOne("2881", TW);
        order.verify(projection).projectOne(eq("2881"), eq(TW), any(Instant.class));
        order.verify(client).resyncOne("7556", TW);
        order.verify(projection).projectOne(eq("7556"), eq(TW), any(Instant.class));
        order.verifyNoMoreInteractions();
        assertThat(report.succeeded()).containsExactly("台股|2881", "台股|7556");
        assertThat(report.failures()).isEmpty();
    }

    @Test
    @DisplayName("357.3c：已完成的標的不重跑，未完成的接續（中斷後續跑）")
    void skipsAlreadyCompletedTargetsAndResumesTheRest() {
        DividendBackfillTargetRepository targets = mock(DividendBackfillTargetRepository.class);
        DividendResyncClient client = mock(DividendResyncClient.class);
        DividendCurrentStateProjectionService projection =
                mock(DividendCurrentStateProjectionService.class);
        StockDividendHistoryRepository history = mock(StockDividendHistoryRepository.class);
        when(targets.findBackfillTargets())
                .thenReturn(List.of(tw("2881"), tw("2885"), tw("2891")));
        when(history.findByStockSinceYear(anyString(), anyString(), anyInt()))
                .thenReturn(List.of());
        InMemoryLedger ledger = new InMemoryLedger("台股|2881", "台股|2885");

        DividendBackfillService.Report report =
                service(targets, client, projection, history, ledger, 10).run();

        verify(client, never()).resyncOne("2881", TW);
        verify(client, never()).resyncOne("2885", TW);
        verify(client).resyncOne("2891", TW);
        verify(projection, never()).projectOne(eq("2881"), anyString(), any(Instant.class));
        assertThat(report.totalTargets()).isEqualTo(3);
        assertThat(report.alreadyDone()).isEqualTo(2);
        assertThat(report.attempted()).isEqualTo(1);
        assertThat(report.succeeded()).containsExactly("台股|2891");
        assertThat(ledger.completedKeys())
                .containsExactlyInAnyOrder("台股|2881", "台股|2885", "台股|2891");
    }

    @Test
    @DisplayName("357.3c：某檔拋例外時逐檔記錄失敗、不標記完成、其餘標的照跑")
    void recordsPerSymbolFailureWithoutStoppingTheRest() {
        DividendBackfillTargetRepository targets = mock(DividendBackfillTargetRepository.class);
        DividendResyncClient client = mock(DividendResyncClient.class);
        DividendCurrentStateProjectionService projection =
                mock(DividendCurrentStateProjectionService.class);
        StockDividendHistoryRepository history = mock(StockDividendHistoryRepository.class);
        when(targets.findBackfillTargets())
                .thenReturn(List.of(tw("2881"), tw("2885"), tw("2891")));
        when(history.findByStockSinceYear(anyString(), anyString(), anyInt()))
                .thenReturn(List.of());
        when(client.resyncOne("2881", TW)).thenReturn(3);
        when(client.resyncOne("2885", TW))
                .thenThrow(new IllegalStateException("FinMind 402 額度用罄"));
        when(client.resyncOne("2891", TW)).thenReturn(4);
        InMemoryLedger ledger = new InMemoryLedger();

        DividendBackfillService.Report report =
                service(targets, client, projection, history, ledger, 2).run();

        // 失敗的那一檔仍然被記下來，而且不影響後面的標的。
        assertThat(report.failures()).hasSize(1);
        assertThat(report.failures().getFirst().code()).isEqualTo("2885");
        assertThat(report.failures().getFirst().reason()).contains("額度用罄");
        assertThat(report.succeeded()).containsExactly("台股|2881", "台股|2891");
        verify(client).resyncOne("2891", TW);
        // 失敗的標的不得被標記完成，否則下一次續跑會永遠跳過它。
        assertThat(ledger.completedKeys()).containsExactlyInAnyOrder("台股|2881", "台股|2891");
        assertThat(ledger.lines).anyMatch(line -> line.startsWith("FAIL\t台股\t2885\t"));
        // 失敗的那一檔不得進入投影：投影只在重抓成功後才有意義。
        verify(projection, never()).projectOne(eq("2885"), anyString(), any(Instant.class));
        assertThat(report.batchCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("357.3b：舊 ex_dividend_date 其實是除權日的列，被逐檔記入修正清單")
    void reportsRowsWhoseExDividendDateWasReallyAnExRightsDate() {
        DividendBackfillTargetRepository targets = mock(DividendBackfillTargetRepository.class);
        DividendResyncClient client = mock(DividendResyncClient.class);
        DividendCurrentStateProjectionService projection =
                mock(DividendCurrentStateProjectionService.class);
        StockDividendHistoryRepository history = mock(StockDividendHistoryRepository.class);
        when(targets.findBackfillTargets()).thenReturn(List.of(tw("2881")));
        List<StockDividendHistory> before = List.of(
                // 純配股：舊資料把除權日塞進了除息日欄
                row(2025, "2025-09-25", null, "0", "1.2"),
                // 只配現金：回補前後都不動，不得被誤認為修正
                row(2019, "2019-07-24", null, "2.0", null));
        List<StockDividendHistory> after = List.of(
                row(2025, null, "2025-09-25", "0", "1.2"),
                row(2019, "2019-07-24", null, "2.0", null));
        when(history.findByStockSinceYear(eq("2881"), eq(TW), anyInt()))
                .thenReturn(before, after);
        InMemoryLedger ledger = new InMemoryLedger();

        DividendBackfillService.Report report =
                service(targets, client, projection, history, ledger, 10).run();

        assertThat(report.corrections()).hasSize(1);
        DividendBackfillService.Correction correction = report.corrections().getFirst();
        assertThat(correction.code()).isEqualTo("2881");
        assertThat(correction.market()).isEqualTo(TW);
        assertThat(correction.year()).isEqualTo(2025);
        assertThat(correction.movedDate()).isEqualTo(LocalDate.parse("2025-09-25"));
        assertThat(correction.cashDividend()).isEqualByComparingTo("0");
        assertThat(correction.stockDividend()).isEqualByComparingTo("1.2");
        assertThat(ledger.lines).anyMatch(line ->
                line.startsWith("CORRECTION\t台股\t2881\t") && line.contains("movedDate=2025-09-25"));
    }

    @Test
    @DisplayName("357.3b：新增除權日但除息日仍在（A 組）不算修正，避免把兩組混為一談")
    void addingAnExRightsDateAlongsideAnExistingExDividendDateIsNotACorrection() {
        DividendBackfillTargetRepository targets = mock(DividendBackfillTargetRepository.class);
        DividendResyncClient client = mock(DividendResyncClient.class);
        DividendCurrentStateProjectionService projection =
                mock(DividendCurrentStateProjectionService.class);
        StockDividendHistoryRepository history = mock(StockDividendHistoryRepository.class);
        when(targets.findBackfillTargets()).thenReturn(List.of(tw("7556")));
        List<StockDividendHistory> before = List.of(row(2025, "2025-07-03", null, "3.2", "0.5"));
        List<StockDividendHistory> after = List.of(
                row(2025, "2025-07-03", "2025-07-03", "3.2", "0.5"));
        when(history.findByStockSinceYear(eq("7556"), eq(TW), anyInt()))
                .thenReturn(before, after);

        DividendBackfillService.Report report = service(
                targets, client, projection, history, new InMemoryLedger(), 10).run();

        assertThat(report.corrections()).isEmpty();
        assertThat(report.succeeded()).containsExactly("台股|7556");
    }

    @Test
    @DisplayName("357.3b：金額 scale 不同不得讓修正比對失效（BigDecimal 一律 compareTo 語意）")
    void correctionMatchingIgnoresBigDecimalScale() {
        DividendBackfillTargetRepository targets = mock(DividendBackfillTargetRepository.class);
        DividendResyncClient client = mock(DividendResyncClient.class);
        DividendCurrentStateProjectionService projection =
                mock(DividendCurrentStateProjectionService.class);
        StockDividendHistoryRepository history = mock(StockDividendHistoryRepository.class);
        when(targets.findBackfillTargets()).thenReturn(List.of(tw("2885")));
        List<StockDividendHistory> before =
                List.of(row(2024, "2024-07-18", null, "0.0000", "1.2000"));
        List<StockDividendHistory> after =
                List.of(row(2024, null, "2024-07-18", "0.00000000", "1.20000000"));
        when(history.findByStockSinceYear(eq("2885"), eq(TW), anyInt()))
                .thenReturn(before, after);

        DividendBackfillService.Report report = service(
                targets, client, projection, history, new InMemoryLedger(), 10).run();

        assertThat(report.corrections()).hasSize(1);
        assertThat(report.corrections().getFirst().year()).isEqualTo(2024);
    }
}
