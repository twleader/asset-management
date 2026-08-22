package com.steven.assets.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Task 356.7：三軌 × 23 因子權重表是本任務的唯一契約。
 *
 * <p>兩條不變式必須被機器釘住，而不是靠讀表：(a) 三軌各自精確合計 {@code 1.00}；
 * (b) 每一列的「一周 → 1周~1月 → 1月~6月」單調不增或單調不減。<b>逐列資料驅動</b>，
 * 失敗訊息要指得出是哪一個因子——否則 23 列的表改錯一格只會得到一句
 * 「合計不是 1.00」，找不到是哪一列。</p>
 */
class TradingRadarThreeHorizonWeightTest {

    private static final double TOLERANCE = 1e-9;

    @Test
    @DisplayName("356.7b 三軌權重各自精確合計 1.00（容差 1e-9）")
    void eachHorizonWeightSumIsExactlyOne() {
        assertThat(TradingRadarRuleEngine.SHORT_WEIGHT_SUM)
                .as("一周軌 23 個權重合計").isCloseTo(1.00, within(TOLERANCE));
        assertThat(TradingRadarRuleEngine.SWING_WEIGHT_SUM)
                .as("1周~1月 軌 23 個權重合計").isCloseTo(1.00, within(TOLERANCE));
        assertThat(TradingRadarRuleEngine.MEDIUM_WEIGHT_SUM)
                .as("1月~6月 軌 23 個權重合計").isCloseTo(1.00, within(TOLERANCE));
        // 舊測試相容別名必須仍指向中期軌，不得在改版時被指到別的軌。
        assertThat(TradingRadarRuleEngine.WEIGHT_SUM)
                .isEqualTo(TradingRadarRuleEngine.MEDIUM_WEIGHT_SUM);
    }

    @Test
    @DisplayName("356.7 權重表就是三個 WEIGHT_SUM 的來源，不得各自維護一份")
    void weightTableColumnsReproduceTheDeclaredSums() {
        double shortSum = 0;
        double swingSum = 0;
        double mediumSum = 0;
        for (double[] row : TradingRadarRuleEngine.WEIGHT_TABLE) {
            shortSum += row[0];
            swingSum += row[1];
            mediumSum += row[2];
        }
        assertThat(shortSum).isCloseTo(TradingRadarRuleEngine.SHORT_WEIGHT_SUM, within(TOLERANCE));
        assertThat(swingSum).isCloseTo(TradingRadarRuleEngine.SWING_WEIGHT_SUM, within(TOLERANCE));
        assertThat(mediumSum).isCloseTo(TradingRadarRuleEngine.MEDIUM_WEIGHT_SUM, within(TOLERANCE));
    }

    @Test
    @DisplayName("356.7 權重表恰為 23 列，因子數不得默默增減")
    void weightTableHasExactlyTwentyThreeRows() {
        assertThat(TradingRadarRuleEngine.WEIGHT_TABLE.length).isEqualTo(23);
        assertThat(TradingRadarRuleEngine.WEIGHT_TABLE_LABELS).hasSize(23);
        for (double[] row : TradingRadarRuleEngine.WEIGHT_TABLE) {
            assertThat(row).hasSize(3);
        }
    }

    @Test
    @DisplayName("356.7c 每一列跨軌單調（不增或不減），中間軌不得高於或低於兩端")
    void everyRowIsMonotonicAcrossHorizons() {
        for (int i = 0; i < TradingRadarRuleEngine.WEIGHT_TABLE.length; i++) {
            double[] row = TradingRadarRuleEngine.WEIGHT_TABLE[i];
            String label = TradingRadarRuleEngine.WEIGHT_TABLE_LABELS[i];
            boolean nonDecreasing = row[0] <= row[1] + TOLERANCE && row[1] <= row[2] + TOLERANCE;
            boolean nonIncreasing = row[0] >= row[1] - TOLERANCE && row[1] >= row[2] - TOLERANCE;
            assertThat(nonDecreasing || nonIncreasing)
                    .as("因子「%s」跨軌不單調：一周 %s／1周~1月 %s／1月~6月 %s"
                            + "——中間軌的存在理由就是介於兩者之間，鋸齒等於宣告它是第三套獨立直覺，"
                            + "那需要各自的回測依據", label, row[0], row[1], row[2])
                    .isTrue();
        }
    }

    @Test
    @DisplayName("356.7 全部權重非負且不超過 1")
    void everyWeightIsInsideZeroToOne() {
        for (int i = 0; i < TradingRadarRuleEngine.WEIGHT_TABLE.length; i++) {
            double[] row = TradingRadarRuleEngine.WEIGHT_TABLE[i];
            String label = TradingRadarRuleEngine.WEIGHT_TABLE_LABELS[i];
            for (double weight : row) {
                assertThat(weight).as("因子「%s」的權重", label).isBetween(0.0, 1.0);
            }
        }
    }

    @Test
    @DisplayName("356.6 引擎的完成週門檻必須與 assembler 同值（兩邊各自宣告，靠測試釘住）")
    void engineAndAssemblerAgreeOnMinimumCompletedWeeks() {
        assertThat(TradingRadarRuleEngine.WEEKLY_MIN_COMPLETED_WEEKS)
                .as("引擎以它決定要不要採計週K 因子，assembler 以它決定要不要把指標欄填 null；"
                        + "兩者不同步會讓「有 60 根卻不採計」或「不足 60 根卻採計」靜默發生")
                .isEqualTo(RadarInputAssembler.MIN_COMPLETED_WEEKS);
    }
}
