package com.steven.assets.service;

import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.model.StockPriceHistory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Task 308 的可成交回測價格與成本模型。
 *
 * <p>這個類別刻意是純 Java helper，不改動既有 Task 273 的 API。主要樣本只接受
 * {@code adjustedOpen[t+1] -> adjustedOpen[t+1+h]}；close 路徑永遠是獨立敏感度，
 * 不會在 open 缺值時悄悄補入主要樣本。</p>
 */
public final class RadarBacktestExecution {

    public static final String TW_MARKET = "台股";
    public static final String US_MARKET = "美股";
    public static final String DEFAULT_COST_SOURCE = "CONSERVATIVE_MODEL_2026_08";
    private static final Set<String> ALLOWED_MARKETS = Set.of(TW_MARKET, US_MARKET);
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final int CALC_SCALE = 12;
    private static final int OUTPUT_SCALE = 8;

    private RadarBacktestExecution() {}

    public enum InstrumentKind { STOCK, EQUITY_ETF, BOND_ETF }

    public enum ExecutionMode { NEXT_OPEN_PRIMARY, CLOSE_FALLBACK_SENSITIVITY }

    public record CostKey(String market, InstrumentKind instrumentKind) {
        public CostKey {
            if (!ALLOWED_MARKETS.contains(market)) {
                throw new IllegalArgumentException("market 僅允許台股或美股");
            }
            Objects.requireNonNull(instrumentKind, "instrumentKind");
        }
    }

    /** 各比例欄位單位皆為百分比，例如 {@code 0.1425} 代表 0.1425%。 */
    public record CostAssumption(
            BigDecimal buyFeePct,
            BigDecimal sellFeePct,
            BigDecimal sellTaxPct,
            BigDecimal slippageEachSidePct,
            String sourceLabel,
            LocalDate effectiveFrom,
            LocalDate effectiveTo
    ) {
        public CostAssumption {
            validateRate(buyFeePct, "buyFeePct");
            validateRate(sellFeePct, "sellFeePct");
            validateRate(sellTaxPct, "sellTaxPct");
            validateRate(slippageEachSidePct, "slippageEachSidePct");
            if (sourceLabel == null || sourceLabel.isBlank()) {
                throw new IllegalArgumentException("sourceLabel 不可空白");
            }
            if (effectiveFrom != null && effectiveTo != null && effectiveFrom.isAfter(effectiveTo)) {
                throw new IllegalArgumentException("effectiveFrom 不得晚於 effectiveTo");
            }
        }

        /** 買進單邊成本後，實際需要付出的金額。 */
        public BigDecimal entryCashRequired(BigDecimal price) {
            requirePositive(price, "entry price");
            BigDecimal rate = buyFeePct.add(slippageEachSidePct).divide(ONE_HUNDRED, CALC_SCALE,
                    RoundingMode.HALF_UP);
            return price.multiply(BigDecimal.ONE.add(rate));
        }

        /** 賣出單邊成本後，實際可收回的金額。 */
        public BigDecimal exitCashReceived(BigDecimal price) {
            requirePositive(price, "exit price");
            BigDecimal rate = sellFeePct.add(sellTaxPct).add(slippageEachSidePct)
                    .divide(ONE_HUNDRED, CALC_SCALE, RoundingMode.HALF_UP);
            return price.multiply(BigDecimal.ONE.subtract(rate));
        }

        /** 依 Task 308 固定公式計算雙邊成本後報酬，單位為百分比。 */
        public BigDecimal netReturnPct(BigDecimal entry, BigDecimal exit) {
            return exitCashReceived(exit)
                    .divide(entryCashRequired(entry), CALC_SCALE, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE)
                    .multiply(ONE_HUNDRED)
                    .setScale(OUTPUT_SCALE, RoundingMode.HALF_UP);
        }

        /** 假設價格不變時的完整來回成本，取正值、單位為百分點。 */
        public BigDecimal roundTripCostPct() {
            return netReturnPct(BigDecimal.ONE, BigDecimal.ONE).negate()
                    .setScale(OUTPUT_SCALE, RoundingMode.HALF_UP);
        }

        /** promotion 的實質差異門檻，不得低於 0.10 個百分點。 */
        public BigDecimal returnPracticalDeltaPct() {
            return roundTripCostPct().max(new BigDecimal("0.10"));
        }

        private static void validateRate(BigDecimal rate, String field) {
            if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(5)) > 0) {
                throw new IllegalArgumentException(field + " 必須介於 0..5");
            }
        }
    }

    /** 已經以同一價格基準完成 distribution/split 還原的 OHLC。 */
    public record AdjustedBar(
            String code,
            String market,
            LocalDate date,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close
    ) {
        public AdjustedBar {
            if (code == null || code.isBlank()) throw new IllegalArgumentException("code 不可空白");
            if (!ALLOWED_MARKETS.contains(market)) {
                throw new IllegalArgumentException("market 僅允許台股或美股");
            }
            Objects.requireNonNull(date, "date");
        }
    }

    public record ExecutionSample(
            String code,
            String market,
            int horizon,
            ExecutionMode mode,
            LocalDate signalDate,
            LocalDate entryDate,
            LocalDate exitDate,
            BigDecimal entryPrice,
            BigDecimal exitPrice,
            BigDecimal grossReturnRatio,
            BigDecimal grossReturnPct,
            BigDecimal netReturnPct,
            CostKey costKey,
            CostAssumption costAssumption
    ) {}

    /**
     * 一次 signal/horizon 的解析結果。缺 open 時仍保留日期與獨立 close sensitivity，
     * 讓呼叫端能分別累計 entry/exit 缺值，而不是只得到一個模糊的 null。
     */
    public record ExecutionAttempt(
            LocalDate signalDate,
            LocalDate entryDate,
            LocalDate exitDate,
            Optional<ExecutionSample> primary,
            Optional<ExecutionSample> closeSensitivity,
            boolean excludedMissingEntryOpen,
            boolean excludedMissingExitOpen,
            boolean excludedInsufficientForward,
            boolean excludedCostOutsideEffectiveRange
    ) {
        public ExecutionAttempt {
            primary = primary == null ? Optional.empty() : primary;
            closeSensitivity = closeSensitivity == null ? Optional.empty() : closeSensitivity;
        }
    }

    /** 固定模型假設；不是券商報價或法規宣稱。 */
    public static Map<CostKey, CostAssumption> defaultCosts() {
        Map<CostKey, CostAssumption> out = new LinkedHashMap<>();
        CostAssumption twStock = assumption("0.1425", "0.1425", "0.3000", "0.0500");
        CostAssumption twEtf = assumption("0.1425", "0.1425", "0.1000", "0.0500");
        CostAssumption us = assumption("0.0500", "0.0500", "0", "0.0500");
        out.put(new CostKey(TW_MARKET, InstrumentKind.STOCK), twStock);
        out.put(new CostKey(TW_MARKET, InstrumentKind.EQUITY_ETF), twEtf);
        out.put(new CostKey(TW_MARKET, InstrumentKind.BOND_ETF), twEtf);
        out.put(new CostKey(US_MARKET, InstrumentKind.STOCK), us);
        out.put(new CostKey(US_MARKET, InstrumentKind.EQUITY_ETF), us);
        out.put(new CostKey(US_MARKET, InstrumentKind.BOND_ETF), us);
        return Map.copyOf(out);
    }

    public static CostAssumption resolveCost(
            CostKey key, Map<CostKey, CostAssumption> requestOverrides) {
        Objects.requireNonNull(key, "key");
        if (requestOverrides != null && requestOverrides.containsKey(key)) {
            return Objects.requireNonNull(requestOverrides.get(key), "cost override");
        }
        CostAssumption fallback = defaultCosts().get(key);
        if (fallback == null) throw new IllegalArgumentException("沒有此 market/instrumentKind 的成本模型");
        return fallback;
    }

    /**
     * 直接重用 production 的還原服務，確保 open/high/low/close 同時套用 distribution/split 因子。
     */
    public static List<AdjustedBar> adjustedBars(
            List<StockPriceHistory> rows,
            List<StockDividendHistory> events,
            DistributionAdjustedPriceService adjustmentService) {
        Objects.requireNonNull(adjustmentService, "adjustmentService");
        if (rows == null || rows.isEmpty()) return List.of();
        DistributionAdjustedPriceService.Adjustment adjusted = adjustmentService.adjust(rows, events);
        List<AdjustedBar> out = new ArrayList<>(adjusted.rowsDesc().size());
        for (StockPriceHistory row : adjusted.rowsDesc()) {
            out.add(new AdjustedBar(row.getStockCode(), row.getMarket(), row.getTradingDate(),
                    row.getOpenPrice(), row.getHighPrice(), row.getLowPrice(), row.getClosePrice()));
        }
        out.sort(Comparator.comparing(AdjustedBar::date));
        return List.copyOf(out);
    }

    /**
     * signal 在 completed close row t 形成；h 是完整持有 session 數，因此成交索引固定為
     * {@code t+1} 與 {@code t+1+h}。close sensitivity 使用相同日期，且永不回填 primary。
     */
    public static ExecutionAttempt execute(
            List<AdjustedBar> barsAsc,
            int signalIndex,
            int horizon,
            CostKey costKey,
            CostAssumption cost,
            boolean includeCloseSensitivity) {
        Objects.requireNonNull(barsAsc, "barsAsc");
        Objects.requireNonNull(costKey, "costKey");
        Objects.requireNonNull(cost, "cost");
        if (signalIndex < 0 || signalIndex >= barsAsc.size()) {
            throw new IllegalArgumentException("signalIndex 越界");
        }
        if (horizon < 1 || horizon > 240) {
            throw new IllegalArgumentException("horizon 必須介於 1..240");
        }

        AdjustedBar signal = barsAsc.get(signalIndex);
        int entryIndex = signalIndex + 1;
        int exitIndex = signalIndex + 1 + horizon;
        LocalDate entryDate = entryIndex < barsAsc.size() ? barsAsc.get(entryIndex).date() : null;
        LocalDate exitDate = exitIndex < barsAsc.size() ? barsAsc.get(exitIndex).date() : null;
        if (entryIndex >= barsAsc.size() || exitIndex >= barsAsc.size()) {
            return new ExecutionAttempt(signal.date(), entryDate, exitDate,
                    Optional.empty(), Optional.empty(), false, false, true, false);
        }

        AdjustedBar entryBar = barsAsc.get(entryIndex);
        AdjustedBar exitBar = barsAsc.get(exitIndex);
        if (!costApplies(cost, entryBar.date(), exitBar.date())) {
            return new ExecutionAttempt(signal.date(), entryBar.date(), exitBar.date(),
                    Optional.empty(), Optional.empty(), false, false, false, true);
        }
        boolean missingEntry = !positive(entryBar.open());
        boolean missingExit = !positive(exitBar.open());
        Optional<ExecutionSample> primary = missingEntry || missingExit
                ? Optional.empty()
                : Optional.of(sample(signal, entryBar, exitBar, horizon,
                        ExecutionMode.NEXT_OPEN_PRIMARY, entryBar.open(), exitBar.open(), costKey, cost));

        Optional<ExecutionSample> sensitivity = Optional.empty();
        if (includeCloseSensitivity && positive(entryBar.close()) && positive(exitBar.close())) {
            sensitivity = Optional.of(sample(signal, entryBar, exitBar, horizon,
                    ExecutionMode.CLOSE_FALLBACK_SENSITIVITY,
                    entryBar.close(), exitBar.close(), costKey, cost));
        }
        return new ExecutionAttempt(signal.date(), entryBar.date(), exitBar.date(),
                primary, sensitivity, missingEntry, missingExit, false, false);
    }

    private static ExecutionSample sample(
            AdjustedBar signal,
            AdjustedBar entryBar,
            AdjustedBar exitBar,
            int horizon,
            ExecutionMode mode,
            BigDecimal entry,
            BigDecimal exit,
            CostKey costKey,
            CostAssumption cost) {
        BigDecimal grossRatio = exit.divide(entry, CALC_SCALE, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE);
        return new ExecutionSample(signal.code(), signal.market(), horizon, mode,
                signal.date(), entryBar.date(), exitBar.date(), entry, exit,
                grossRatio, grossRatio.multiply(ONE_HUNDRED).setScale(OUTPUT_SCALE, RoundingMode.HALF_UP),
                cost.netReturnPct(entry, exit), costKey, cost);
    }

    private static CostAssumption assumption(String buy, String sell, String tax, String slip) {
        return new CostAssumption(new BigDecimal(buy), new BigDecimal(sell), new BigDecimal(tax),
                new BigDecimal(slip), DEFAULT_COST_SOURCE, null, null);
    }

    /** Override 生效區間為 inclusive，entry 與 exit 必須同時落在區間內。 */
    private static boolean costApplies(
            CostAssumption cost,
            LocalDate entryDate,
            LocalDate exitDate) {
        if (entryDate == null || exitDate == null) return false;
        if (cost.effectiveFrom() != null
                && (entryDate.isBefore(cost.effectiveFrom()) || exitDate.isBefore(cost.effectiveFrom()))) {
            return false;
        }
        return cost.effectiveTo() == null
                || (!entryDate.isAfter(cost.effectiveTo()) && !exitDate.isAfter(cost.effectiveTo()));
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static void requirePositive(BigDecimal value, String field) {
        if (!positive(value)) throw new IllegalArgumentException(field + " 必須大於 0");
    }
}
