package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 356.3b：{@code FullIndicators.ma10} 是<b>純追加</b>的輸出欄。
 *
 * <p>本檔以固定資料釘住兩件事：(1) {@code ma10} 就是「最近 10 筆收盤的簡單平均、
 * {@code setScale(2, HALF_UP)}」，與既有 MA 同一支 {@code simpleMa}；
 * (2) 追加之後 {@code monthlyMa}／{@code quarterlyMa}／{@code annualMa}／{@code weeklyMa}／
 * {@code k}／{@code d}／{@code previousK}／{@code previousD}／{@code extended} 全部逐位不變
 * （既有欄位以固定資料的 golden 值釘住）。</p>
 */
class TechnicalIndicatorMa10AppendTest {

    private TechnicalIndicatorService service() {
        return new TechnicalIndicatorService(
                mock(StockPriceHistoryRepository.class),
                mock(PriceQueryService.class),
                mock(TwseIndexDailyHistoryRepository.class),
                mock(UsIndexDailyHistoryRepository.class));
    }

    @Test
    void ma10IsTheSimpleAverageOfTheNewestTenCloses() {
        List<StockPriceHistory> desc = rampDesc(300);

        TechnicalIndicatorService.FullIndicators ind = service().computeFromSeries(desc);

        assertThat(ind.ma10()).isEqualByComparingTo(manualMa(desc, 10));
        // 既有 MA 一律仍是同一支 simpleMa（同一累加方向、同一精度）。
        assertThat(ind.weeklyMa()).isEqualByComparingTo(manualMa(desc, 5));
        assertThat(ind.monthlyMa()).isEqualByComparingTo(manualMa(desc, 20));
        assertThat(ind.quarterlyMa()).isEqualByComparingTo(manualMa(desc, 60));
        assertThat(ind.annualMa()).isEqualByComparingTo(manualMa(desc, 240));
    }

    @Test
    void ma10IsNullWhenSeriesIsShorterThanTenRows() {
        TechnicalIndicatorService.FullIndicators ind = service().computeFromSeries(rampDesc(9));

        assertThat(ind.ma10()).as("視窗不足一律 null，不得以短序列硬算或補 0").isNull();
        assertThat(ind.weeklyMa()).isNotNull();
    }

    @Test
    void appendingMa10LeavesEveryExistingFieldBitIdentical() {
        // golden 值取自固定資料（wavyDesc(300)），用來證明本次追加沒有動到任何既有輸出。
        TechnicalIndicatorService.FullIndicators ind = service().computeFromSeries(wavyDesc(300));

        assertThat(ind.weeklyMa()).isEqualByComparingTo("404.20");
        assertThat(ind.monthlyMa()).isEqualByComparingTo("405.10");
        assertThat(ind.quarterlyMa()).isEqualByComparingTo("387.70");
        assertThat(ind.annualMa()).isEqualByComparingTo("307.34");
        assertThat(ind.k()).isEqualByComparingTo("24.77");
        assertThat(ind.d()).isEqualByComparingTo("40.84");
        assertThat(ind.previousK()).isEqualByComparingTo("32.35");
        assertThat(ind.previousD()).isEqualByComparingTo("48.88");
        assertThat(ind.extended().j9()).isEqualByComparingTo("72.98");
        assertThat(ind.extended().osc()).isEqualByComparingTo("-1.81");
        assertThat(ind.extended().rsi5()).isEqualByComparingTo("26.14");
        assertThat(ind.extended().rsi10()).isEqualByComparingTo("44.06");
        assertThat(ind.extended().bias10()).isEqualByComparingTo("-2.31");
        assertThat(ind.extended().bias20()).isEqualByComparingTo("-1.26");
        assertThat(ind.extended().wr9()).isEqualByComparingTo("90.38");
        assertThat(ind.extended().dif()).isEqualByComparingTo("3.83");
        assertThat(ind.extended().macd()).isEqualByComparingTo("5.64");
        // 新欄位確實有值，且與既有欄位互不干擾。
        assertThat(ind.ma10()).isEqualByComparingTo("409.45");
    }

    @Test
    void taiexPathFillsMa10WithNull() {
        TwseIndexDailyHistoryRepository twseRepo = mock(TwseIndexDailyHistoryRepository.class);
        PriceQueryService priceQuery = mock(PriceQueryService.class);
        when(twseRepo.findTopNByOrderByTradingDateDesc(240)).thenReturn(taiexDesc(300));
        lenient().when(priceQuery.getLive(anyString(), anyString())).thenReturn(Optional.empty());
        TechnicalIndicatorService service = new TechnicalIndicatorService(
                mock(StockPriceHistoryRepository.class), priceQuery, twseRepo,
                mock(UsIndexDailyHistoryRepository.class));

        TechnicalIndicatorService.FullIndicators ind = service.computeAll("0000", "台股");

        assertThat(ind.ma10()).as("大盤路徑不需要 MA10，一律 null，不得以 ma5／ma20 冒充").isNull();
        assertThat(ind.weeklyMa()).isNotNull();
        assertThat(ind.monthlyMa()).isNotNull();
    }

    @Test
    void emptyIndicatorsCarryNullMa10() {
        assertThat(TechnicalIndicatorService.FullIndicators.EMPTY.ma10()).isNull();
    }

    // ───────────────────────── helpers ─────────────────────────

    /** 收盤由舊到新遞增（desc 索引 0 為最新）：close(i) = 400 − 0.9 × i。 */
    private static List<StockPriceHistory> rampDesc(int rows) {
        List<StockPriceHistory> desc = new ArrayList<>(rows);
        LocalDate date = LocalDate.of(2026, 8, 21);
        for (int i = 0; i < rows; i++) {
            BigDecimal close = BigDecimal.valueOf(400).subtract(
                    BigDecimal.valueOf(9L * i, 1));
            desc.add(StockPriceHistory.builder()
                    .stockCode("2330").market("台股")
                    .tradingDate(date.minusDays(i))
                    .openPrice(close)
                    .highPrice(close.add(BigDecimal.ONE))
                    .lowPrice(close.subtract(BigDecimal.ONE))
                    .closePrice(close)
                    .volume(1000L)
                    .build());
        }
        return desc;
    }

    /** 帶波動的固定序列（golden 用）：close(i) = 400 − 0.9 × i + 3 × (i mod 11)。 */
    private static List<StockPriceHistory> wavyDesc(int rows) {
        List<StockPriceHistory> desc = new ArrayList<>(rows);
        LocalDate date = LocalDate.of(2026, 8, 21);
        for (int i = 0; i < rows; i++) {
            BigDecimal close = BigDecimal.valueOf(400)
                    .subtract(BigDecimal.valueOf(9L * i, 1))
                    .add(BigDecimal.valueOf(3L * (i % 11)));
            desc.add(StockPriceHistory.builder()
                    .stockCode("2330").market("台股")
                    .tradingDate(date.minusDays(i))
                    .openPrice(close)
                    .highPrice(close.add(BigDecimal.valueOf(2)))
                    .lowPrice(close.subtract(BigDecimal.valueOf(2)))
                    .closePrice(close)
                    .volume(1000L)
                    .build());
        }
        return desc;
    }

    private static List<TwseIndexDailyHistory> taiexDesc(int rows) {
        List<TwseIndexDailyHistory> desc = new ArrayList<>(rows);
        LocalDate date = LocalDate.of(2026, 8, 21);
        for (int i = 0; i < rows; i++) {
            TwseIndexDailyHistory row = new TwseIndexDailyHistory();
            BigDecimal close = BigDecimal.valueOf(20000 - i);
            row.setTradingDate(date.minusDays(i));
            row.setOpenPoint(close);
            row.setHighPoint(close.add(BigDecimal.ONE));
            row.setLowPoint(close.subtract(BigDecimal.ONE));
            row.setClosePoint(close);
            desc.add(row);
        }
        return desc;
    }

    /** 與 {@code simpleMa} 同式的手算對照：最近 days 筆收盤平均、scale 2。 */
    private static BigDecimal manualMa(List<StockPriceHistory> desc, int days) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < days; i++) sum = sum.add(desc.get(i).getClosePrice());
        return sum.divide(BigDecimal.valueOf(days), 10, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
