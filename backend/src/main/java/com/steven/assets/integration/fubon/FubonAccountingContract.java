package com.steven.assets.integration.fubon;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

/** Pure validation of normalized accounting observations. It never infers missing source evidence. */
final class FubonAccountingContract {
    static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private FubonAccountingContract() {}

    static void validateBank(FubonDtos.BankBalance body, Clock clock) {
        require(body != null, "INVALID_BANK_BALANCE");
        header(body.queryDate(), body.observedAt(), body.accountFingerprint(), clock);
        require("TWD".equals(body.currency()), "INVALID_CURRENCY");
        decimal(body.balance(), false, false);
        decimal(body.availableBalance(), false, false);
        money(body.balance().value());
    }

    static void validateSettlement(FubonDtos.SettlementBatch body, Clock clock) {
        require(body != null, "INVALID_SETTLEMENT");
        header(body.queryDate(), body.observedAt(), body.accountFingerprint(), clock);
        require("UNVERIFIED".equals(body.coverageStatus())
                && "MISSING_SETTLEMENT_RANGE_CONTRACT".equals(body.reason()), "SETTLEMENT_SCOPE_UNVERIFIED");
        require(body.details() != null, "INVALID_SETTLEMENT");
        Set<String> sourcePairs = new HashSet<>();
        Set<LocalDate> settlementDays = new HashSet<>();
        for (FubonDtos.SettlementDay row : body.details()) {
            require(row != null && row.sourceQueryDate() != null
                    && !row.sourceQueryDate().isAfter(body.queryDate()), "INVALID_SETTLEMENT_DATE");
            require(sourcePairs.add(row.sourceQueryDate() + ":" + row.settlementDate()), "AMBIGUOUS_SETTLEMENT");
            CanonicalFubonDecimal[] amounts = {row.buyValue(), row.buyFee(), row.buySettlement(), row.buyTax(),
                    row.sellValue(), row.sellFee(), row.sellSettlement(), row.sellTax(), row.totalBsValue(),
                    row.totalFee(), row.totalTax(), row.totalSettlementAmount()};
            if ("NO_DATA_OBSERVED".equals(row.status())) {
                require(row.settlementDate() == null && row.currency() == null, "INVALID_SETTLEMENT");
                for (CanonicalFubonDecimal amount : amounts) require(amount == null, "INVALID_SETTLEMENT");
                continue;
            }
            require("AVAILABLE".equals(row.status()) && "TWD".equals(row.currency()), "INVALID_SETTLEMENT");
            require(row.settlementDate() != null && !row.settlementDate().isBefore(row.sourceQueryDate()),
                    "INVALID_SETTLEMENT_DATE");
            require(settlementDays.add(row.settlementDate()), "AMBIGUOUS_SETTLEMENT");
            for (CanonicalFubonDecimal amount : amounts) decimal(amount, true, true);
            BigDecimal buy = row.buySettlement().value();
            BigDecimal sell = row.sellSettlement().value();
            require(buy.signum() <= 0 && sell.signum() >= 0
                    && buy.add(sell).compareTo(row.totalSettlementAmount().value()) == 0, "INVALID_SETTLEMENT_AMOUNT");
            require(!row.settlementDate().equals(body.queryDate()) || (buy.signum() == 0 && sell.signum() == 0),
                    "AMBIGUOUS_SETTLEMENT");
        }
    }

    static void validateRealized(FubonDtos.RealizedGainBatch body, Clock clock) {
        require(body != null, "INVALID_REALIZED_GAIN");
        header(body.queryDate(), body.observedAt(), body.accountFingerprint(), clock);
        require(body.rows() != null, "INVALID_REALIZED_GAIN");
        Set<String> candidates = new HashSet<>();
        for (FubonDtos.RealizedGainRow row : body.rows()) {
            require(row != null && row.stockNo() != null && row.stockNo().matches("[0-9A-Z]{2,10}")
                    && !"0000".equals(row.stockNo()) && "Sell".equals(row.buySell())
                    && "Stock".equals(row.orderType()), "INVALID_REALIZED_GAIN");
            require(row.sourceDate() != null && !row.sourceDate().isAfter(body.queryDate()), "INVALID_SOURCE_DATE");
            require(row.filledQty() >= 1 && row.filledQty() <= ExactSharesDeserializer.MAX_SHARES,
                    "INVALID_REALIZED_QUANTITY");
            decimal(row.filledPrice(), false, false);
            require(row.filledPrice().value().signum() > 0, "INVALID_REALIZED_PRICE");
            decimal(row.realizedProfit(), false, true);
            decimal(row.realizedLoss(), false, true);
            require(row.realizedProfit().value().signum() == 0 || row.realizedLoss().value().signum() == 0,
                    "ACCOUNTING_SEMANTICS_UNVERIFIED");
            BigDecimal price = row.filledPrice().value().setScale(4, RoundingMode.HALF_UP);
            require(price.signum() > 0 && price.precision() <= 15, "INVALID_REALIZED_PRICE");
            String candidate = row.stockNo() + ":" + row.sourceDate() + ":" + row.filledQty() + ":" + price;
            require(candidates.add(candidate), "AMBIGUOUS_IDENTITY");
        }
    }

    static void fresh(LocalDate queryDate, Instant observedAt, Clock clock) {
        Instant now = clock.instant();
        require(queryDate != null && observedAt != null && queryDate.equals(now.atZone(TAIPEI).toLocalDate())
                && queryDate.equals(observedAt.atZone(TAIPEI).toLocalDate()) && !observedAt.isAfter(now)
                && Duration.between(observedAt, now).compareTo(Duration.ofSeconds(60)) <= 0, "STALE_QUERY");
    }

    static BigDecimal money(BigDecimal value) {
        require(value != null, "INVALID_MONEY");
        BigDecimal rounded = value.setScale(2, RoundingMode.HALF_UP);
        require(rounded.precision() <= 20, "MONEY_OVERFLOW");
        return rounded;
    }

    private static void header(LocalDate queryDate, Instant observedAt, String fingerprint, Clock clock) {
        require(queryDate != null && observedAt != null && fingerprint != null
                && fingerprint.matches("[0-9a-f]{24}"), "INVALID_ACCOUNTING_ENVELOPE");
        require(queryDate.equals(observedAt.atZone(TAIPEI).toLocalDate())
                && !observedAt.isAfter(clock.instant()), "INVALID_ACCOUNTING_OBSERVATION");
    }

    private static void decimal(CanonicalFubonDecimal decimal, boolean signed, boolean integer) {
        require(decimal != null, "INVALID_ACCOUNTING_AMOUNT");
        BigDecimal value = decimal.value();
        require((signed || value.signum() >= 0) && value.precision() <= 20
                && value.scale() >= 0 && value.scale() <= 10 && (!integer || value.scale() == 0),
                "INVALID_ACCOUNTING_AMOUNT");
    }

    private static void require(boolean valid, String reason) {
        if (!valid) throw new Rejected(reason);
    }

    static final class Rejected extends RuntimeException {
        Rejected(String reason) { super(reason); }
    }
}
