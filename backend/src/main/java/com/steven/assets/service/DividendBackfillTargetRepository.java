package com.steven.assets.service;

import java.util.List;

/**
 * 「這次回補要跑哪些標的」的 data-access port（Requirement 94 / Task 357.3a）。
 *
 * <p>刻意只回傳一份<b>已排序、去重</b>的清單，不做任何過濾決策以外的事——分批、續跑與
 * 失敗記錄全屬 {@link DividendBackfillService} 的業務邏輯。</p>
 */
public interface DividendBackfillTargetRepository {

    /** 一檔待回補標的。{@link #key()} 是續跑 ledger 的唯一鍵。 */
    record Target(String code, String market) {
        public String key() {
            return market + "|" + code;
        }
    }

    /**
     * 全量回補對象：{@code stock_dividend_history} 已有列的標的 ∪ {@code stock} 主檔，
     * 依 {@code (market, code)} 遞增排序。
     *
     * <p><b>排序必須穩定</b>：續跑靠 ledger 比對 key，但分批的邊界與日誌的可讀性都依賴
     * 每次執行拿到同一個順序。</p>
     */
    List<Target> findBackfillTargets();
}
