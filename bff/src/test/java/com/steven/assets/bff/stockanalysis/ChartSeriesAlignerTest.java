package com.steven.assets.bff.stockanalysis;

import com.steven.assets.bff.stockanalysis.dto.ChartSeriesDto;
import com.steven.assets.bff.stockanalysis.dto.IndicatorPointDto;
import com.steven.assets.bff.stockanalysis.dto.PricePointDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChartSeriesAligner} 的日期聯集對齊（Task 261）。
 *
 * 這組測試是 BFF 端唯一的機械保護：`asset-bff` 的 runtime 映像沒有 curl，
 * 且未登入一律回 401（狀態碼無法區分 controller 有沒有接走），端點本身無法用 shell 驗。
 *
 * 最關鍵的一條是「指標側多一天」——0000 台股大盤必然如此（股價側的
 * getStockHistory 對大盤完全不併 live，指標側則比照 computeAll 併入）。
 * 若拿股價側日期當基準，今日的指標點會被靜默丟掉、legend 顯示前一交易日的值，
 * 等於 Task 261 想修的不一致原封不動。
 */
class ChartSeriesAlignerTest {

    private PricePointDto price(String date, double close) {
        return new PricePointDto(date, BigDecimal.valueOf(close));
    }
    private PricePointDto candle(String date, double open, double high, double low, double close) {
        return new PricePointDto(date, BigDecimal.valueOf(open), BigDecimal.valueOf(high), BigDecimal.valueOf(low), BigDecimal.valueOf(close));
    }

    private IndicatorPointDto indicator(String date, double k) {
        return new IndicatorPointDto(date,
                BigDecimal.valueOf(5),   // Task 265：ma5（週線）
                BigDecimal.valueOf(10), BigDecimal.valueOf(20), BigDecimal.valueOf(30),
                BigDecimal.valueOf(k), BigDecimal.valueOf(k - 1),
                BigDecimal.valueOf(k - 2), BigDecimal.valueOf(k + 2), BigDecimal.valueOf(k + 5),
                // Task 262 的 11 個新欄位：值由 k 衍生，只要能辨別「有沒有被對齊帶出來」即可
                BigDecimal.valueOf(k + 10), BigDecimal.valueOf(k + 11), BigDecimal.valueOf(k + 12),
                BigDecimal.valueOf(k + 13), BigDecimal.valueOf(k + 14), BigDecimal.valueOf(k + 15),
                BigDecimal.valueOf(k + 16), BigDecimal.valueOf(k + 17), BigDecimal.valueOf(k + 18),
                BigDecimal.valueOf(k + 19), BigDecimal.valueOf(k + 20));
    }

    private IndicatorPointDto indicatorWithoutK(String date, double ma5) {
        return new IndicatorPointDto(date,
                BigDecimal.valueOf(ma5), BigDecimal.valueOf(10), BigDecimal.valueOf(20), BigDecimal.valueOf(30),
                null, BigDecimal.valueOf(2), BigDecimal.valueOf(3), BigDecimal.valueOf(4), BigDecimal.valueOf(5),
                BigDecimal.valueOf(6), BigDecimal.valueOf(7), BigDecimal.valueOf(8), BigDecimal.valueOf(9),
                BigDecimal.valueOf(10), BigDecimal.valueOf(11), BigDecimal.valueOf(12), BigDecimal.valueOf(13),
                BigDecimal.valueOf(14), BigDecimal.valueOf(15), BigDecimal.valueOf(16));
    }

    @Test
    void 指標側多一天時該日仍須進入x軸且股價格為null() {
        List<PricePointDto> prices = List.of(price("2026-07-29", 100), price("2026-07-30", 101));
        List<IndicatorPointDto> indicators = List.of(
                indicator("2026-07-29", 40), indicator("2026-07-30", 41), indicator("2026-07-31", 42));

        ChartSeriesDto out = ChartSeriesAligner.align(prices, indicators);

        assertThat(out.dates()).containsExactly("2026-07-29", "2026-07-30", "2026-07-31");
        assertThat(out.prices()).containsExactly(
                BigDecimal.valueOf(100.0), BigDecimal.valueOf(101.0), null);
        assertThat(out.k().get(2)).isEqualByComparingTo(BigDecimal.valueOf(42));
    }

    @Test
    void latest取指標序列尾筆而非對齊後陣列尾筆() {
        // 股價只到 7/30，指標到 7/31：latest 必須是 7/31 的 42，不是 7/30 的 41
        List<PricePointDto> prices = List.of(price("2026-07-30", 101));
        List<IndicatorPointDto> indicators = List.of(
                indicator("2026-07-30", 41), indicator("2026-07-31", 42));

        ChartSeriesDto out = ChartSeriesAligner.align(prices, indicators);

        assertThat(out.latest()).isNotNull();
        assertThat(out.latest().k()).isEqualByComparingTo(BigDecimal.valueOf(42));
        assertThat(out.latest().prevK()).isEqualByComparingTo(BigDecimal.valueOf(41));
    }

    @Test
    void 股價側多一天時該日指標欄為null() {
        // 指標暖機不足導致前段沒有指標點
        List<PricePointDto> prices = List.of(
                price("2026-07-28", 99), price("2026-07-29", 100), price("2026-07-30", 101));
        List<IndicatorPointDto> indicators = List.of(indicator("2026-07-30", 41));

        ChartSeriesDto out = ChartSeriesAligner.align(prices, indicators);

        assertThat(out.dates()).containsExactly("2026-07-28", "2026-07-29", "2026-07-30");
        assertThat(out.k()).containsExactly(null, null, BigDecimal.valueOf(41.0));
        // fixture 的 ma20 是 BigDecimal.valueOf(10)（long overload、scale 0），
        // 而 BigDecimal.equals 會比 scale，故不可寫成 valueOf(10.0)
        assertThat(out.ma20()).containsExactly(null, null, BigDecimal.valueOf(10));
    }

    @Test
    void 指標上游失敗時仍回傳股價陣列且latest為null() {
        List<PricePointDto> prices = List.of(price("2026-07-29", 100), price("2026-07-30", 101));

        ChartSeriesDto out = ChartSeriesAligner.align(prices, List.of());

        assertThat(out.dates()).containsExactly("2026-07-29", "2026-07-30");
        assertThat(out.prices()).containsExactly(BigDecimal.valueOf(100.0), BigDecimal.valueOf(101.0));
        assertThat(out.k()).containsExactly(null, null);
        assertThat(out.latest()).isNull();
    }

    @Test
    void 兩側皆空時回傳空陣列而非例外() {
        ChartSeriesDto out = ChartSeriesAligner.align(List.of(), List.of());

        assertThat(out.dates()).isEmpty();
        assertThat(out.prices()).isEmpty();
        assertThat(out.latest()).isNull();
    }

    @Test
    void 日期一律升冪排序不受輸入順序影響() {
        List<PricePointDto> prices = List.of(
                price("2026-07-31", 102), price("2026-07-29", 100), price("2026-07-30", 101));

        ChartSeriesDto out = ChartSeriesAligner.align(prices, List.of());

        assertThat(out.dates()).containsExactly("2026-07-29", "2026-07-30", "2026-07-31");
        assertThat(out.prices()).containsExactly(
                BigDecimal.valueOf(100.0), BigDecimal.valueOf(101.0), BigDecimal.valueOf(102.0));
    }

    /** Task 262 的新欄位必須同樣參與聯集對齊，長度與 dates 一致（漏接的話畫面會少一整組指標）。 */
    @Test
    void 新增的指標欄位同樣參與聯集對齊() {
        List<PricePointDto> prices = List.of(price("2026-07-29", 100), price("2026-07-30", 101));
        List<IndicatorPointDto> indicators = List.of(
                indicator("2026-07-29", 40), indicator("2026-07-30", 41), indicator("2026-07-31", 42));

        ChartSeriesDto out = ChartSeriesAligner.align(prices, indicators);

        int n = out.dates().size();
        assertThat(n).isEqualTo(3);
        assertThat(out.ema12()).hasSize(n);
        assertThat(out.ema26()).hasSize(n);
        assertThat(out.dif()).hasSize(n);
        assertThat(out.macd()).hasSize(n);
        assertThat(out.osc()).hasSize(n);
        assertThat(out.rsi5()).hasSize(n);
        assertThat(out.rsi10()).hasSize(n);
        assertThat(out.bias10()).hasSize(n);
        assertThat(out.bias20()).hasSize(n);
        assertThat(out.b10b20()).hasSize(n);
        assertThat(out.wr9()).hasSize(n);
        // 指標側多出的 7/31 那格，新欄位也要帶出來（k+10 = 52）
        assertThat(out.ema12().get(2)).isEqualByComparingTo(BigDecimal.valueOf(52));
        // latest 的新欄位取指標序列尾筆（7/31），prev 取 7/30
        assertThat(out.latest().wr9()).isEqualByComparingTo(BigDecimal.valueOf(62));
        assertThat(out.latest().prevWr9()).isEqualByComparingTo(BigDecimal.valueOf(61));
        assertThat(out.latest().osc()).isEqualByComparingTo(BigDecimal.valueOf(56));
    }

    @Test
    void 只有一筆指標時prev為null以免箭頭誤判() {
        ChartSeriesDto out = ChartSeriesAligner.align(
                List.of(price("2026-07-30", 101)), List.of(indicator("2026-07-30", 41)));

        assertThat(out.latest().k()).isEqualByComparingTo(BigDecimal.valueOf(41));
        assertThat(out.latest().prevK()).isNull();
    }

    @Test
    void daily截在最後完整OHLC且weekly以ISO週聚合() {
        ChartSeriesDto out = ChartSeriesAligner.align(List.of(
                candle("2026-01-05", 10, 13, 9, 12), candle("2026-01-06", 12, 15, 11, 14),
                new PricePointDto("2026-01-07", BigDecimal.valueOf(15))),
                List.of(indicator("2026-01-05", 40), indicator("2026-01-06", 41), indicator("2026-01-07", 42)), LocalDate.parse("2026-01-05"));
        assertThat(out.daily().dates()).containsExactly(LocalDate.parse("2026-01-05"), LocalDate.parse("2026-01-06"));
        assertThat(out.daily().currentClose()).isEqualByComparingTo("14");
        assertThat(out.daily().previousClose()).isEqualByComparingTo("12");
        assertThat(out.weekly().dates()).isEmpty(); // close-only 1/7 makes the whole ISO week fail closed
    }

    @Test
    void nonMondayStartSkipsTruncatedWeekButKeepsNextWeek() {
        ChartSeriesDto out = ChartSeriesAligner.align(List.of(
                candle("2026-01-07", 10, 11, 9, 10), candle("2026-01-12", 11, 14, 10, 13), candle("2026-01-13", 13, 15, 12, 14)),
                List.of(indicator("2026-01-07", 40), indicator("2026-01-12", 41), indicator("2026-01-13", 42)), LocalDate.parse("2026-01-07"));
        assertThat(out.weekly().dates()).containsExactly(LocalDate.parse("2026-01-13"));
        assertThat(out.weekly().opens().getFirst()).isEqualByComparingTo("11");
        assertThat(out.weekly().highs().getFirst()).isEqualByComparingTo("15");
        assertThat(out.weekly().lows().getFirst()).isEqualByComparingTo("10");
        assertThat(out.weekly().closes().getFirst()).isEqualByComparingTo("14");
    }

    @Test
    void daily保留中段壞OHLC但尾端closeOnly與indicatorOnly不越過最後完整日() {
        ChartSeriesDto out = ChartSeriesAligner.align(List.of(
                candle("2026-02-02", 10, 12, 9, 11),
                new PricePointDto("2026-02-03", BigDecimal.valueOf(11), null, null, BigDecimal.valueOf(12)),
                candle("2026-02-04", 12, 14, 11, 13),
                new PricePointDto("2026-02-05", BigDecimal.valueOf(13))),
                List.of(indicator("2026-02-02", 10), indicator("2026-02-03", 11), indicator("2026-02-04", 12), indicator("2026-02-05", 13), indicator("2026-02-06", 14)));

        assertThat(out.daily().dates()).containsExactly(LocalDate.parse("2026-02-02"), LocalDate.parse("2026-02-03"), LocalDate.parse("2026-02-04"));
        assertThat(out.daily().opens()).containsExactly(BigDecimal.valueOf(10.0), null, BigDecimal.valueOf(12.0));
        assertThat(out.daily().currentClose()).isEqualByComparingTo("13");
        assertThat(out.daily().previousClose()).isEqualByComparingTo("11");
        assertThat(out.daily().latest().k()).isEqualByComparingTo("12");
        assertThat(out.daily().latest().prevK()).isEqualByComparingTo("11");
        assertThat(out.daily().ma5()).hasSize(out.daily().dates().size());
    }

    @Test
    void weekly支援一到四日短週與ISO跨年並在壞日整週failClosed() {
        ChartSeriesDto out = ChartSeriesAligner.align(List.of(
                candle("2025-12-29", 10, 12, 9, 11), candle("2025-12-30", 11, 13, 10, 12),
                candle("2026-01-02", 12, 15, 11, 14),
                candle("2026-01-05", 14, 16, 13, 15), new PricePointDto("2026-01-06", BigDecimal.valueOf(15))),
                List.of(indicator("2025-12-29", 10), indicator("2025-12-30", 11), indicator("2026-01-02", 12), indicator("2026-01-05", 13), indicator("2026-01-06", 14)), LocalDate.parse("2025-12-29"));

        assertThat(out.weekly().dates()).containsExactly(LocalDate.parse("2026-01-02"));
        assertThat(out.weekly().opens()).containsExactly(BigDecimal.valueOf(10.0));
        assertThat(out.weekly().highs()).containsExactly(BigDecimal.valueOf(15.0));
        assertThat(out.weekly().lows()).containsExactly(BigDecimal.valueOf(9.0));
        assertThat(out.weekly().closes()).containsExactly(BigDecimal.valueOf(14.0));
    }

    @Test
    void weekly指標取價格週內最後非null且不取同週D加一() {
        IndicatorPointDto early = indicator("2026-03-02", 20);
        IndicatorPointDto lateWithNullK = new IndicatorPointDto("2026-03-06", BigDecimal.valueOf(50), BigDecimal.valueOf(10), BigDecimal.valueOf(20), BigDecimal.valueOf(30),
                null, BigDecimal.valueOf(22), BigDecimal.valueOf(23), BigDecimal.valueOf(24), BigDecimal.valueOf(25), BigDecimal.valueOf(26), BigDecimal.valueOf(27), BigDecimal.valueOf(28), BigDecimal.valueOf(29), BigDecimal.valueOf(30), BigDecimal.valueOf(31), BigDecimal.valueOf(32), BigDecimal.valueOf(33), BigDecimal.valueOf(34), BigDecimal.valueOf(35), BigDecimal.valueOf(36));
        ChartSeriesDto out = ChartSeriesAligner.align(List.of(
                candle("2026-03-02", 10, 11, 9, 10), candle("2026-03-06", 10, 13, 9, 12)),
                List.of(early, lateWithNullK, indicator("2026-03-07", 99)), LocalDate.parse("2026-03-02"));

        assertThat(out.weekly().k()).containsExactly(BigDecimal.valueOf(20.0));
        assertThat(out.weekly().ma5()).containsExactly(BigDecimal.valueOf(50));
        assertThat(out.weekly().latest().k()).isEqualByComparingTo("20");
        assertThat(out.weekly().latest().ma5()).isEqualByComparingTo("50");
    }

    @Test
    void mondayHolidayStart仍保留短週且所有frame欄位等長() {
        ChartSeriesDto out = ChartSeriesAligner.align(List.of(
                candle("2026-04-07", 10, 12, 9, 11), candle("2026-04-08", 11, 13, 10, 12)),
                List.of(indicator("2026-04-07", 10), indicator("2026-04-08", 11)), LocalDate.parse("2026-04-06"));
        assertThat(out.weekly().dates()).containsExactly(LocalDate.parse("2026-04-08"));
        assertThat(out.daily().dates()).hasSize(out.daily().closes().size());
        assertThat(out.daily().dates()).hasSize(out.daily().ma5().size());
        assertThat(out.weekly().dates()).hasSize(out.weekly().closes().size());
        assertThat(out.weekly().dates()).hasSize(out.weekly().ma5().size());
    }

    @Test
    void weekly有自己的currentPrevious及latest不借用topLevel尾值() {
        ChartSeriesDto out = ChartSeriesAligner.align(List.of(
                candle("2026-06-01", 10, 12, 9, 11), candle("2026-06-08", 11, 14, 10, 13)),
                List.of(indicator("2026-06-01", 20), indicator("2026-06-08", 30), indicator("2026-06-09", 99)), LocalDate.parse("2026-06-01"));
        assertThat(out.weekly().currentClose()).isEqualByComparingTo("13");
        assertThat(out.weekly().previousClose()).isEqualByComparingTo("11");
        assertThat(out.weekly().latest().k()).isEqualByComparingTo("30");
        assertThat(out.weekly().latest().prevK()).isEqualByComparingTo("20");
        assertThat(out.latest().k()).isEqualByComparingTo("99");
    }

    @Test
    void weeklyLatestPrev只取直接前一週同欄的null不得回退更早週() {
        ChartSeriesDto out = ChartSeriesAligner.align(List.of(
                candle("2026-07-06", 10, 12, 9, 11),
                candle("2026-07-13", 11, 13, 10, 12),
                candle("2026-07-20", 12, 15, 11, 14)),
                List.of(indicator("2026-07-06", 20), indicatorWithoutK("2026-07-13", 25), indicator("2026-07-20", 30)),
                LocalDate.parse("2026-07-06"));

        assertThat(out.weekly().k()).containsExactly(BigDecimal.valueOf(20.0), null, BigDecimal.valueOf(30.0));
        assertThat(out.weekly().latest().k()).isEqualByComparingTo("30");
        assertThat(out.weekly().latest().prevK()).isNull();
        assertThat(out.weekly().latest().ma5()).isEqualByComparingTo("5");
    }

    @Test
    void noValidCandle的空closeOnly及全invalid皆回傳nonNull空frames() {
        for (List<PricePointDto> prices : List.of(
                List.<PricePointDto>of(),
                List.of(price("2026-05-01", 10)),
                List.of(new PricePointDto("2026-05-01", BigDecimal.valueOf(10), BigDecimal.valueOf(9), BigDecimal.valueOf(11), BigDecimal.valueOf(10))))) {
            ChartSeriesDto out = ChartSeriesAligner.align(prices, List.of(indicator("2026-05-01", 10)));
            assertThat(out.daily()).isNotNull();
            assertThat(out.weekly()).isNotNull();
            assertThat(out.daily().dates()).isEmpty();
            assertThat(out.weekly().dates()).isEmpty();
        }
    }
}
