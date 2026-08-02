package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.CommodityFetchClient;
import com.steven.assets.externalmaterials.client.ExchangeRateFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient.HistoricalBar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 258：{@link HistoricalBackfillService#repairRange} —— 對既有錯誤列的覆寫式修復。
 *
 * <p>既有的 {@code backfillTwStock} 有兩道各自獨立的阻擋使它<b>修不了</b>已存在的錯誤列：
 * 起點一律 {@code maxDate.plusDays(1)}（碰不到區間中段）、for-loop 內
 * {@code if (store.existsHistory(...)) continue;}（skip-if-exists）。
 * {@code StockSourceQuery.upsertHistory} 本身是真 upsert（先 SELECT id、存在則 UPDATE），
 * 但那兩道守門讓 backfill 路徑從不對既有日期呼叫它。</p>
 *
 * <p>本檔釘住 {@code repairRange} 與它相反的語意，以及三條不得放寬的邊界：
 * 今日列獨佔、來源查無時不動既有列、單檔失敗不中斷整批。</p>
 */
class HistoricalRepairRangeTest {

    private static final String TW = "台股";

    private final PriceFetchClient priceFetch = mock(PriceFetchClient.class);
    private final ExchangeRateFetchClient rateFetch = mock(ExchangeRateFetchClient.class);
    private final CommodityFetchClient commodityFetch = mock(CommodityFetchClient.class);
    private final StockSourceQuery store = mock(StockSourceQuery.class);
    private final MarketDataFetchService etfFetch = mock(MarketDataFetchService.class);

    private final HistoricalBackfillService service =
            new HistoricalBackfillService(priceFetch, rateFetch, commodityFetch, store, etfFetch);

    @BeforeEach
    void stubUpsertAsWritten() {
        // Task 279：upsertHistory 改回 boolean（true = 實際寫入），且 rowsOverwritten 只在
        // 回 true 時累加。mock 的 boolean 預設是 false，不 stub 的話這裡的筆數斷言會全部變 0。
        // 一律回 true＝「這些案例的收盤價都是正常值」，與各案例的 bar(...) 資料相符。
        when(store.upsertHistory(anyString(), anyString(), any(),
                any(), any(), any(), any(), any())).thenReturn(true);
    }

    private static HistoricalBar bar(LocalDate d, String close) {
        return new HistoricalBar(d, new BigDecimal("18.6"), new BigDecimal("19.0"),
                new BigDecimal("18.5"), new BigDecimal(close), 41119896L, "2002");
    }

    // ── 258.5.8 既有列會被覆寫（與 backfill 的 skip-if-exists 對照）────

    /**
     * 本任務存在的理由：{@code existsHistory} 回 true 時<b>仍必須</b>覆寫。
     * 若有人照抄 {@code backfillTwStock} 把 skip-if-exists 帶進來，這條會變紅。
     */
    @Test
    void 既有列必須被覆寫而不是跳過() {
        LocalDate d = LocalDate.of(2026, 7, 20);
        when(priceFetch.fetchTwHistoricalRange(eq("2002"), any(), any()))
                .thenReturn(List.of(bar(d, "18.55")));
        when(store.existsHistory(anyString(), anyString(), any())).thenReturn(true);

        var summary = service.repairRange(TW, "2002", d, d.plusDays(1));

        verify(store).upsertHistory(eq("2002"), eq(TW), eq(d),
                any(), any(), any(), eq(new BigDecimal("18.55")), anyLong());
        assertThat(summary.rowsOverwritten()).isEqualTo(1);
    }

    // ── 258.5.9 今日列獨佔（Task 84）────────────────────────────────

    /**
     * {@code stock_price_history} 的市場時區當日列只能由 {@code ClosePersister} 收盤後路徑寫入。
     * 外部日線源在盤中也會回一根「今日 partial bar」（open/high/low ＋ 此刻 last trade 當 close）。
     */
    @Test
    void 該市場當日bar必須被跳過() {
        LocalDate today = LocalDate.now(MarketClock.zoneOf(TW));
        LocalDate past = today.minusDays(7);
        when(priceFetch.fetchTwHistoricalRange(eq("2002"), any(), any()))
                .thenReturn(List.of(bar(past, "18.55"), bar(today, "19.99")));

        var summary = service.repairRange(TW, "2002", past, today);

        verify(store).upsertHistory(eq("2002"), eq(TW), eq(past),
                any(), any(), any(), any(), anyLong());
        verify(store, never()).upsertHistory(eq("2002"), eq(TW), eq(today),
                any(), any(), any(), any(), anyLong());
        assertThat(summary.rowsOverwritten()).isEqualTo(1);
    }

    // ── 258.5.10 來源查無時不動既有列 ──────────────────────────────

    /**
     * 來源查無可能是真休市、可能是該檔當日無交易、也可能是來源暫時故障；
     * 三者都不足以支撐「刪掉既有資料」或「填一個近似值」（Requirement 7 禁止回寫充數）。
     */
    @Test
    void 來源完全沒回傳時不得寫入也不得刪除() {
        LocalDate d = LocalDate.of(2026, 7, 20);
        when(priceFetch.fetchTwHistoricalRange(eq("2002"), any(), any())).thenReturn(List.of());

        var summary = service.repairRange(TW, "2002", d, d.plusDays(5));

        verify(store, never()).upsertHistory(anyString(), anyString(), any(),
                any(), any(), any(), any(), anyLong());
        verify(store, never()).deleteTwHistoryOn(any());
        assertThat(summary.rowsOverwritten()).isZero();
        assertThat(summary.codesWithNoSource()).isEqualTo(1);
    }

    /** 區間內只有部分日期有 bar 時，沒有 bar 的那些日期完全不被碰。 */
    @Test
    void 區間內來源缺漏的日期完全不被碰() {
        LocalDate d1 = LocalDate.of(2026, 7, 20);
        LocalDate d3 = LocalDate.of(2026, 7, 22);
        when(priceFetch.fetchTwHistoricalRange(eq("2002"), any(), any()))
                .thenReturn(List.of(bar(d1, "18.55"), bar(d3, "18.85")));

        var summary = service.repairRange(TW, "2002", d1, d3.plusDays(1));

        verify(store, never()).upsertHistory(eq("2002"), eq(TW), eq(d1.plusDays(1)),
                any(), any(), any(), any(), anyLong());
        assertThat(summary.rowsOverwritten()).isEqualTo(2);
    }

    // ── 258.5.11 市場字串驗證 ──────────────────────────────────────

    @Test
    void 市場字串非三者之一時擲IllegalArgumentException() {
        LocalDate d = LocalDate.of(2026, 7, 20);

        assertThatThrownBy(() -> service.repairRange("日股", "7203", d, d.plusDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── 258.5.12 單檔失敗不中斷整批 ────────────────────────────────

    @Test
    void 單檔抓取失敗時其餘檔仍被處理且計入codesFailed() {
        LocalDate d = LocalDate.of(2026, 7, 20);
        when(priceFetch.fetchTwHistoricalRange(eq("2002"), any(), any()))
                .thenThrow(new RuntimeException("FinMind 逾時"));
        when(priceFetch.fetchTwHistoricalRange(eq("2412"), any(), any()))
                .thenReturn(List.of(bar(d, "144.5")));
        // 未指定 code → 代號集合由 collectAllStockCodes 供給
        doAnswerAddCodes("2002", "2412");

        var summary = service.repairRange(TW, null, d, d.plusDays(1));

        assertThat(summary.codesFailed()).isEqualTo(1);
        assertThat(summary.rowsOverwritten()).isEqualTo(1);
        verify(store).upsertHistory(eq("2412"), eq(TW), eq(d),
                any(), any(), any(), any(), anyLong());
    }

    /** 讓 {@code collectAllStockCodes} 把指定代號填進 twCodes 這個 out-param。 */
    @SuppressWarnings("unchecked")
    private void doAnswerAddCodes(String... codes) {
        org.mockito.Mockito.doAnswer(inv -> {
            java.util.Set<String> tw = inv.getArgument(0);
            tw.addAll(List.of(codes));
            return null;
        }).when(store).collectAllStockCodes(any(java.util.Set.class),
                any(java.util.Set.class), any(java.util.Set.class));
    }
}
