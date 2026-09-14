package com.steven.assets.repository;

import com.steven.assets.model.AssetTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 資產交易紀錄 repository（Requirement 49 / Task 237）。
 *
 * <p>HTTP 情境下 {@code @Filter(ownerFilter)} 由 {@link com.steven.assets.security.TenantFilterAspect}
 * 自動套用，故列表／匯出查詢無需顯式帶 owner；owner 由 filter 自動縮到本人。
 */
@Repository
public interface AssetTransactionRepository extends JpaRepository<AssetTransaction, Long> {

    /**
     * 列表與匯出用，依交易日期新到舊；owner 由 {@code @Filter} 自動縮。
     *
     * <p>年度篩選（比照已實現損益頁）由前端自 {@code getAssetTransactionsByYear()} 回的各年度
     * {@code records} 於客戶端切換，故 repository 不另設日期區間查詢。
     */
    List<AssetTransaction> findAllByOrderByTradeDateDesc();

    /** Public readonly history fixes equal-date ordering by id so repeated reads are deterministic. */
    List<AssetTransaction> findAllByOrderByTradeDateDescIdDesc();

    /**
     * Idempotency check for the Fubon filled-trade sync (Requirement 120 / Task 385). Owner is
     * passed explicitly rather than relying on the tenant filter — this method is called both by
     * the background scheduler (no request context, filter never applies) and by the manual
     * {@code /internal/brokers/fubon/trade-sync} endpoint, which has an HTTP request context but
     * no {@code X-User-*} tenant identity. In that second case {@link
     * com.steven.assets.security.TenantFilterAspect} fail-closes {@code ownerFilter} to an
     * impossible owner id, which would make a normal derived-query call on this {@code @Filter}-
     * annotated entity always return false regardless of the real data. A native query bypasses
     * Hibernate's {@code @Filter} entirely so the explicit {@code ownerUserId} parameter is the
     * only thing that decides the result, on every calling path.
     */
    @Query(value = """
        SELECT EXISTS (
            SELECT 1 FROM asset_transaction
            WHERE owner_user_id = :ownerUserId AND broker_filled_no = :brokerFilledNo
        )
        """, nativeQuery = true)
    boolean existsByOwnerUserIdAndBrokerFilledNo(Long ownerUserId, String brokerFilledNo);

    /**
     * Fubon settlement notes are an informative view of the same owner's already-persisted
     * filled-trade ledger. This internal writer has no HTTP tenant identity, so a native query
     * with every ownership/source/date/direction predicate is required to bypass ownerFilter
     * without admitting manual rows or another owner's transactions.
     */
    @Query(value = """
        SELECT * FROM asset_transaction
        WHERE owner_user_id = :ownerUserId
          AND source = 'FUBON_SYNC'
          AND trade_date = :tradeDate
          AND transaction_type = :transactionType
        ORDER BY asset_code ASC NULLS LAST, asset_name ASC NULLS LAST, id ASC
        """, nativeQuery = true)
    List<AssetTransaction> findFubonSyncedDetailsForTransitNote(
            @Param("ownerUserId") Long ownerUserId,
            @Param("tradeDate") LocalDate tradeDate,
            @Param("transactionType") String transactionType);

    /**
     * The Fubon batch writer alone calls this inside its transaction. Only the existing
     * owner/filled-no partial unique index is an expected conflict; every other database error
     * must abort that transaction. Never update a ledger row the user may already have edited.
     */
    @Modifying
    @Query(value = """
        INSERT INTO asset_transaction (
            owner_user_id, broker_filled_no, transaction_type, asset_type, asset_name,
            asset_code, market, currency, channel, trade_date, shares, price, amount,
            fee, transaction_tax, exchange_rate, notes, source
        ) VALUES (
            :ownerUserId, :brokerFilledNo, :transactionType, '股票', :assetName,
            :assetCode, '台股', 'TWD', '富邦證券', :tradeDate, :shares, :price, :amount,
            NULL, NULL, NULL, NULL, 'FUBON_SYNC'
        )
        ON CONFLICT (owner_user_id, broker_filled_no)
            WHERE broker_filled_no IS NOT NULL DO NOTHING
        """, nativeQuery = true)
    int insertFubonTradeIfAbsent(
            @Param("ownerUserId") Long ownerUserId,
            @Param("brokerFilledNo") String brokerFilledNo,
            @Param("transactionType") String transactionType,
            @Param("assetName") String assetName,
            @Param("assetCode") String assetCode,
            @Param("tradeDate") LocalDate tradeDate,
            @Param("shares") BigDecimal shares,
            @Param("price") BigDecimal price,
            @Param("amount") BigDecimal amount);
}
