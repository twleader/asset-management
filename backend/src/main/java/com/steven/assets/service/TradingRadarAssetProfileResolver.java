package com.steven.assets.service;

import com.steven.assets.model.Stock;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 交易雷達專用的 strict asset profile。
 *
 * <p>此 resolver 是 production/backtest 共用的純規則。它不讀 owner、成本、配置或持股
 * 殖利率；每一欄都保留 provenance 與 completeness，不能以既有分類器的 unknown→MID、
 * null→TWD 等寬鬆 fallback 掩蓋資料缺口。</p>
 */
public final class TradingRadarAssetProfileResolver {

    public enum InstrumentKind { STOCK, EQUITY_ETF, BOND_ETF, UNKNOWN }

    public enum Source { OVERRIDE, CODE_RULE, NAME_RULE, PUBLIC_VALUATION, MARKET_DEFAULT, UNKNOWN }

    public record AssetProfile(
            String assetClass,
            Source assetClassSource,
            boolean assetClassComplete,
            InstrumentKind instrumentKind,
            Source instrumentKindSource,
            boolean instrumentKindComplete,
            String stockStyle,
            Source stockStyleSource,
            boolean stockStyleComplete,
            String bondTerm,
            Source bondTermSource,
            boolean bondTermComplete,
            String quoteCurrency,
            Source quoteCurrencySource,
            boolean quoteCurrencyComplete,
            String underlyingCurrency,
            Source underlyingCurrencySource,
            boolean underlyingCurrencyComplete,
            boolean currencyDataComplete,
            boolean profileComplete,
            List<String> missingReasons
    ) {
        public boolean bond() {
            return AssetClassifier.BOND.equals(assetClass);
        }

        public boolean equity() {
            return AssetClassifier.STOCK.equals(assetClass);
        }

        public static AssetProfile unknown(String reason) {
            return new AssetProfile(
                    AssetClassifier.STOCK, Source.UNKNOWN, false, InstrumentKind.UNKNOWN, Source.UNKNOWN,
                    false, null, Source.UNKNOWN, false, null, Source.UNKNOWN, false,
                    null, Source.UNKNOWN, false, null, Source.UNKNOWN, false, false, false,
                    List.of(reason));
        }
    }

    private TradingRadarAssetProfileResolver() {}

    public static AssetProfile resolve(Stock stock, String code, String market, String name) {
        return resolve(stock, code, market, name, null);
    }

    /** Production/backtest hook: valuation yield must already be resolved as-of the decision instant. */
    public static AssetProfile resolve(
            Stock stock, String code, String market, String name, BigDecimal valuationYieldPct) {
        return resolve(stock, code, market, name, valuationYieldPct,
                AssetClassifier.defaultDividendThreshold());
    }

    /** Production/backtest hook with explicit global INCOME rule threshold. */
    public static AssetProfile resolve(
            Stock stock, String code, String market, String name,
            BigDecimal valuationYieldPct, BigDecimal incomeThresholdRatio) {
        return resolve(
                code,
                market,
                name,
                stock == null ? null : stock.getAssetClass(),
                null,
                stock == null ? null : stock.getStockStyle(),
                stock == null ? null : stock.getBondTerm(),
                stock == null ? null : stock.getUnderlyingCurrency(),
                valuationYieldPct,
                incomeThresholdRatio);
    }

    /**
     * Explicit overload for tests and future master-data instrument override.
     * {@code valuationYieldPct} is a percentage value (4.00 means 4%), not a ratio.
     */
    public static AssetProfile resolve(
            String code,
            String market,
            String name,
            String assetOverride,
            String instrumentOverride,
            String stockStyleOverride,
            String bondTermOverride,
            String underlyingCurrencyOverride,
            BigDecimal valuationYieldPct) {
        return resolve(code, market, name, assetOverride, instrumentOverride,
                stockStyleOverride, bondTermOverride, underlyingCurrencyOverride,
                valuationYieldPct, AssetClassifier.defaultDividendThreshold());
    }

    /**
     * Resolve with the global INCOME threshold read from the non-owner stock-style
     * rule catalog.  {@code valuationYieldPct} remains in percentage points while
     * {@code incomeThresholdRatio} is the catalog ratio (0.04 = 4%).
     */
    public static AssetProfile resolve(
            String code,
            String market,
            String name,
            String assetOverride,
            String instrumentOverride,
            String stockStyleOverride,
            String bondTermOverride,
            String underlyingCurrencyOverride,
            BigDecimal valuationYieldPct,
            BigDecimal incomeThresholdRatio) {
        String normalizedCode = code == null ? null : code.trim().toUpperCase(Locale.ROOT);
        String normalizedMarket = market == null ? null : market.trim();
        String normalizedName = name == null ? "" : name.trim();
        AssetClassifier classifier = AssetClassifier.defaultClassifier();
        List<String> missing = new ArrayList<>();

        String assetClass;
        Source assetSource;
        if (present(assetOverride)) {
            assetClass = upper(assetOverride);
            assetSource = Source.OVERRIDE;
        } else {
            assetClass = classifier.classifyStockByRule(normalizedCode, normalizedMarket);
            if (classifier.isBondByRule(normalizedCode, normalizedMarket)) {
                assetSource = Source.CODE_RULE;
            } else if (isBondNameRule(normalizedName)) {
                // A public ETF name is an explicit instrument fact when the code catalog has
                // no entry (common for a newly listed US/UK bond ETF).  Do not let the generic
                // market default STOCK swallow its bond applicability and Treasury risk gate.
                assetClass = AssetClassifier.BOND;
                assetSource = Source.NAME_RULE;
            } else {
                assetSource = Source.MARKET_DEFAULT;
            }
        }
        boolean assetComplete = isKnownAssetClass(assetClass);
        if (!assetComplete) missing.add("asset_class");

        boolean etfByRule = isEtfByRule(normalizedCode, normalizedName, normalizedMarket, classifier);
        InstrumentKind instrumentKind;
        Source instrumentSource;
        if (present(instrumentOverride)) {
            instrumentKind = parseInstrumentKind(instrumentOverride);
            instrumentSource = Source.OVERRIDE;
        } else if (AssetClassifier.BOND.equals(assetClass) && etfByRule) {
            instrumentKind = InstrumentKind.BOND_ETF;
            instrumentSource = classifier.isBondByRule(normalizedCode, normalizedMarket)
                    ? Source.CODE_RULE : Source.NAME_RULE;
        } else if (etfByRule) {
            instrumentKind = InstrumentKind.EQUITY_ETF;
            instrumentSource = normalizedCode != null && normalizedCode.startsWith("00")
                    ? Source.CODE_RULE : Source.NAME_RULE;
        } else if (AssetClassifier.STOCK.equals(assetClass)) {
            instrumentKind = InstrumentKind.STOCK;
            instrumentSource = assetSource == Source.OVERRIDE
                    ? Source.OVERRIDE : Source.MARKET_DEFAULT;
        } else {
            instrumentKind = InstrumentKind.UNKNOWN;
            instrumentSource = Source.UNKNOWN;
        }
        boolean instrumentComplete = instrumentKind != InstrumentKind.UNKNOWN;
        if (!instrumentComplete) missing.add("instrument_kind");

        String stockStyle = null;
        Source stockStyleSource = Source.UNKNOWN;
        if (AssetClassifier.STOCK.equals(assetClass)) {
            if (present(stockStyleOverride)) {
                stockStyle = upper(stockStyleOverride);
                stockStyleSource = Source.OVERRIDE;
            } else {
                stockStyle = classifier.classifyStockStyleByCodeRule(normalizedCode);
                if (stockStyle != null) stockStyleSource = Source.CODE_RULE;
                else if (valuationYieldPct != null && valuationYieldPct.signum() >= 0) {
                    BigDecimal cutoff = validThreshold(incomeThresholdRatio)
                            ? incomeThresholdRatio : AssetClassifier.defaultDividendThreshold();
                    stockStyle = valuationYieldPct.movePointLeft(2)
                                    .compareTo(cutoff) >= 0
                            ? AssetClassifier.INCOME : AssetClassifier.GROWTH;
                    stockStyleSource = Source.PUBLIC_VALUATION;
                }
            }
        }
        boolean stockStyleComplete = !AssetClassifier.STOCK.equals(assetClass) || stockStyle != null;
        if (!stockStyleComplete) missing.add("stock_style");

        String bondTerm = null;
        Source bondTermSource = Source.UNKNOWN;
        if (AssetClassifier.BOND.equals(assetClass)) {
            if (present(bondTermOverride) && isKnownBondTerm(bondTermOverride)) {
                bondTerm = upper(bondTermOverride);
                bondTermSource = Source.OVERRIDE;
            } else {
                bondTerm = classifier.classifyBondTermStrict(normalizedName);
                if (bondTerm != null) bondTermSource = Source.NAME_RULE;
            }
        }
        boolean bondTermComplete = !AssetClassifier.BOND.equals(assetClass) || bondTerm != null;
        if (!bondTermComplete) missing.add("bond_term");

        String quoteCurrency = marketCurrency(normalizedMarket);
        Source quoteSource = quoteCurrency == null ? Source.UNKNOWN : Source.MARKET_DEFAULT;
        boolean quoteComplete = quoteCurrency != null;
        if (!quoteComplete) missing.add("quote_currency");

        String underlyingCurrency;
        Source underlyingSource;
        if (present(underlyingCurrencyOverride)) {
            underlyingCurrency = upper(underlyingCurrencyOverride);
            underlyingSource = Source.OVERRIDE;
        } else if ("美股".equals(normalizedMarket)) {
            underlyingCurrency = "USD";
            underlyingSource = Source.MARKET_DEFAULT;
        } else if ("英股".equals(normalizedMarket)) {
            underlyingCurrency = "GBP";
            underlyingSource = Source.MARKET_DEFAULT;
        } else if ("台股".equals(normalizedMarket) && AssetClassifier.BOND.equals(assetClass)) {
            // 台股 00...B 債券 ETF 的 underlying currency 是客觀 master data；未知時不可猜 TWD。
            underlyingCurrency = null;
            underlyingSource = Source.UNKNOWN;
        } else if ("台股".equals(normalizedMarket)) {
            underlyingCurrency = "TWD";
            underlyingSource = Source.MARKET_DEFAULT;
        } else {
            underlyingCurrency = null;
            underlyingSource = Source.UNKNOWN;
        }
        boolean currencyComplete = underlyingCurrency != null;
        if (!currencyComplete) missing.add("underlying_currency");

        boolean profileComplete = assetComplete && instrumentComplete && quoteComplete
                && stockStyleComplete && bondTermComplete && currencyComplete;
        return new AssetProfile(
                assetClass, assetSource, assetComplete, instrumentKind, instrumentSource, instrumentComplete,
                stockStyle, stockStyleSource, stockStyleComplete,
                bondTerm, bondTermSource, bondTermComplete,
                quoteCurrency, quoteSource, quoteComplete,
                underlyingCurrency, underlyingSource, currencyComplete, currencyComplete,
                profileComplete, List.copyOf(missing));
    }

    private static boolean isEtfByRule(String code, String name, String market, AssetClassifier classifier) {
        if (code != null && "台股".equals(market) && code.startsWith("00")) return true;
        if (classifier.isBondByRule(code, market)) return true;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("etf") || lower.contains("ishares") || lower.contains("vanguard")
                || lower.contains("spdr") || lower.contains("invesco") || lower.contains("schwab")
                || lower.contains("bond") || name.contains("債");
    }

    private static boolean isBondNameRule(String name) {
        if (name == null || name.isBlank()) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("bond") || lower.contains("treasury") || name.contains("債");
    }

    private static InstrumentKind parseInstrumentKind(String raw) {
        try {
            return InstrumentKind.valueOf(upper(raw));
        } catch (RuntimeException e) {
            return InstrumentKind.UNKNOWN;
        }
    }

    private static boolean isKnownAssetClass(String value) {
        return AssetClassifier.STOCK.equals(value) || AssetClassifier.BOND.equals(value)
                || AssetClassifier.CASH.equals(value);
    }

    private static boolean validThreshold(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.compareTo(BigDecimal.ONE) <= 0
                && value.scale() <= 8;
    }

    private static boolean isKnownBondTerm(String value) {
        return AssetClassifier.SHORT.equals(upper(value)) || AssetClassifier.MID.equals(upper(value))
                || AssetClassifier.LONG.equals(upper(value));
    }

    private static String marketCurrency(String market) {
        if (market == null) return null;
        return switch (market) {
            case "台股" -> "TWD";
            case "美股" -> "USD";
            case "英股" -> "GBP";
            default -> null;
        };
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

}
