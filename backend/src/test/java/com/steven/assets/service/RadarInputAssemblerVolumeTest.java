package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/** Task 291 個股相對量守門：完成日、分母排除與最小樣本不可漂移。 */
class RadarInputAssemblerVolumeTest {

    private final RadarInputAssembler assembler = new RadarInputAssembler(
            mock(TechnicalIndicatorService.class), mock(DistributionAdjustedPriceService.class),
            mock(TradingRadarRuleEngine.class));

    @Test
    void latestCompletedDayIsComparedWithPriorMedianAndLiveRowIsExcluded() {
        List<StockPriceHistory> rows = new ArrayList<>();
        rows.add(row(0, 9_999L));       // 盤中列，不是本次完成日
        rows.add(row(1, 200L));         // latestCompletedIndex = 1
        for (int i = 2; i < 12; i++) rows.add(row(i, 100L));

        assertEquals("2.0000", assembler.volumeRatio(rows, 1).toPlainString());
    }

    @Test
    void fewerThanTenPositivePriorSamplesIsUnavailable() {
        List<StockPriceHistory> rows = new ArrayList<>();
        rows.add(row(0, 200L));
        for (int i = 1; i < 10; i++) rows.add(row(i, i == 9 ? 0L : 100L));

        assertNull(assembler.volumeRatio(rows, 0));
    }

    private static StockPriceHistory row(int daysAgo, Long volume) {
        return StockPriceHistory.builder()
                .stockCode("2330").market("台股")
                .tradingDate(LocalDate.of(2026, 8, 8).minusDays(daysAgo))
                .volume(volume).build();
    }

    // ═══ Task 299：季線乖離自身分位——直測 public 純方法，不走 assemble() ═══════
    //
    // 本檔的 mock 未 stub adjust／computeFromSeries，走 assemble() 會 NPE，
    // 這正是 299.1 把計算本體抽成 public 純方法的理由。

    @Test
    void percentileIsHundred_whenCurrentBiasExceedsEntireDistribution() {
        // 240 筆單純遞增收盤序列（close[i] = 339 − i，i 為降序索引：i=0 最新收盤 339、
        // i=239 最舊收盤 100），firstCompleted=0。線性遞增序列逐日回算出的乖離觀測值
        // 隨 i 增加而變大（越舊乖離越高，可用等差數列均值的閉式解驗證），181 筆觀測值
        // 全部落在 [9.53%, 22.78%] 內。現行乖離傳入 30%（高於整個分布的上界），
        // 故全部觀測皆 ≤ 現行值 → 分位為 100。
        List<StockPriceHistory> rows = increasingCloseRows(240);

        BigDecimal percentile = assembler.ma60BiasPercentile(rows, 0, new BigDecimal("30.0"));

        assertEquals("100.0", percentile.toPlainString());
    }

    @Test
    void percentileIsNull_whenObservationsBelowMinimumSampleSize() {
        // 序列僅 150 筆：可成立窗口的最後起點 = 150 − 60 = 90，觀測數 = 90 − 0 + 1 = 91 < 120 → null。
        List<StockPriceHistory> rows = increasingCloseRows(150);

        assertNull(assembler.ma60BiasPercentile(rows, 0, new BigDecimal("30.0")));
    }

    @Test
    void firstCompletedExcludesLiveRowFromDistribution() {
        // 同一組 240 筆序列，i=0 的觀測乖離為 9.53150242%、i=1 為 9.56239870%（相鄰兩筆）。
        // 現行乖離取兩者之間的 9.55%：
        // - firstCompleted=0：i=0 本身落入分布且 ≤ 9.55 → 分位為 100×1/181，四捨五入為 0.6；
        // - firstCompleted=1：i=0（模擬 live 首列）被排除於分布外，剩餘觀測最小者（i=1）
        //   已超過 9.55 → 沒有任何觀測 ≤ 現行值，分位為 0.0。
        // 兩者結果不同，證明 firstCompleted 之前的列確實未進入分布。
        List<StockPriceHistory> rows = increasingCloseRows(240);

        BigDecimal includingLiveRow = assembler.ma60BiasPercentile(rows, 0, new BigDecimal("9.55"));
        BigDecimal excludingLiveRow = assembler.ma60BiasPercentile(rows, 1, new BigDecimal("9.55"));

        assertEquals("0.6", includingLiveRow.toPlainString());
        assertEquals("0.0", excludingLiveRow.toPlainString());
    }

    /** 降序（新到舊）遞增收盤序列：{@code close[i] = 339 − i}，i=0（最新）收盤最高。 */
    private static List<StockPriceHistory> increasingCloseRows(int n) {
        List<StockPriceHistory> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(StockPriceHistory.builder()
                    .stockCode("2330").market("台股")
                    .tradingDate(LocalDate.of(2026, 8, 8).minusDays(i))
                    .closePrice(BigDecimal.valueOf(339 - i))
                    .build());
        }
        return rows;
    }
}
