package com.steven.assets.bff.dashboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 同一 live-assets 回應的純投影，保留各股票自己的報價時間。 */
final class DashboardStockPrices {
    private DashboardStockPrices() {}

    /**
     * 將同一輪 live-assets 持股報價投影回 dashboard 既有的 stockPrices 契約。
     * 每筆 updatedAt 必須保留該筆 LivePrice 的時間，不能以 root 的最大時間覆蓋。
     */
    public static List<Map<String, Object>> fromLiveAssets(Map<String, Object> liveAssets) {
        if (liveAssets == null || !(liveAssets.get("stocks") instanceof List<?> stocks)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> prices = new ArrayList<>();
        for (Object item : stocks) {
            if (!(item instanceof Map<?, ?> stock)) continue;
            Map<String, Object> projected = new LinkedHashMap<>();
            projected.put("stockCode", stock.get("stockCode"));
            projected.put("stockName", stock.get("stockName"));
            projected.put("market", stock.get("market"));
            projected.put("price", stock.get("currentPrice"));
            projected.put("previousClose", stock.get("previousClose"));
            projected.put("priceChange", stock.get("priceChange"));
            projected.put("changePercent", stock.get("changePercent"));
            projected.put("tradingDate", stock.get("tradingDate"));
            projected.put("updatedAt", stock.get("updatedAt"));
            projected.put("closed", stock.get("closed"));
            projected.put("source", stock.get("source"));
            projected.put("quoteStatus", stock.get("quoteStatus"));
            prices.add(projected);
        }
        prices.sort(Comparator.comparing((Map<String, Object> row) -> String.valueOf(row.get("market")))
                .thenComparing(row -> String.valueOf(row.get("stockCode"))));
        return prices;
    }

}
