package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/** Price-only display projection from one completed local BB20/2 observation. */
public final class RadarPriceReference {
    private RadarPriceReference() {}

    public static TradingRadarDto.PriceReference from(
            TradingRadarRuleEngine.BollingerInput bands, LocalDate completedDate,
            BigDecimal acceptedPrice, TradingRadarRuleEngine.Action finalMediumAction,
            boolean sameRawBasis) {
        if (!sameRawBasis || bands == null || completedDate == null || acceptedPrice == null
                || acceptedPrice.signum() <= 0 || !Double.isFinite(acceptedPrice.doubleValue())
                || finalMediumAction == null || finalMediumAction == TradingRadarRuleEngine.Action.NO_TRADE
                || !completedDate.equals(bands.asOfDate()) || bands.period() != 20
                || bands.standardDeviationMultiplier() != 2) return null;
        BigDecimal lower = bands.lowerBand();
        BigDecimal middle = bands.middleBand();
        BigDecimal upper = bands.upperBand();
        if (lower == null || middle == null || upper == null || lower.signum() <= 0
                || lower.compareTo(middle) > 0 || middle.compareTo(upper) > 0
                || !Double.isFinite(lower.doubleValue()) || !Double.isFinite(middle.doubleValue())
                || !Double.isFinite(upper.doubleValue())) return null;
        BigDecimal buyLower = lower.setScale(2, RoundingMode.DOWN);
        BigDecimal buyUpper = middle.setScale(2, RoundingMode.UP);
        BigDecimal sellLower = middle.setScale(2, RoundingMode.DOWN);
        BigDecimal sellUpper = upper.setScale(2, RoundingMode.UP);
        if (buyLower.signum() <= 0 || buyUpper.signum() <= 0
                || sellLower.signum() <= 0 || sellUpper.signum() <= 0) return null;
        String side = switch (finalMediumAction) {
            case BUY_CANDIDATE, ADD_CANDIDATE, TRIAL_BUY -> "BUY";
            case REDUCE_CANDIDATE, EXIT_CANDIDATE -> "SELL";
            default -> "NONE";
        };
        return new TradingRadarDto.PriceReference(buyLower, buyUpper, sellLower, sellUpper,
                completedDate.toString(), side);
    }
}
