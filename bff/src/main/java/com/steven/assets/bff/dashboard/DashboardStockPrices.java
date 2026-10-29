package com.steven.assets.bff.dashboard;

import com.steven.assets.bff.common.LatestSnapshotLiveEligibility;
import com.steven.assets.bff.common.SnapshotEnricher;

import java.time.Clock;
import java.time.LocalDate;
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

    /** 同一輪身份合格才採真正 live tuple，凍結來源不能繼承其他快照的 live 值。 */
    static List<Map<String, Object>> forSnapshot(Map<String, Object> snapshot, Map<String, Object> live,
                                                SnapshotEnricher.SnapshotCloseData close, Clock clock) {
        Object date = snapshot.get("snapshotDate");
        LocalDate basedate = date == null ? null : LocalDate.parse(date.toString());
        Map<String, Map<String, Object>> prices = new LinkedHashMap<>();
        for (Map<String, Object> row : SnapshotEnricher.closeMapToPriceList(close.closeMap(), basedate)) {
            String key = key(row);
            Map<String, Object> display = close.displayRows().get(key);
            // prices-on-date 在市場當日也可能回 LIVE；不同身份不可把它偽裝成 frozen close。
            if (display != null && "LIVE".equals(display.get("quoteStatus"))
                    && !LatestSnapshotLiveEligibility.isEligible(snapshot, live,
                    SnapshotEnricher.asString(row.get("market")), clock)) continue;
            Map<String, Object> change = close.changeMap().get(key);
            if (change != null) {
                row.put("priceChange", change.get("priceChange"));
                row.put("changePercent", change.get("changePercent"));
            }
            prices.put(key, row);
        }
        // 當日歷史查詢的真正收盤／等待狀態可保留原交易日；LIVE 仍須同一身份授權。
        close.displayRows().forEach((key, row) -> {
            String market = SnapshotEnricher.asString(row.get("market"));
            Object status = row.get("quoteStatus");
            boolean historicalState = "VERIFIED_CLOSE".equals(status) || "PREVIOUS_CLOSE".equals(status)
                    || "CLOSE_PENDING".equals(status);
            if (LatestSnapshotLiveEligibility.isCurrentDate(date, market, clock)
                    && (historicalState || LatestSnapshotLiveEligibility.isEligible(snapshot, live, market, clock))) {
                prices.put(key, row);
            }
        });
        for (Map<String, Object> row : fromLiveAssets(live)) {
            String market = SnapshotEnricher.asString(row.get("market"));
            if (LatestSnapshotLiveEligibility.isEligible(snapshot, live, market, clock)) {
                prices.put(key(row), row);
            }
        }
        return new ArrayList<>(prices.values());
    }

    private static String key(Map<String, Object> row) {
        return row.get("market") + "_" + row.get("stockCode");
    }

}
