package com.steven.assets.service;

import com.steven.assets.dto.AssetTransactionDto;
import com.steven.assets.model.AssetTransaction;
import com.steven.assets.repository.AssetTransactionRepository;
import com.steven.assets.security.TenantGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AssetTransactionService 單元測試（Requirement 49 / Task 237）。
 * 覆蓋 CRUD、年度分組彙總、amountTwd 三情境。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssetTransactionServiceTest {

    @Mock private AssetTransactionRepository txRepo;
    @Mock private TenantGuard tenantGuard;

    @InjectMocks private AssetTransactionService service;

    private static AssetTransaction tx(String type, String currency, LocalDate date,
                                       BigDecimal amount, BigDecimal rate) {
        return AssetTransaction.builder()
                .transactionType(type).assetType("股票").assetName("台積電")
                .currency(currency).tradeDate(date).amount(amount).exchangeRate(rate)
                .build();
    }

    private static AssetTransactionDto.CreateAssetTransactionRequest req(
            String type, String currency, LocalDate date, BigDecimal amount, BigDecimal rate) {
        return new AssetTransactionDto.CreateAssetTransactionRequest(
                type, "股票", "台積電", "2330", "台股", currency, "富邦",
                date, new BigDecimal("1000"), new BigDecimal("1000"), amount, rate, "備註");
    }

    @Test
    void 建立時ownerUserId正確寫入() {
        when(tenantGuard.requireCurrentUserId()).thenReturn(42L);
        when(txRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createAssetTransaction(req("買", "TWD", LocalDate.of(2026, 7, 1),
                new BigDecimal("1000000"), null));

        ArgumentCaptor<AssetTransaction> cap = ArgumentCaptor.forClass(AssetTransaction.class);
        verify(txRepo).save(cap.capture());
        assertThat(cap.getValue().getOwnerUserId()).isEqualTo(42L);
        assertThat(cap.getValue().getTransactionType()).isEqualTo("買");
        assertThat(cap.getValue().getAssetName()).isEqualTo("台積電");
    }

    @Test
    void 更新載入後驗歸屬並更新欄位() {
        AssetTransaction existing = tx("買", "TWD", LocalDate.of(2026, 1, 1),
                new BigDecimal("100"), null);
        existing.setId(7L);
        existing.setOwnerUserId(9L);
        when(txRepo.findById(7L)).thenReturn(java.util.Optional.of(existing));
        when(txRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AssetTransactionDto.AssetTransactionResponse resp = service.updateAssetTransaction(7L,
                req("賣", "TWD", LocalDate.of(2026, 3, 3), new BigDecimal("200"), null));

        verify(tenantGuard).assertOwned(9L);
        assertThat(resp.transactionType()).isEqualTo("賣");
        assertThat(resp.amount()).isEqualByComparingTo("200");
    }

    @Test
    void 刪除載入後驗歸屬() {
        AssetTransaction existing = tx("買", "TWD", LocalDate.of(2026, 1, 1),
                new BigDecimal("100"), null);
        existing.setId(5L);
        existing.setOwnerUserId(3L);
        when(txRepo.findById(5L)).thenReturn(java.util.Optional.of(existing));

        service.deleteAssetTransaction(5L);

        verify(tenantGuard).assertOwned(3L);
        verify(txRepo).delete(existing);
    }

    @Test
    void amountTwd_USD幣別等於amount乘匯率() {
        when(txRepo.findAllByOrderByTradeDateDesc()).thenReturn(List.of(
                tx("買", "USD", LocalDate.of(2026, 5, 1), new BigDecimal("100"), new BigDecimal("32"))));

        var list = service.getAssetTransactionsByYear().get(0).records();

        assertThat(list).hasSize(1);
        assertThat(list.get(0).amountTwd()).isEqualByComparingTo("3200");
    }

    @Test
    void amountTwd_TWD幣別等於amount() {
        when(txRepo.findAllByOrderByTradeDateDesc()).thenReturn(List.of(
                tx("買", "TWD", LocalDate.of(2026, 5, 1), new BigDecimal("5000"), new BigDecimal("32"))));

        var list = service.getAssetTransactionsByYear().get(0).records();

        assertThat(list.get(0).amountTwd()).isEqualByComparingTo("5000");
    }

    @Test
    void amountTwd_USD但匯率為null時退回amount() {
        when(txRepo.findAllByOrderByTradeDateDesc()).thenReturn(List.of(
                tx("買", "USD", LocalDate.of(2026, 5, 1), new BigDecimal("100"), null)));

        var list = service.getAssetTransactionsByYear().get(0).records();

        assertThat(list.get(0).amountTwd()).isEqualByComparingTo("100");
    }

    @Test
    void getAssetTransactionsByYear依年度分組並彙總買賣筆數與台幣金額() {
        // 2026：買 USD 100×32=3200、賣 TWD 5000；2025：買 TWD 1000
        when(txRepo.findAllByOrderByTradeDateDesc()).thenReturn(List.of(
                tx("買", "USD", LocalDate.of(2026, 12, 31), new BigDecimal("100"), new BigDecimal("32")),
                tx("賣", "TWD", LocalDate.of(2026, 1, 1), new BigDecimal("5000"), null),
                tx("買", "TWD", LocalDate.of(2025, 6, 1), new BigDecimal("1000"), null)));

        List<AssetTransactionDto.YearSummaryResponse> byYear = service.getAssetTransactionsByYear();

        // 年度新到舊
        assertThat(byYear).extracting(AssetTransactionDto.YearSummaryResponse::year)
                .containsExactly(2026, 2025);

        var y2026 = byYear.get(0);
        assertThat(y2026.buyCount()).isEqualTo(1);
        assertThat(y2026.sellCount()).isEqualTo(1);
        assertThat(y2026.totalBuyAmountTwd()).isEqualByComparingTo("3200");
        assertThat(y2026.totalSellAmountTwd()).isEqualByComparingTo("5000");
        assertThat(y2026.records()).hasSize(2);

        var y2025 = byYear.get(1);
        assertThat(y2025.buyCount()).isEqualTo(1);
        assertThat(y2025.sellCount()).isEqualTo(0);
        assertThat(y2025.totalBuyAmountTwd()).isEqualByComparingTo("1000");
        assertThat(y2025.totalSellAmountTwd()).isEqualByComparingTo("0");
    }
}
