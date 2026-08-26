package com.steven.assets.repository;

import com.steven.assets.model.AssetTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
