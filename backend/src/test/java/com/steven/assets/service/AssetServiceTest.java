package com.steven.assets.service;

import com.steven.assets.model.*;
import com.steven.assets.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
    @Mock BankRepository bankRepo;
    @Mock BrokerRepository brokerRepo;
    @Mock MarketDataService marketDataService;

    @InjectMocks AssetService service;

    private AssetSnapshot snapshot;

    @BeforeEach
    void setUp() {
        snapshot = new AssetSnapshot();
        snapshot.setId(1L);
        snapshot.setSnapshotDate(LocalDate.of(2024, 1, 31));
        snapshot.setDeposits(new ArrayList<>());
        snapshot.setFunds(new ArrayList<>());
        snapshot.setStocks(new ArrayList<>());
    }

    // ---- deleteSnapshot ----

    @Test
    void deleteSnapshot_呼叫deleteById() {
        service.deleteSnapshot(1L);

        verify(snapshotRepo).deleteById(1L);
    }

    @Test
    void deleteSnapshot_不存在時deleteById仍被呼叫() {
        // deleteSnapshot 直接委派 deleteById，不預先 findById
        service.deleteSnapshot(99L);

        verify(snapshotRepo).deleteById(99L);
    }

    // ---- recalcAllDividends ----

    @Test
    void recalcAllDividends_有配息率且有現值時重算() {
        StockHolding stock = new StockHolding();
        stock.setDividendRate(new BigDecimal("0.05"));
        stock.setCurrentValue(new BigDecimal("100000"));
        stock.setEstimatedDividend(BigDecimal.ZERO);
        snapshot.getStocks().add(stock);

        when(snapshotRepo.findAllByOrderBySnapshotDateAsc()).thenReturn(List.of(snapshot));
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

        when(snapshotRepo.findAllByOrderBySnapshotDateAsc()).thenReturn(List.of(snapshot));

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

        when(snapshotRepo.findAllByOrderBySnapshotDateAsc()).thenReturn(List.of(snapshot));

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
}
