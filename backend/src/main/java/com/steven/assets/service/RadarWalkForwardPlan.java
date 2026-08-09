package com.steven.assets.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Task 308 的全市場交易日切分與 expanding walk-forward 規劃器。 */
public final class RadarWalkForwardPlan {

    private RadarWalkForwardPlan() {}

    public record MarketHorizon(String market, int horizon) {
        public MarketHorizon {
            // 共用 CostKey 的 market 白名單與 horizon 驗證，避免兩處規則漂移。
            new RadarBacktestExecution.CostKey(market, RadarBacktestExecution.InstrumentKind.STOCK);
            if (horizon < 1 || horizon > 240) {
                throw new IllegalArgumentException("horizon 必須介於 1..240");
            }
        }
    }

    /**
     * 與 candidate 無關的可成交觀察。只有 entry/exit open 都有效的日期才會進全域日曆；
     * code 只供驗證不同標的共用同一條邊界，不參與切分比例。
     */
    public record TradableOpportunity(
            String market,
            int horizon,
            String code,
            LocalDate signalDate,
            boolean validEntryOpen,
            boolean validExitOpen
    ) {
        public TradableOpportunity {
            new MarketHorizon(market, horizon);
            if (code == null || code.isBlank()) throw new IllegalArgumentException("code 不可空白");
            Objects.requireNonNull(signalDate, "signalDate");
        }
    }

    public record ChronologicalSplit(
            List<LocalDate> globalDates,
            BigDecimal calibrationRatio,
            LocalDate cutoff,
            List<LocalDate> calibrationDates,
            List<LocalDate> holdoutDates,
            boolean sufficientForCutoff
    ) {
        public ChronologicalSplit {
            globalDates = List.copyOf(globalDates);
            calibrationDates = List.copyOf(calibrationDates);
            holdoutDates = List.copyOf(holdoutDates);
        }

        public boolean isCalibration(LocalDate date) {
            return cutoff != null && date != null && !date.isAfter(cutoff);
        }

        public boolean isHoldout(LocalDate date) {
            return cutoff != null && date != null && date.isAfter(cutoff);
        }
    }

    public record Fold(
            int index,
            List<LocalDate> trainDates,
            List<LocalDate> evaluationDates
    ) {
        public Fold {
            if (index < 1) throw new IllegalArgumentException("fold index 從 1 起算");
            trainDates = List.copyOf(trainDates);
            evaluationDates = List.copyOf(evaluationDates);
            if (trainDates.isEmpty() || evaluationDates.isEmpty()) {
                throw new IllegalArgumentException("fold 必須同時有 train 與 evaluation 日期");
            }
            LocalDate evaluationMin = evaluationDates.get(0);
            if (trainDates.stream().anyMatch(date -> !date.isBefore(evaluationMin))) {
                throw new IllegalArgumentException("所有 train date 必須嚴格早於 evaluation block");
            }
        }

        public LocalDate evaluationFrom() { return evaluationDates.get(0); }

        public LocalDate evaluationTo() { return evaluationDates.get(evaluationDates.size() - 1); }
    }

    /** 先以 market/horizon 分組，再跨 code 去重；不接受 candidate hit 作輸入。 */
    public static Map<MarketHorizon, List<LocalDate>> globalCalendars(
            Collection<TradableOpportunity> opportunities) {
        if (opportunities == null || opportunities.isEmpty()) return Map.of();
        Map<MarketHorizon, TreeSet<LocalDate>> grouped = new LinkedHashMap<>();
        for (TradableOpportunity opportunity : opportunities) {
            if (opportunity == null || !opportunity.validEntryOpen() || !opportunity.validExitOpen()) continue;
            grouped.computeIfAbsent(new MarketHorizon(opportunity.market(), opportunity.horizon()),
                    ignored -> new TreeSet<>()).add(opportunity.signalDate());
        }
        List<Map.Entry<MarketHorizon, TreeSet<LocalDate>>> entries = new ArrayList<>(grouped.entrySet());
        entries.sort(Comparator.comparing((Map.Entry<MarketHorizon, TreeSet<LocalDate>> e) -> e.getKey().market())
                .thenComparingInt(e -> e.getKey().horizon()));
        Map<MarketHorizon, List<LocalDate>> out = new LinkedHashMap<>();
        for (var entry : entries) out.put(entry.getKey(), List.copyOf(entry.getValue()));
        return java.util.Collections.unmodifiableMap(out);
    }

    /**
     * cutoff 完全由全市場唯一日期決定。單一日期時 floor 可能為 0，這時明確標示 insufficient，
     * 不偷偷把該日同時放進 calibration 與 holdout。
     */
    public static ChronologicalSplit chronologicalSplit(
            Collection<LocalDate> dates, BigDecimal calibrationRatio) {
        validateCalibrationRatio(calibrationRatio);
        List<LocalDate> global = sortedUnique(dates);
        int count = calibrationRatio.multiply(BigDecimal.valueOf(global.size()))
                .setScale(0, RoundingMode.FLOOR).intValueExact();
        if (count <= 0 || count >= global.size()) {
            return new ChronologicalSplit(global, calibrationRatio, null,
                    List.of(), global, false);
        }
        List<LocalDate> calibration = global.subList(0, count);
        List<LocalDate> holdout = global.subList(count, global.size());
        return new ChronologicalSplit(global, calibrationRatio, calibration.get(calibration.size() - 1),
                calibration, holdout, true);
    }

    /**
     * 前 50% 是 initial train；其餘日期切成 K 個連續、大小差至多 1 的 evaluation blocks。
     * 日期不足時不製造空 fold，讓 promotion gate 以 valid fold 數明確拒絕。
     */
    public static List<Fold> expandingWalkForward(Collection<LocalDate> dates, int requestedFolds) {
        if (requestedFolds < 3 || requestedFolds > 10) {
            throw new IllegalArgumentException("walkForwardFolds 必須介於 3..10");
        }
        List<LocalDate> global = sortedUnique(dates);
        int initial = global.size() / 2;
        int remaining = global.size() - initial;
        if (initial == 0 || remaining == 0) return List.of();

        int baseSize = remaining / requestedFolds;
        int extra = remaining % requestedFolds;
        List<Fold> out = new ArrayList<>();
        int start = initial;
        for (int i = 0; i < requestedFolds; i++) {
            int blockSize = baseSize + (i < extra ? 1 : 0);
            if (blockSize == 0) continue;
            int end = start + blockSize;
            out.add(new Fold(out.size() + 1,
                    global.subList(0, start), global.subList(start, end)));
            start = end;
        }
        return List.copyOf(out);
    }

    private static List<LocalDate> sortedUnique(Collection<LocalDate> dates) {
        if (dates == null || dates.isEmpty()) return List.of();
        TreeSet<LocalDate> sorted = new TreeSet<>();
        for (LocalDate date : dates) if (date != null) sorted.add(date);
        return List.copyOf(sorted);
    }

    private static void validateCalibrationRatio(BigDecimal ratio) {
        if (ratio == null || ratio.compareTo(new BigDecimal("0.50")) < 0
                || ratio.compareTo(new BigDecimal("0.80")) > 0) {
            throw new IllegalArgumentException("calibrationRatio 必須介於 0.50..0.80");
        }
    }
}
