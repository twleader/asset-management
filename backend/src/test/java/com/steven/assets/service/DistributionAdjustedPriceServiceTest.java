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

        var adjusted = service.adjust(raw, canonical(dividend));

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

        var adjusted = service.adjust(rows, canonical(dividend));

        assertTrue(adjusted.adjusted());
        assertEquals(0, new BigDecimal("100.00").compareTo(adjusted.rowsDesc().get(0).getClosePrice()));
        assertEquals(0, new BigDecimal("100.00").compareTo(adjusted.rowsDesc().get(1).getClosePrice()));
        assertTrue(changePercent(rows.get(0).getClosePrice(), rows.get(1).getClosePrice())
                .compareTo(new BigDecimal("-5")) < 0);
        assertEquals(0, BigDecimal.ZERO.compareTo(changePercent(
                adjusted.rowsDesc().get(0).getClosePrice(),
                adjusted.rowsDesc().get(1).getClosePrice())));
    }

    @Test
    void forwardSplitAdjustsHistoricalVolumeToTheLatestShareBasis() {
        LocalDate splitDate = LocalDate.of(2026, 3, 2);
        List<StockPriceHistory> rows = List.of(
                rowWithVolume(splitDate, "25.00", 400L),
                rowWithVolume(splitDate.minusDays(1), "100.00", 100L));

        var adjusted = service.adjust(rows, List.of());

        assertTrue(adjusted.adjusted());
        assertEquals(400L, adjusted.rowsDesc().get(0).getVolume());
        assertEquals(400L, adjusted.rowsDesc().get(1).getVolume(),
                "1:4 分割前的成交股數須乘 4，才能和分割後的相對量同股數基準");
    }

    @Test
    void cashDividendAdjustsPriceButNeverVolume() {
        LocalDate exDate = LocalDate.of(2026, 4, 1);
        List<StockPriceHistory> rows = List.of(
                rowWithVolume(exDate, "90.00", 120L),
                rowWithVolume(exDate.minusDays(1), "100.00", 100L));
        StockDividendHistory dividend = StockDividendHistory.builder()
                .id(20L).stockCode("00751B").market("台股")
                .exDividendDate(exDate).cashDividend(BigDecimal.TEN).build();

        var adjusted = service.adjust(rows, canonical(dividend));

        assertTrue(adjusted.adjusted());
        assertEquals(120L, adjusted.rowsDesc().get(0).getVolume());
        assertEquals(100L, adjusted.rowsDesc().get(1).getVolume(),
                "現金股利不改變流通股數，歷史成交量不得跟著價格因子縮放");
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

        var adjusted = service.adjust(rows, canonical(dividend));

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

        var adjusted = service.adjust(rows, canonical(stockDividend));

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

    // ─── Task 356.4e：appliedEventDates ────────────────────────────────────

    /**
     * {@code appliedEventDates} 是呼叫端判斷「事件是否落在自己的視窗內」的唯一依據：
     * {@code adjusted()} 只說「整段序列有沒有被縮放」，不說事件在哪一天。
     */
    @Test
    void appliedEventDatesListsEveryGrowthChangingEventAscending() {
        LocalDate splitDate = LocalDate.of(2026, 2, 2);
        LocalDate exDate = LocalDate.of(2026, 3, 2);
        List<StockPriceHistory> rows = List.of(
                row(exDate, new BigDecimal("45.00")),
                row(exDate.minusDays(1), new BigDecimal("50.00")),
                row(splitDate, new BigDecimal("50.00")),
                row(splitDate.minusDays(1), new BigDecimal("200.00")));
        StockDividendHistory dividend = StockDividendHistory.builder()
                .id(31L).stockCode("00751B").market("台股")
                .exDividendDate(exDate).cashDividend(new BigDecimal("5.00")).build();

        var adjusted = service.adjust(rows, canonical(dividend));

        assertTrue(adjusted.adjusted());
        assertEquals(List.of(splitDate, exDate), adjusted.appliedEventDates(),
                "分割與除息都會改動 growth，且必須依日期升冪");
    }

    @Test
    void appliedEventDatesIsEmptyWhenNothingWasActuallyScaled() {
        List<StockPriceHistory> rows = List.of(
                row(LocalDate.of(2026, 4, 2), new BigDecimal("100.00")),
                row(LocalDate.of(2026, 4, 1), new BigDecimal("100.00")));

        var adjusted = service.adjust(rows, List.of());

        assertFalse(adjusted.adjusted());
        assertTrue(adjusted.appliedEventDates().isEmpty());
    }

    @Test
    void compatibilityConstructorLeavesAppliedEventDatesEmpty() {
        var adjustment = new DistributionAdjustedPriceService.Adjustment(List.of(), true);

        assertTrue(adjustment.appliedEventDates().isEmpty());
    }

    // ───────────────────── Task 357／Requirement 94 ─────────────────────

    /**
     * 357.4a-3（NPE 陷阱，必修）：純配股事件（exDividendDate 為 null）在 validEvent()
     * 放寬後，若 adjust() 的區間過濾／排序／FactorEvent 構造／closeOn 查價任一處仍直接呼叫
     * getExDividendDate()，會立刻 NullPointerException。以 2881 實際查得的真實事件
     * （2022 年純配股 stockDividend=2.690500，除權日 2022-09-22，exDividendDate 拆欄後為
     * null）驗證不拋例外，且還原後的股數／價格因子正確（面額 10 元：股票股利 2.6905 元
     * → 1 股配 0.26905 股 → shareGrowth=1.26905）。
     */
    @Test
    void pureStockDividendWithNullExDividendDateAdjustsWithoutThrowing() {
        LocalDate exRights = LocalDate.of(2022, 9, 22);
        List<StockPriceHistory> rows = List.of(
                row(exRights.plusDays(1), new BigDecimal("30.00")),
                row(exRights, new BigDecimal("38.00")),
                row(exRights.minusDays(1), new BigDecimal("38.00")));
        StockDividendHistory pureStock = StockDividendHistory.builder()
                .id(41L).stockCode("2881").market("台股").year(2022)
                .exDividendDate(null).exRightsDate(exRights)
                .cashDividend(BigDecimal.ZERO).stockDividend(new BigDecimal("2.690500"))
                .build();

        var adjusted = service.adjust(rows, canonical(pureStock));

        assertTrue(adjusted.adjusted());
        assertEquals(List.of(exRights), adjusted.appliedEventDates());
        // 「還原權息」以最新價基為準：事件後最新一列（30.00）還原後應維持原值不變，
        // 事件前的列才會被縮放——與既有 stockDistributionUsesParValueFactorAndKeepsLatestPrice
        // 同一慣例，這裡驗的是純配股（null exDividendDate）走同一條路徑不拋例外。
        assertEquals(0, new BigDecimal("30.00").compareTo(
                adjusted.rowsDesc().stream()
                        .filter(r -> r.getTradingDate().equals(exRights.plusDays(1)))
                        .findFirst().orElseThrow().getClosePrice()));
    }

    /**
     * 357.4a（核心規則）：現金股利套用 exDividendDate、股票股利套用 exRightsDate；兩者
     * 皆有值且不同日時<b>拆成兩筆獨立事件</b>，各自在自己的日期套用因子，不得合併或只取
     * 其一。用一組除息日與除權日刻意不同的合成事件驗證：事件之間那一天只套現金因子，
     * 除權日當天才追加股票因子。
     */
    @Test
    void cashAndStockDividendOnDifferentDatesSplitIntoTwoIndependentFactorEvents() {
        LocalDate exDividend = LocalDate.of(2026, 7, 10);
        LocalDate exRights = LocalDate.of(2026, 7, 20);
        List<StockPriceHistory> rows = List.of(
                row(exRights, new BigDecimal("100.00")),
                row(exDividend, new BigDecimal("100.00")),
                row(exDividend.minusDays(1), new BigDecimal("100.00")));
        StockDividendHistory event = StockDividendHistory.builder()
                .id(42L).stockCode("7556").market("台股").year(2026)
                .exDividendDate(exDividend).exRightsDate(exRights)
                .cashDividend(new BigDecimal("2.00")).stockDividend(new BigDecimal("1.00"))
                .build();

        var adjusted = service.adjust(rows, canonical(event));

        assertTrue(adjusted.adjusted());
        assertEquals(List.of(exDividend, exRights), adjusted.appliedEventDates(),
                "現金與股票因子必須各自在自己的日期套用，而不是合併成單一事件日");
    }

    /**
     * 357.4a 的另一半：兩個日期<b>相同</b>時（含只有一方有值、fallback 到同一個
     * anchorDate 的情形）維持既有「一事件一日期」的合併因子，逐位不變既有回歸
     * （357.4b）——不得因新增除權日欄就意外把同日事件也拆成兩筆。
     */
    @Test
    void cashAndStockDividendOnTheSameDateStayAsOneCombinedFactorEvent() {
        LocalDate sameDate = LocalDate.of(2026, 7, 10);
        List<StockPriceHistory> rows = List.of(
                row(sameDate, new BigDecimal("100.00")),
                row(sameDate.minusDays(1), new BigDecimal("100.00")));
        StockDividendHistory event = StockDividendHistory.builder()
                .id(43L).stockCode("2330").market("台股").year(2026)
                .exDividendDate(sameDate).exRightsDate(sameDate)
                .cashDividend(new BigDecimal("2.00")).stockDividend(new BigDecimal("1.00"))
                .build();

        var adjusted = service.adjust(rows, canonical(event));

        assertTrue(adjusted.adjusted());
        assertEquals(List.of(sameDate), adjusted.appliedEventDates(),
                "同日兩個因子必須合併成單一事件，不得因除權日欄存在就拆成兩筆");
    }

    /**
     * 357.4a-2：hasStockDividendOn() 必須改判除權日。純配股事件的除息日為 null，若判斷式
     * 仍讀 getExDividendDate()，這個保護會對這類事件失效——用一個「除息日缺值、除權日
     * 恰好與跳空同一天」的合成分割樣本驗證：跳空幅度落在 2:1 分割門檻內，但因為有除權日
     * 可解釋，不得被誤判為分割。
     */
    @Test
    void hasStockDividendOnJudgesExRightsDateNotExDividendDateForPureStockEvent() {
        LocalDate jumpDate = LocalDate.of(2026, 5, 4);
        List<StockPriceHistory> rows = List.of(
                row(jumpDate, new BigDecimal("50.00")),
                row(jumpDate.minusDays(1), new BigDecimal("100.00")));
        StockDividendHistory pureStock = StockDividendHistory.builder()
                .id(44L).stockCode("00881").market("台股").year(2026)
                .exDividendDate(null).exRightsDate(jumpDate)
                .cashDividend(BigDecimal.ZERO).stockDividend(new BigDecimal("10.00"))
                .build();

        var adjusted = service.adjust(rows, canonical(pureStock));

        // 若被誤判為分割，appliedEventDates 會多出一筆 detectSplits 產生的事件；
        // 純配股 10 元＝1:1 換股，dividendFactor 應為 2（面額 10），與分割的 canonical 2
        // 湊巧同值，故真正該驗的是「只有一筆事件」而非分割＋配股各算一次。
        assertTrue(adjusted.adjusted());
        assertEquals(List.of(jumpDate), adjusted.appliedEventDates(),
                "跳空已由股票股利（除權日）解釋，不得再被 detectSplits 認定為分割");
    }

    // ─── Task 565：detectSplits 抽出共用比值 helper 後，門檻邊界與既有輸出逐位不變 ───────────

    /** 邊界特徵測試（重構前後皆綠）：比值恰 2.0 與恰 0.5 仍是疑似分割，1.99 與 0.51 不是。 */
    @Test
    void detectSplitsBoundaryRatiosAreUnchangedByTheSharedHelper() {
        LocalDate day = LocalDate.of(2026, 3, 2);
        var forwardAtTwo = service.adjust(List.of(
                row(day, new BigDecimal("50.00")), row(day.minusDays(1), new BigDecimal("100.00"))), List.of());
        assertTrue(forwardAtTwo.adjusted(), "100 → 50（比值 2.0）是 2:1 分割");
        assertEquals(0, new BigDecimal("50.00").compareTo(forwardAtTwo.rowsDesc().get(1).getClosePrice()));

        var reverseAtHalf = service.adjust(List.of(
                row(day, new BigDecimal("100.00")), row(day.minusDays(1), new BigDecimal("50.00"))), List.of());
        assertTrue(reverseAtHalf.adjusted(), "50 → 100（比值 0.5）是 1:2 反向分割");

        assertFalse(service.adjust(List.of(
                row(day, new BigDecimal("50.25")), row(day.minusDays(1), new BigDecimal("100.00"))), List.of())
                .adjusted(), "比值 1.99 不是分割");
        assertFalse(service.adjust(List.of(
                row(day, new BigDecimal("100.00")), row(day.minusDays(1), new BigDecimal("51.00"))), List.of())
                .adjusted(), "比值 0.51 不是分割");
    }

    // ─── Task 565：美股 price-only 價基（priceOnlyBasis） ──────────────────────────────

    private static final LocalDate PO_NEWEST = LocalDate.of(2026, 8, 10);

    /** 降序連續（逐日）收盤序列，最新一列 {@link #PO_NEWEST}；相鄰變動 0.01，遠離分割門檻。 */
    private List<StockPriceHistory> poContinuousRowsDesc(int count) {
        List<StockPriceHistory> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(row(PO_NEWEST.minusDays(i),
                    new BigDecimal("100.00").subtract(new BigDecimal("0.01").multiply(BigDecimal.valueOf(i)))));
        }
        return rows;
    }

    private static StockDividendHistory poEvent(LocalDate canonicalDate, String cash, String stock) {
        return StockDividendHistory.builder()
                .id(901L).stockCode("VOO").market("美股").year(2026)
                .canonicalDate(canonicalDate)
                .exDividendDate(canonicalDate)
                .cashDividend(cash == null ? null : new BigDecimal(cash))
                .stockDividend(stock == null ? null : new BigDecimal(stock))
                .resolutionStatus("RESOLVED").eventStatus("ACTIVE").build();
    }

    private static String poUnavailableMessage(Runnable action) {
        return org.junit.jupiter.api.Assertions.assertThrows(
                com.steven.assets.model.CanonicalDividendFinancialGuard.Unavailable.class, action::run).getMessage();
    }

    @Test
    void priceOnlyBasisReturnsAnEmptyUnadjustedResultForNullOrEmptyRows() {
        for (List<StockPriceHistory> rows : java.util.Arrays.<List<StockPriceHistory>>asList(null, List.of())) {
            var result = service.priceOnlyBasis(rows, List.of());
            assertTrue(result.rowsDesc().isEmpty());
            assertFalse(result.adjusted());
            assertTrue(result.appliedEventDates().isEmpty());
        }
    }

    @Test
    void priceOnlyBasisKeepsAContinuousSeriesUnscaledAndDoesNotMutateTheInput() {
        List<StockPriceHistory> rows = poContinuousRowsDesc(60);
        List<StockPriceHistory> before = new ArrayList<>(rows);

        var result = service.priceOnlyBasis(rows, List.of());

        assertFalse(result.adjusted(), "price-only 不還原，adjusted 恆為 false");
        assertEquals(before, result.rowsDesc());
        assertEquals(before, rows, "不得改動輸入序列");
        for (int i = 0; i < rows.size(); i++) {
            org.junit.jupiter.api.Assertions.assertSame(rows.get(i), result.rowsDesc().get(i),
                    "原列副本：不得縮放任何價格（縮放會把 scale 4 變成 scale 8 的新列）");
        }
        org.junit.jupiter.api.Assertions.assertNotSame(rows, result.rowsDesc());
    }

    @Test
    void priceOnlyBasisRejectsAGapOfExactlyTwoAndExactlyOneHalfButNotJustInsideTheBand() {
        LocalDate day = PO_NEWEST;
        String forward = poUnavailableMessage(() -> service.priceOnlyBasis(List.of(
                row(day, new BigDecimal("50.00")), row(day.minusDays(1), new BigDecimal("100.00"))), List.of()));
        assertEquals("US_PRICE_ONLY_BASIS_BREAK:" + day, forward, "前收 ÷ 後收恰 2.0；日期取後一列");

        String reverse = poUnavailableMessage(() -> service.priceOnlyBasis(List.of(
                row(day, new BigDecimal("100.00")), row(day.minusDays(1), new BigDecimal("50.00"))), List.of()));
        assertEquals("US_PRICE_ONLY_BASIS_BREAK:" + day, reverse, "前收 ÷ 後收恰 0.5；日期取後一列");

        // 門檻內側：1.99 與 0.51 通過（不放寬也不收緊 detectSplits 的門檻）。
        assertFalse(service.priceOnlyBasis(List.of(
                row(day, new BigDecimal("50.25")), row(day.minusDays(1), new BigDecimal("100.00"))), List.of())
                .adjusted());
        assertFalse(service.priceOnlyBasis(List.of(
                row(day, new BigDecimal("100.00")), row(day.minusDays(1), new BigDecimal("51.00"))), List.of())
                .adjusted());
    }

    @Test
    void priceOnlyBasisRejectsANonStandardRatioToo() {
        // 2.3 不接近 {2,3,4,5,10}：detectSplits 只 warn 不還原，price-only 沒有能力還原 → 一律放棄。
        LocalDate day = PO_NEWEST;
        assertEquals("US_PRICE_ONLY_BASIS_BREAK:" + day, poUnavailableMessage(() -> service.priceOnlyBasis(List.of(
                row(day, new BigDecimal("100.00")), row(day.minusDays(1), new BigDecimal("230.00"))), List.of())));
    }

    @Test
    void priceOnlyBasisReportsTheDateOfTheLaterRowOfTheBreakingPairInTheMiddleOfTheSeries() {
        List<StockPriceHistory> rows = poContinuousRowsDesc(50);
        // 第 20 列（含）以前的舊列全部放大 2.5 倍：斷層出現在第 19、20 列之間，後一列＝第 19 列。
        List<StockPriceHistory> broken = new ArrayList<>(rows.subList(0, 20));
        for (int i = 20; i < rows.size(); i++) {
            broken.add(row(rows.get(i).getTradingDate(), rows.get(i).getClosePrice().multiply(new BigDecimal("2.5"))));
        }
        assertEquals("US_PRICE_ONLY_BASIS_BREAK:" + rows.get(19).getTradingDate(),
                poUnavailableMessage(() -> service.priceOnlyBasis(broken, List.of())));
    }

    @Test
    void priceOnlyBasisDoesNotApplyTheStockDividendExemptionThatDetectSplitsHas() {
        LocalDate day = PO_NEWEST;
        StockDividendHistory stockDividend = StockDividendHistory.builder()
                .id(902L).stockCode("X").market("美股").year(2026).canonicalDate(day).exRightsDate(day)
                .cashDividend(BigDecimal.ZERO).stockDividend(BigDecimal.TEN)
                .resolutionStatus("RESOLVED").eventStatus("ACTIVE").build();
        List<StockPriceHistory> rows = List.of(
                row(day, new BigDecimal("50.00")), row(day.minusDays(1), new BigDecimal("100.00")));

        assertEquals("US_PRICE_ONLY_BASIS_BREAK:" + day,
                poUnavailableMessage(() -> service.priceOnlyBasis(rows, List.of(stockDividend))),
                "adjust 會以股票股利解釋這個跳空，price-only 沒有還原能力，不得套用該豁免");
    }

    @Test
    void priceOnlyBasisSkipsPairsWithANullOrNonPositiveClose() {
        LocalDate day = PO_NEWEST;
        for (BigDecimal hole : new BigDecimal[] {BigDecimal.ZERO, null}) {
            var result = service.priceOnlyBasis(List.of(
                    row(day, new BigDecimal("50.00")),
                    row(day.minusDays(1), hole),
                    row(day.minusDays(2), new BigDecimal("50.00"))), List.of());
            assertFalse(result.adjusted());
            assertEquals(3, result.rowsDesc().size(), "缺值列被跳過、不誤拋，也不被移除");
        }
    }

    @Test
    void priceOnlyBasisCooldownThrowsOnTheFifteenthBarFromTheExDividendDayAndPassesOnTheSixteenth() {
        List<StockPriceHistory> rows = poContinuousRowsDesc(40);
        LocalDate fifteenthBar = PO_NEWEST.minusDays(14);   // 含除息日當根共 15 根
        LocalDate sixteenthBar = PO_NEWEST.minusDays(15);   // 共 16 根

        assertEquals("US_PRICE_ONLY_RECENT_EX_DIVIDEND:" + fifteenthBar,
                poUnavailableMessage(() -> service.priceOnlyBasis(rows, List.of(poEvent(fifteenthBar, "1.50", "0")))));
        assertFalse(service.priceOnlyBasis(rows, List.of(poEvent(sixteenthBar, "1.50", "0"))).adjusted());
        // 除息日恰為最新一列（只有 1 根）也要退避。
        assertEquals("US_PRICE_ONLY_RECENT_EX_DIVIDEND:" + PO_NEWEST,
                poUnavailableMessage(() -> service.priceOnlyBasis(rows, List.of(poEvent(PO_NEWEST, "1.50", "0")))));
        assertEquals(15, DistributionAdjustedPriceService.US_PRICE_ONLY_EX_DIVIDEND_COOLDOWN_BARS);
    }

    @Test
    void priceOnlyBasisUsesTheLatestEffectiveDateWhenThereAreSeveralEvents() {
        List<StockPriceHistory> rows = poContinuousRowsDesc(60);
        StockDividendHistory old = poEvent(PO_NEWEST.minusDays(40), "1.00", "0");
        StockDividendHistory recent = poEvent(PO_NEWEST.minusDays(5), "1.00", "0");

        assertEquals("US_PRICE_ONLY_RECENT_EX_DIVIDEND:" + PO_NEWEST.minusDays(5),
                poUnavailableMessage(() -> service.priceOnlyBasis(rows, List.of(old, recent))));
        assertEquals("US_PRICE_ONLY_RECENT_EX_DIVIDEND:" + PO_NEWEST.minusDays(5),
                poUnavailableMessage(() -> service.priceOnlyBasis(rows, List.of(recent, old))),
                "事件順序不影響結果");
        assertFalse(service.priceOnlyBasis(rows, List.of(old, poEvent(PO_NEWEST.minusDays(30), "1.00", "0")))
                .adjusted(), "全部事件都在最近 15 根之外才通過");
    }

    @Test
    void priceOnlyBasisFallsBackToAnchorDateWhenCanonicalDateIsNullAndPrefersCanonicalDateOtherwise() {
        List<StockPriceHistory> rows = poContinuousRowsDesc(60);
        // canonicalDate 為 null：有效日＝anchorDate()＝除息日與除權日較早者。
        StockDividendHistory exDividendOnly = StockDividendHistory.builder()
                .id(903L).stockCode("VOO").market("美股").year(2026)
                .exDividendDate(PO_NEWEST.minusDays(3)).cashDividend(new BigDecimal("1.00"))
                .stockDividend(BigDecimal.ZERO).resolutionStatus("RESOLVED").eventStatus("ACTIVE").build();
        assertEquals("US_PRICE_ONLY_RECENT_EX_DIVIDEND:" + PO_NEWEST.minusDays(3),
                poUnavailableMessage(() -> service.priceOnlyBasis(rows, List.of(exDividendOnly))));

        StockDividendHistory earlierRights = StockDividendHistory.builder()
                .id(904L).stockCode("VOO").market("美股").year(2026)
                .exDividendDate(PO_NEWEST.minusDays(3)).exRightsDate(PO_NEWEST.minusDays(30))
                .cashDividend(new BigDecimal("1.00")).stockDividend(BigDecimal.ZERO)
                .resolutionStatus("RESOLVED").eventStatus("ACTIVE").build();
        assertFalse(service.priceOnlyBasis(rows, List.of(earlierRights)).adjusted(),
                "anchorDate 取較早者（除權日 30 天前）→ 在 15 根之外 → 通過");

        // canonicalDate 非 null 時優先採用，即使除息日較近。
        StockDividendHistory canonicalWins = poEvent(PO_NEWEST.minusDays(30), "1.00", "0");
        canonicalWins.setExDividendDate(PO_NEWEST.minusDays(2));
        assertFalse(service.priceOnlyBasis(rows, List.of(canonicalWins)).adjusted());
    }

    @Test
    void priceOnlyBasisDoesNotCooldownForAFutureEvent() {
        List<StockPriceHistory> rows = poContinuousRowsDesc(40);
        assertFalse(service.priceOnlyBasis(rows, List.of(poEvent(PO_NEWEST.plusDays(3), "1.00", "0"))).adjusted(),
                "有效日晚於序列最新列（尚未發生）不退避");
    }

    @Test
    void priceOnlyBasisFailsClosedWhenEventsAreNull() {
        assertEquals("DIVIDEND_FACTS_MISSING",
                poUnavailableMessage(() -> service.priceOnlyBasis(poContinuousRowsDesc(40), null)));
    }

    @Test
    void priceOnlyBasisIgnoresAnEventWhoseCashAndStockAreBothZero() {
        List<StockPriceHistory> rows = poContinuousRowsDesc(40);
        assertFalse(service.priceOnlyBasis(rows, List.of(poEvent(PO_NEWEST.minusDays(2), "0", "0"))).adjusted());
        // 無日期且金額皆為 0：同樣忽略，不拋日期無法證明。
        StockDividendHistory undatedZero = StockDividendHistory.builder().id(905L).stockCode("VOO").market("美股")
                .year(2026).cashDividend(BigDecimal.ZERO).stockDividend(BigDecimal.ZERO)
                .resolutionStatus("RESOLVED").eventStatus("ACTIVE").build();
        assertFalse(service.priceOnlyBasis(rows, List.of(undatedZero)).adjusted());
    }

    @Test
    void priceOnlyBasisTreatsAnUnresolvedEventWithNullAmountsAsAnExDividendInsideTheWindow() {
        List<StockPriceHistory> rows = poContinuousRowsDesc(40);
        StockDividendHistory unresolved = poEvent(PO_NEWEST.minusDays(4), null, null);
        unresolved.setResolutionStatus("UNRESOLVED");

        assertEquals("US_PRICE_ONLY_RECENT_EX_DIVIDEND:" + PO_NEWEST.minusDays(4),
                poUnavailableMessage(() -> service.priceOnlyBasis(rows, List.of(unresolved))),
                "金額未知＝無法證明沒有除息，仍須退避");
    }

    @Test
    void priceOnlyBasisFailsClosedForAnEventWithNoDateAtAll() {
        StockDividendHistory undated = StockDividendHistory.builder().id(906L).stockCode("VOO").market("美股")
                .year(2026).cashDividend(new BigDecimal("1.00")).stockDividend(BigDecimal.ZERO)
                .resolutionStatus("RESOLVED").eventStatus("ACTIVE").build();
        assertEquals("US_PRICE_ONLY_EX_DIVIDEND_DATE_UNVERIFIED",
                poUnavailableMessage(() -> service.priceOnlyBasis(poContinuousRowsDesc(40), List.of(undated))));
    }

    @Test
    void priceOnlyBasisChecksThePriceBasisBreakBeforeTheCooldown() {
        LocalDate day = PO_NEWEST;
        List<StockPriceHistory> rows = List.of(
                row(day, new BigDecimal("50.00")), row(day.minusDays(1), new BigDecimal("100.00")));
        assertEquals("US_PRICE_ONLY_BASIS_BREAK:" + day,
                poUnavailableMessage(() -> service.priceOnlyBasis(rows, List.of(poEvent(day, "1.00", "0")))));
    }

    /** Existing arithmetic fixtures now describe V2 canonical rows, already split by effective date. */
    private static List<StockDividendHistory> canonical(StockDividendHistory event) {
        BigDecimal cash=event.getCashDividend()==null?BigDecimal.ZERO:event.getCashDividend();
        BigDecimal stock=event.getStockDividend()==null?BigDecimal.ZERO:event.getStockDividend();
        LocalDate cashDate=event.getExDividendDate(),stockDate=event.getExRightsDate()==null?cashDate:event.getExRightsDate();
        if(cash.signum()==0)cashDate=stockDate;
        if(stock.signum()==0)stockDate=cashDate;
        if(java.util.Objects.equals(cashDate,stockDate))return List.of(canonicalRow(event,event.getId(),cashDate,cash,stock));
        return List.of(canonicalRow(event,event.getId()*10,cashDate,cash,BigDecimal.ZERO),
                canonicalRow(event,event.getId()*10+1,stockDate,BigDecimal.ZERO,stock));
    }
    private static StockDividendHistory canonicalRow(StockDividendHistory original,Long id,LocalDate date,BigDecimal cash,BigDecimal stock){
        return StockDividendHistory.builder().id(id).stockCode(original.getStockCode()).market(original.getMarket())
                .year(date.getYear()).canonicalDate(date).exDividendDate(cash.signum()>0?date:null)
                .exRightsDate(stock.signum()>0?date:null).cashDividend(cash).stockDividend(stock)
                .sourceSnapshotId(1L).source("VERIFIED_FIXTURE").resolutionStatus("RESOLVED").eventStatus("ACTIVE").build();
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

    private StockPriceHistory rowWithVolume(LocalDate date, String close, long volume) {
        return StockPriceHistory.builder()
                .stockCode("00751B")
                .market("台股")
                .tradingDate(date)
                .openPrice(new BigDecimal(close))
                .highPrice(new BigDecimal(close))
                .lowPrice(new BigDecimal(close))
                .closePrice(new BigDecimal(close))
                .volume(volume)
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
