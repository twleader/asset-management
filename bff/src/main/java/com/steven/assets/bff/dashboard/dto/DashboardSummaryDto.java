package com.steven.assets.bff.dashboard.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Aggregated response for the Dashboard page.
 * Combines data from multiple business-service calls into a single BFF response,
 * reducing the number of round-trips the frontend needs to make.
 */
@Data
public class DashboardSummaryDto {
    /** All snapshots (summary list) — same as GET /api/snapshots */
    private List<Map<String, Object>> snapshots;

    /** Historical trend data — same as GET /api/snapshots/history */
    private List<Map<String, Object>> history;

    /** Full detail of the latest snapshot — same as GET /api/snapshots/{id} */
    private Map<String, Object> latestSnapshotDetail;

    /** All cached stock prices keyed by "{market}_{code}" */
    private List<Map<String, Object>> stockPrices;

    /** Current market open/closed status */
    private Map<String, Object> marketStatus;

    /**
     * 預先彙總的持股清單（依 stockCode + market 合併多筆 broker rows），
     * 已附上 stockPrice (快照日歷史收盤價，原幣別) 與 profit / profitRate。
     * 前端直接 render，無需再做計算。
     */
    private List<Map<String, Object>> mergedStocks;

    /**
     * 與「歷年資產管理」共用的 /api/market-data/live-assets 回應：
     * 即時持倉估值（liveStockValue / liveTotalAssets / per-stock liveValue 等）。
     * 即使收盤後 Redis cache 過期，後端會 fallback 至 stock_price_history（最近一筆收盤價），
     * 故此欄位永遠有值。前端 KPI / 每股 row 應一律以此覆蓋快照凍結值，
     * 避免 Dashboard 與「歷年資產管理」今日列出現不同的「資產總計」。
     */
    private Map<String, Object> liveAssets;
}
