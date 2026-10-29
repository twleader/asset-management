package com.steven.assets.bff.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * 以同一身份與日期的 live-assets 覆蓋資產歷史最新列。
 * 台北業務日與市場交易日分離，Dashboard 與歷年資產共用同一 eligibility。
 */
public final class LiveAssetsOverlay {

    private LiveAssetsOverlay() {}

    public static void applyToLatest(List<Map<String, Object>> history, Map<String, Object> live) {
        applyToLatest(history, live, Clock.systemUTC());
    }

    @SuppressWarnings("unchecked")
    public static void applyToLatest(List<Map<String, Object>> history, Map<String, Object> live, Clock clock) {
        if (history == null || history.isEmpty() || live == null || live.isEmpty()) return;
        Map<String, Object> latest = history.get(history.size() - 1);
        boolean twToday = LatestSnapshotLiveEligibility.isEligible(latest, live, "台股", clock);
        boolean usToday = LatestSnapshotLiveEligibility.isEligible(latest, live, "美股", clock);
        boolean ukToday = LatestSnapshotLiveEligibility.isEligible(latest, live, "英股", clock);
        if (!twToday && !usToday && !ukToday) return;

        // 依市場彙總 live stock value（僅供「今日」市場使用）。
        BigDecimal liveTw = BigDecimal.ZERO;
        BigDecimal liveUs = BigDecimal.ZERO;
        BigDecimal liveUk = BigDecimal.ZERO;
        Object stocksObj = live.get("stocks");
        if (stocksObj instanceof List<?> stocksList) {
            for (Object o : stocksList) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> s = (Map<String, Object>) o;
                BigDecimal v = toBd(s.get("liveValue"));
                if (v == null) continue;
                if ("台股".equals(s.get("market"))) liveTw = liveTw.add(v);
                else if ("美股".equals(s.get("market"))) liveUs = liveUs.add(v);
                else if ("英股".equals(s.get("market"))) liveUk = liveUk.add(v);
            }
        }

        // 今日市場用 live，非今日市場保留 history 凍結值（= 基準日收盤）。
        BigDecimal twStock = twToday ? liveTw : toBdOr(latest.get("totalTwStockValue"), BigDecimal.ZERO);
        BigDecimal usStock = usToday ? liveUs : toBdOr(latest.get("totalUsStockValue"), BigDecimal.ZERO);
        BigDecimal ukStock = ukToday ? liveUk : toBdOr(latest.get("totalUkStockValue"), BigDecimal.ZERO);
        BigDecimal stockValue = twStock.add(usStock).add(ukStock);
        BigDecimal totalAssets = toBdOr(latest.get("totalDeposit"), BigDecimal.ZERO)
                .add(toBdOr(latest.get("totalFundValue"), BigDecimal.ZERO))
                .add(stockValue);

        latest.put("totalTwStockValue", twStock);
        latest.put("totalUsStockValue", usStock);
        latest.put("totalUkStockValue", ukStock);
        latest.put("totalStockValue", stockValue);
        latest.put("totalAssets", totalAssets);

        // 投資比例（基金 + 股票）/ 總資產
        BigDecimal fund = toBdOr(latest.get("totalFundValue"), BigDecimal.ZERO);
        if (totalAssets.compareTo(BigDecimal.ZERO) > 0) {
            latest.put("investmentRate", fund.add(stockValue)
                    .divide(totalAssets, 6, RoundingMode.HALF_UP));
        }

        // 增加金額 / 增幅 vs 前一筆
        if (history.size() >= 2) {
            BigDecimal prevTotal = toBd(history.get(history.size() - 2).get("totalAssets"));
            if (prevTotal != null) {
                BigDecimal increase = totalAssets.subtract(prevTotal);
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
