package com.steven.assets.integration.fubon;

import com.steven.assets.model.AssetTransaction;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/** Pure evidence check; ledger facts can label, but never replace, official settlement money. */
final class FubonSettlementLedgerNoteReconciler {
    Optional<String> verify(Long ownerId, FubonDtos.SettlementDay day, List<AssetTransaction> ledger) {
        if (ownerId == null || day == null || ledger == null || ledger.isEmpty()) return Optional.empty();
        Totals buy = new Totals();
        Totals sell = new Totals();
        for (AssetTransaction row : ledger) {
            if (row == null || !Objects.equals(ownerId, row.getOwnerUserId())
                    || !day.sourceQueryDate().equals(row.getTradeDate())
                    || !"股票".equals(row.getAssetType()) || !"台股".equals(row.getMarket())
                    || !"TWD".equals(row.getCurrency()) || !"富邦證券".equals(row.getChannel())
                    || !("MANUAL".equals(row.getSource()) || "FUBON_SYNC".equals(row.getSource()))
                    || !FubonTradeContract.validCode(row.getAssetCode())
                    || row.getShares() == null || row.getShares().signum() <= 0
                    || row.getPrice() == null || row.getPrice().signum() <= 0
                    || !nonnegativeInteger(row.getAmount()) || !nonnegativeInteger(row.getFee())
                    || !nonnegativeInteger(row.getTransactionTax())
                    || row.getShares().multiply(row.getPrice()).compareTo(row.getAmount()) != 0) {
                return Optional.empty();
            }
            Totals target;
            if ("買".equals(row.getTransactionType())) target = buy;
            else if ("賣".equals(row.getTransactionType())) target = sell;
            else return Optional.empty();
            target.amount = target.amount.add(row.getAmount());
            target.fee = target.fee.add(row.getFee());
            target.tax = target.tax.add(row.getTransactionTax());
            target.codes.add(row.getAssetCode());
        }
        if (!buy.matches(day.buyValue(), day.buyFee(), day.buyTax())
                || !sell.matches(day.sellValue(), day.sellFee(), day.sellTax())
                || !equal(buy.amount.add(buy.fee).add(buy.tax).negate(), day.buySettlement())
                || !equal(sell.amount.subtract(sell.fee).subtract(sell.tax), day.sellSettlement())
                || !equal(buy.amount.add(sell.amount), day.totalBsValue())
                || !equal(buy.fee.add(sell.fee), day.totalFee())
                || !equal(buy.tax.add(sell.tax), day.totalTax())) return Optional.empty();
        String trades = (buy.codes.isEmpty() ? "" : "買 " + String.join("、", buy.codes))
                + (buy.codes.isEmpty() || sell.codes.isEmpty() ? "" : "；")
                + (sell.codes.isEmpty() ? "" : "賣 " + String.join("、", sell.codes));
        BigDecimal net = day.totalSettlementAmount().value();
        String suffix = "；淨" + (net.signum() < 0 ? "付 " : net.signum() > 0 ? "收 " : "額 ")
                + net.abs().stripTrailingZeros().toPlainString();
        String note = "富邦證券已記錄成交：" + trades + suffix;
        if (note.length() > 200) {
            note = "富邦證券已記錄成交：買 " + buy.codes.size() + " 個標的；賣 " + sell.codes.size()
                    + " 個標的（明細過長）" + suffix;
        }
        return Optional.of(note);
    }

    private static boolean nonnegativeInteger(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.stripTrailingZeros().scale() <= 0;
    }

    private static boolean equal(BigDecimal value, CanonicalFubonDecimal source) {
        return source != null && value.compareTo(source.value()) == 0;
    }

    private static final class Totals {
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal fee = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        TreeSet<String> codes = new TreeSet<>();

        boolean matches(CanonicalFubonDecimal sourceAmount, CanonicalFubonDecimal sourceFee,
                CanonicalFubonDecimal sourceTax) {
            return equal(amount, sourceAmount) && equal(fee, sourceFee)
                    && (sourceTax == null || equal(tax, sourceTax));
        }
    }
}
