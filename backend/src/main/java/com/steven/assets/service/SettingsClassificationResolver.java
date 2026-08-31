package com.steven.assets.service;

import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.Stock;
import com.steven.assets.model.StockHolding;
import com.steven.assets.model.StockStyle;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.StockStyleRepository;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Single authoritative projection of Asset Class Settings' effective stock
 * classification.
 *
 * <p>Both the settings list and Trading Radar use this resolver so latest
 * snapshot dividend yield, the INCOME threshold, override provenance and the
 * BOND MID fallback cannot drift.  The radar calls {@link #safeContext()} to
 * retain its existing fail-soft detail behavior; the settings list calls the
 * strict {@link #context()} read path.</p>
 */
@Component
@RequiredArgsConstructor
public class SettingsClassificationResolver {
    public static final String RULE = "RULE";
    public static final String OVERRIDE = "OVERRIDE";

    private final AssetClassifier assetClassifier;
    private final AssetSnapshotRepository snapshotRepo;
    private final StockStyleRepository stockStyleRepo;

    /** Settings-page context: failures remain visible to its normal read path. */
    public Context context() {
        return new Context(latestYieldMap(), incomeThreshold());
    }

    /** Radar's supplemental detail must not make an otherwise usable decision fail. */
    public Context safeContext() {
        try {
            return context();
        } catch (RuntimeException unavailable) {
            return new Context(Map.of(), null);
        }
    }

    public Resolution resolve(Stock stock, Context context) {
        if (stock == null) return resolve(null, null, null, null, context);
        return resolve(stock, stock.getCode(), stock.getMarket(), stock.getName(), context);
    }

    /** Pure classification projection once the request-level settings context is loaded. */
    public Resolution resolve(Stock stock, String code, String market, String name, Context context) {
        Context effectiveContext = context == null ? new Context(Map.of(), null) : context;
        String assetClassOverride = stock == null ? null : stock.getAssetClass();
        boolean hasAssetClassOverride = !isBlank(assetClassOverride);
        String effectiveAssetClass = assetClassifier.classifyStock(code, market, assetClassOverride);

        String stockStyleOverride = stock == null ? null : stock.getStockStyle();
        boolean hasStockStyleOverride = !isBlank(stockStyleOverride);
        String effectiveStockStyle = null;
        String stockStyleSource = null;
        if (AssetClassifier.STOCK.equals(effectiveAssetClass)) {
            BigDecimal dividendRate = effectiveContext.latestDividendYield().get(market + "|" + code);
            effectiveStockStyle = assetClassifier.classifyStockStyle(
                    code, market, stockStyleOverride, dividendRate, effectiveContext.incomeThreshold());
            stockStyleSource = hasStockStyleOverride ? OVERRIDE : RULE;
        }

        String bondTermOverride = stock == null ? null : stock.getBondTerm();
        boolean hasBondTermOverride = !isBlank(bondTermOverride);
        String effectiveBondTerm = null;
        String bondTermSource = null;
        if (AssetClassifier.BOND.equals(effectiveAssetClass)) {
            effectiveBondTerm = assetClassifier.classifyBondTerm(code, market, name, bondTermOverride);
            bondTermSource = hasBondTermOverride ? OVERRIDE : RULE;
        }

        return new Resolution(
                effectiveAssetClass,
                hasAssetClassOverride ? OVERRIDE : RULE,
                effectiveStockStyle,
                stockStyleSource,
                effectiveBondTerm,
                bondTermSource,
                hasAssetClassOverride ? assetClassOverride : null,
                hasStockStyleOverride ? stockStyleOverride : null,
                hasBondTermOverride ? bondTermOverride : null);
    }

    private Map<String, BigDecimal> latestYieldMap() {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        List<AssetSnapshot> snapshots = snapshotRepo.findAllOrderByDateDesc();
        if (snapshots.isEmpty() || snapshots.getFirst().getStocks() == null) return values;
        for (StockHolding holding : snapshots.getFirst().getStocks()) {
            if (holding.getDividendRate() != null) {
                values.put(holding.getMarket() + "|" + holding.getStockCode(), holding.getDividendRate());
            }
        }
        return values;
    }

    private BigDecimal incomeThreshold() {
        return stockStyleRepo.findByCode(AssetClassifier.INCOME)
                .map(StockStyle::getDividendThreshold)
                .orElse(null);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record Context(Map<String, BigDecimal> latestDividendYield, BigDecimal incomeThreshold) {
        public Context {
            latestDividendYield = latestDividendYield == null ? Map.of() : Map.copyOf(latestDividendYield);
        }
    }

    /** Immutable settings-equivalent values plus stored overrides, when present. */
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
