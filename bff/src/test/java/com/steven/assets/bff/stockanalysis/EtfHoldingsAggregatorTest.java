package com.steven.assets.bff.stockanalysis;

import com.steven.assets.bff.stockanalysis.dto.EtfHoldingDto;
import com.steven.assets.bff.stockanalysis.dto.EtfHoldingsDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EtfHoldingsAggregator} 的「前 10 大 + 其它」聚合（Task 359.4）。
 *
 * 比照 {@link ChartSeriesAlignerTest} 的手法：純函式、不需要 Spring context 或 WebClient，
 * 直接餵 fixture 驗證輸出。
 */
class EtfHoldingsAggregatorTest {

    private EtfHoldingDto holding(String code, String name, double weight) {
        return new EtfHoldingDto(code, name, BigDecimal.valueOf(weight), BigDecimal.valueOf(1000));
    }

    private EtfHoldingsDto source(List<EtfHoldingDto> holdings) {
        return new EtfHoldingsDto("00919", "台股", true, "MoneyDJ", "2026/08/14", null, holdings);
    }

    @Test
    void 超過10檔時前10大依weight降冪保留第11名起加總為其它() {
        List<EtfHoldingDto> holdings = List.of(
                holding("2882", "國泰金", 3.0),
                holding("2330", "台積電", 9.5),
                holding("2881", "富邦金", 5.0),
                holding("2891", "中信金", 4.5),
                holding("2886", "兆豐金", 4.0),
                holding("2884", "玉山金", 3.5),
                holding("5880", "合庫金", 3.2),
                holding("2885", "元大金", 3.1),
                holding("2887", "台新金", 2.9),
                holding("2892", "第一金", 2.8),
                holding("2880", "華南金", 2.7),
                holding("2883", "凱基金", 2.6));

        EtfHoldingsDto out = EtfHoldingsAggregator.aggregate(source(holdings));

        assertThat(out.holdings()).hasSize(11);
        assertThat(out.holdings().subList(0, 10).stream().map(EtfHoldingDto::stockCode).toList())
                .containsExactly("2330", "2881", "2891", "2886", "2884", "5880", "2885", "2882", "2887", "2892");
        for (int i = 1; i < 10; i++) {
            assertThat(out.holdings().get(i - 1).weight())
                    .isGreaterThanOrEqualTo(out.holdings().get(i).weight());
        }

        EtfHoldingDto others = out.holdings().get(10);
        assertThat(others.stockCode()).isNull();
        assertThat(others.stockName()).isEqualTo("其它");
        assertThat(others.shares()).isNull();
        // 第 11 名起：2880(2.7) + 2883(2.6) = 5.3
        assertThat(others.weight()).isEqualByComparingTo("5.3");
    }

    @Test
    void 恰好10檔時原樣透傳不產生其它項() {
        List<EtfHoldingDto> holdings = List.of(
                holding("1", "a", 10), holding("2", "b", 9), holding("3", "c", 8),
                holding("4", "d", 7), holding("5", "e", 6), holding("6", "f", 5),
                holding("7", "g", 4), holding("8", "h", 3), holding("9", "i", 2),
                holding("10", "j", 1));

        EtfHoldingsDto out = EtfHoldingsAggregator.aggregate(source(holdings));

        assertThat(out.holdings()).hasSize(10).containsExactlyElementsOf(holdings);
    }

    @Test
    void 少於10檔時原樣透傳() {
        List<EtfHoldingDto> holdings = List.of(holding("1", "a", 60), holding("2", "b", 40));

        EtfHoldingsDto out = EtfHoldingsAggregator.aggregate(source(holdings));

        assertThat(out.holdings()).hasSize(2).containsExactlyElementsOf(holdings);
    }

    @Test
    void holdings為空陣列或null時不產生其它項() {
        EtfHoldingsDto unsupported = new EtfHoldingsDto("XXXX", "美股", false, null, null, "不支援", List.of());
        assertThat(EtfHoldingsAggregator.aggregate(unsupported).holdings()).isEmpty();

        EtfHoldingsDto nullHoldings = new EtfHoldingsDto("XXXX", "美股", false, null, null, "不支援", null);
        assertThat(EtfHoldingsAggregator.aggregate(nullHoldings)).isSameAs(nullHoldings);
    }

    @Test
    void source為null時直接回傳null不拋例外() {
        assertThat(EtfHoldingsAggregator.aggregate(null)).isNull();
    }

    @Test
    void 頂層欄位原封不動透傳不因排序截斷而遺失() {
        List<EtfHoldingDto> holdings = IntStream.rangeClosed(1, 11)
                .mapToObj(i -> holding(String.valueOf(i), "s" + i, 12 - i))
                .toList();
        EtfHoldingsDto src = new EtfHoldingsDto("0056", "台股", true, "MoneyDJ", "2026/08/14", "備註", holdings);

        EtfHoldingsDto out = EtfHoldingsAggregator.aggregate(src);

        assertThat(out.stockCode()).isEqualTo("0056");
        assertThat(out.market()).isEqualTo("台股");
        assertThat(out.supported()).isTrue();
        assertThat(out.source()).isEqualTo("MoneyDJ");
        assertThat(out.asOfDate()).isEqualTo("2026/08/14");
        assertThat(out.message()).isEqualTo("備註");
        assertThat(out.holdings()).hasSize(11); // 10 個別 + 1 其它（輸入 11 檔）
    }
}
