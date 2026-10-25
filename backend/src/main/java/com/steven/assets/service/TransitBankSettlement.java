package com.steven.assets.service;

import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.BankDeposit;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 本機快照內的到期入帳純規則；由呼叫端持有快照鎖與交易，不執行銀行 I/O。 */
@Slf4j
final class TransitBankSettlement {
    private TransitBankSettlement() {}

    static boolean settle(AssetSnapshot snapshot, LocalDate today) {
        Map<Batch, List<BankDeposit>> batches = new LinkedHashMap<>();
        snapshot.getDeposits().stream()
                .filter(row -> TransitProcessingDatePolicy.isDue(row.getCurrency(), row.getProcessingDate(), today))
                .sorted(Comparator.comparing(BankDeposit::getProcessingDate))
                .forEach(row -> batches.computeIfAbsent(new Batch(bankId(row),
                        "TRANSIT_USD".equals(row.getCurrency()) ? "USD" : "TWD", row.getProcessingDate()),
                        ignored -> new ArrayList<>()).add(row));
        Set<BankDeposit> settled = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var entry : batches.entrySet()) {
            Batch batch = entry.getKey();
            List<BankDeposit> rows = entry.getValue();
            String reason = apply(snapshot, batch, rows);
            if (reason == null) settled.addAll(rows);
            else log.warn("在途入帳保留 snapshot={} bank={} currency={} processingDate={} reason={}",
                    snapshot.getId(), batch.bankId(), batch.currency(), batch.date(), reason);
        }
        // Entity 的 Lombok equals 會走回 snapshot；使用物件 identity 避免遞迴及誤刪同值列。
        return snapshot.getDeposits().removeIf(settled::contains);
    }

    /** 全批驗證完成後才修改目標；任一失敗不會消耗任何在途列。 */
    private static String apply(AssetSnapshot snapshot, Batch batch, List<BankDeposit> rows) {
        if (batch.bankId() == null) return "MISSING_BANK";
        String type = "USD".equals(batch.currency()) ? "美元活存" : "活存";
        List<BankDeposit> targets = snapshot.getDeposits().stream()
                .filter(row -> Objects.equals(batch.bankId(), bankId(row))
                        && batch.currency().equals(row.getCurrency()) && type.equals(row.getDepositType()))
                .toList();
        if (targets.isEmpty()) return "MISSING_TARGET";
        if (targets.size() != 1) return "DUPLICATE_TARGET";
        BankDeposit target = targets.getFirst();
        if (isSourceManagedCash(target)) return "SOURCE_MANAGED_TARGET";
        if (target.getAmount() == null || rows.stream().anyMatch(row -> row.getAmount() == null)) {
            return "MISSING_AMOUNT";
        }
        if (target.getAmount().signum() < 0) return "INVALID_TARGET_BALANCE";
        BigDecimal amount;
        BigDecimal original = null;
        if ("USD".equals(batch.currency())) {
            if (target.getOriginalAmount() == null
                    || rows.stream().anyMatch(row -> row.getOriginalAmount() == null)) return "MISSING_ORIGINAL_AMOUNT";
            if (target.getOriginalAmount().signum() < 0) return "INVALID_TARGET_BALANCE";
            if (rows.stream().anyMatch(row -> row.getAmount().signum() == 0
                    && row.getOriginalAmount().signum() != 0)) return "UNDETERMINED_USD_DIRECTION";
            BigDecimal rate = snapshot.getUsdExchangeRate();
            if (rate == null || rate.signum() <= 0) return "INVALID_EXCHANGE_RATE";
            BigDecimal net = rows.stream().map(row -> row.getOriginalAmount().abs()
                            .multiply(BigDecimal.valueOf(row.getAmount().signum())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            original = target.getOriginalAmount().add(net);
            if (original.signum() < 0) return "INSUFFICIENT_BALANCE";
            amount = original.multiply(rate).setScale(0, RoundingMode.HALF_UP);
        } else {
            amount = target.getAmount().add(rows.stream().map(BankDeposit::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        if (amount.signum() < 0) return "INSUFFICIENT_BALANCE";
        target.setAmount(amount);
        if (original != null) target.setOriginalAmount(original);
        return null;
    }

    static boolean isSourceManagedCash(BankDeposit row) {
        return row != null && (("TWD".equals(row.getCurrency()) && "活存".equals(row.getDepositType()))
                || ("USD".equals(row.getCurrency()) && "美元活存".equals(row.getDepositType())))
                && row.getSource() != null && !"MANUAL".equals(row.getSource());
    }

    private static Long bankId(BankDeposit row) {
        return row.getBank() == null ? null : row.getBank().getId();
    }

    private record Batch(Long bankId, String currency, LocalDate date) {}
}
