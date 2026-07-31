package com.steven.assets.bff.stockanalysis;

import com.steven.assets.bff.stockanalysis.dto.ChartSeriesDto;
import com.steven.assets.bff.stockanalysis.dto.IndicatorPointDto;
import com.steven.assets.bff.stockanalysis.dto.PricePointDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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

    private IndicatorPointDto indicator(String date, double k) {
        return new IndicatorPointDto(date,
                BigDecimal.valueOf(10), BigDecimal.valueOf(20), BigDecimal.valueOf(30),
                BigDecimal.valueOf(k), BigDecimal.valueOf(k - 1),
                BigDecimal.valueOf(k - 2), BigDecimal.valueOf(k + 2), BigDecimal.valueOf(k + 5));
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

    @Test
    void 只有一筆指標時prev為null以免箭頭誤判() {
        ChartSeriesDto out = ChartSeriesAligner.align(
                List.of(price("2026-07-30", 101)), List.of(indicator("2026-07-30", 41)));

        assertThat(out.latest().k()).isEqualByComparingTo(BigDecimal.valueOf(41));
        assertThat(out.latest().prevK()).isNull();
    }
}
