package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Task 356.3：週K 指標的取得方式、最少 60 根完成週、週量比與精度。
 *
 * <p>指標一律由既有的 {@code TechnicalIndicatorService.computeFromSeries} 算出——換序列不換公式，
 * 沒有第二份 KD／MACD／RSI／SMA 實作。本檔驗證的是 {@code RadarInputAssembler} 這一側的接線：
 * 哪些週進得了指標序列、不足 60 根時如何缺值、週量比的分母怎麼取。</p>
 */
class RadarInputAssemblerWeeklyTest {

    private static final LocalDate FIRST_MONDAY = LocalDate.of(2024, 1, 1);
    private static final int DAILY_CONTRACT_ROWS = RadarObservationResolver.INDICATOR_SERIES_MAX_ROWS;

    private RadarInputAssembler assembler() {
        return new RadarInputAssembler(
                new TechnicalIndicatorService(
                        mock(StockPriceHistoryRepository.class),
                        mock(PriceQueryService.class),
                        mock(TwseIndexDailyHistoryRepository.class),
                        mock(UsIndexDailyHistoryRepository.class)),
                new DistributionAdjustedPriceService(),
                new TradingRadarRuleEngine());
    }

    private RadarInputAssembler.Assembled assemble(List<StockPriceHistory> desc) {
        return assembler().assemble(desc, List.of(), false,
                Math.min(desc.size(), DAILY_CONTRACT_ROWS), DAILY_CONTRACT_ROWS,
                desc.get(0).getClosePrice());
    }

    // ───────────────────────── 60 根完成週門檻 ─────────────────────────

    @Test
    void exactlySixtyCompletedWeeksMakesTheWholeWeeklyGroupAvailable() {
        // 61 個 ISO 週：最晚一週恆為進行中週 → 完成週剛好 60 根。
        RadarInputAssembler.Assembled assembled = assemble(weekdayRowsDesc(61));

        TradingRadarRuleEngine.WeeklyInput weekly = assembled.weekly();
        assertThat(weekly).isNotNull();
        assertThat(weekly.completedWeeks()).isEqualTo(RadarInputAssembler.MIN_COMPLETED_WEEKS);
        assertThat(weekly.ma5()).isNotNull();
        assertThat(weekly.ma10()).isNotNull();
        assertThat(weekly.ma20()).isNotNull();
        assertThat(weekly.k()).isNotNull();
        assertThat(weekly.d()).isNotNull();
        assertThat(weekly.j9()).isNotNull();
        assertThat(weekly.osc()).isNotNull();
        assertThat(weekly.rsi5()).isNotNull();
        assertThat(weekly.rsi10()).isNotNull();
        assertThat(weekly.bias10()).isNotNull();
        assertThat(weekly.bias20()).isNotNull();
        assertThat(weekly.changePercent()).isNotNull();
        assertThat(weekly.candle()).isNotNull();
        // weekEndDate 必須是「上一個完成週」的最後交易日，不是進行中那一週的任何一天。
        assertThat(weekly.weekEndDate()).isEqualTo(FIRST_MONDAY.plusWeeks(59).plusDays(4));
        assertThat(assembled.weeklyBarsDesc()).hasSize(60);
        assertThat(assembled.weeklyIndicators().weeklyMa()).isNotNull();
        // 60 根週K 撐不起週MA240，該欄必須是 null 而不是硬算。
        assertThat(assembled.weeklyIndicators().annualMa()).isNull();
    }

    @Test
    void fiftyNineCompletedWeeksLeavesEveryWeeklyIndicatorNullButStillReportsTheCount() {
        RadarInputAssembler.Assembled assembled = assemble(weekdayRowsDesc(60));

        TradingRadarRuleEngine.WeeklyInput weekly = assembled.weekly();
        assertThat(weekly).isNotNull();
        assertThat(weekly.completedWeeks())
                .as("揭露文案要寫得出「目前 N 根」，故根數仍如實回報")
                .isEqualTo(59);
        assertThat(weekly.candle()).isNull();
        assertThat(weekly.ma5()).isNull();
        assertThat(weekly.ma10()).isNull();
        assertThat(weekly.ma20()).isNull();
        assertThat(weekly.k()).isNull();
        assertThat(weekly.osc()).isNull();
        assertThat(weekly.volumeRatio()).isNull();
        assertThat(weekly.changePercent()).isNull();
        assertThat(assembled.weeklyIndicators())
                .as("整組週K 指標缺值時，dif／macd 純揭露欄也不得單獨外露")
                .isEqualTo(TechnicalIndicatorService.FullIndicators.EMPTY);
    }

    @Test
    void aDailyContractOnlyWindowCannotReachSixtyCompletedWeeks() {
        // 241 個交易日 ≈ 48 個 ISO 週：這正是「不擴窗就等於週K 因子永遠缺值」的機械證據。
        List<StockPriceHistory> desc = weekdayRowsDesc(61).subList(0, DAILY_CONTRACT_ROWS);

        RadarInputAssembler.Assembled assembled = assemble(desc);

        assertThat(assembled.weekly().completedWeeks())
                .isLessThan(RadarInputAssembler.MIN_COMPLETED_WEEKS);
        assertThat(assembled.weekly().ma5()).isNull();
    }

    // ───────────────────────── 週量比（356.3e） ─────────────────────────

    @Test
    void weeklyVolumeRatioExcludesTheLatestWeekFromItsOwnDenominator() {
        List<StockPriceHistory> desc = weekdayRowsDesc(61);
        // 最新完成週（ISO 週序 59，desc 索引 5..9）每日 40 → 週量 200；其餘每日 20 → 週量 100。
        for (int i = 5; i < 10; i++) desc.set(i, withVolume(desc.get(i), 40L));

        RadarInputAssembler.Assembled assembled = assemble(desc);

        assertThat(assembled.weekly().volumeRatio())
                .as("分母只取之前 20 根完成週的中位數，最新週不得進入自己的基準")
                .isEqualByComparingTo("2.00");
    }

    @Test
    void weeklyVolumeRatioIsNullWhenFewerThanTenPositivePriorWeeks() {
        List<StockPriceHistory> desc = weekdayRowsDesc(61);
        // 最新完成週之前的每一週都至少有一天缺量 → 該週整根量為 null → 正樣本 0 根。
        for (int i = 10; i < desc.size(); i += 5) desc.set(i, withVolume(desc.get(i), null));

        RadarInputAssembler.Assembled assembled = assemble(desc);

        assertThat(assembled.weekly().volumeRatio()).isNull();
        assertThat(assembled.weekly().ma5())
                .as("量能缺值不得連帶讓價格指標一起消失")
                .isNotNull();
    }

    @Test
    void weeklyIndicatorsAreScaledToTwoDecimals() {
        RadarInputAssembler.Assembled assembled = assemble(weekdayRowsDesc(61));

        TradingRadarRuleEngine.WeeklyInput weekly = assembled.weekly();
        assertThat(weekly.bias10().scale()).isEqualTo(2);
        assertThat(weekly.bias20().scale()).isEqualTo(2);
        assertThat(weekly.changePercent().scale()).isEqualTo(2);
        assertThat(weekly.ma5().scale()).isEqualTo(2);
    }

    // ───────────────────────── high／low 缺值週不得進指標序列（356.3c-2） ─────────────────────────

    @Test
    void weekMissingHighOrLowIsDroppedAndNeverFallsBackToClose() {
        // 62 個 ISO 週 → 61 根完成週；把其中一根（週序 30）的某一天 high 清成 null。
        List<StockPriceHistory> withBadWeek = weekdayRowsDesc(62);
        LocalDate badWeekEnd = FIRST_MONDAY.plusWeeks(30).plusDays(4);
        int badIndex = indexOf(withBadWeek, FIRST_MONDAY.plusWeeks(30).plusDays(2));
        withBadWeek.set(badIndex, withoutHigh(withBadWeek.get(badIndex)));

        // 對照組：同一段序列，但整週直接不存在。
        List<StockPriceHistory> withoutBadWeek = new ArrayList<>(weekdayRowsDesc(62));
        withoutBadWeek.removeIf(row -> !row.getTradingDate().isBefore(FIRST_MONDAY.plusWeeks(30))
                && row.getTradingDate().isBefore(FIRST_MONDAY.plusWeeks(31)));

        RadarInputAssembler.Assembled dropped = assemble(withBadWeek);
        RadarInputAssembler.Assembled absent = assemble(withoutBadWeek);

        assertThat(dropped.weeklyBarsDesc())
                .as("high 缺值的週整根不得進入指標序列")
                .noneMatch(bar -> bar.weekEndDate().equals(badWeekEnd));
        assertThat(dropped.weekly().completedWeeks()).isEqualTo(60);
        assertThat(absent.weekly().completedWeeks()).isEqualTo(60);
        // 兩者的週K 指標逐位相同，證明那一根壞週<b>完全沒有</b>以「close 頂替高低」的偽K 形式參與計算
        // （若被餵進 computeFromSeries，其 KD 的 RSV 會拿 close 當高低，兩組 k／d 必然不同）。
        assertThat(dropped.weekly().k()).isEqualByComparingTo(absent.weekly().k());
        assertThat(dropped.weekly().d()).isEqualByComparingTo(absent.weekly().d());
        assertThat(dropped.weekly().osc()).isEqualByComparingTo(absent.weekly().osc());
        assertThat(dropped.weekly().ma5()).isEqualByComparingTo(absent.weekly().ma5());
    }

    @Test
    void droppingBadWeeksCanPushTheGroupBelowTheSixtyWeekThreshold() {
        List<StockPriceHistory> desc = weekdayRowsDesc(61);
        int badIndex = indexOf(desc, FIRST_MONDAY.plusWeeks(20).plusDays(1));
        desc.set(badIndex, withoutHigh(desc.get(badIndex)));

        RadarInputAssembler.Assembled assembled = assemble(desc);

        assertThat(assembled.weekly().completedWeeks()).isEqualTo(59);
        assertThat(assembled.weekly().ma5()).isNull();
    }

    // ───────────────────────── helpers ─────────────────────────

    /** {@code weeks} 個 ISO 週、每週五個交易日的降序序列（最新在前）。 */
    private static List<StockPriceHistory> weekdayRowsDesc(int weeks) {
        List<StockPriceHistory> asc = new ArrayList<>(weeks * 5);
        for (int w = 0; w < weeks; w++) {
            for (int d = 0; d < 5; d++) {
                LocalDate date = FIRST_MONDAY.plusWeeks(w).plusDays(d);
                BigDecimal close = BigDecimal.valueOf(10_000 + w * 10L + d * 2L, 2);
                asc.add(StockPriceHistory.builder()
                        .stockCode("2330").market("台股")
                        .tradingDate(date)
                        .openPrice(close)
                        .highPrice(close.add(BigDecimal.valueOf(50, 2)))
                        .lowPrice(close.subtract(BigDecimal.valueOf(50, 2)))
                        .closePrice(close)
                        .volume(20L)
                        .build());
            }
        }
        return new ArrayList<>(asc.reversed());
    }

    private static int indexOf(List<StockPriceHistory> desc, LocalDate date) {
        for (int i = 0; i < desc.size(); i++) {
            if (date.equals(desc.get(i).getTradingDate())) return i;
        }
        throw new IllegalArgumentException("序列中沒有 " + date);
    }

    private static StockPriceHistory withVolume(StockPriceHistory row, Long volume) {
        return copy(row).volume(volume).build();
    }

    private static StockPriceHistory withoutHigh(StockPriceHistory row) {
        return copy(row).highPrice(null).build();
    }

    private static StockPriceHistory.StockPriceHistoryBuilder copy(StockPriceHistory row) {
        return StockPriceHistory.builder()
                .stockCode(row.getStockCode()).market(row.getMarket())
                .tradingDate(row.getTradingDate())
                .openPrice(row.getOpenPrice())
                .highPrice(row.getHighPrice())
                .lowPrice(row.getLowPrice())
                .closePrice(row.getClosePrice())
                .volume(row.getVolume());
    }
}
