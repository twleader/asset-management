package com.steven.assets.bff.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 共用：以 /api/market-data/live-assets 覆蓋資產歷史「最新一筆」的股票現值與資產總計。
 *
 * <p><b>per-market 基準日閘門</b>（{@link SnapshotEnricher#isCurrentBasedate}）：只有「最新快照
 * 的 snapshotDate == 該市場時區今日」的市場才用 live 值覆蓋；其餘市場一律保留該快照凍結的收盤值
 * （{@code totalTwStockValue / totalUsStockValue / totalUkStockValue}）。
 *
 * <p>為什麼要 per-market 閘門：{@code live-assets} 後端是讀 Redis（最新一筆 tick / 收盤），當「最新
 * 快照日」< 今日（昨日快照、今日尚未建檔）且已有更新一個交易日的收盤時，直接覆蓋會把<b>較新交易日</b>
 * 的收盤洩漏到一個過去的基準日上（例：最新快照 6/23 被刪掉 6/24 後，整列被 6/24 收盤蓋掉）。
 * 而快照建檔當下凍結的 {@code stock_holding.currentValue} 對「已收盤定案的過去日期」本來就 == 該日收盤，
 * 故過去日期保留凍結值即為正解，不需也不應 overlay。
 *
 * <p>正確情境：
 * <ul>
 *   <li>最新快照 == 今日（建檔當天，盤中可能是暫定價）→ 各市場用 live 覆蓋，refresh 成最新成交 / 收盤。</li>
 *   <li>最新快照 &lt; 今日 → 三市場皆非今日 → 完全不覆蓋 → 顯示基準日收盤。</li>
 *   <li>跨午夜（TW 已隔日、美股仍盤中）→ 台股 frozen、美股 live，per-market 各自判斷。</li>
 * </ul>
 *
 * <p>{@code DashboardBffController.summary} 與 {@code AssetHistoryBffController} 兩支 BFF 皆呼叫此工具，
 * 並與前端 {@code DashboardView.liveLatest} 採同一 per-market 規則 → 三處 history 最新列「同義欄位同值」。
 */
public final class LiveAssetsOverlay {

    private LiveAssetsOverlay() {}

    /**
     * per-market 覆蓋 history 最新一筆：僅「basedate == 該市場時區今日」的市場用 live，其餘保留凍結收盤。
     * 三市場皆非今日（最新一筆為過去日期）時完全不動，使其顯示基準日收盤。
     */
    @SuppressWarnings("unchecked")
    public static void applyToLatest(List<Map<String, Object>> history, Map<String, Object> live) {
        if (history == null || history.isEmpty() || live == null || live.isEmpty()) return;
        Map<String, Object> latest = history.get(history.size() - 1);
        Object latestDate = latest.get("snapshotDate");
        Object liveDate = live.get("snapshotDate");
        // live-assets 必須指向同一筆最新快照才採用（防呆）
        if (latestDate == null || liveDate == null
                || !latestDate.toString().equals(liveDate.toString())) return;

        LocalDate basedate;
        try { basedate = LocalDate.parse(latestDate.toString()); }
        catch (Exception e) { return; }

        // per-market 基準日閘門
        boolean twToday = SnapshotEnricher.isCurrentBasedate(basedate, "台股");
        boolean usToday = SnapshotEnricher.isCurrentBasedate(basedate, "美股");
        boolean ukToday = SnapshotEnricher.isCurrentBasedate(basedate, "英股");
        // 最新一筆非任何市場的今日（過去日期）→ 保留快照凍結收盤值，不覆蓋。
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
