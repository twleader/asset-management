package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 356.5b／356.5c／356.5b-2：日K 棒三分量的 public static 純函數。
 *
 * <p>三支一律回傳<b>未經 clamp、未經線性轉換的原值</b>：{@code closePosition ∈ [0,1]}、
 * {@code bodyDirection ∈ {-1,0,1}}、{@code lowerShadowRatio ∈ [0,1]}。轉成 contribution
 * （{@code (closePosition − 0.5) × 2}、{@code (lowerShadow − 0.25) × 4}）留在引擎。</p>
 *
 * <p><b>三支共用同一條全幅判準</b>（{@code high − low <= 0} 即缺值），故
 * {@code bodyDirection} 的簽章是四參數的 {@code (open, high, low, close)}。</p>
 */
class TradingRadarCandleComponentsTest {

    @Test
    void normalBarProducesAllThreeComponents() {
        // O=10 H=20 L=0 C=15：收在區間 75%、實體為正、下影線 = (min(10,15) − 0)/20 = 0.5
        assertThat(TradingRadarRuleEngine.closePosition(bd(20), bd(0), bd(15)))
                .isEqualByComparingTo("0.75");
        assertThat(TradingRadarRuleEngine.bodyDirection(bd(10), bd(20), bd(0), bd(15)))
                .isEqualByComparingTo("1");
        assertThat(TradingRadarRuleEngine.lowerShadowRatio(bd(10), bd(20), bd(0), bd(15)))
                .isEqualByComparingTo("0.5");
    }

    @Test
    void closeAtRangeEdgesGivesZeroAndOne() {
        assertThat(TradingRadarRuleEngine.closePosition(bd(20), bd(10), bd(10)))
                .isEqualByComparingTo("0");
        assertThat(TradingRadarRuleEngine.closePosition(bd(20), bd(10), bd(20)))
                .isEqualByComparingTo("1");
    }

    @Test
    void highEqualsLowMakesRangeComponentsUnavailable() {
        // 漲跌停鎖死：全幅為 0，收盤區間位置與下影線比例都無定義，不得以 0.5／0 冒充中性。
        assertThat(TradingRadarRuleEngine.closePosition(bd(30), bd(30), bd(30))).isNull();
        assertThat(TradingRadarRuleEngine.lowerShadowRatio(bd(30), bd(30), bd(30), bd(30))).isNull();
        // 全幅非正＝這根 K 棒是一個沒有價格區間的點，不是十字線：實體方向一律缺值，
        // 不得回 0——0 會進 Accumulator 的 sumW，被讀成「十字線＝多空平衡」的有效中性讀數。
        assertThat(TradingRadarRuleEngine.bodyDirection(bd(30), bd(30), bd(30), bd(30))).isNull();
    }

    @Test
    void invertedHighLowDirtyRowLeavesAllThreeComponentsUnavailable() {
        // high < low 的倒置髒列（schema 對 O/H/L 沒有任何 CHECK，這不是理論風險）：
        // 若判準寫成「相等」而非「非正」，實體方向會回 ±1，讓一根確定無效的 K 棒拿到滿貢獻。
        assertThat(TradingRadarRuleEngine.closePosition(bd(10), bd(20), bd(15))).isNull();
        assertThat(TradingRadarRuleEngine.bodyDirection(bd(12), bd(10), bd(20), bd(15))).isNull();
        assertThat(TradingRadarRuleEngine.lowerShadowRatio(bd(12), bd(10), bd(20), bd(15))).isNull();
    }

    @Test
    void missingOpenLeavesOnlyTheClosePositionComponent() {
        assertThat(TradingRadarRuleEngine.closePosition(bd(20), bd(10), bd(18)))
                .isEqualByComparingTo("0.8");
        assertThat(TradingRadarRuleEngine.bodyDirection(null, bd(20), bd(10), bd(18))).isNull();
        assertThat(TradingRadarRuleEngine.lowerShadowRatio(null, bd(20), bd(10), bd(18))).isNull();
    }

    @Test
    void dojiHasZeroBodyDirection() {
        // 真正的十字線＝ high > low 且 close == open。必須用有價格區間的 K 棒構造，
        // 直接把 high = low = 12 會踩進全幅非正的 null 分支而得到相反的期望。
        assertThat(TradingRadarRuleEngine.bodyDirection(bd(12), bd(14), bd(11), bd(12)))
                .isEqualByComparingTo("0");
        assertThat(TradingRadarRuleEngine.bodyDirection(bd(12), bd(14), bd(11), bd(11)))
                .isEqualByComparingTo("-1");
    }

    @Test
    void longLowerShadowScoresHighAndLongUpperShadowScoresLow() {
        // 長下影線：O=18 H=20 L=10 C=19 → (min(18,19) − 10)/10 = 0.8（中性點 0.25 之上）
        BigDecimal longLower = TradingRadarRuleEngine.lowerShadowRatio(bd(18), bd(20), bd(10), bd(19));
        assertThat(longLower).isEqualByComparingTo("0.8");
        assertThat(longLower).isGreaterThan(new BigDecimal("0.25"));

        // 長上影線：O=10 H=20 L=10 C=11 → (min(10,11) − 10)/10 = 0（中性點之下）
        BigDecimal longUpper = TradingRadarRuleEngine.lowerShadowRatio(bd(10), bd(20), bd(10), bd(11));
        assertThat(longUpper).isEqualByComparingTo("0");
        assertThat(longUpper).isLessThan(new BigDecimal("0.25"));
    }

    @Test
    void anyMissingColumnIsUnavailableNeverZero() {
        assertThat(TradingRadarRuleEngine.closePosition(null, bd(10), bd(12))).isNull();
        assertThat(TradingRadarRuleEngine.closePosition(bd(20), null, bd(12))).isNull();
        assertThat(TradingRadarRuleEngine.closePosition(bd(20), bd(10), null)).isNull();
        assertThat(TradingRadarRuleEngine.bodyDirection(bd(10), bd(20), bd(10), null)).isNull();
        assertThat(TradingRadarRuleEngine.bodyDirection(null, bd(20), bd(10), bd(12))).isNull();
        assertThat(TradingRadarRuleEngine.bodyDirection(bd(10), null, bd(10), bd(12))).isNull();
        assertThat(TradingRadarRuleEngine.bodyDirection(bd(10), bd(20), null, bd(12))).isNull();
        assertThat(TradingRadarRuleEngine.lowerShadowRatio(bd(10), bd(20), bd(10), null)).isNull();
        assertThat(TradingRadarRuleEngine.lowerShadowRatio(bd(10), null, bd(10), bd(12))).isNull();
    }

    @Test
    void componentsStayInsideTheirDeclaredRanges() {
        for (int close = 10; close <= 20; close++) {
            BigDecimal position = TradingRadarRuleEngine.closePosition(bd(20), bd(10), bd(close));
            BigDecimal shadow = TradingRadarRuleEngine.lowerShadowRatio(bd(15), bd(20), bd(10), bd(close));
            assertThat(position).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
            assertThat(shadow).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
            assertThat(TradingRadarRuleEngine.bodyDirection(bd(15), bd(20), bd(10), bd(close)).abs())
                    .isLessThanOrEqualTo(BigDecimal.ONE);
        }
    }

    private static BigDecimal bd(int value) {
        return BigDecimal.valueOf(value);
    }
}
