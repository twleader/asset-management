package com.steven.assets.integration.fubon;

import com.steven.assets.model.AssetTransaction;
import com.steven.assets.model.Bank;
import com.steven.assets.model.BankDeposit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FubonPayableLedgerReconcilerTest {
    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);
    private final FubonPayableLedgerReconciler reconciler = new FubonPayableLedgerReconciler();

    @Test
    void completeLedgerUsesFeesOnceAndProducesSortedCodes() {
        var evidence = reconciler.verify(1L, DATE, new BigDecimal("-56586"), List.of(
                buy(208L, "009826", "10.08", "10080", "20"),
                buy(207L, "00881", "46.42", "46486", "66"))).orElseThrow();
        assertThat(evidence.costByCode().get("009826")).isEqualByComparingTo("10100");
        assertThat(evidence.costByCode().get("00881")).isEqualByComparingTo("46486");
        assertThat(evidence.note()).isEqualTo("富邦證券交割款：00881、009826");
    }

    @Test
    void incompleteOrInvalidIdentityOrCostNeverProvesThePayable() {
        AssetTransaction row = buy(208L, "009826", "10.08", "10080", "20");
        assertThat(reconciler.verify(1L, DATE, new BigDecimal("-56586"), List.of(row))).isEmpty();
        row.setFee(new BigDecimal("-1"));
        assertThat(reconciler.verify(1L, DATE, new BigDecimal("-10100"), List.of(row))).isEmpty();
        row.setFee(new BigDecimal("20"));
        row.setOwnerUserId(2L);
        assertThat(reconciler.verify(1L, DATE, new BigDecimal("-10100"), List.of(row))).isEmpty();
        row.setOwnerUserId(1L);
        row.setChannel("其他券商");
        assertThat(reconciler.verify(1L, DATE, new BigDecimal("-10100"), List.of(row))).isEmpty();
        row.setChannel("富邦證券");
        row.setAssetCode(null);
        assertThat(reconciler.verify(1L, DATE, new BigDecimal("-10100"), List.of(row))).isEmpty();
    }

    @Test
    void matchesOnlyUniqueExactSameBankDateCodeAndManualAmount() {
        var evidence = reconciler.verify(1L, DATE, new BigDecimal("-10100"),
                List.of(buy(208L, "009826", "10.08", "10080", "20"))).orElseThrow();
        BankDeposit match = pending(1L, " 009826 ", "-10100", "MANUAL");
        BankDeposit wrongBank = pending(2L, "009826", "-10100", "MANUAL");
        BankDeposit wrongDate = pending(1L, "009826", "-10100", "MANUAL");
        wrongDate.setProcessingDate(DATE.plusDays(3));
        BankDeposit substring = pending(1L, "009826及其他", "-10100", "MANUAL");
        var result = reconciler.matchingManualPayables(List.of(match, wrongBank, wrongDate, substring),
                1L, DATE.plusDays(2), evidence);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst()).isSameAs(match);
        assertThat(reconciler.matchingManualPayables(List.of(match,
                        pending(1L, "009826", "-10100", "MANUAL")), 1L, DATE.plusDays(2), evidence)).isEmpty();
        assertThat(reconciler.matchingManualPayables(List.of(pending(1L, "009826", "-10100", "FUBON_SYNC")),
                1L, DATE.plusDays(2), evidence)).isEmpty();
        assertThat(reconciler.matchingManualPayables(List.of(pending(1L, "009826", "-10080", "MANUAL")),
                1L, DATE.plusDays(2), evidence)).isEmpty();
    }

    private static AssetTransaction buy(Long id, String code, String price, String amount, String fee) {
        return AssetTransaction.builder().id(id).ownerUserId(1L).transactionType("買").assetType("股票")
                .assetName(code).assetCode(code).market("台股").currency("TWD").channel("富邦證券")
                .tradeDate(DATE).shares(new BigDecimal("1000")).price(new BigDecimal(price))
                .amount(new BigDecimal(amount)).fee(new BigDecimal(fee)).transactionTax(BigDecimal.ZERO)
                .source("MANUAL").build();
    }

    private static BankDeposit pending(Long bankId, String note, String amount, String source) {
        return BankDeposit.builder().bank(Bank.builder().id(bankId).build()).depositType("買股待付款")
                .currency("TRANSIT_TWD").processingDate(DATE.plusDays(2)).notes(note)
                .amount(new BigDecimal(amount)).source(source).build();
    }
}
