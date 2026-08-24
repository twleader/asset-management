package com.steven.assets.service;

import com.steven.assets.model.*;
import com.steven.assets.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * AssetService 單元測試
 * 覆蓋：總額計算邏輯、快照刪除、配息重算
 */
@ExtendWith(MockitoExtension.class)
class AssetServiceTest {

    @Mock AssetSnapshotRepository snapshotRepo;
    @Mock BankDepositRepository depositRepo;
    @Mock FundHoldingRepository fundRepo;
    @Mock StockHoldingRepository stockRepo;
    @Mock RealizedGainRepository gainRepo;
    @Mock ExchangeRateHistoryRepository rateHistRepo;
    @Mock MarketDataService marketDataService;
    @Mock BankRepository bankRepo;
    @Mock BrokerRepository brokerRepo;
    @Mock StockRepository stockMasterRepo;
    @Mock StockMasterService stockMasterService;
    @Mock TransitFundTypeRepository transitFundTypeRepo;
    @Mock FundNavService fundNavService;
    @Mock FundDividendService fundDividendService;
    @Mock AssetClassifier assetClassifier;
    @Mock StockStyleRepository stockStyleRepo;
    @Mock FundClassOverrideRepository fundClassOverrideRepo;
    @Mock com.steven.assets.security.TenantGuard tenantGuard;
    @Mock AssetSnapshotMutationLock snapshotMutationLock;
    @Mock SnapshotStockScopeOwnershipPort stockScopeOwnershipPort;
    @Spy SnapshotAggregateCalculator aggregateCalculator = new SnapshotAggregateCalculator();

    @InjectMocks AssetService service;

    private AssetSnapshot snapshot;

    @BeforeEach
    void setUp() {
        snapshot = new AssetSnapshot();
        snapshot.setId(1L);
        snapshot.setOwnerUserId(9L);
        snapshot.setSnapshotDate(LocalDate.of(2024, 1, 31));
        snapshot.setDeposits(new ArrayList<>());
        snapshot.setFunds(new ArrayList<>());
        snapshot.setStocks(new ArrayList<>());
    }

    // ---- deleteSnapshot ----

    @Test
    void deleteSnapshot_存在時先驗歸屬再刪除() {
        when(snapshotMutationLock.lockById(1L)).thenReturn(snapshot);

        service.deleteSnapshot(1L);

        verify(tenantGuard).assertOwned(snapshot.getOwnerUserId());
        verify(snapshotRepo).delete(snapshot);
    }

    @Test
    void deleteSnapshot_不存在時拋NoSuchElementException() {
        // findSnapshot 找不到即拋例外，不呼叫 delete
        when(snapshotMutationLock.lockById(99L))
                .thenThrow(new NoSuchElementException("找不到快照 ID: 99"));

        assertThatThrownBy(() -> service.deleteSnapshot(99L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("找不到快照 ID");

        verify(snapshotRepo, never()).delete(any());
    }

    @Test
    void updateSnapshot_先鎖定並以最終日期擷取一次ownershipDecision() {
        LocalDate finalDate = LocalDate.of(2024, 2, 1);
        when(snapshotMutationLock.lockById(1L)).thenReturn(snapshot);
        when(stockScopeOwnershipPort.capture(any())).thenReturn(SnapshotStockScopeOwnership.payloadOwned());
        when(snapshotRepo.save(snapshot)).thenReturn(snapshot);

        service.updateSnapshot(1L, new com.steven.assets.dto.AssetSnapshotDto.CreateSnapshotRequest(
                finalDate, BigDecimal.ONE, "updated", List.of(), List.of(), List.of()));

        InOrder order = inOrder(snapshotMutationLock, tenantGuard, snapshotRepo, stockScopeOwnershipPort);
        order.verify(snapshotMutationLock).lockById(1L);
        order.verify(tenantGuard).assertOwned(9L);
        order.verify(snapshotRepo).existsBySnapshotDate(finalDate);
        ArgumentCaptor<SnapshotUpdateTarget> target = ArgumentCaptor.forClass(SnapshotUpdateTarget.class);
        order.verify(stockScopeOwnershipPort).capture(target.capture());
        assertThat(target.getValue()).isEqualTo(new SnapshotUpdateTarget(1L, 9L, finalDate));
    }

    // ---- recalcAllDividends ----

    @Test
    void recalcAllDividends_有配息率且有現值時重算() {
        StockHolding stock = new StockHolding();
        stock.setDividendRate(new BigDecimal("0.05"));
        stock.setCurrentValue(new BigDecimal("100000"));
        stock.setEstimatedDividend(BigDecimal.ZERO);
        snapshot.getStocks().add(stock);

        when(snapshotMutationLock.lockAllInIdOrder()).thenReturn(List.of(snapshot));
        when(snapshotRepo.save(any())).thenReturn(snapshot);

        int updated = service.recalcAllDividends();

        assertThat(updated).isEqualTo(1);
        assertThat(stock.getEstimatedDividend()).isEqualByComparingTo("5000");
    }

    @Test
    void recalcAllDividends_無配息率時跳過() {
        StockHolding stock = new StockHolding();
        stock.setDividendRate(null);
        stock.setCurrentValue(new BigDecimal("100000"));
        snapshot.getStocks().add(stock);
        snapshot.setEstimatedAnnualDividend(BigDecimal.ZERO);

        when(snapshotMutationLock.lockAllInIdOrder()).thenReturn(List.of(snapshot));

        int updated = service.recalcAllDividends();

        assertThat(updated).isEqualTo(0);
        verify(snapshotRepo, never()).save(any());
    }

    @Test
    void recalcAllDividends_現值為零時跳過() {
        StockHolding stock = new StockHolding();
        stock.setDividendRate(new BigDecimal("0.05"));
        stock.setCurrentValue(BigDecimal.ZERO);
        snapshot.getStocks().add(stock);
        snapshot.setEstimatedAnnualDividend(BigDecimal.ZERO);

        when(snapshotMutationLock.lockAllInIdOrder()).thenReturn(List.of(snapshot));

        int updated = service.recalcAllDividends();

        assertThat(updated).isEqualTo(0);
    }

    // ---- getAssetHistory ----

    @Test
    void getAssetHistory_空列表回傳空() {
        when(snapshotRepo.findAllByOrderBySnapshotDateAsc()).thenReturn(List.of());

        var result = service.getAssetHistory();

        assertThat(result).isEmpty();
    }

    @Test
    void getAssetHistory_單筆快照increase為null() {
        snapshot.setTotalAssets(new BigDecimal("5000000"));
        snapshot.setEstimatedAnnualDividend(new BigDecimal("50000"));

        when(snapshotRepo.findAllByOrderBySnapshotDateAsc()).thenReturn(List.of(snapshot));

        var result = service.getAssetHistory();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).increase()).isNull();   // 第一筆無前期比較
        assertThat(result.get(0).increaseRate()).isNull();
        assertThat(result.get(0).estimatedAnnualDividend())
                .isEqualByComparingTo("50000");
    }

    @Test
    void getAssetHistory_兩筆快照計算增幅() {
        AssetSnapshot snap1 = new AssetSnapshot();
        snap1.setId(1L);
        snap1.setSnapshotDate(LocalDate.of(2023, 1, 1));
        snap1.setTotalAssets(new BigDecimal("4000000"));
        snap1.setStocks(new ArrayList<>());

        AssetSnapshot snap2 = new AssetSnapshot();
        snap2.setId(2L);
        snap2.setSnapshotDate(LocalDate.of(2024, 1, 1));
        snap2.setTotalAssets(new BigDecimal("5000000"));
        snap2.setStocks(new ArrayList<>());

        when(snapshotRepo.findAllByOrderBySnapshotDateAsc()).thenReturn(List.of(snap1, snap2));

        var result = service.getAssetHistory();

        assertThat(result.get(1).increase()).isEqualByComparingTo("1000000");
        assertThat(result.get(1).increaseRate()).isEqualByComparingTo("0.250000");
    }

    // ---- rollLatestSnapshotToTodayForOwner (Requirement 35 / Task 174) ----

    @Test
    void roll_最新快照為過去日期_釘成當日並save回true() {
        LocalDate today = LocalDate.of(2026, 7, 12);
        snapshot.setSnapshotDate(LocalDate.of(2026, 7, 10));
        when(snapshotMutationLock.lockLatestForOwner(1L))
                .thenReturn(Optional.of(snapshot));
        when(snapshotRepo.save(any())).thenReturn(snapshot);

        boolean rolled = service.rollLatestSnapshotToTodayForOwner(1L, today);

        assertThat(rolled).isTrue();
        assertThat(snapshot.getSnapshotDate()).isEqualTo(today);
        verify(snapshotRepo).save(snapshot);
    }

    @Test
    void roll_最新快照已是當日_skip不save() {
        LocalDate today = LocalDate.of(2026, 7, 12);
        snapshot.setSnapshotDate(today);
        when(snapshotMutationLock.lockLatestForOwner(1L))
                .thenReturn(Optional.of(snapshot));

        boolean rolled = service.rollLatestSnapshotToTodayForOwner(1L, today);

        assertThat(rolled).isFalse();
        assertThat(snapshot.getSnapshotDate()).isEqualTo(today);
        verify(snapshotRepo, never()).save(any());
    }

    @Test
    void roll_最新快照為未來日期_skip不往回搬() {
        LocalDate today = LocalDate.of(2026, 7, 12);
        LocalDate future = today.plusDays(1);
        snapshot.setSnapshotDate(future);
        when(snapshotMutationLock.lockLatestForOwner(1L))
                .thenReturn(Optional.of(snapshot));

        boolean rolled = service.rollLatestSnapshotToTodayForOwner(1L, today);

        assertThat(rolled).isFalse();
        assertThat(snapshot.getSnapshotDate()).isEqualTo(future);
        verify(snapshotRepo, never()).save(any());
    }

    @Test
    void roll_owner無快照_回false() {
        LocalDate today = LocalDate.of(2026, 7, 12);
        when(snapshotMutationLock.lockLatestForOwner(99L))
                .thenReturn(Optional.empty());

        boolean rolled = service.rollLatestSnapshotToTodayForOwner(99L, today);

        assertThat(rolled).isFalse();
        verify(snapshotRepo, never()).save(any());
    }
}
