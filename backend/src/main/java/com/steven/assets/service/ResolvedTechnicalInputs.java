package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;

/**
 * The sole technical-value boundary handed to {@link TradingRadarRuleEngine}.
 *
 * <p>It intentionally carries a copied {@code FullIndicators} rather than
 * mutating the local calculator result.  That makes every Fubon overlay
 * explicit at the StockInput/WeeklyInput call site and prevents an unrelated
 * derived formula (J9, MACD, BIAS, confirmations, candles) from silently
 * consuming a provider value.</p>
 */
public record ResolvedTechnicalInputs(
        TechnicalIndicatorService.FullIndicators indicators,
        TradingRadarRuleEngine.WeeklyInput weekly,
        /** Full weekly-series values remain local/display-only, but are cached with a LOCAL snapshot to avoid recomputation. */
        TechnicalIndicatorService.FullIndicators weeklyIndicators,
        TradingRadarDto.TechnicalResolution resolution
) {
    public ResolvedTechnicalInputs(TechnicalIndicatorService.FullIndicators indicators,
                                   TradingRadarRuleEngine.WeeklyInput weekly,
                                   TradingRadarDto.TechnicalResolution resolution) {
        this(indicators, weekly, TechnicalIndicatorService.FullIndicators.EMPTY, resolution);
    }

    public static ResolvedTechnicalInputs local(
            TechnicalIndicatorService.FullIndicators indicators,
            TradingRadarRuleEngine.WeeklyInput weekly,
            TechnicalIndicatorService.FullIndicators weeklyIndicators,
            TradingRadarDto.TechnicalResolution resolution) {
        return new ResolvedTechnicalInputs(
                indicators == null ? TechnicalIndicatorService.FullIndicators.EMPTY : indicators,
                weekly,
                weeklyIndicators == null ? TechnicalIndicatorService.FullIndicators.EMPTY : weeklyIndicators,
                resolution);
    }

    public static ResolvedTechnicalInputs local(
            TechnicalIndicatorService.FullIndicators indicators,
            TradingRadarRuleEngine.WeeklyInput weekly,
            TradingRadarDto.TechnicalResolution resolution) {
        return local(indicators, weekly, TechnicalIndicatorService.FullIndicators.EMPTY, resolution);
    }
}
