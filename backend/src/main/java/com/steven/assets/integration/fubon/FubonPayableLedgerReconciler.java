package com.steven.assets.integration.fubon;

import com.steven.assets.model.AssetTransaction;
import com.steven.assets.model.BankDeposit;
import com.steven.assets.service.TaiwanLedgerHoldingCostCalculator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Pure proof of a complete buy ledger against an already-validated SDK payable. */
final class FubonPayableLedgerReconciler {
    record Evidence(Map<String, BigDecimal> costByCode, String note) {
        Evidence {
            costByCode = Collections.unmodifiableMap(new LinkedHashMap<>(costByCode));
        }
    }

    Optional<Evidence> verify(Long ownerId, LocalDate sourceDate, BigDecimal payable,
            List<AssetTransaction> ledger) {
        if (ownerId == null || sourceDate == null || payable == null || payable.signum() >= 0
                || ledger == null || ledger.isEmpty()) return Optional.empty();
        Map<String, List<TaiwanLedgerHoldingCostCalculator.Trade>> byCode = new TreeMap<>();
        for (AssetTransaction row : ledger) {
            if (row == null || !Objects.equals(ownerId, row.getOwnerUserId())
                    || !sourceDate.equals(row.getTradeDate()) || !"股票".equals(row.getAssetType())
                    || !"台股".equals(row.getMarket()) || !"TWD".equals(row.getCurrency())
                    || !"富邦證券".equals(row.getChannel()) || !"買".equals(row.getTransactionType())
                    || !("MANUAL".equals(row.getSource()) || "FUBON_SYNC".equals(row.getSource()))
                    || !FubonTradeContract.validCode(row.getAssetCode())) return Optional.empty();
            byCode.computeIfAbsent(row.getAssetCode(), ignored -> new ArrayList<>()).add(
                    new TaiwanLedgerHoldingCostCalculator.Trade(row.getId(), row.getTradeDate(),
                            row.getTransactionType(), row.getShares(), row.getPrice(), row.getAmount(),
                            row.getFee(), row.getTransactionTax()));
        }
        Map<String, BigDecimal> costs = new TreeMap<>();
        BigDecimal total = BigDecimal.ZERO;
        var calculator = new TaiwanLedgerHoldingCostCalculator();
        for (var entry : byCode.entrySet()) {
            var result = calculator.calculate(entry.getValue());
            if (result.isEmpty()) return Optional.empty();
            BigDecimal cost = result.orElseThrow().costTwd();
            costs.put(entry.getKey(), cost);
            total = total.add(cost);
        }
        if (total.compareTo(payable.negate()) != 0) return Optional.empty();
        String note = "富邦證券交割款：" + String.join("、", costs.keySet());
        if (note.length() > 200) {
            note = note.substring(0, 180) + "…（共" + costs.size() + "個標的）";
        }
        return Optional.of(new Evidence(costs, note));
    }

    /** A duplicate or mismatched same-code candidate is never consumed. */
    List<BankDeposit> matchingManualPayables(List<BankDeposit> deposits, Long bankId,
            LocalDate processingDate, Evidence evidence) {
        if (bankId == null || processingDate == null || evidence == null) return List.of();
        List<BankDeposit> matched = new ArrayList<>();
        for (var entry : evidence.costByCode().entrySet()) {
            List<BankDeposit> candidates = deposits.stream()
                    .filter(row -> row.getBank() != null && Objects.equals(bankId, row.getBank().getId())
                            && "買股待付款".equals(row.getDepositType())
                            && processingDate.equals(row.getProcessingDate())
                            && row.getNotes() != null && entry.getKey().equals(row.getNotes().trim()))
                    .toList();
            if (candidates.size() != 1) continue;
            BankDeposit candidate = candidates.getFirst();
            if (!"TRANSIT_TWD".equals(candidate.getCurrency())
                    || !(candidate.getSource() == null || "MANUAL".equals(candidate.getSource()))
                    || candidate.getAmount() == null || candidate.getAmount().signum() >= 0
                    || candidate.getAmount().negate().compareTo(entry.getValue()) != 0) continue;
            matched.add(candidate);
        }
        return List.copyOf(matched);
    }
}
