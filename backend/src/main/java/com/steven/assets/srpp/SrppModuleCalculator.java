package com.steven.assets.srpp;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.dto.AssetSnapshotDto;
import com.steven.assets.dto.LatestAssetsDto;
import com.steven.assets.service.SnapshotAggregateCalculator;
import com.steven.assets.service.StockPriceService;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Requirement 163／Task 452.7：五模組純函式計算（不注入 Spring、不碰 DB）。
 *
 * <p>輸入同一份 {@code LatestAssetsDto.Response}、已驗證政策與交易日；輸出 context 的 {@code modules}，或
 * 拒絕發布的原因碼。所有金額以 canonical Decimal 字串輸出；未知一律 UNAVAILABLE／LOWER_BOUND，
 * 不以零代替、不套用任何稅率；配置差距僅為描述性比較，不得解讀為可買張數。
 */
public final class SrppModuleCalculator {
    private SrppModuleCalculator() {}

    public record Result(ObjectNode modules, Optional<String> rejectReason) {
        static Result reject(String reason) {
            return new Result(null, Optional.of(reason));
        }
    }

    public static final List<String> CHECK_NAMES = List.of(
            "snapshot_deposits", "live_deposits", "snapshot_funds", "live_funds",
            "snapshot_stocks", "live_stocks", "snapshot_total", "live_total");
    static final Set<String> SUPPORTED_CURRENCIES = Set.of("TWD", "USD", "TRANSIT_TWD", "TRANSIT_USD");
    private static final List<String> NET_UNAVAILABLE_FIELDS = List.of(
            "permanentTermInterestReinvested", "spendableAnnualGross", "taiwanIncomeTaxOrRefund",
            "usWithholding", "additionalBasicTax", "supplementaryNhi", "afterAllTaxAnnualCashIncome");
    private static final MathContext DIVISION = new MathContext(34, RoundingMode.HALF_EVEN);
    private static final BigDecimal MIN_TOLERANCE = new BigDecimal("0.01");
    private static final BigDecimal RELATIVE_TOLERANCE = new BigDecimal("0.0001");
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final List<String> ASSETS_ONLY = List.of("assets");
    private static final List<String> POLICY_ONLY = List.of("policy");
    private static final List<String> ASSETS_POLICY = List.of("assets", "policy");

    public static Result calculate(LatestAssetsDto.Response response, SupportedPolicy policy, LocalDate tradingDate) {
        try {
            return new Result(modules(response, policy, tradingDate), Optional.empty());
        } catch (SrppRejectedException e) {
            return Result.reject(e.reasonCode());
        }
    }

    private static ObjectNode modules(LatestAssetsDto.Response response, SupportedPolicy policy, LocalDate tradingDate) {
        if (response == null || response.snapshot() == null || response.liveAssets() == null) {
            throw new SrppRejectedException("ASSET_IDENTITY_MISMATCH");
        }
        AssetSnapshotDto.SnapshotDetailResponse snapshot = response.snapshot();
        StockPriceService.LiveAssetsResponse live = response.liveAssets();
        verifyIdentity(snapshot, live);
        verifyCurrencies(snapshot);
        Totals totals = totals(snapshot, live);

        ObjectNode modules = F.objectNode();
        modules.set("assets", assetsModule(response, snapshot, live, totals));
        modules.set("allocation", allocationModule(snapshot, live, policy));
        modules.set("cashIncome", cashIncomeModule(snapshot, tradingDate));
        modules.set("funding", unavailableModule(SrppFormulaCatalog.CALC_FUNDING));
        modules.set("completedTechnicals", unavailableModule(SrppFormulaCatalog.CALC_TECHNICALS));
        return modules;
    }

    // ───────────── assets ─────────────

    private static void verifyIdentity(AssetSnapshotDto.SnapshotDetailResponse snapshot,
                                       StockPriceService.LiveAssetsResponse live) {
        if (snapshot.id() == null || !snapshot.id().equals(live.snapshotId())
                || snapshot.snapshotDate() == null
                || !snapshot.snapshotDate().toString().equals(live.snapshotDate())) {
            throw new SrppRejectedException("ASSET_IDENTITY_MISMATCH");
        }
        List<AssetSnapshotDto.StockResponse> stocks = nullSafe(snapshot.stocks());
        List<StockPriceService.LiveStockItem> liveStocks = nullSafe(live.stocks());
        Map<Long, AssetSnapshotDto.StockResponse> byId = new HashMap<>();
        for (AssetSnapshotDto.StockResponse stock : stocks) {
            if (stock.id() == null || byId.put(stock.id(), stock) != null) {
                throw new SrppRejectedException("ASSET_IDENTITY_MISMATCH");
            }
        }
        Set<Long> seen = new HashSet<>();
        for (StockPriceService.LiveStockItem item : liveStocks) {
            AssetSnapshotDto.StockResponse stock = item == null ? null : byId.get(item.holdingId());
            if (stock == null || !seen.add(item.holdingId())
                    || !Objects.equals(stock.market(), item.market())
                    || !Objects.equals(stock.stockCode(), item.stockCode())
                    || !sharesEqual(stock.shares(), item.shares())) {
                throw new SrppRejectedException("ASSET_IDENTITY_MISMATCH");
            }
        }
        if (!seen.equals(byId.keySet())) throw new SrppRejectedException("ASSET_IDENTITY_MISMATCH");
    }

    private static boolean sharesEqual(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return a == null && b == null;
        return a.compareTo(b) == 0;
    }

    private static void verifyCurrencies(AssetSnapshotDto.SnapshotDetailResponse snapshot) {
        for (AssetSnapshotDto.DepositResponse deposit : nullSafe(snapshot.deposits())) {
            if (deposit.currency() == null || !SUPPORTED_CURRENCIES.contains(deposit.currency())) {
                throw new SrppRejectedException("UNSUPPORTED_DEPOSIT_CURRENCY");
            }
            if (deposit.depositType() == null || deposit.id() == null) {
                throw new SrppRejectedException("ASSET_AMOUNT_MISSING");
            }
        }
    }

    private record Totals(BigDecimal deposits, BigDecimal funds, BigDecimal snapshotStocks, BigDecimal liveStocks,
                          ArrayNode checks) {}

    private static Totals totals(AssetSnapshotDto.SnapshotDetailResponse snapshot,
                                 StockPriceService.LiveAssetsResponse live) {
        BigDecimal deposits = sum(nullSafe(snapshot.deposits()).stream().map(AssetSnapshotDto.DepositResponse::amount).toList());
        BigDecimal funds = sum(nullSafe(snapshot.funds()).stream().map(AssetSnapshotDto.FundResponse::currentValue).toList());
        BigDecimal snapshotStocks = sum(nullSafe(snapshot.stocks()).stream().map(AssetSnapshotDto.StockResponse::currentValue).toList());
        BigDecimal liveStocks = sum(nullSafe(live.stocks()).stream().map(StockPriceService.LiveStockItem::liveValue).toList());

        ArrayNode checks = F.arrayNode();
        boolean passed = true;
        passed &= check(checks, "snapshot_deposits", deposits, snapshot.totalDeposit());
        passed &= check(checks, "live_deposits", deposits, live.totalDeposit());
        passed &= check(checks, "snapshot_funds", funds, snapshot.totalFundValue());
        passed &= check(checks, "live_funds", funds, live.totalFundValue());
        passed &= check(checks, "snapshot_stocks", snapshotStocks, snapshot.totalStockValue());
        passed &= check(checks, "live_stocks", liveStocks, live.liveStockValue());
        passed &= check(checks, "snapshot_total", deposits.add(funds).add(snapshotStocks), snapshot.totalAssets());
        passed &= check(checks, "live_total", deposits.add(funds).add(liveStocks), live.liveTotalAssets());
        if (!passed) throw new SrppRejectedException("RECONCILIATION_FAILED");
        return new Totals(deposits, funds, snapshotStocks, liveStocks, checks);
    }

    /** {@code max(0.01, max(|detail|,|reported|) × 0.0001)}。 */
    static BigDecimal tolerance(BigDecimal detail, BigDecimal reported) {
        BigDecimal relative = detail.abs().max(reported.abs()).multiply(RELATIVE_TOLERANCE);
        return MIN_TOLERANCE.max(relative);
    }

    private static boolean check(ArrayNode checks, String name, BigDecimal detail, BigDecimal reported) {
        if (reported == null) throw new SrppRejectedException("ASSET_AMOUNT_MISSING");
        BigDecimal difference = detail.subtract(reported);
        BigDecimal tolerance = tolerance(detail, reported);
        boolean passed = difference.abs().compareTo(tolerance) <= 0;
        ObjectNode check = F.objectNode();
        check.put("name", name);
        check.put("detailTwd", SrppDecimal.format(detail));
        check.put("reportedTwd", SrppDecimal.format(reported));
        check.put("differenceTwd", SrppDecimal.format(difference));
        check.put("toleranceTwd", SrppDecimal.format(tolerance));
        check.put("passed", passed);
        checks.add(check);
        return passed;
    }

    private static ObjectNode assetsModule(LatestAssetsDto.Response response,
                                           AssetSnapshotDto.SnapshotDetailResponse snapshot,
                                           StockPriceService.LiveAssetsResponse live, Totals totals) {
        ObjectNode data = F.objectNode();
        data.set("snapshotTotalDeposit", exact(snapshot.totalDeposit(), "TWD", ASSETS_ONLY));
        data.set("snapshotStockValue", exact(snapshot.totalStockValue(), "TWD", ASSETS_ONLY));
        data.set("snapshotFundValue", exact(snapshot.totalFundValue(), "TWD", ASSETS_ONLY));
        data.set("snapshotTotalAssets", exact(snapshot.totalAssets(), "TWD", ASSETS_ONLY));
        data.set("liveStockValue", exact(live.liveStockValue(), "TWD", ASSETS_ONLY));
        data.set("liveTotalAssets", exact(live.liveTotalAssets(), "TWD", ASSETS_ONLY));
        data.put("targetPriceComplete", response.targetPriceComplete());
        ObjectNode rowCounts = F.objectNode();
        rowCounts.put("deposits", nullSafe(snapshot.deposits()).size());
        rowCounts.put("stocks", nullSafe(snapshot.stocks()).size());
        rowCounts.put("funds", nullSafe(snapshot.funds()).size());
        data.set("rowCounts", rowCounts);
        data.set("checks", totals.checks());
        data.set("depositGroups", depositGroups(snapshot));

        List<String> reasons = response.targetPriceComplete() ? List.of() : List.of("TARGET_PRICE_INCOMPLETE");
        return module(response.targetPriceComplete() ? "COMPLETE" : "PARTIAL", reasons, ASSETS_ONLY,
                SrppFormulaCatalog.CALC_ASSETS, data);
    }

    private record GroupKey(String currency, String depositType, Long bankId) {}

    private static final Comparator<GroupKey> GROUP_ORDER = Comparator
            .comparing(GroupKey::currency)
            .thenComparing(GroupKey::depositType)
            .thenComparing(k -> k.bankId() == null ? null : String.valueOf(k.bankId()),
                    Comparator.nullsFirst(Comparator.<String>naturalOrder()));

    private static ArrayNode depositGroups(AssetSnapshotDto.SnapshotDetailResponse snapshot) {
        SortedMap<GroupKey, List<AssetSnapshotDto.DepositResponse>> groups = new TreeMap<>(GROUP_ORDER);
        for (AssetSnapshotDto.DepositResponse d : nullSafe(snapshot.deposits())) {
            groups.computeIfAbsent(new GroupKey(d.currency(), d.depositType(), d.bankId()), k -> new ArrayList<>()).add(d);
        }
        ArrayNode out = F.arrayNode();
        groups.forEach((key, rows) -> {
            ObjectNode group = F.objectNode();
            if (key.bankId() == null) group.putNull("bankId");
            else group.put("bankId", String.valueOf(key.bankId()));
            String bankName = rows.stream().map(AssetSnapshotDto.DepositResponse::bankDisplayName)
                    .filter(Objects::nonNull).findFirst().orElse(null);
            if (bankName == null) group.putNull("bankName");
            else group.put("bankName", bankName);
            group.put("currency", key.currency());
            group.put("depositType", key.depositType());
            group.set("sourceRowIds", sortedIds(rows.stream().map(r -> "DEPOSIT-" + r.id()).toList()));
            group.put("amountTwd", SrppDecimal.format(sum(rows.stream().map(AssetSnapshotDto.DepositResponse::amount).toList())));
            boolean originalComplete = rows.stream().allMatch(r -> r.originalAmount() != null);
            if (originalComplete) {
                group.put("originalAmount", SrppDecimal.format(
                        rows.stream().map(AssetSnapshotDto.DepositResponse::originalAmount).reduce(BigDecimal.ZERO, BigDecimal::add)));
            } else {
                group.putNull("originalAmount");
            }
            BigDecimal interest = rows.stream().map(SrppModuleCalculator::depositInterest)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            group.set("estimatedAnnualInterest", metric(interest, "TWD", "ESTIMATE", List.of(), ASSETS_ONLY));
            out.add(group);
        });
        return out;
    }

    /** 委派共用純函式 {@link SnapshotAggregateCalculator#depositEstimatedInterest(BigDecimal, BigDecimal, String)}，不另寫公式。 */
    static BigDecimal depositInterest(AssetSnapshotDto.DepositResponse deposit) {
        return SnapshotAggregateCalculator.depositEstimatedInterest(
                deposit.amount(), deposit.annualInterestRate(), deposit.currency());
    }

    // ───────────── allocation ─────────────

    private static final class Exposure {
        BigDecimal value = BigDecimal.ZERO;
        final List<String> holdingIds = new ArrayList<>();
    }

    private static ObjectNode allocationModule(AssetSnapshotDto.SnapshotDetailResponse snapshot,
                                               StockPriceService.LiveAssetsResponse live, SupportedPolicy policy) {
        BigDecimal denominator = live.liveTotalAssets();
        if (denominator == null || denominator.signum() <= 0) {
            ObjectNode module = F.objectNode();
            module.put("status", "UNAVAILABLE");
            module.set("reasonCodes", strings(List.of("DENOMINATOR_NOT_POSITIVE")));
            module.set("sourceIds", F.arrayNode());
            module.put("calculationId", SrppFormulaCatalog.CALC_ALLOCATION);
            module.putNull("data");
            return module;
        }
        SortedMap<String, Exposure> held = new TreeMap<>();
        for (StockPriceService.LiveStockItem item : nullSafe(live.stocks())) {
            Exposure e = held.computeIfAbsent("STOCK:" + item.market() + ":" + item.stockCode(), k -> new Exposure());
            e.value = e.value.add(item.liveValue());
            e.holdingIds.add("STOCK-" + item.holdingId());
        }
        List<String> unmapped = new ArrayList<>();
        for (AssetSnapshotDto.FundResponse fund : nullSafe(snapshot.funds())) {
            if (fund.fundCode() == null || fund.fundCode().isBlank()) {
                unmapped.add("FUND-" + fund.id());
                continue;
            }
            Exposure e = held.computeIfAbsent("FUND:" + fund.fundCode(), k -> new Exposure());
            e.value = e.value.add(fund.currentValue());
            e.holdingIds.add("FUND-" + fund.id());
        }
        for (AssetSnapshotDto.DepositResponse deposit : nullSafe(snapshot.deposits())) {
            Exposure e = held.computeIfAbsent("CASH:" + deposit.currency() + ":" + deposit.depositType(), k -> new Exposure());
            e.value = e.value.add(deposit.amount());
            e.holdingIds.add("DEPOSIT-" + deposit.id());
        }

        TreeSet<String> keys = new TreeSet<>(held.keySet());
        keys.addAll(policy.targets().keySet());
        ArrayNode rows = F.arrayNode();
        boolean targetMissing = false;
        for (String key : keys) {
            Exposure e = held.get(key);
            BigDecimal exposure = e == null ? BigDecimal.ZERO : e.value;
            BigDecimal currentWeight = exposure.divide(denominator, DIVISION);
            ObjectNode row = F.objectNode();
            row.put("assetKey", key);
            String[] parts = key.split(":", 3);
            if (key.startsWith("STOCK:") && parts.length == 3) {
                row.put("market", parts[1]);
                row.put("symbol", parts[2]);
            } else {
                row.putNull("market");
                row.putNull("symbol");
            }
            row.set("sourceHoldingIds", sortedIds(e == null ? List.of() : e.holdingIds));
            row.set("exposureTwd", exact(exposure, "TWD", ASSETS_ONLY));
            row.set("currentWeight", exact(currentWeight, "RATIO", ASSETS_ONLY));
            BigDecimal target = policy.targets().get(key);
            if (target != null) {
                row.set("targetWeight", exact(target, "RATIO", POLICY_ONLY));
                row.set("gapWeight", exact(target.subtract(currentWeight), "RATIO", ASSETS_POLICY));
                row.set("gapValueTwd", exact(target.multiply(denominator).subtract(exposure), "TWD", ASSETS_POLICY));
            } else {
                targetMissing = true;
                row.set("targetWeight", unavailable("RATIO", "TARGET_NOT_MAPPED"));
                row.set("gapWeight", unavailable("RATIO", "TARGET_NOT_MAPPED"));
                row.set("gapValueTwd", unavailable("TWD", "TARGET_NOT_MAPPED"));
            }
            rows.add(row);
        }
        ObjectNode data = F.objectNode();
        data.put("basis", "TOTAL_EXPOSURE_REFERENCE");
        data.set("denominatorTwd", exact(denominator, "TWD", ASSETS_ONLY));
        data.put("shortTermSleeveSeparated", false);
        data.set("rows", rows);
        data.set("unmappedHoldingIds", sortedIds(unmapped));

        List<String> reasons = new ArrayList<>();
        if (!unmapped.isEmpty()) reasons.add("HOLDING_NOT_MAPPED");
        if (targetMissing) reasons.add("TARGET_NOT_MAPPED");
        return module(reasons.isEmpty() ? "COMPLETE" : "PARTIAL", reasons, ASSETS_POLICY,
                SrppFormulaCatalog.CALC_ALLOCATION, data);
    }

    // ───────────── cashIncome ─────────────

    private static ObjectNode cashIncomeModule(AssetSnapshotDto.SnapshotDetailResponse snapshot, LocalDate tradingDate) {
        List<String> missing = new ArrayList<>();
        BigDecimal stockIncome = BigDecimal.ZERO;
        boolean stockMissing = false;
        for (AssetSnapshotDto.StockResponse stock : nullSafe(snapshot.stocks())) {
            if (stock.estimatedDividend() == null) {
                stockMissing = true;
                missing.add("STOCK-" + stock.id());
            } else {
                stockIncome = stockIncome.add(stock.estimatedDividend());
            }
        }
        BigDecimal fundIncome = BigDecimal.ZERO;
        boolean fundMissing = false;
        for (AssetSnapshotDto.FundResponse fund : nullSafe(snapshot.funds())) {
            if (fund.estimatedDividend() == null) {
                fundMissing = true;
                missing.add("FUND-" + fund.id());
            } else {
                fundIncome = fundIncome.add(fund.estimatedDividend());
            }
        }
        BigDecimal depositIncome = nullSafe(snapshot.deposits()).stream()
                .map(SrppModuleCalculator::depositInterest).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal classifiedSum = stockIncome.add(fundIncome).add(depositIncome);
        boolean anyMissing = stockMissing || fundMissing;

        TreeSet<String> moduleReasons = new TreeSet<>();
        moduleReasons.add("NET_CALCULATION_NOT_VERIFIED");
        if (anyMissing) moduleReasons.add("INCOME_ROWS_MISSING");
        // sourceAccruedAnnualIncome 單一來源：快照 estimatedAnnualDividend；三項分類和只用於對帳。
        BigDecimal reported = snapshot.estimatedAnnualDividend();
        ObjectNode sourceAccrued;
        if (reported == null) {
            moduleReasons.add("SNAPSHOT_ESTIMATED_DIVIDEND_MISSING");
            sourceAccrued = unavailable("TWD", "SNAPSHOT_ESTIMATED_DIVIDEND_MISSING");
        } else {
            if (classifiedSum.subtract(reported).abs().compareTo(tolerance(classifiedSum, reported)) > 0) {
                moduleReasons.add("INCOME_RECONCILIATION_MISMATCH");
            }
            sourceAccrued = income(reported, anyMissing);
        }

        ObjectNode data = F.objectNode();
        data.set("stockAndEtfDistributions", income(stockIncome, stockMissing));
        data.set("fundDistributions", income(fundIncome, fundMissing));
        data.set("depositInterest", income(depositIncome, false));
        data.set("sourceAccruedAnnualIncome", sourceAccrued);
        for (String field : NET_UNAVAILABLE_FIELDS) {
            data.set(field, unavailable("TWD", "NET_CALCULATION_NOT_VERIFIED"));
        }
        data.set("missingIncomeRowIds", sortedIds(missing));
        data.putNull("netCalculationStandard");
        data.put("taxYear", tradingDate.getYear());
        return module("PARTIAL", List.copyOf(moduleReasons), ASSETS_ONLY, SrppFormulaCatalog.CALC_CASH_INCOME, data);
    }

    private static ObjectNode income(BigDecimal value, boolean lowerBound) {
        return lowerBound
                ? metric(value, "TWD", "LOWER_BOUND", List.of("INCOME_ROWS_MISSING"), ASSETS_ONLY)
                : metric(value, "TWD", "ESTIMATE", List.of(), ASSETS_ONLY);
    }

    // ───────────── helpers ─────────────

    private static ObjectNode unavailableModule(String calculationId) {
        ObjectNode module = F.objectNode();
        module.put("status", "UNAVAILABLE");
        module.set("reasonCodes", strings(List.of("CALCULATOR_NOT_VERIFIED")));
        module.set("sourceIds", F.arrayNode());
        module.put("calculationId", calculationId);
        module.putNull("data");
        return module;
    }

    private static ObjectNode module(String status, List<String> reasons, List<String> sourceIds,
                                     String calculationId, ObjectNode data) {
        ObjectNode module = F.objectNode();
        module.put("status", status);
        module.set("reasonCodes", strings(new TreeSet<>(reasons)));
        module.set("sourceIds", strings(sourceIds));
        module.put("calculationId", calculationId);
        module.set("data", data);
        return module;
    }

    private static ObjectNode exact(BigDecimal value, String unit, List<String> sourceIds) {
        if (value == null) throw new SrppRejectedException("ASSET_AMOUNT_MISSING");
        return metric(value, unit, "EXACT", List.of(), sourceIds);
    }

    private static ObjectNode metric(BigDecimal value, String unit, String quality, List<String> reasons,
                                     List<String> sourceIds) {
        ObjectNode metric = F.objectNode();
        metric.put("value", SrppDecimal.format(value));
        metric.put("unit", unit);
        metric.put("quality", quality);
        metric.set("reasonCodes", strings(reasons));
        metric.set("sourceIds", strings(sourceIds));
        return metric;
    }

    private static ObjectNode unavailable(String unit, String reason) {
        ObjectNode metric = F.objectNode();
        metric.putNull("value");
        metric.put("unit", unit);
        metric.put("quality", "UNAVAILABLE");
        metric.set("reasonCodes", strings(List.of(reason)));
        metric.set("sourceIds", F.arrayNode());
        return metric;
    }

    private static ArrayNode strings(Collection<String> values) {
        ArrayNode out = F.arrayNode();
        values.forEach(out::add);
        return out;
    }

    private static ArrayNode sortedIds(Collection<String> ids) {
        return strings(new TreeSet<>(ids));
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            if (value == null) throw new SrppRejectedException("ASSET_AMOUNT_MISSING");
            total = total.add(value);
        }
        return total;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
