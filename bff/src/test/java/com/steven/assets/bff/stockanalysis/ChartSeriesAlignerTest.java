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
}
