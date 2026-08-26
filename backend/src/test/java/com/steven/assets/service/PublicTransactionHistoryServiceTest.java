package com.steven.assets.service;

import com.steven.assets.repository.AssetTransactionRepository;
import com.steven.assets.model.AssetTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Requirement 112: owner-filtered repository read is stable; amountTwd never absorbs fee or tax. */
class PublicTransactionHistoryServiceTest {

    @Test
    void yearSelectionKeepsAllTimeSummaryAndUsesUsdAmountTimesRateWithoutFeeOrTax() {
        AssetTransactionRepository repository = mock(AssetTransactionRepository.class);
        AssetTransaction usdSell = transaction(9L, "賣", LocalDate.of(2026, 8, 20),
                "USD", "10.00", "30.00", "2.00", "3.00");
        AssetTransaction twdBuy = transaction(8L, "買", LocalDate.of(2025, 12, 31),
                "TWD", "100.00", null, "9.00", "8.00");
        when(repository.findAllByOrderByTradeDateDescIdDesc()).thenReturn(List.of(usdSell, twdBuy));

        var result = new PublicTransactionHistoryService(repository).current(List.of("2026"), null, null);

        assertThat(result.selection().mode().name()).isEqualTo("YEAR");
        assertThat(result.selection().year()).isEqualTo(2026);
        assertThat(result.records()).extracting(record -> record.id()).containsExactly(9L);
        assertThat(result.records().getFirst().amountTwd()).isEqualByComparingTo("300.00");
        assertThat(result.summary().sellCount()).isEqualTo(1);
        assertThat(result.summary().totalSellAmountTwd()).isEqualByComparingTo("300.00");
        assertThat(result.allTimeSummary().buyCount()).isEqualTo(1);
        assertThat(result.allTimeSummary().totalBuyAmountTwd()).isEqualByComparingTo("100.00");
        assertThat(result.yearSummaries()).extracting(summary -> summary.year()).containsExactly(2026);
        verify(repository).findAllByOrderByTradeDateDescIdDesc();
        verifyNoMoreInteractions(repository);
    }

    @Test
    void rangeSelectionAndAllTimeSummaryUseTheSameSingleOrderedLedgerRead() {
        AssetTransactionRepository repository = mock(AssetTransactionRepository.class);
        AssetTransaction newest = transaction(12L, "買", LocalDate.of(2026, 2, 2),
                "TWD", "12.00", null, "0.00", "0.00");
        AssetTransaction selected = transaction(11L, "賣", LocalDate.of(2026, 2, 1),
                "TWD", "11.00", null, "0.00", "0.00");
        AssetTransaction older = transaction(10L, "買", LocalDate.of(2025, 12, 31),
                "TWD", "10.00", null, "0.00", "0.00");
        when(repository.findAllByOrderByTradeDateDescIdDesc()).thenReturn(List.of(newest, selected, older));

        var result = new PublicTransactionHistoryService(repository).current(
                null, List.of("2026-02-01"), List.of("2026-02-01"));

        assertThat(result.records()).extracting(record -> record.id()).containsExactly(11L);
        assertThat(result.summary().totalSellAmountTwd()).isEqualByComparingTo("11.00");
        assertThat(result.allTimeSummary().totalBuyAmountTwd()).isEqualByComparingTo("22.00");
        verify(repository).findAllByOrderByTradeDateDescIdDesc();
        verifyNoMoreInteractions(repository);
    }

    @Test
    void malformedOrAmbiguousFiltersDoNotReadTheRepository() {
        AssetTransactionRepository repository = mock(AssetTransactionRepository.class);
        PublicTransactionHistoryService service = new PublicTransactionHistoryService(repository);

        assertThatThrownBy(() -> service.current(List.of("2026", "2025"), null, null))
                .isInstanceOf(InvalidPublicTransactionHistoryRequestException.class);
        assertThatThrownBy(() -> service.current(List.of("2026"), List.of("2026-01-01"), List.of("2026-12-31")))
                .isInstanceOf(InvalidPublicTransactionHistoryRequestException.class);
        assertThatThrownBy(() -> service.current(null, List.of("2026-02-01"), List.of("2026-01-01")))
                .isInstanceOf(InvalidPublicTransactionHistoryRequestException.class);
        verifyNoInteractions(repository);
    }

    private static AssetTransaction transaction(
            long id, String type, LocalDate date, String currency, String amount, String rate, String fee, String tax) {
        return AssetTransaction.builder()
                .id(id)
                .ownerUserId(1L)
                .transactionType(type)
                .assetType("股票")
                .assetName("合成標的")
                .assetCode("EXM")
                .market("台股")
                .currency(currency)
                .channel("synthetic")
                .tradeDate(date)
                .shares(BigDecimal.ONE)
                .price(BigDecimal.TEN)
                .amount(new BigDecimal(amount))
                .fee(new BigDecimal(fee))
                .transactionTax(new BigDecimal(tax))
                .exchangeRate(rate == null ? null : new BigDecimal(rate))
                .notes(null)
                .build();
    }
}
