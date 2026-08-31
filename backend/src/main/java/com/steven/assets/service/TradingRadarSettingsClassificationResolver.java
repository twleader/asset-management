package com.steven.assets.service;

import com.steven.assets.model.Stock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only projection of the Asset Class Settings page's effective
 * classification for a Trading Radar row.
 *
 * <p>This is intentionally separate from {@link TradingRadarAssetProfileResolver}:
 * the radar profile is strict decision evidence, whereas this projection must
 * preserve the settings page's fallback and override semantics.  In
 * particular, STOCK style uses the latest settings-scope snapshot dividend
 * rate and the configured INCOME threshold; BOND term retains the settings
 * page's MID fallback.</p>
 */
@Component
@RequiredArgsConstructor
public class TradingRadarSettingsClassificationResolver {

    static final String TAIWAN_MARKET = "台股";
    static final String TAIEX_CODE = "0000";
    private final SettingsClassificationResolver settingsClassification;

    /**
     * Resolves one current radar row using exactly the settings-list inputs.
     * The Taiwan market index is deliberately excluded only from this display
     * projection; callers keep its normal radar/index/regime processing.
     */
    @Transactional(readOnly = true)
    public Resolution resolve(Stock stock, String code, String market, String name) {
        if (TAIWAN_MARKET.equals(market) && TAIEX_CODE.equals(code)) {
            return null;
        }
        return safeResolve(stock, code, market, name);
    }

    /** Package-visible pure seam for regression coverage of settings parity. */
    Resolution resolve(
            Stock stock,
            String code,
            String market,
            String name,
            SettingsClassificationResolver.Context context) {
        return from(settingsClassification.resolve(stock, code, market, name, context));
    }

    private static Resolution from(SettingsClassificationResolver.Resolution source) {
        if (source == null) return null;
        return new Resolution(
                source.effectiveAssetClass(), source.assetClassSource(),
                source.effectiveStockStyle(), source.stockStyleSource(),
                source.effectiveBondTerm(), source.bondTermSource(),
                source.assetClassOverride(), source.stockStyleOverride(), source.bondTermOverride());
    }

    /**
     * Supplemental classification must stay fail-soft even if the optional
     * settings repositories are temporarily unavailable.  A non-index row
     * still receives an explicit projection with null effective values rather
     * than disappearing from current/error detail.
     */
    private Resolution safeResolve(Stock stock, String code, String market, String name) {
        try {
            return from(settingsClassification.resolve(stock, code, market, name, settingsClassification.safeContext()));
        } catch (RuntimeException ignored) {
            return new Resolution(null, null, null, null, null, null, null, null, null);
        }
    }

    /** Immutable settings-equivalent values plus the stored override values, if any. */
    public record Resolution(
            String effectiveAssetClass,
            String assetClassSource,
            String effectiveStockStyle,
            String stockStyleSource,
            String effectiveBondTerm,
            String bondTermSource,
            String assetClassOverride,
            String stockStyleOverride,
            String bondTermOverride) {}
}
