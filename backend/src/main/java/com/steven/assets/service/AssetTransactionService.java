package com.steven.assets.service;

import com.steven.assets.dto.AssetTransactionDto;
import com.steven.assets.model.AssetTransaction;
import com.steven.assets.repository.AssetTransactionRepository;
import com.steven.assets.security.TenantGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.TreeMap;

/**
 * 資產交易紀錄服務（Requirement 49 / Task 237）。
 *
 * <p>flow event ledger 的 CRUD。owner 取得一律走 {@link TenantGuard#requireCurrentUserId()}
 * （fail-closed，底層讀 request-scoped {@code CurrentUserContext}），by-id 讀寫再以
 * {@link TenantGuard#assertOwned(Long)} 驗歸屬（{@code findById} 不受 {@code @Filter} 約束）。
 *
 * <p>交易紀錄不算損益：衍生 {@code amountTwd}／{@code year} 於 {@link #toResponse} 即時計算，不入庫。
 *
 * <p>Task 268 的 {@code fee}／{@code transactionTax} 是**純記錄欄**：原樣讀寫，
 * 不參與 {@code amountTwd} 計算、不參與 {@link #getAssetTransactionsByYear} 的年度彙總、
 * 不回頭調整 {@code amount}。既有 59 列的 {@code amount} 本就依「含費用後之實際交割金額」語意輸入，
 * 讓新欄位參與計算會在升級當下無聲改寫既有年度統計數字。
 */
@Service
@RequiredArgsConstructor
public class AssetTransactionService {

    private final AssetTransactionRepository txRepo;
    private final TenantGuard tenantGuard;

    @Transactional
    public AssetTransactionDto.AssetTransactionResponse createAssetTransaction(
            AssetTransactionDto.CreateAssetTransactionRequest req) {
        AssetTransaction tx = AssetTransaction.builder()
                .ownerUserId(tenantGuard.requireCurrentUserId())
                .transactionType(req.transactionType())
                .assetType(req.assetType())
                .assetName(req.assetName())
                .assetCode(req.assetCode())
                .market(req.market())
                .currency(req.currency())
                .channel(req.channel())
                .tradeDate(req.tradeDate())
                .shares(req.shares())
                .price(req.price())
                .amount(req.amount())
                .fee(req.fee())
                .transactionTax(req.transactionTax())
                .exchangeRate(req.exchangeRate())
                .notes(req.notes())
                .build();
        return toResponse(txRepo.save(tx));
    }

    @Transactional
    public AssetTransactionDto.AssetTransactionResponse updateAssetTransaction(
            Long id, AssetTransactionDto.CreateAssetTransactionRequest req) {
        AssetTransaction tx = txRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("AssetTransaction not found: " + id));
        tenantGuard.assertOwned(tx.getOwnerUserId());

        tx.setTransactionType(req.transactionType());
        tx.setAssetType(req.assetType());
        tx.setAssetName(req.assetName());
        tx.setAssetCode(req.assetCode());
        tx.setMarket(req.market());
        tx.setCurrency(req.currency());
        tx.setChannel(req.channel());
        tx.setTradeDate(req.tradeDate());
        tx.setShares(req.shares());
        tx.setPrice(req.price());
        tx.setAmount(req.amount());
        // 全量取代語意：送 null 即清空該欄（與同表既有 shares／price／notes 一致）
        tx.setFee(req.fee());
        tx.setTransactionTax(req.transactionTax());
        tx.setExchangeRate(req.exchangeRate());
        tx.setNotes(req.notes());

        return toResponse(txRepo.save(tx));
    }

    @Transactional
    public void deleteAssetTransaction(Long id) {
        AssetTransaction tx = txRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("AssetTransaction not found: " + id));
        tenantGuard.assertOwned(tx.getOwnerUserId());
        txRepo.delete(tx);
    }

    /** 依年度分組回彙總列表（年度新到舊）；owner 由 {@code @Filter} 自動縮。 */
    @Transactional(readOnly = true)
    public List<AssetTransactionDto.YearSummaryResponse> getAssetTransactionsByYear() {
        // TreeMap 逆序：年度新到舊
        TreeMap<Integer, List<AssetTransactionDto.AssetTransactionResponse>> byYear =
                new TreeMap<>(java.util.Comparator.reverseOrder());
        for (AssetTransaction tx : txRepo.findAllByOrderByTradeDateDesc()) {
            AssetTransactionDto.AssetTransactionResponse r = toResponse(tx);
            byYear.computeIfAbsent(r.year(), k -> new ArrayList<>()).add(r);
        }

        List<AssetTransactionDto.YearSummaryResponse> result = new ArrayList<>();
        for (var entry : byYear.entrySet()) {
            List<AssetTransactionDto.AssetTransactionResponse> records = entry.getValue();
            int buyCount = 0;
            int sellCount = 0;
            BigDecimal totalBuy = BigDecimal.ZERO;
            BigDecimal totalSell = BigDecimal.ZERO;
            for (AssetTransactionDto.AssetTransactionResponse r : records) {
                BigDecimal twd = r.amountTwd() != null ? r.amountTwd() : BigDecimal.ZERO;
                if ("賣".equals(r.transactionType())) {
                    sellCount++;
                    totalSell = totalSell.add(twd);
                } else {
                    buyCount++;
                    totalBuy = totalBuy.add(twd);
                }
            }
            result.add(new AssetTransactionDto.YearSummaryResponse(
                    entry.getKey(), buyCount, sellCount, totalBuy, totalSell, records));
        }
        return result;
    }

    /** 統一算 amountTwd／year。 */
    private AssetTransactionDto.AssetTransactionResponse toResponse(AssetTransaction tx) {
        BigDecimal amountTwd = tx.getAmount();
        if ("USD".equals(tx.getCurrency()) && tx.getExchangeRate() != null && tx.getAmount() != null) {
            amountTwd = tx.getAmount().multiply(tx.getExchangeRate());
        }
        return new AssetTransactionDto.AssetTransactionResponse(
                tx.getId(),
                tx.getTransactionType(),
                tx.getAssetType(),
                tx.getAssetName(),
                tx.getAssetCode(),
                tx.getMarket(),
                tx.getCurrency(),
                tx.getChannel(),
                tx.getTradeDate(),
                tx.getShares(),
                tx.getPrice(),
                tx.getAmount(),
                tx.getFee(),
                tx.getTransactionTax(),
                tx.getExchangeRate(),
                tx.getNotes(),
                amountTwd,
                tx.getYear());
    }
}
