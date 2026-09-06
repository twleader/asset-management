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
        require(Boolean.TRUE.equals(body.accountBindingExplicit()), "ACCOUNT_BINDING_UNVERIFIED");
        require("SDK_RANGE_3D_RETURNED_ROWS".equals(body.coverageStatus()) && body.reason() == null,
                "INVALID_SETTLEMENT_COVERAGE");
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
            require(row.settlementDate().isAfter(body.queryDate())
                    || (buy.signum() == 0 && sell.signum() == 0), "AMBIGUOUS_SETTLEMENT");
        }
    }

    static void validateRealized(FubonDtos.RealizedGainBatch body, Clock clock) {
        require(body != null, "INVALID_REALIZED_GAIN");
        header(body.queryDate(), body.observedAt(), body.accountFingerprint(), clock);
        require(Boolean.TRUE.equals(body.accountBindingExplicit()), "ACCOUNT_BINDING_UNVERIFIED");
        require(body.rows() != null, "INVALID_REALIZED_GAIN");
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
            salePrice(row.filledPrice().value());
        }
    }

    static BigDecimal salePrice(BigDecimal sourcePrice) {
        require(sourcePrice != null && sourcePrice.signum() > 0, "INVALID_REALIZED_PRICE");
        try {
            BigDecimal value = sourcePrice.setScale(4, RoundingMode.UNNECESSARY);
            require(value.precision() <= 15, "INVALID_REALIZED_PRICE");
            return value;
        } catch (ArithmeticException exception) {
            throw new Rejected("INVALID_REALIZED_PRICE");
        }
    }

    static PreparedRealized prepareRealized(FubonDtos.RealizedGainRow row) {
        BigDecimal salePrice = salePrice(row.filledPrice().value());
        BigDecimal proceeds = money(row.filledPrice().value().multiply(BigDecimal.valueOf(row.filledQty())));
        BigDecimal reportedNetPnl = row.realizedProfit().value().subtract(row.realizedLoss().value());
        BigDecimal investmentCost = money(proceeds.subtract(reportedNetPnl));
        require(investmentCost.signum() >= 0, "INVALID_REALIZED_COST");
        return new PreparedRealized(row, salePrice, proceeds, investmentCost);
    }

    static SettlementProjection settlementProjection(FubonDtos.SettlementBatch body) {
        validateSettlementShapeOnly(body);
        BigDecimal payable = BigDecimal.ZERO;
        BigDecimal receivable = BigDecimal.ZERO;
        int futureRows = 0;
        for (FubonDtos.SettlementDay row : body.details()) {
            if (!"AVAILABLE".equals(row.status()) || !row.settlementDate().isAfter(body.queryDate())) continue;
            futureRows++;
            payable = payable.add(row.buySettlement().value());
            receivable = receivable.add(row.sellSettlement().value());
        }
        return new SettlementProjection(money(payable), money(receivable), futureRows);
    }

    private static void validateSettlementShapeOnly(FubonDtos.SettlementBatch body) {
        require(body != null && body.details() != null, "INVALID_SETTLEMENT");
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

    record SettlementProjection(BigDecimal payableAmount, BigDecimal receivableAmount, int futureRowCount) {}

    record PreparedRealized(FubonDtos.RealizedGainRow source, BigDecimal salePrice,
                            BigDecimal proceeds, BigDecimal investmentCost) {}

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
