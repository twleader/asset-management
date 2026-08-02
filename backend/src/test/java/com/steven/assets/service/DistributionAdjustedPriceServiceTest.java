package com.steven.assets.service;

import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.model.StockPriceHistory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistributionAdjustedPriceServiceTest {

    private final DistributionAdjustedPriceService service =
            new DistributionAdjustedPriceService();

    @Test
    void cashDistributionDoesNotCreateFalseMediumTermBreakdown() {
        LocalDate first = LocalDate.of(2025, 7, 1);
        LocalDate exDate = first.plusDays(221);
        List<StockPriceHistory> raw = new ArrayList<>();
        for (int i = 0; i < 241; i++) {
            BigDecimal close = i < 221
                    ? new BigDecimal("100.00")
                    : new BigDecimal("90.00").add(BigDecimal.valueOf(i - 221)
                            .divide(BigDecimal.valueOf(19), 8, RoundingMode.HALF_UP));
            raw.add(row(first.plusDays(i), close));
        }
        raw = raw.reversed();

        StockDividendHistory dividend = StockDividendHistory.builder()
                .id(1L)
                .stockCode("00751B")
                .market("台股")
                .exDividendDate(exDate)
                .cashDividend(new BigDecimal("10.00"))
                .build();

        var adjusted = service.adjust(raw, List.of(dividend));

        assertTrue(adjusted.adjusted());
        assertEquals(0, new BigDecimal("91.00").compareTo(adjusted.rowsDesc().get(0).getClosePrice()));
        assertTrue(average(raw, 60).compareTo(new BigDecimal("91.00")) > 0,
                "原始季線會被除息前價格墊高");
        assertTrue(average(adjusted.rowsDesc(), 60).compareTo(new BigDecimal("91.00")) < 0,
                "還原權息後現價應站回季線之上");
    }

    @Test
    void noEffectiveEventIsExactNoOp() {
        List<StockPriceHistory> rows = List.of(
                row(LocalDate.of(2026, 7, 17), new BigDecimal("31.44")),
                row(LocalDate.of(2026, 7, 16), new BigDecimal("31.38")));

        var adjusted = service.adjust(rows, List.of());

        assertFalse(adjusted.adjusted());
        assertEquals(rows, adjusted.rowsDesc());
    }

    @Test
    void stockDistributionUsesParValueFactorAndKeepsLatestPrice() {
        LocalDate exDate = LocalDate.of(2026, 1, 2);
        List<StockPriceHistory> rows = List.of(
                row(exDate, new BigDecimal("100.00")),
                row(exDate.minusDays(1), new BigDecimal("110.00")));
        StockDividendHistory dividend = StockDividendHistory.builder()
                .id(2L)
                .stockCode("TEST")
                .market("台股")
                .exDividendDate(exDate)
                .stockDividend(BigDecimal.ONE)
                .build();

        var adjusted = service.adjust(rows, List.of(dividend));

        assertTrue(adjusted.adjusted());
        assertEquals(0, new BigDecimal("100.00").compareTo(adjusted.rowsDesc().get(0).getClosePrice()));
        assertEquals(0, new BigDecimal("100.00").compareTo(adjusted.rowsDesc().get(1).getClosePrice()));
        assertTrue(changePercent(rows.get(0).getClosePrice(), rows.get(1).getClosePrice())
                .compareTo(new BigDecimal("-5")) < 0);
        assertEquals(0, BigDecimal.ZERO.compareTo(changePercent(
                adjusted.rowsDesc().get(0).getClosePrice(),
                adjusted.rowsDesc().get(1).getClosePrice())));
    }

    // ─── Task 265：股票分割還原 ──────────────────────────────────────────────

    /**
     * 本任務最關鍵的一條：分割偵測不得依賴除權息事件存在。
     *
     * <p>2327 是全庫兩筆真分割之一，而它在 {@code stock_dividend_history} 完全沒有紀錄。
     * 舊結構「無事件即原樣返回」對它永遠不生效卻也不報錯。</p>
     */
    @Test
    void splitIsAdjustedEvenWhenTheStockHasNoDividendRecordAtAll() {
        LocalDate splitDate = LocalDate.of(2025, 8, 25);
        List<StockPriceHistory> rows = List.of(
                row(splitDate.plusDays(1), new BigDecimal("145.00")),
                row(splitDate, new BigDecimal("143.00")),
                row(splitDate.minusDays(1), new BigDecimal("546.00")),
                row(splitDate.minusDays(2), new BigDecimal("552.00")));

        var adjusted = service.adjust(rows, List.of());

        assertTrue(adjusted.adjusted(), "無配息紀錄的標的仍須因分割而還原");
        // 分割後的價格不動，分割前的價格 ÷4 對齊。
        assertEquals(0, new BigDecimal("145.00").compareTo(adjusted.rowsDesc().get(0).getClosePrice()));
        assertEquals(0, new BigDecimal("143.00").compareTo(adjusted.rowsDesc().get(1).getClosePrice()));
        assertEquals(0, new BigDecimal("136.50").compareTo(adjusted.rowsDesc().get(2).getClosePrice()));
        assertEquals(0, new BigDecimal("138.00").compareTo(adjusted.rowsDesc().get(3).getClosePrice()));
        // 跨分割日不再出現數倍跳空。
        assertTrue(changePercent(
                adjusted.rowsDesc().get(1).getClosePrice(),
                adjusted.rowsDesc().get(2).getClosePrice()).abs().compareTo(BigDecimal.TEN) < 0,
                "還原後跨分割日的單日變動應回到常態區間");
    }

    /** 反向分割的因子 < 1；舊的 {@code factor > 1} 守衛使這條路徑在結構上無法表達。 */
    @Test
    void reverseSplitIsAdjusted() {
        LocalDate splitDate = LocalDate.of(2026, 3, 2);
        List<StockPriceHistory> rows = List.of(
                row(splitDate, new BigDecimal("80.00")),
                row(splitDate.minusDays(1), new BigDecimal("20.00")));

        var adjusted = service.adjust(rows, List.of());

        assertTrue(adjusted.adjusted(), "反向分割須被還原");
        assertEquals(0, new BigDecimal("80.00").compareTo(adjusted.rowsDesc().get(0).getClosePrice()));
        assertEquals(0, new BigDecimal("80.00").compareTo(adjusted.rowsDesc().get(1).getClosePrice()),
                "分割前價格須放大 4 倍");
    }

    /** 誤報防護：非整數倍的暴跌不得被當成分割。 */
    @Test
    void nonCanonicalGapsAreNotTreatedAsSplits() {
        for (String[] pair : new String[][] {
                {"100.00", "78.00"},   // −22%
                {"100.00", "147.00"},  // +47%
                {"100.00", "70.00"},   // −30%
                {"100.00", "29.41"}}) {  // ratio 3.4，誤差 15% 超出容差
            List<StockPriceHistory> rows = List.of(
                    row(LocalDate.of(2026, 3, 2), new BigDecimal(pair[1])),
                    row(LocalDate.of(2026, 3, 1), new BigDecimal(pair[0])));

            var adjusted = service.adjust(rows, List.of());

            assertFalse(adjusted.adjusted(), "非標準比例的跳空不得被還原：" + pair[0] + " → " + pair[1]);
            assertEquals(rows, adjusted.rowsDesc());
        }
    }

    /** 容差內側：比例 3.95（誤差 1.25%）須觸發 4:1 還原。 */
    @Test
    void ratioWithinToleranceTriggersCanonicalAdjustment() {
        List<StockPriceHistory> rows = List.of(
                row(LocalDate.of(2026, 3, 2), new BigDecimal("25.3165")),
                row(LocalDate.of(2026, 3, 1), new BigDecimal("100.00")));

        var adjusted = service.adjust(rows, List.of());

        assertTrue(adjusted.adjusted());
        assertEquals(0, new BigDecimal("25.00").compareTo(adjusted.rowsDesc().get(1).getClosePrice()),
                "須採標準比例 4 而非觀察值 3.95，保留當日真實漲跌");
    }

    /**
     * 非正收盤不得造成除以零或誤判。Task 279 前實測台股有 175 筆這種列，已全數刪除且
     * DB 加上 CHECK (close_price > 0)；本測試守的是程式端的縱深防禦，不因資料清乾淨而移除。
     */
    @Test
    void nonPositiveCloseRowsAreSkippedWithoutThrowing() {
        List<StockPriceHistory> rows = List.of(
                row(LocalDate.of(2026, 3, 3), new BigDecimal("50.00")),
                row(LocalDate.of(2026, 3, 2), BigDecimal.ZERO),
                row(LocalDate.of(2026, 3, 1), new BigDecimal("50.00")));

        var adjusted = service.adjust(rows, List.of());

        assertFalse(adjusted.adjusted());
        assertEquals(rows, adjusted.rowsDesc());
    }

    /** 配息與分割並存時，累積因子須依日期升冪正確疊乘。 */
    @Test
    void splitAndCashDividendCompoundInDateOrder() {
        LocalDate splitDate = LocalDate.of(2026, 2, 2);
        LocalDate exDate = LocalDate.of(2026, 3, 2);
        List<StockPriceHistory> rows = List.of(
                row(exDate, new BigDecimal("45.00")),
                row(exDate.minusDays(1), new BigDecimal("50.00")),
                row(splitDate, new BigDecimal("50.00")),
                row(splitDate.minusDays(1), new BigDecimal("200.00")));
        StockDividendHistory dividend = StockDividendHistory.builder()
                .id(3L)
                .stockCode("00751B")
                .market("台股")
                .exDividendDate(exDate)
                .cashDividend(new BigDecimal("5.00"))
                .build();

        var adjusted = service.adjust(rows, List.of(dividend));

        assertTrue(adjusted.adjusted());
        // 最新價不動；分割前的 200 先 ÷4 得 50，再乘上除息因子 45/50 得 45。
        assertEquals(0, new BigDecimal("45.00").compareTo(adjusted.rowsDesc().get(0).getClosePrice()));
        assertEquals(0, new BigDecimal("45.00").compareTo(adjusted.rowsDesc().get(3).getClosePrice()));
    }

    /**
     * 大額股票股利（配股 10 元＝1:1）同樣使價格腰斬，不得同時被認定為 2:1 分割。
     *
     * <p>若不排除，該事件會被 {@code dividendFactor} 與 {@code detectSplits} 各計一次、
     * shares 被乘成 4 倍，分割前價格被縮成 1/4 而非 1/2——方向錯、幅度錯、且不拋任何例外。</p>
     */
    @Test
    void largeStockDividendIsNotAlsoCountedAsSplit() {
        LocalDate exDate = LocalDate.of(2026, 4, 1);
        List<StockPriceHistory> rows = List.of(
                row(exDate, new BigDecimal("50.00")),
                row(exDate.minusDays(1), new BigDecimal("100.00")));
        StockDividendHistory stockDividend = StockDividendHistory.builder()
                .id(9L).stockCode("00751B").market("台股")
                .exDividendDate(exDate)
                .stockDividend(BigDecimal.TEN)   // 配股 10 元 → 1:1，價格腰斬
                .build();

        var adjusted = service.adjust(rows, List.of(stockDividend));

        assertTrue(adjusted.adjusted());
        assertEquals(0, new BigDecimal("50.00").compareTo(adjusted.rowsDesc().get(0).getClosePrice()));
        assertEquals(0, new BigDecimal("50.00").compareTo(adjusted.rowsDesc().get(1).getClosePrice()),
                "只能被除權還原一次（100 → 50），不得再被分割還原成 25");
    }

    /** 0050 真實序列：還原後年線不再被分割前的高價拉高。 */
    @Test
    void realSplitDoesNotInflateAnnualMovingAverage() {
        LocalDate splitDate = LocalDate.of(2025, 6, 18);
        List<StockPriceHistory> raw = new ArrayList<>();
        // 分割後 20 根（約 47.5），分割前 20 根（約 188.6）
        for (int i = 19; i >= 0; i--) raw.add(row(splitDate.plusDays(i), new BigDecimal("47.57")));
        for (int i = 1; i <= 20; i++) raw.add(row(splitDate.minusDays(i), new BigDecimal("188.65")));

        var adjusted = service.adjust(raw, List.of());

        assertTrue(adjusted.adjusted(), "0050 的 1:4 分割須被偵測");
        BigDecimal ma = average(adjusted.rowsDesc(), 40);
        assertTrue(ma.compareTo(new BigDecimal("60.00")) < 0,
                "還原後均線須落在分割後價基（實得 " + ma + "）；未還原時會被 188.65 拉高到約 118");
    }

    /** OHLC 一致性：可捕捉「只還原 close 沒還原 high/low」這類錯誤。 */
    @Test
    void adjustedRowsKeepOhlcOrdering() {
        LocalDate splitDate = LocalDate.of(2026, 3, 2);
        List<StockPriceHistory> rows = List.of(
                ohlc(splitDate, "24.00", "26.00", "23.00", "25.00"),
                ohlc(splitDate.minusDays(1), "98.00", "104.00", "96.00", "100.00"));

        var adjusted = service.adjust(rows, List.of());

        assertTrue(adjusted.adjusted());
        for (StockPriceHistory r : adjusted.rowsDesc()) {
            assertTrue(r.getLowPrice().compareTo(r.getOpenPrice().min(r.getClosePrice())) <= 0,
                    "low 須不高於 open/close 的較小者");
            assertTrue(r.getHighPrice().compareTo(r.getOpenPrice().max(r.getClosePrice())) >= 0,
                    "high 須不低於 open/close 的較大者");
        }
        assertEquals(0, new BigDecimal("25.00").compareTo(adjusted.rowsDesc().get(1).getClosePrice()));
        assertEquals(0, new BigDecimal("26.00").compareTo(adjusted.rowsDesc().get(1).getHighPrice()));
        assertEquals(0, new BigDecimal("24.00").compareTo(adjusted.rowsDesc().get(1).getLowPrice()));
    }

    private StockPriceHistory ohlc(LocalDate date, String open, String high, String low, String close) {
        return StockPriceHistory.builder()
                .stockCode("2327")
                .market("台股")
                .tradingDate(date)
                .openPrice(new BigDecimal(open))
                .highPrice(new BigDecimal(high))
                .lowPrice(new BigDecimal(low))
                .closePrice(new BigDecimal(close))
                .build();
    }

    private StockPriceHistory row(LocalDate date, BigDecimal close) {
        return StockPriceHistory.builder()
                .stockCode("00751B")
                .market("台股")
                .tradingDate(date)
                .openPrice(close)
                .highPrice(close)
                .lowPrice(close)
                .closePrice(close)
                .build();
    }

    private BigDecimal average(List<StockPriceHistory> rowsDesc, int days) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < days; i++) {
            sum = sum.add(rowsDesc.get(i).getClosePrice());
        }
        return sum.divide(BigDecimal.valueOf(days), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal changePercent(BigDecimal current, BigDecimal previous) {
        return current.subtract(previous)
                .divide(previous, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }
}
