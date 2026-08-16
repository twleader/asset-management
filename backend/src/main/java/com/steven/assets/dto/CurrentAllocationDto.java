package com.steven.assets.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 資產配置建議（Requirement 32）——使用者目前資產配置概覽（依最新 {@code asset_snapshot} 計算）。
 * 供前端「目前資產配置」render，且與產生建議時餵給 Claude 的現況為同一來源（避免各頁重算不一致）。
 */
public record CurrentAllocationDto(
        Long snapshotId,
        LocalDate snapshotDate,
        BigDecimal totalAssets,
        List<Item> items
) {
    /**
     * 一個資產類別的現況：金額（台幣）與占比（%）。
     *
     * <p>{@code subItems}（Requirement 82）：「股票」「信託基金」兩桶再細分成長型／收益型／短中長期債
     * 子類別（只含金額 &gt; 0 者）；「存款（現金）」恆為空陣列。</p>
     */
    public record Item(String assetClass, BigDecimal value, BigDecimal pct, List<SubItem> subItems) {}

    /** 一個子類別的現況：金額（台幣）與占「所屬頂層桶」的占比（%），非占資產總額。 */
    public record SubItem(String subClass, BigDecimal value, BigDecimal pct) {}

    /** 尚無任何快照時的占位。 */
    public static CurrentAllocationDto empty() {
        return new CurrentAllocationDto(null, null, null, List.of());
    }
}
