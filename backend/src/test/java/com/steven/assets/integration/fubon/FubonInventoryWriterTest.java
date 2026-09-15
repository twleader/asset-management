package com.steven.assets.integration.fubon;

import com.steven.assets.model.AppUser;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.BrokerEntity;
import com.steven.assets.model.StockHolding;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.service.AssetSnapshotMutationLock;
import com.steven.assets.service.SnapshotAggregateCalculator;
import com.steven.assets.service.UserAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FubonInventoryWriterTest {
    @Mock AssetSnapshotMutationLock mutationLock;
    @Mock AssetSnapshotRepository snapshotRepository;
    @Mock BrokerRepository brokerRepository;
    @Mock UserAdminService userAdminService;
    @Mock FubonSyncFreshness freshness;

    private final SnapshotAggregateCalculator calculator = new SnapshotAggregateCalculator();
    private FubonInventoryWriter writer;
    private AssetSnapshot snapshot;
    private BrokerEntity fubon;
    private BrokerEntity other;

    @BeforeEach
    void setUp() {
        writer = new FubonInventoryWriter(
                mutationLock, snapshotRepository, brokerRepository, calculator, userAdminService, freshness,
                Clock.fixed(Instant.parse("2026-08-21T02:00:00Z"), ZoneOffset.UTC));
        fubon = BrokerEntity.builder().id(1L).code("fubon").displayName("富邦證券").active(true).build();
        other = BrokerEntity.builder().id(2L).code("cathay").displayName("國泰證券").active(true).build();
        snapshot = AssetSnapshot.builder()
                .id(7L).ownerUserId(9L).snapshotDate(LocalDate.of(2026, 8, 21))
                .deposits(new ArrayList<>()).funds(new ArrayList<>()).stocks(new ArrayList<>())
                .build();
        when(mutationLock.lockLatestForFubonConfiguredOwner(9L)).thenReturn(Optional.of(snapshot));
        lenient().when(userAdminService.configuredAdmin()).thenReturn(Optional.of(AppUser.builder()
                .id(9L).role(AppUser.ROLE_ADMIN).status(AppUser.STATUS_ACTIVE).build()));
        lenient().when(brokerRepository.findByCode("fubon")).thenReturn(Optional.of(fubon));
    }

    @Test
    void replacesOnlyFubonTwMapsNullMetadataAndRecalculatesDividendAndTotals() {
        StockHolding preserved = holding("0050", other, "100", "50", null, null, 4);
        StockHolding oldFubon = holding("2330", fubon, "37.04", "20", "99", "0.05", 2);
        snapshot.getStocks().addAll(List.of(preserved, oldFubon));

        FubonInventoryWriter.CommitResult result = writer.replace(9L, 7L, snapshot.getSnapshotDate(),
                List.of(new FubonInventoryWriter.PreparedPosition(
                        "2330", 3, new BigDecimal("12.345"), new BigDecimal("20"), "台積電")), false);

        assertThat(result.snapshotId()).isEqualTo(7L);
        assertThat(result.replaceCount()).isEqualTo(1);
        assertThat(snapshot.getStocks()).hasSize(2).contains(preserved);
        StockHolding mapped = snapshot.getStocks().stream()
                .filter(row -> "2330".equals(row.getStockCode())).findFirst().orElseThrow();
        assertThat(mapped.getShares()).isEqualByComparingTo("3");
        assertThat(mapped.getInvestmentCost()).isEqualByComparingTo("37.04");
        assertThat(mapped.getCurrentValue()).isEqualByComparingTo("60.00");
        assertThat(mapped.getDividendRate()).isEqualByComparingTo("0.05");
        assertThat(mapped.getEstimatedDividend()).isEqualByComparingTo("3");
        assertThat(mapped.getOriginalCurrencyValue()).isNull();
        assertThat(mapped.getTransactionType()).isNull();
        assertThat(mapped.getTransactionDate()).isNull();
        assertThat(mapped.getTransactionExchangeRate()).isNull();
        assertThat(mapped.getDisplayOrder()).isEqualTo(2);
        assertThat(mapped.getCurrency()).isEqualTo("TWD");
        assertThat(snapshot.getTotalStockValue()).isEqualByComparingTo("160.00");
        assertThat(snapshot.getTotalAssets()).isEqualByComparingTo("160.00");
        assertThat(snapshot.getEstimatedAnnualDividend()).isEqualByComparingTo("3");
        verify(snapshotRepository).saveAndFlush(snapshot);
    }

    @Test
    void explicitEmptyProofClearsOnlyFubonTwScope() {
        StockHolding preserved = holding("AAPL", fubon, "100", "80", null, null, 0);
        preserved.setMarket("美股");
        snapshot.getStocks().addAll(List.of(
                preserved,
                holding("2330", fubon, "100", "90", null, null, 1)));

        FubonInventoryWriter.CommitResult result = writer.replace(
                9L, 7L, snapshot.getSnapshotDate(), List.of(), true);

        assertThat(result.emptyCleared()).isTrue();
        assertThat(snapshot.getStocks()).containsExactly(preserved);
        verify(snapshotRepository).saveAndFlush(snapshot);
    }

    @Test
    void changedPositionDoesNotCarryManualDividendAndDuplicateOldRowsAreAmbiguous() {
        snapshot.getStocks().addAll(List.of(
                holding("2330", fubon, "100", "90", "8", null, 1),
                holding("2330", fubon, "100", "90", "9", "0.04", 1)));

        writer.replace(9L, 7L, snapshot.getSnapshotDate(),
                List.of(new FubonInventoryWriter.PreparedPosition(
                        "2330", 2, new BigDecimal("50"), new BigDecimal("60"), "台積電")), false);

        StockHolding mapped = snapshot.getStocks().getFirst();
        assertThat(mapped.getDividendRate()).isNull();
        assertThat(mapped.getEstimatedDividend()).isNull();
        assertThat(mapped.getDisplayOrder()).isNotNull();
    }

    @Test
    void precisionTwentyMoneyIsWritableButPrecisionTwentyOneRollsBackBeforeSave() {
        writer.replace(9L, 7L, snapshot.getSnapshotDate(),
                List.of(new FubonInventoryWriter.PreparedPosition(
                        "2330", 1, new BigDecimal("999999999999999999.99"), BigDecimal.ONE, "台積電")), false);
        assertThat(snapshot.getStocks().getFirst().getInvestmentCost())
                .isEqualByComparingTo("999999999999999999.99");

        snapshot.getStocks().clear();
        assertThatThrownBy(() -> writer.replace(9L, 7L, snapshot.getSnapshotDate(),
                List.of(new FubonInventoryWriter.PreparedPosition(
                        "2330", 9_999_999_999L, new BigDecimal("1000000000"), BigDecimal.ONE, "台積電")), false))
                .isInstanceOf(FubonInventoryWriter.CommitRejected.class)
                .hasMessage("MONEY_PRECISION_EXCEEDED");
        verify(snapshotRepository).saveAndFlush(snapshot);
    }

    @Test
    void latestSnapshotAndBrokerAreRecheckedUnderLock() {
        assertThatThrownBy(() -> writer.replace(9L, 8L, snapshot.getSnapshotDate(), List.of(), true))
                .isInstanceOf(FubonInventoryWriter.CommitRejected.class)
                .hasMessage("LATEST_SNAPSHOT_CHANGED");
        verify(snapshotRepository, never()).saveAndFlush(snapshot);
    }

    private StockHolding holding(
            String code,
            BrokerEntity broker,
            String current,
            String cost,
            String estimated,
            String rate,
            Integer order) {
        return StockHolding.builder()
                .snapshot(snapshot).stockCode(code).market("台股").broker(broker).shares(BigDecimal.ONE)
                .investmentCost(new BigDecimal(cost)).currentValue(new BigDecimal(current))
                .estimatedDividend(estimated == null ? null : new BigDecimal(estimated))
                .dividendRate(rate == null ? null : new BigDecimal(rate))
                .currency("TWD").displayOrder(order).build();
    }
}
