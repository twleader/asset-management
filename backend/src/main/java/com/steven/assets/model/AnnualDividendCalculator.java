package com.steven.assets.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

import static com.steven.assets.model.AnnualDividendData.*;

/** Tax-before cash estimates from dated holdings and the independently recorded trade ledger. */
public final class AnnualDividendCalculator {
    private AnnualDividendCalculator() {}

    public static Calculation calculate(Facts facts, int year, String market, LocalDate today) {
        List<Row> rows = new ArrayList<>();
        List<Gap> gaps = new ArrayList<>();
        List<Security> securities = facts.securities().stream()
                .filter(s -> market == null || market.isEmpty() || market.equals(s.market()))
                .sorted(Comparator.comparing(Security::market).thenComparing(Security::code)).toList();
        for (Security security : securities) {
            List<Event> events = facts.events().stream().filter(e -> same(security, e.code(), e.market())).toList();
            boolean anySelectedEvent = false;
            Map<LocalDate, List<Event>> grouped = new TreeMap<>();
            for (Event event : events) {
                if (onlyStock(event)) {
                    if (event.exRightsDate().getYear() == year) anySelectedEvent = true;
                    continue;
                }
                if (event.exDividendDate() == null) {
                    if (event.year() == year) {
                        gaps.add(gap(security, "配息資料缺少除息日；年度彙總不納入加總"));
                        anySelectedEvent = true;
                    }
                } else if (event.exDividendDate().getYear() == year) {
                    anySelectedEvent = true;
                    grouped.computeIfAbsent(event.exDividendDate(), ignored -> new ArrayList<>()).add(event);
                }
            }
            if (!anySelectedEvent) gaps.add(gap(security, "此年度查無已保存的配息公告，不能視為零配息"));
            for (var entry : grouped.entrySet()) {
                if (entry.getValue().stream().allMatch(e -> e.cashDividend() != null && e.cashDividend().signum() == 0)) continue;
                rows.add(estimate(facts, security, entry.getKey(), entry.getValue(), today));
            }
        }
        rows.sort(Comparator.comparing(Row::exDividendDate).thenComparing(Row::market).thenComparing(Row::code));
        Map<String, BigDecimal[]> totals = new TreeMap<>();
        gaps = new ArrayList<>(new LinkedHashSet<>(gaps));
        int unavailable = gaps.size();
        for (Row row : rows) {
            if (row.estimatedCash() == null) { unavailable++; continue; }
            BigDecimal[] amounts = totals.computeIfAbsent(row.currency(), ignored ->
                    new BigDecimal[]{new BigDecimal("0.00"), new BigDecimal("0.00")});
            int bucket = row.exDividendDate().isAfter(today) ? 1 : 0;
            amounts[bucket] = amounts[bucket].add(row.estimatedCash());
        }
        List<CurrencyTotal> currencyTotals = totals.entrySet().stream()
                .map(e -> new CurrencyTotal(e.getKey(), e.getValue()[0], e.getValue()[1],
                        e.getValue()[0].add(e.getValue()[1]))).toList();
        return new Calculation(rows, gaps, currencyTotals, unavailable);
    }

    private static Row estimate(Facts facts, Security security, LocalDate exDate,
                                List<Event> events, LocalDate today) {
        List<String> reasons = new ArrayList<>();
        Set<String> amounts = new HashSet<>();
        for (Event e : events) amounts.add(e.cashDividend() == null ? "UNKNOWN"
                : e.cashDividend().stripTrailingZeros().toPlainString());
        BigDecimal cash = amounts.size() == 1 && !amounts.contains("UNKNOWN")
                ? events.getFirst().cashDividend() : null;
        if (cash == null) reasons.add(amounts.size() > 1 ? "同次除息的現金股利來源衝突或有缺值" : "每股現金股利未提供");
        else if (cash.signum() < 0) { reasons.add("每股現金股利不可為負數"); cash = null; }
        List<LocalDate> paymentDates = events.stream().map(Event::cashPaymentDate)
                .filter(Objects::nonNull).distinct().toList();
        LocalDate payment = paymentDates.size() == 1 ? paymentDates.getFirst() : null;
        if (paymentDates.size() > 1) reasons.add("現金發放日來源不一致，未擅選日期");
        String source = events.stream().map(Event::source).filter(s -> s != null && !s.isBlank())
                .distinct().sorted().reduce((a, b) -> a + "、" + b).orElse(null);
        String currency = switch (security.market()) {
            case "台股" -> "TWD";
            case "美股" -> "USD";
            default -> null;
        };
        if (currency == null) reasons.add("此市場缺少可確認的股利幣別，未依成本幣別推定");
        boolean future = exDate.isAfter(today);
        LocalDate cutoff = future ? today : exDate.minusDays(1);
        Holding holding = holding(facts, security, cutoff);
        if (holding.reason() != null) reasons.add(holding.reason());
        BigDecimal estimate = cash == null || currency == null || holding.shares() == null ? null
                : holding.shares().multiply(cash).setScale(2, RoundingMode.HALF_UP);
        return new Row(security.code(), security.name(), security.market(), currency,
                exDate, payment, cash, holding.shares(), estimate, holding.baseline(), holding.tradeCount(),
                future ? "FUTURE_ESTIMATE" : "HISTORICAL_ESTIMATE",
                reasons.isEmpty() ? null : String.join("；", reasons), source);
    }

    private record Holding(BigDecimal shares, LocalDate baseline, int tradeCount, String reason) {}

    private static Holding holding(Facts facts, Security security, LocalDate cutoff) {
        Snapshot baseline = facts.snapshots().stream().filter(s -> !s.date().isAfter(cutoff))
                .max(Comparator.comparing(Snapshot::date)).orElse(null);
        if (baseline == null) return new Holding(null, null, 0, "缺少截止日前的持股快照，無法由交易紀錄從零推算");
        List<Trade> trades = facts.trades().stream().filter(t -> same(security, t.code(), t.market())
                && t.date().isAfter(baseline.date()) && !t.date().isAfter(cutoff)).toList();
        BigDecimal shares = BigDecimal.ZERO;
        for (Position position : baseline.positions()) {
            if (!same(security, position.code(), position.market())) continue;
            if (position.shares() == null || position.shares().signum() < 0)
                return new Holding(null, baseline.date(), trades.size(), "基準快照的持股股數缺漏或為負數");
            shares = shares.add(position.shares());
        }
        Map<LocalDate, BigDecimal> daily = new TreeMap<>();
        for (Trade trade : trades) {
            if (trade.shares() == null || trade.shares().signum() < 0)
                return new Holding(null, baseline.date(), trades.size(), "交易紀錄的股數缺漏或為負數");
            if (!"買".equals(trade.direction()) && !"賣".equals(trade.direction()))
                return new Holding(null, baseline.date(), trades.size(), "交易紀錄的買賣方向無法辨識");
            BigDecimal delta = "買".equals(trade.direction()) ? trade.shares() : trade.shares().negate();
            daily.merge(trade.date(), delta, BigDecimal::add);
        }
        for (BigDecimal delta : daily.values()) {
            shares = shares.add(delta);
            if (shares.signum() < 0)
                return new Holding(null, baseline.date(), trades.size(), "交易調整後曾出現日末負股數，持股資料不足或不一致");
        }
        return new Holding(shares, baseline.date(), trades.size(), null);
    }

    private static boolean same(Security security, String code, String market) {
        return Objects.equals(security.code(), code) && Objects.equals(security.market(), market);
    }
    private static boolean onlyStock(Event event) {
        return event.exDividendDate() == null && event.exRightsDate() != null
                && (event.cashDividend() == null || event.cashDividend().signum() == 0)
                && event.stockDividend() != null && event.stockDividend().signum() > 0;
    }
    private static Gap gap(Security security, String reason) {
        return new Gap(security.code(), security.name(), security.market(), reason);
    }
}
