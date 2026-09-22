package com.steven.assets.service;

import com.steven.assets.model.*;
import com.steven.assets.dto.AssetSnapshotDto;
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

    @Test
    void updateSnapshot_preservesOnlyFubonOwnedTransitAndDropsEveryPayloadCollision() {
        Bank fubon = Bank.builder().id(7L).code("fubon").displayName("台北富邦銀行").build();
        Bank manualBank = Bank.builder().id(8L).code("manual").displayName("手動銀行").build();
        BankDeposit managed = BankDeposit.builder().snapshot(snapshot).bank(fubon).depositType("買股待付款")
                .currency("TRANSIT_TWD").amount(new BigDecimal("-1002")).notes("auto note")
                .source("FUBON_SYNC").build();
        snapshot.getDeposits().add(managed);
        when(snapshotMutationLock.lockById(1L)).thenReturn(snapshot);
        when(stockScopeOwnershipPort.capture(any())).thenReturn(SnapshotStockScopeOwnership.payloadOwned());
        when(bankRepo.findById(8L)).thenReturn(Optional.of(manualBank));
        when(snapshotRepo.save(snapshot)).thenReturn(snapshot);

        service.updateSnapshot(1L, new com.steven.assets.dto.AssetSnapshotDto.CreateSnapshotRequest(
                snapshot.getSnapshotDate(), BigDecimal.ONE, "updated", List.of(
                // Currency deliberately differs: collision identity is bank id + type only.
                new com.steven.assets.dto.AssetSnapshotDto.DepositRequest(7L, "買股待付款", BigDecimal.ONE,
                        null, "USD", null, "malicious overwrite"),
                new com.steven.assets.dto.AssetSnapshotDto.DepositRequest(8L, "活存", new BigDecimal("20"),
                        null, "TWD", null, "manual")), List.of(), List.of()));

        assertThat(snapshot.getDeposits()).hasSize(2);
        assertThat(snapshot.getDeposits().getFirst()).isSameAs(managed);
        assertThat(managed.getAmount()).isEqualByComparingTo("-1002");
        assertThat(managed.getNotes()).isEqualTo("auto note");
        assertThat(managed.getSource()).isEqualTo("FUBON_SYNC");
        assertThat(snapshot.getDeposits().get(1).getSource()).isEqualTo("MANUAL");
        verify(bankRepo, never()).findById(7L);
    }

    @Test
    void depositResponse_projectsOnlyManualOrAutoUpdateMode() {
        Bank bank = Bank.builder().id(7L).code("fubon").displayName("台北富邦銀行").build();
        snapshot.getDeposits().add(BankDeposit.builder().snapshot(snapshot).bank(bank).depositType("買股待付款")
                .currency("TRANSIT_TWD").amount(new BigDecimal("-1")).source("FUBON_SYNC").build());
        snapshot.getDeposits().add(BankDeposit.builder().snapshot(snapshot).bank(bank).depositType("活存")
                .currency("TWD").amount(new BigDecimal("1")).source("MANUAL").build());
        when(snapshotRepo.findById(1L)).thenReturn(Optional.of(snapshot));

        var detail = service.getSnapshotDetail(1L);

        assertThat(detail.deposits()).extracting(com.steven.assets.dto.AssetSnapshotDto.DepositResponse::updateMode)
                .containsExactly("AUTO", "MANUAL");
    }

    @Test
    void createSnapshot_explicitlyMarksEveryUserDepositManual() {
        LocalDate date = LocalDate.of(2026, 8, 21);
        Bank bank = Bank.builder().id(8L).code("manual").displayName("手動銀行").build();
        when(snapshotRepo.existsBySnapshotDate(date)).thenReturn(false);
        when(tenantGuard.requireCurrentUserId()).thenReturn(9L);
        when(bankRepo.findById(8L)).thenReturn(Optional.of(bank));
        when(snapshotRepo.save(any(AssetSnapshot.class))).thenAnswer(call -> call.getArgument(0));

        service.createSnapshot(new com.steven.assets.dto.AssetSnapshotDto.CreateSnapshotRequest(
                date, BigDecimal.ONE, null, List.of(new com.steven.assets.dto.AssetSnapshotDto.DepositRequest(
                8L, "活存", new BigDecimal("20"), null, "TWD", null, "manual")), List.of(), List.of()));

        ArgumentCaptor<AssetSnapshot> captured = ArgumentCaptor.forClass(AssetSnapshot.class);
        verify(snapshotRepo).save(captured.capture());
        assertThat(captured.getValue().getDeposits()).singleElement()
                .extracting(BankDeposit::getSource).isEqualTo("MANUAL");
    }

    @Test
    void updateTransitSupplementsOnlyExactLegacyAutoAndProtectsAllOtherFields() {
        LocalDate date = LocalDate.now(java.time.ZoneId.of("Asia/Taipei")).plusDays(3);
        BankDeposit auto = autoDeposit(10L, null);
        prepareUpdate();
        service.updateSnapshot(1L, request(List.of(depositRequest(auto, date, "TRANSIT_TWD"))));
        assertThat(snapshot.getDeposits()).containsExactly(auto);
        assertThat(auto.getProcessingDate()).isEqualTo(date);
        assertThat(auto.getAmount()).isEqualByComparingTo("-1002");
        assertThat(auto.getNotes()).isEqualTo("source note");
        assertThat(auto.getSource()).isEqualTo("FUBON_SYNC");
    }

    @Test
    void updateTransitDoesNotModifyExistingDateOrLegacyWithoutExactIdAndCurrency() {
        LocalDate date = LocalDate.of(2035, 1, 1);
        BankDeposit first = autoDeposit(10L, date);
        BankDeposit legacy = autoDeposit(11L, null);
        prepareUpdate();
        service.updateSnapshot(1L, request(List.of(
                depositRequest(first, date.plusDays(1), "TRANSIT_TWD"),
                depositRequest(legacy, date, "USD"),
                new AssetSnapshotDto.DepositRequest(7L, "買股待付款", BigDecimal.ONE, null,
                        "TRANSIT_TWD", null, "legacy client", null, date))));
        assertThat(snapshot.getDeposits()).containsExactly(first, legacy);
        assertThat(first.getProcessingDate()).isEqualTo(date);
        assertThat(legacy.getProcessingDate()).isNull();
        service.updateSnapshot(1L, request(List.of(depositRequest(first, null, "TRANSIT_TWD"))));
        assertThat(first.getProcessingDate()).isEqualTo(date);
    }

    @Test
    void updateTransitAutoIdCannotBeClonedWithChangedBankOrType() {
        BankDeposit auto = autoDeposit(10L, null);
        prepareUpdate();
        service.updateSnapshot(1L, request(List.of(new AssetSnapshotDto.DepositRequest(99L, "活存",
                BigDecimal.ONE, null, "TWD", null, "overwrite", auto.getId(), LocalDate.of(2035, 1, 1)))));
        assertThat(snapshot.getDeposits()).containsExactly(auto);
        assertThat(auto.getProcessingDate()).isNull();
        verifyNoInteractions(bankRepo);
    }

    @Test
    void updateTransitDueAutoCannotBeRecreatedFromSamePayloadAndRecalculatesTotals() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Taipei"));
        snapshot.setSnapshotDate(today);
        BankDeposit due = autoDeposit(10L, null);
        BankDeposit future = autoDeposit(11L, today.plusDays(1));
        prepareUpdate();
        when(snapshotRepo.findFirstByOwnerUserIdOrderBySnapshotDateDesc(9L)).thenReturn(Optional.of(snapshot));
        service.updateSnapshot(1L, request(List.of(depositRequest(due, today, "TRANSIT_TWD"),
                depositRequest(future, null, "TRANSIT_TWD"))));
        assertThat(snapshot.getDeposits()).containsExactly(future);
        assertThat(snapshot.getTotalDeposit()).isEqualByComparingTo("-1002");
        assertThat(snapshot.getTotalAssets()).isEqualByComparingTo("-1002");
    }

    @Test
    void updateTransitRejectsUnknownOrDuplicateIdsBeforeAnyMutation() {
        BankDeposit auto = autoDeposit(10L, null);
        when(snapshotMutationLock.lockById(1L)).thenReturn(snapshot);
        var valid = depositRequest(auto, LocalDate.of(2035, 1, 1), "TRANSIT_TWD");
        var stale = new AssetSnapshotDto.DepositRequest(7L, "買股待付款", BigDecimal.ONE, null,
                "TRANSIT_TWD", null, null, 999L, null);
        for (List<AssetSnapshotDto.DepositRequest> requests : List.of(List.of(valid, stale), List.of(valid, valid))) {
            assertThatThrownBy(() -> service.updateSnapshot(1L, request(requests)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("重新載入");
            assertThat(snapshot.getDeposits()).containsExactly(auto);
            assertThat(auto.getProcessingDate()).isNull();
            assertThat(snapshot.getNotes()).isNull();
        }
        verifyNoInteractions(stockScopeOwnershipPort, snapshotRepo, bankRepo, aggregateCalculator);
    }

    @Test
    void createTransitRejectsCopiedIdsAndClearsDueRowsOnlyOnNewLatest() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Taipei"));
        when(tenantGuard.requireCurrentUserId()).thenReturn(9L);
        var due = new AssetSnapshotDto.DepositRequest(null, "賣股待收款", BigDecimal.TEN,
                null, "TRANSIT_TWD", null, null, null, today);
        var normal = new AssetSnapshotDto.DepositRequest(null, "活存", BigDecimal.ONE,
                null, "TWD", null, null, null, today);
        when(snapshotRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        var result = service.createSnapshot(new AssetSnapshotDto.CreateSnapshotRequest(
                today, BigDecimal.ONE, null, List.of(due, normal), List.of(), List.of()));
        assertThat(result.totalDeposit()).isEqualByComparingTo("1");
        ArgumentCaptor<AssetSnapshot> captured = ArgumentCaptor.forClass(AssetSnapshot.class);
        verify(snapshotRepo).save(captured.capture());
        assertThat(captured.getValue().getDeposits()).singleElement()
                .extracting(BankDeposit::getProcessingDate).isNull();
        assertThatThrownBy(() -> service.createSnapshot(new AssetSnapshotDto.CreateSnapshotRequest(
                today, BigDecimal.ONE, null, List.of(new AssetSnapshotDto.DepositRequest(null, "活存",
                BigDecimal.ONE, null, "TWD", null, null, 10L, null)), List.of(), List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void manualTransitDateCanBeClearedOnUpdate() {
        BankDeposit manual = BankDeposit.builder().id(21L).snapshot(snapshot).currency("TRANSIT_USD")
                .depositType("賣股待收款").amount(BigDecimal.TEN).processingDate(LocalDate.of(2035, 1, 1)).build();
        snapshot.getDeposits().add(manual);
        prepareUpdate();
        service.updateSnapshot(1L, request(List.of(new AssetSnapshotDto.DepositRequest(null, "賣股待收款",
                BigDecimal.TEN, null, "TRANSIT_USD", null, null, manual.getId(), null))));
        assertThat(snapshot.getDeposits()).singleElement().extracting(BankDeposit::getProcessingDate).isNull();
    }

    private void prepareUpdate() {
        when(snapshotMutationLock.lockById(1L)).thenReturn(snapshot);
        when(stockScopeOwnershipPort.capture(any())).thenReturn(SnapshotStockScopeOwnership.payloadOwned());
        when(snapshotRepo.save(snapshot)).thenReturn(snapshot);
    }

    private BankDeposit autoDeposit(Long id, LocalDate processingDate) {
        BankDeposit auto = BankDeposit.builder().id(id).snapshot(snapshot)
                .bank(Bank.builder().id(7L).code("fubon").build()).depositType("買股待付款")
                .currency("TRANSIT_TWD").amount(new BigDecimal("-1002")).source("FUBON_SYNC")
                .processingDate(processingDate).notes("source note").build();
        snapshot.getDeposits().add(auto);
        return auto;
    }

    private AssetSnapshotDto.DepositRequest depositRequest(BankDeposit deposit, LocalDate date, String currency) {
        return new AssetSnapshotDto.DepositRequest(deposit.getBank().getId(), deposit.getDepositType(),
                BigDecimal.ONE, null, currency, BigDecimal.TEN, "malicious edit", deposit.getId(), date);
    }

    private AssetSnapshotDto.CreateSnapshotRequest request(List<AssetSnapshotDto.DepositRequest> deposits) {
        return new AssetSnapshotDto.CreateSnapshotRequest(snapshot.getSnapshotDate(), BigDecimal.ONE,
                "updated", deposits, List.of(), List.of());
    }

    @Test
    void createSnapshotTaiwanPayloadNeverWritesTheStockMaster() {
        LocalDate date = LocalDate.of(2026, 8, 21);
        when(snapshotRepo.existsBySnapshotDate(date)).thenReturn(false);
        when(tenantGuard.requireCurrentUserId()).thenReturn(9L);
        when(snapshotRepo.save(any(AssetSnapshot.class))).thenAnswer(call -> call.getArgument(0));

        service.createSnapshot(new com.steven.assets.dto.AssetSnapshotDto.CreateSnapshotRequest(
                date, BigDecimal.ONE, null, List.of(), List.of(), List.of(
                new com.steven.assets.dto.AssetSnapshotDto.StockRequest("00850", "USER SUPPLIED WRONG NAME", "台股", null,
                        BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, null, null, "TWD", null, null, null, null))));

        verify(stockMasterService, never()).upsert(anyString(), anyString(), anyString());
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
