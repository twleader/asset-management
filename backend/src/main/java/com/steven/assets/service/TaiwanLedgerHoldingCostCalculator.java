package com.steven.assets.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Strict, side-effect-free historical Taiwan/TWD ledger calculator.
 *
 * <p>This is deliberately an evidence validator, not a broker projection policy.  In particular,
 * a mathematically valid sell here never authorises {@link BrokerFilledTradeCostProjector} to infer
 * a broker sell basis.</p>
 */
public final class TaiwanLedgerHoldingCostCalculator {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal MAX_TWD_COST = new BigDecimal("999999999999999999.99");
    private static final Comparator<Trade> ORDER = Comparator
            .comparing(Trade::tradeDate)
            .thenComparing(Trade::id);

    /** Immutable input deliberately contains only the fields needed for one already-scoped identity. */
    public record Trade(Long id, LocalDate tradeDate, String transactionType, BigDecimal shares,
                        BigDecimal price, BigDecimal amount, BigDecimal fee, BigDecimal transactionTax) {}

    /** A qualified result retains the unrounded moving-average basis for audit-friendly testing. */
    public record Result(BigDecimal quantity, BigDecimal unroundedCost, BigDecimal costTwd) {}

    /**
     * Validates and calculates one complete ledger identity in deterministic {@code (tradeDate,id)} order.
     * Any unknown or unprovable row returns empty rather than guessing a cost.
     */
    public Optional<Result> calculate(List<Trade> rows) {
        return calculate(rows, null);
    }

    /** Same calculation plus an optional current-holding quantity evidence check. */
    public Optional<Result> calculate(List<Trade> rows, BigDecimal expectedHoldingShares) {
        if (expectedHoldingShares != null && expectedHoldingShares.signum() <= 0) return Optional.empty();
        if (rows == null || rows.isEmpty() || rows.stream().anyMatch(row -> row == null || !valid(row))) {
            return Optional.empty();
        }

        List<Trade> ordered = rows.stream().sorted(ORDER).toList();
        BigDecimal quantity = ZERO;
        BigDecimal cost = ZERO;
        for (Trade trade : ordered) {
            BigDecimal fee = zeroIfNull(trade.fee());
            BigDecimal tax = zeroIfNull(trade.transactionTax());
            if ("買".equals(trade.transactionType())) {
                BigDecimal computed = trade.shares().multiply(trade.price()).add(fee).add(tax);
                BigDecimal buyCost = trade.amount().max(computed);
                quantity = quantity.add(trade.shares());
                cost = cost.add(buyCost);
                if (cost.compareTo(MAX_TWD_COST) > 0) return Optional.empty();
            } else {
                if (trade.shares().compareTo(quantity) > 0 || quantity.signum() <= 0) return Optional.empty();
                // Do not round this subtraction: future moving-average sales must retain the exact basis.
                BigDecimal soldCost = cost.multiply(trade.shares()).divide(quantity, 30, RoundingMode.HALF_UP);
                quantity = quantity.subtract(trade.shares());
                cost = cost.subtract(soldCost);
                if (cost.signum() < 0) return Optional.empty();
            }
        }
        if (quantity.signum() <= 0 || cost.signum() < 0 || cost.compareTo(MAX_TWD_COST) > 0
                || (expectedHoldingShares != null && quantity.compareTo(expectedHoldingShares) != 0)) return Optional.empty();
        BigDecimal rounded = cost.setScale(2, RoundingMode.HALF_UP);
        if (rounded.compareTo(MAX_TWD_COST) > 0) return Optional.empty();
        return Optional.of(new Result(quantity, cost, rounded));
    }

    private static boolean valid(Trade trade) {
        return trade.id() != null && trade.id() > 0 && trade.tradeDate() != null
                && ("買".equals(trade.transactionType()) || "賣".equals(trade.transactionType()))
                && positive(trade.shares()) && positive(trade.price()) && positive(trade.amount())
                && nonNegative(trade.fee()) && nonNegative(trade.transactionTax())
                && twdValue(trade.amount()) && optionalTwdValue(trade.fee()) && optionalTwdValue(trade.transactionTax());
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static boolean nonNegative(BigDecimal value) {
        return value == null || value.signum() >= 0;
    }

    private static boolean twdValue(BigDecimal value) {
        return value.precision() <= 20 && value.scale() <= 2 && value.compareTo(MAX_TWD_COST) <= 0;
    }

    private static boolean optionalTwdValue(BigDecimal value) {
        return value == null || twdValue(value);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? ZERO : value;
    }
}
