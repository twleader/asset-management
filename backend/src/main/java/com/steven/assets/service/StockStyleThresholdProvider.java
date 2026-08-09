package com.steven.assets.service;

import com.steven.assets.repository.StockStyleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Read-only global rule source for the INCOME style threshold.  This provider
 * intentionally has no owner/portfolio inputs, so production and backtest use
 * the same configured {@code stock_style(INCOME).dividend_threshold} rule.
 */
@Component
@RequiredArgsConstructor
public class StockStyleThresholdProvider {

    private final StockStyleRepository repository;

    public BigDecimal incomeThreshold() {
        try {
            return repository.findByCode(AssetClassifier.INCOME)
                    .filter(style -> Boolean.TRUE.equals(style.getActive()))
                    .map(style -> style.getDividendThreshold())
                    .filter(StockStyleThresholdProvider::validRatio)
                    .orElseGet(AssetClassifier::defaultDividendThreshold);
        } catch (RuntimeException ignored) {
            // Missing/unavailable reference data must not turn a radar request into
            // an owner-data fallback; the immutable catalog fallback is explicit.
            return AssetClassifier.defaultDividendThreshold();
        }
    }

    private static boolean validRatio(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.compareTo(BigDecimal.ONE) <= 0
                && value.scale() <= 8;
    }
}
