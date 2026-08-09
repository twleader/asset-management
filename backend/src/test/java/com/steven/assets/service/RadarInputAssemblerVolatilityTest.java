package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** t274：只用完成日還原收盤的 60 日報酬 ratio sigma。 */
class RadarInputAssemblerVolatilityTest {

    private final RadarInputAssembler assembler = new RadarInputAssembler(
            mock(TechnicalIndicatorService.class), mock(DistributionAdjustedPriceService.class),
            mock(TradingRadarRuleEngine.class));

    @Test
    void usesSixtyCompletedPriceRatiosAndKeepsRatioScale() {
        List<BigDecimal> closesDesc = alternatingReturnPrices();

        RadarInputAssembler.VolatilityObservation result = assembler.returnStdDev60Ratio(
                closesDesc, LocalDate.of(2026, 8, 8));

        assertEquals(LocalDate.of(2026, 8, 8), result.asOfDate());
        assertEquals("DISTRIBUTION_ADJUSTED_COMPLETED_CLOSES", result.source());
        assertTrue(result.available());
        // alternating 1% / 3% daily ratios => sample sigma approximately 1.0085% (0.010085 ratio),
        // proving this is not a percentage-scaled 1.0085 value and not annualised.
        assertEquals(0.010085, result.returnStdDev60Ratio().doubleValue(), 0.00001);
    }

    @Test
    void insufficientOrInvalidPricesAreUnavailable() {
        assertEquals("sigma_insufficient_prices",
                assembler.returnStdDev60Ratio(List.of(BigDecimal.ONE), null).missingReason());

        List<BigDecimal> invalid = new ArrayList<>(alternatingReturnPrices());
        invalid.set(12, BigDecimal.ZERO);
        RadarInputAssembler.VolatilityObservation result = assembler.returnStdDev60Ratio(invalid, null);
        assertNull(result.returnStdDev60Ratio());
        assertEquals("sigma_non_positive_or_non_finite_price", result.missingReason());
    }

    @Test
    void constantPricesDoNotGetAFalseSigmaFloor() {
        RadarInputAssembler.VolatilityObservation result = assembler.returnStdDev60Ratio(
                java.util.Collections.nCopies(61, new BigDecimal("100")), null);

        assertNull(result.returnStdDev60Ratio());
        assertEquals("sigma_non_positive", result.missingReason());
    }

    private static List<BigDecimal> alternatingReturnPrices() {
        List<BigDecimal> prices = new ArrayList<>();
        BigDecimal current = new BigDecimal("100");
        prices.add(current);
        for (int i = 0; i < 60; i++) {
            BigDecimal dailyReturn = new BigDecimal(i % 2 == 0 ? "0.01" : "0.03");
            current = current.divide(BigDecimal.ONE.add(dailyReturn), 24, RoundingMode.HALF_UP);
            prices.add(current);
        }
        return prices;
    }
}
