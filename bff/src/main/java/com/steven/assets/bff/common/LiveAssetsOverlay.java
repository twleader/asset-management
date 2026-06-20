package com.steven.assets.bff.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 共用：以 /api/market-data/live-assets 覆蓋資產歷史「最新一筆」的股票現值與資產總計。
 *
 * 休市時 live-assets 後端會 fallback 至 Redis 內最後一筆收盤價，故覆蓋後即為收盤價口徑。
 * Dashboard 與 AssetHistory 兩頁的 history 皆套用本邏輯，確保「同義欄位 = 同一 business service
 * 同一算法」—— 兩頁的最新一筆 totalStockValue / totalAssets / 增幅必為同值。
 *
 * 為什麼不直接用快照儲存的 asset_snapshot.total_*：建檔當下凍結的暫定價會與較新的收盤價脫鉤，
 * 導致台股總值偏差（見 spec Task 108 / 109）。
 */
public final class LiveAssetsOverlay {

    private LiveAssetsOverlay() {}

    /**
     * 若 history 最新一筆的 snapshotDate == live.snapshotDate，就以 live 值就地覆蓋該筆的
     * totalTwStockValue / totalUsStockValue / totalUkStockValue / totalStockValue / totalAssets，
     * 並連動 investmentRate 與（對前一筆的）increase / increaseRate。否則不動。
     */
    @SuppressWarnings("unchecked")
    public static void applyToLatest(List<Map<String, Object>> history, Map<String, Object> live) {
        if (history == null || history.isEmpty() || live == null || live.isEmpty()) return;
        Map<String, Object> latest = history.get(history.size() - 1);
        Object latestDate = latest.get("snapshotDate");
        Object liveDate = live.get("snapshotDate");
        if (latestDate == null || liveDate == null
                || !latestDate.toString().equals(liveDate.toString())) return;

        // 依市場彙總 live stock value：未開盤的市場仍會回 Redis 內最後一筆收盤價，不另做 market open 檢查。
        BigDecimal twStock = BigDecimal.ZERO;
        BigDecimal usStock = BigDecimal.ZERO;
        BigDecimal ukStock = BigDecimal.ZERO;
        Object stocksObj = live.get("stocks");
        if (stocksObj instanceof List<?> stocksList) {
            for (Object o : stocksList) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> s = (Map<String, Object>) o;
                BigDecimal v = toBd(s.get("liveValue"));
                if (v == null) continue;
                if ("台股".equals(s.get("market"))) twStock = twStock.add(v);
                else if ("美股".equals(s.get("market"))) usStock = usStock.add(v);
                else if ("英股".equals(s.get("market"))) ukStock = ukStock.add(v);
            }
        }
        BigDecimal liveStockValue = toBdOr(live.get("liveStockValue"), twStock.add(usStock).add(ukStock));
        BigDecimal liveTotalAssets = toBdOr(live.get("liveTotalAssets"),
                toBdOr(latest.get("totalDeposit"), BigDecimal.ZERO)
                        .add(toBdOr(latest.get("totalFundValue"), BigDecimal.ZERO))
                        .add(liveStockValue));

        latest.put("totalTwStockValue", twStock);
        latest.put("totalUsStockValue", usStock);
        latest.put("totalUkStockValue", ukStock);
        latest.put("totalStockValue", liveStockValue);
        latest.put("totalAssets", liveTotalAssets);

        // 投資比例（基金 + 股票）/ 總資產
        BigDecimal fund = toBdOr(latest.get("totalFundValue"), BigDecimal.ZERO);
        if (liveTotalAssets.compareTo(BigDecimal.ZERO) > 0) {
            latest.put("investmentRate", fund.add(liveStockValue)
                    .divide(liveTotalAssets, 6, RoundingMode.HALF_UP));
        }

        // 增加金額 / 增幅 vs 前一筆
        if (history.size() >= 2) {
            BigDecimal prevTotal = toBd(history.get(history.size() - 2).get("totalAssets"));
            if (prevTotal != null) {
                BigDecimal increase = liveTotalAssets.subtract(prevTotal);
                latest.put("increase", increase);
                if (prevTotal.compareTo(BigDecimal.ZERO) != 0) {
                    latest.put("increaseRate", increase.divide(prevTotal, 6, RoundingMode.HALF_UP));
                }
            }
        }
    }

    static BigDecimal toBd(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return null; }
    }

    static BigDecimal toBdOr(Object v, BigDecimal fallback) {
        BigDecimal bd = toBd(v);
        return bd != null ? bd : fallback;
    }
}
