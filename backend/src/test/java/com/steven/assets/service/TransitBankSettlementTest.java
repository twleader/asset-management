package com.steven.assets.service;

import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.Bank;
import com.steven.assets.model.BankDeposit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class TransitBankSettlementTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);

    @Test
    void stockPayableUsesSecuritiesBalanceAndSettlesObservedCaseOnlyOnce() {
        BankDeposit securities = securities(1, "TWD", "63454", null);
        BankDeposit cash = cash(1, "TWD", "1000", null);
        var snapshot = snapshot(securities, cash, stockTransit(1, "TWD", "-48168", null, "買股待付款"));
        assertThat(TransitBankSettlement.settle(snapshot, TODAY)).isTrue();
        assertThat(securities.getAmount()).isEqualByComparingTo("15286");
        assertThat(cash.getAmount()).isEqualByComparingTo("1000");
        assertThat(snapshot.getDeposits()).hasSize(2);
        assertThat(TransitBankSettlement.settle(snapshot, TODAY)).isFalse();
        assertThat(securities.getAmount()).isEqualByComparingTo("15286");
    }

    @Test
    void stockReceivablesAndPayablesNetTogetherWhileOtherFundsUseCash() {
        BankDeposit securities = securities(1, "TWD", "5", null);
        BankDeposit cash = cash(1, "TWD", "100", null);
        var snapshot = snapshot(cash, securities,
                stockTransit(1, "TWD", "-20", null, "買股待付款"),
                stockTransit(1, "TWD", "30", null, "賣股待收款"),
                transit(1, "TWD", "-7", null, TODAY));
        assertThat(TransitBankSettlement.settle(snapshot, TODAY)).isTrue();
        assertThat(securities.getAmount()).isEqualByComparingTo("15");
        assertThat(cash.getAmount()).isEqualByComparingTo("93");
        assertThat(snapshot.getDeposits()).hasSize(2);
    }

    @Test
    void differentTargetTypesCannotOffsetAnInsufficientBatchInEitherDirection() {
        for (boolean stockInsufficient : new boolean[]{true, false}) {
            BankDeposit securities = securities(1, "TWD", "5", null);
            BankDeposit cash = cash(1, "TWD", "5", null);
            BankDeposit stock = stockTransit(1, "TWD", stockInsufficient ? "-10" : "20", null,
                    stockInsufficient ? "買股待付款" : "賣股待收款");
            BankDeposit other = transit(1, "TWD", stockInsufficient ? "20" : "-10", null, TODAY);
            var snapshot = snapshot(securities, cash, stock, other);
            assertThat(TransitBankSettlement.settle(snapshot, TODAY)).isTrue();
            assertThat(securities.getAmount()).isEqualByComparingTo(stockInsufficient ? "5" : "25");
            assertThat(cash.getAmount()).isEqualByComparingTo(stockInsufficient ? "25" : "5");
            assertThat(snapshot.getDeposits()).hasSize(3);
            assertThat(snapshot.getDeposits().getLast()).isSameAs(stockInsufficient ? stock : other);
        }
    }

    @Test
    void usdStockSettlementUsesOriginalCurrencyAndNeverTouchesCashOrAnotherBank() {
        BankDeposit securities = securities(1, "USD", "3200", "100");
        BankDeposit otherBank = securities(2, "USD", "640", "20");
        BankDeposit cash = cash(1, "USD", "320", "10");
        var snapshot = snapshot(securities, cash, otherBank,
                stockTransit(1, "USD", "64", "2", "賣股待收款"),
                stockTransit(1, "USD", "-1", "5", "買股待付款"),
                transit(1, "USD", "-32", "1", TODAY));
        assertThat(TransitBankSettlement.settle(snapshot, TODAY)).isTrue();
        assertThat(securities.getOriginalAmount()).isEqualByComparingTo("97");
        assertThat(securities.getAmount()).isEqualByComparingTo("3104");
        assertThat(cash.getOriginalAmount()).isEqualByComparingTo("9");
        assertThat(cash.getAmount()).isEqualByComparingTo("288");
        assertThat(otherBank.getOriginalAmount()).isEqualByComparingTo("20");
        assertThat(otherBank.getAmount()).isEqualByComparingTo("640");
    }

    @Test
    void stockFundsDoNotFallBackToCashFixedAnotherBankOrAnotherCurrency() {
        BankDeposit cash = cash(1, "TWD", "100", null);
        BankDeposit fixed = cash(1, "TWD", "200", null);
        fixed.setDepositType("定存");
        BankDeposit otherBank = securities(2, "TWD", "300", null);
        BankDeposit otherCurrency = securities(1, "USD", "3200", "100");
        assertRetained(snapshot(cash, fixed, otherBank, otherCurrency,
                stockTransit(1, "TWD", "10", null, "賣股待收款")), cash, "100");
        assertThat(fixed.getAmount()).isEqualByComparingTo("200");
        assertThat(otherBank.getAmount()).isEqualByComparingTo("300");
        assertThat(otherCurrency.getAmount()).isEqualByComparingTo("3200");
    }

    @Test
    void duplicateOrSourceManagedSecuritiesTargetsRetainStocksForBothCurrencies() {
        for (String currency : new String[]{"TWD", "USD"}) {
            BankDeposit securities = securities(1, currency, "100", "10");
            assertRetained(snapshot(securities, securities(1, currency, "100", "10"),
                    stockTransit(1, currency, "10", "1", "賣股待收款")), securities, "100");
            securities.setSource("FUBON_SYNC");
            assertRetained(snapshot(securities, stockTransit(1, currency, "-10", "1", "買股待付款")),
                    securities, "100");
            assertThat(securities.getOriginalAmount()).isEqualByComparingTo("10");
        }
    }

    @Test
    void twdNetsSameDayBeforeCheckingBalanceAndPreservesOtherFields() {
        BankDeposit target = cash(1, "TWD", "100", null);
        target.setAnnualInterestRate(new BigDecimal("1.25"));
        target.setNotes("retain");
        target.setSource(null); // legacy manual
        var snapshot = snapshot(target, transit(1, "TWD", "-150", null, TODAY),
                transit(1, "TWD", "70", null, TODAY));
        assertThat(TransitBankSettlement.settle(snapshot, TODAY)).isTrue();
        assertThat(snapshot.getDeposits()).singleElement().isSameAs(target);
        assertThat(target.getAmount()).isEqualByComparingTo("20");
        assertThat(target.getAnnualInterestRate()).isEqualByComparingTo("1.25");
        assertThat(target.getNotes()).isEqualTo("retain");
        assertThat(target.getSource()).isNull();
        assertThat(TransitBankSettlement.settle(snapshot, TODAY)).isFalse();
    }

    @Test
    void usdUsesOriginalAbsoluteValueAndPersistedTwdSignThenRoundsAtSnapshotRate() {
        BankDeposit target = cash(1, "USD", "3200", "100");
        var snapshot = snapshot(target, transit(1, "USD", "999", "-5.25", TODAY),
                transit(1, "USD", "-1", "10", TODAY));
        snapshot.setUsdExchangeRate(new BigDecimal("32.123"));
        assertThat(TransitBankSettlement.settle(snapshot, TODAY)).isTrue();
        assertThat(target.getOriginalAmount()).isEqualByComparingTo("95.25");
        assertThat(target.getAmount()).isEqualByComparingTo("3060");
    }

    @Test
    void processesDatesAscendingIndependentlyEvenIfCollectionIsReversed() {
        BankDeposit target = cash(1, "TWD", "5", null);
        BankDeposit failed = transit(1, "TWD", "-10", null, TODAY.minusDays(2));
        var snapshot = snapshot(target, transit(1, "TWD", "-12", null, TODAY),
                transit(1, "TWD", "20", null, TODAY.minusDays(1)), failed);
        assertThat(TransitBankSettlement.settle(snapshot, TODAY)).isTrue();
        assertThat(target.getAmount()).isEqualByComparingTo("13");
        assertThat(snapshot.getDeposits()).hasSize(2);
        assertThat(snapshot.getDeposits().get(1)).isSameAs(failed);
    }

    @Test
    void banksAndCurrenciesAreIndependent() {
        BankDeposit first = cash(1, "TWD", "100", null);
        BankDeposit second = cash(2, "TWD", "100", null);
        BankDeposit usd = cash(1, "USD", "320", "10");
        var snapshot = snapshot(first, second, usd,
                transit(1, "TWD", "-20", null, TODAY), transit(2, "TWD", "30", null, TODAY),
                transit(1, "USD", "64", "2", TODAY));
        assertThat(TransitBankSettlement.settle(snapshot, TODAY)).isTrue();
        assertThat(first.getAmount()).isEqualByComparingTo("80");
        assertThat(second.getAmount()).isEqualByComparingTo("130");
        assertThat(usd.getOriginalAmount()).isEqualByComparingTo("12");
        assertThat(usd.getAmount()).isEqualByComparingTo("384");
    }

    @Test
    void nullFutureAndOrdinaryDatesAreNeverConsumed() {
        BankDeposit target = cash(1, "TWD", "100", null);
        target.setProcessingDate(TODAY);
        var snapshot = snapshot(target, transit(1, "TWD", "-10", null, null),
                transit(1, "TWD", "-10", null, TODAY.plusDays(1)));
        assertThat(TransitBankSettlement.settle(snapshot, TODAY)).isFalse();
        assertThat(snapshot.getDeposits()).hasSize(3);
        assertThat(target.getAmount()).isEqualByComparingTo("100");
    }

    @Test
    void missingBankNeverMatchesAnotherMissingBank() {
        BankDeposit target = cash(1, "TWD", "100", null);
        BankDeposit due = transit(1, "TWD", "10", null, TODAY);
        target.setBank(null);
        due.setBank(null);
        assertRetained(snapshot(target, due), target, "100");
    }

    @Test
    void missingTargetDoesNotUseAnotherBankOrFixedOrSecuritiesDeposit() {
        BankDeposit wrongBank = cash(2, "TWD", "100", null);
        BankDeposit fixed = cash(1, "TWD", "200", null);
        fixed.setDepositType("定存");
        BankDeposit securities = cash(1, "TWD", "300", null);
        securities.setDepositType("證券戶");
        assertRetained(snapshot(wrongBank, fixed, securities, transit(1, "TWD", "10", null, TODAY)),
                wrongBank, "100");
    }

    @Test
    void duplicateTargetRetainsWholeBatchIncludingEqualLookingEntities() {
        BankDeposit target = cash(1, "TWD", "100", null);
        assertRetained(snapshot(target, cash(1, "TWD", "100", null),
                transit(1, "TWD", "10", null, TODAY), transit(1, "TWD", "-5", null, TODAY)), target, "100");
    }

    @Test
    void anyNonManualSourceCannotBeSettledForEitherCurrency() {
        for (String currency : new String[]{"TWD", "USD"}) {
            BankDeposit target = cash(1, currency, "100", "10");
            target.setSource("OTHER_SYNC");
            assertRetained(snapshot(target, transit(1, currency, "10", "1", TODAY)), target, "100");
            assertThat(target.getOriginalAmount()).isEqualByComparingTo("10");
        }
    }

    @Test
    void missingTwdAmountOnAnyRowRetainsEntireBatch() {
        BankDeposit target = cash(1, "TWD", "100", null);
        assertRetained(snapshot(target, transit(1, "TWD", "20", null, TODAY),
                transit(1, "TWD", null, null, TODAY)), target, "100");
        target.setAmount(null);
        assertThat(TransitBankSettlement.settle(snapshot(target, transit(1, "TWD", "20", null, TODAY)), TODAY))
                .isFalse();
        assertThat(target.getAmount()).isNull();
    }

    @Test
    void missingUsdOriginalOnTargetOrAnyTransitRetainsEntireBatch() {
        for (boolean missingTarget : new boolean[]{true, false}) {
            BankDeposit target = cash(1, "USD", "3200", missingTarget ? null : "100");
            assertRetained(snapshot(target, transit(1, "USD", "32", "1", TODAY),
                    transit(1, "USD", "32", missingTarget ? "1" : null, TODAY)), target, "3200");
            if (!missingTarget) assertThat(target.getOriginalAmount()).isEqualByComparingTo("100");
        }
    }

    @Test
    void missingZeroOrNegativeFxRetainsUsdBatch() {
        for (BigDecimal rate : Arrays.asList(null, BigDecimal.ZERO, BigDecimal.ONE.negate())) {
            BankDeposit target = cash(1, "USD", "3200", "100");
            var snapshot = snapshot(target, transit(1, "USD", "32", "1", TODAY));
            snapshot.setUsdExchangeRate(rate);
            assertRetained(snapshot, target, "3200");
        }
    }

    @Test
    void insufficientBalanceRetainsAllRowsWithoutAbsAndExactZeroIsAllowed() {
        for (String currency : new String[]{"TWD", "USD"}) {
            BankDeposit target = cash(1, currency, "10", "10");
            assertRetained(snapshot(target, transit(1, currency, "-20", "20", TODAY),
                    transit(1, currency, "1", "1", TODAY)), target, "10");
            assertThat(TransitBankSettlement.settle(snapshot(target,
                    transit(1, currency, "-10", "10", TODAY)), TODAY)).isTrue();
            assertThat(target.getAmount()).isZero();
        }
    }

    @Test
    void zeroTwdSignCannotConsumeNonzeroUsdOriginal() {
        BankDeposit target = cash(1, "USD", "3200", "100");
        assertRetained(snapshot(target, transit(1, "USD", "0", "0.0001", TODAY),
                transit(1, "USD", "32", "1", TODAY)), target, "3200");
        assertThat(target.getOriginalAmount()).isEqualByComparingTo("100");
    }

    @Test
    void abnormalNegativeTargetIsNotRepairedByReceivable() {
        BankDeposit twd = cash(1, "TWD", "-10", null);
        assertRetained(snapshot(twd, transit(1, "TWD", "20", null, TODAY)), twd, "-10");
        BankDeposit usd = cash(1, "USD", "10", "-1");
        assertRetained(snapshot(usd, transit(1, "USD", "64", "2", TODAY)), usd, "10");
    }

    private void assertRetained(AssetSnapshot snapshot, BankDeposit target, String amount) {
        int size = snapshot.getDeposits().size();
        assertThat(TransitBankSettlement.settle(snapshot, TODAY)).isFalse();
        assertThat(snapshot.getDeposits()).hasSize(size);
        assertThat(target.getAmount()).isEqualByComparingTo(amount);
    }

    private AssetSnapshot snapshot(BankDeposit... rows) {
        var snapshot = AssetSnapshot.builder().snapshotDate(TODAY).usdExchangeRate(new BigDecimal("32")).build();
        for (var row : rows) {
            row.setSnapshot(snapshot);
            snapshot.getDeposits().add(row);
        }
        return snapshot;
    }

    private BankDeposit cash(long bankId, String currency, String amount, String original) {
        return BankDeposit.builder().bank(Bank.builder().id(bankId).build()).currency(currency)
                .depositType("USD".equals(currency) ? "美元活存" : "活存")
                .amount(decimal(amount)).originalAmount(decimal(original)).source("MANUAL").build();
    }

    private BankDeposit transit(long bankId, String currency, String amount, String original, LocalDate date) {
        BankDeposit row = cash(bankId, currency, amount, original);
        row.setCurrency("TRANSIT_" + currency);
        row.setDepositType("任意既有在途類型"); // 不重新查分類決定 persisted sign
        row.setProcessingDate(date);
        return row;
    }

    private BankDeposit securities(long bankId, String currency, String amount, String original) {
        BankDeposit row = cash(bankId, currency, amount, original);
        row.setDepositType("證券戶");
        return row;
    }

    private BankDeposit stockTransit(long bankId, String currency, String amount, String original, String type) {
        BankDeposit row = transit(bankId, currency, amount, original, TODAY);
        row.setDepositType(type);
        return row;
    }

    private static BigDecimal decimal(String value) { return value == null ? null : new BigDecimal(value); }
}
