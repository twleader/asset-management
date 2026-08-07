package com.steven.assets.bff.common;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 共用的快照 enrichment 邏輯：
 *  - investmentCostOriginal：每筆持股注入原幣別成本（美股 USD / 台股 TWD）
 *  - 取得快照基準日的歷史收盤價（USD/TWD 原幣別）
 *  - 依 stockCode + market 合併多筆 broker rows，預先計算前端表格所需欄位
 *
 * 各頁的 BFF controller 都應使用本工具，確保「同義欄位 = 同一邏輯」。
 */
@Component
@RequiredArgsConstructor
public class SnapshotEnricher {

    private final WebClient businessServicesClient;

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};

    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final ZoneId US_ZONE = ZoneId.of("America/New_York");
    private static final ZoneId LON_ZONE = ZoneId.of("Europe/London");

    /**
     * 「股價基準日規則」（per-market）：當基準日 == 該市場時區的今日，才回傳即時價格（會持續變動）；
     * 其他情形一律回傳該基準日的收盤價（鎖定在當天）。
     *
     * 為什麼分市場：使用者多在 TW 收盤後建快照（basedate = TW 那天），但美股 session 跨午夜
     * （TW 21:30 → 隔日 05:00），TW 過午夜後 basedate（昨天）== TW 今日（今天）會誤判，導致美股盤中
     * 顯示前一交易日收盤。改用 basedate == 美東今日 才能正確涵蓋 TW 凌晨對應的美股盤中。
     * EST/EDT 由 JVM `ZoneId` 自動處理。
     */
    public static boolean isCurrentBasedate(LocalDate basedate, String market) {
        if (basedate == null) return false;
        ZoneId zone;
        if ("美股".equals(market)) zone = US_ZONE;
        else if ("英股".equals(market)) zone = LON_ZONE;
        else zone = TW_ZONE;
        return basedate.equals(LocalDate.now(zone));
    }

    /** 將 closeMap（基準日收盤價）轉換成與即時 /api/market-data/prices 同形狀的 list，priceChange 留空。 */
    public static List<Map<String, Object>> closeMapToPriceList(
            Map<String, BigDecimal> closeMap, LocalDate basedate) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : closeMap.entrySet()) {
            String[] mc = e.getKey().split("_", 2);
            if (mc.length != 2) continue;
            out.add(buildSnapshotPrice(mc[0], mc[1], e.getValue(), basedate));
        }
        return out;
    }

    /**
     * Per-market 合併 live 即時價與 basedate 收盤價：
     *  - basedate == 該市場時區的今日 → 保留該市場的 live price
     *  - 否則 → 該市場的所有 live price 換成 closeMap 中的 basedate 收盤價
     * Live cache 沒涵蓋但 closeMap 有的（可能是觀察清單以外的持股）一併補上。
     */
    public static List<Map<String, Object>> mergePerMarketPrices(
            LocalDate basedate,
            List<Map<String, Object>> livePrices,
            Map<String, BigDecimal> closeMap) {
        return mergePerMarketPrices(basedate, livePrices, closeMap, Collections.emptyMap());
    }

    /**
     * 同上，但 frozen（非該市場當日）的收盤價 entry 額外帶入 changeMap 中「該收盤日 vs 前一交易日收盤」
     * 的當日漲跌（priceChange / changePercent），使收盤 / 週末頁的「股價/漲跌(%)」欄仍能顯示漲跌。
     */
    public static List<Map<String, Object>> mergePerMarketPrices(
            LocalDate basedate,
            List<Map<String, Object>> livePrices,
            Map<String, BigDecimal> closeMap,
            Map<String, Map<String, Object>> changeMap) {
        return mergePerMarketPrices(
                basedate, livePrices, closeMap, changeMap, Collections.emptyMap());
    }

    /**
     * 同上，並以 prices-on-date 的完整 display row（包含 {@code CLOSE_PENDING + null price}）
     * 補齊 live prices 短暫失敗或缺 key 的情形。不能只傳 closeMap，因為 null price 不會進 map，
     * 會讓前端倒退顯示快照中的舊價。
     */
    public static List<Map<String, Object>> mergePerMarketPrices(
            LocalDate basedate,
            List<Map<String, Object>> livePrices,
            Map<String, BigDecimal> closeMap,
            Map<String, Map<String, Object>> changeMap,
            Map<String, Map<String, Object>> displayRows) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> p : livePrices) {
            String code = asString(p.get("stockCode"));
            String market = asString(p.get("market"));
            if (code == null || market == null) continue;
            String key = market + "_" + code;
            seen.add(key);
            if (isCurrentBasedate(basedate, market)) {
                out.add(p);
                continue;
            }
            BigDecimal close = closeMap.get(key);
            if (close != null) {
                out.add(buildSnapshotPrice(market, code, close, basedate, changeMap.get(key)));
            } else {
                out.add(p);
            }
        }
        for (Map.Entry<String, Map<String, Object>> e : displayRows.entrySet()) {
            if (seen.contains(e.getKey())) continue;
            Map<String, Object> row = e.getValue();
            String market = asString(row.get("market"));
            if (market == null || !isCurrentBasedate(basedate, market)) continue;
            out.add(row);
            seen.add(e.getKey());
        }
        for (Map.Entry<String, BigDecimal> e : closeMap.entrySet()) {
            if (seen.contains(e.getKey())) continue;
            String[] mc = e.getKey().split("_", 2);
            if (mc.length != 2) continue;
            if (isCurrentBasedate(basedate, mc[0])) continue;
            out.add(buildSnapshotPrice(mc[0], mc[1], e.getValue(), basedate, changeMap.get(e.getKey())));
        }
        return out;
    }

    private static Map<String, Object> buildSnapshotPrice(
            String market, String code, BigDecimal price, LocalDate basedate) {
        return buildSnapshotPrice(market, code, price, basedate, null);
    }

    private static Map<String, Object> buildSnapshotPrice(
            String market, String code, BigDecimal price, LocalDate basedate,
            Map<String, Object> change) {
        Map<String, Object> p = new HashMap<>();
        p.put("market", market);
        p.put("stockCode", code);
        p.put("price", price);
        p.put("priceChange", change != null ? change.get("priceChange") : null);
        p.put("changePercent", change != null ? change.get("changePercent") : null);
        p.put("tradingDate", basedate != null ? basedate.toString() : null);
        p.put("closed", true);
        p.put("quoteStatus", "PREVIOUS_CLOSE");
        return p;
    }

    /** 為每筆持股加上 investmentCostOriginal（買入均價計算用，原幣別）。 */
    @SuppressWarnings("unchecked")
    public void enrichInvestmentCostOriginal(Map<String, Object> detail) {
        Object stocksObj = detail.get("stocks");
        if (!(stocksObj instanceof List<?> list)) return;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            Map<String, Object> stock = (Map<String, Object>) m;
            BigDecimal cost = toBigDecimal(stock.get("investmentCost"));
            if (cost == null) continue;
            String market = asString(stock.get("market"));
            String currency = asString(stock.get("currency"));
            BigDecimal rate = toBigDecimal(stock.get("transactionExchangeRate"));

            BigDecimal original = cost;
            // 美股 / 英股（USD 計價 UCITS）：若 cost 是 TWD（rate > 0），反算原幣 USD
            if (("美股".equals(market) || "英股".equals(market)) && !"USD".equals(currency)
                    && rate != null && rate.compareTo(BigDecimal.ZERO) > 0) {
                original = cost.divide(rate, 6, RoundingMode.HALF_UP);
            }
            stock.put("investmentCostOriginal", original);
        }
    }

    /**
     * 以快照日期向 business-services 取得每檔股票的歷史收盤價（USD/TWD 原幣別）。
     * 回傳 map: "市場_代號" -> price。
     */
    public Mono<Map<String, BigDecimal>> fetchSnapshotClosePrices(Map<String, Object> detail) {
        return fetchSnapshotPriceRows(detail).map(prices -> {
            Map<String, BigDecimal> map = new HashMap<>();
            for (Map<String, Object> p : prices) {
                String code = asString(p.get("stockCode"));
                String market = asString(p.get("market"));
                BigDecimal price = toBigDecimal(p.get("price"));
                if (code != null && market != null && price != null) {
                    map.put(market + "_" + code, price);
                }
            }
            return map;
        });
    }

    /** closeMap + per-key 當日漲跌一次取回（key -> {priceChange, changePercent}）。
     *  與 {@link #fetchSnapshotClosePrices} 同一支 business API、同一次 HTTP，供 Dashboard 摘要
     *  在收盤 / 週末（frozen）時「股價/漲跌(%)」欄仍顯示漲跌，避免另發一次查詢。 */
    public Mono<SnapshotCloseData> fetchSnapshotCloseData(Map<String, Object> detail) {
        return fetchSnapshotPriceRows(detail).map(prices -> {
            Map<String, BigDecimal> closeMap = new HashMap<>();
            Map<String, Map<String, Object>> changeMap = new HashMap<>();
            Map<String, Map<String, Object>> displayRows = new HashMap<>();
            for (Map<String, Object> p : prices) {
                String code = asString(p.get("stockCode"));
                String market = asString(p.get("market"));
                BigDecimal price = toBigDecimal(p.get("price"));
                if (code == null || market == null) continue;
                String key = market + "_" + code;
                displayRows.put(key, new HashMap<>(p));
                if (price == null) continue;
                closeMap.put(key, price);
                BigDecimal pc = toBigDecimal(p.get("priceChange"));
                BigDecimal cp = toBigDecimal(p.get("changePercent"));
                if (pc != null && cp != null) {
                    Map<String, Object> ch = new HashMap<>();
                    ch.put("priceChange", pc);
                    ch.put("changePercent", cp);
                    changeMap.put(key, ch);
                }
            }
            return new SnapshotCloseData(closeMap, changeMap, displayRows);
        });
    }

    /** {@link #fetchSnapshotCloseData} 回傳結構：收盤價、當日漲跌與含空價 status 的完整 display rows。 */
    public record SnapshotCloseData(Map<String, BigDecimal> closeMap,
                                    Map<String, Map<String, Object>> changeMap,
                                    Map<String, Map<String, Object>> displayRows) {}

    /** 內部：POST /history/prices-on-date 取回原始 price rows（含 price / priceChange / changePercent / tradingDate）。 */
    @SuppressWarnings("unchecked")
    private Mono<List<Map<String, Object>>> fetchSnapshotPriceRows(Map<String, Object> detail) {
        Object stocksObj = detail.get("stocks");
        Object dateObj = detail.get("snapshotDate");
        if (!(stocksObj instanceof List<?> list) || dateObj == null || list.isEmpty()) {
            return Mono.just(Collections.emptyList());
        }
        List<Map<String, String>> body = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            Map<String, Object> stock = (Map<String, Object>) m;
            String code = asString(stock.get("stockCode"));
            String market = asString(stock.get("market"));
            if (code == null || market == null) continue;
            String key = market + "_" + code;
            if (!seen.add(key)) continue;
            body.add(Map.of("code", code, "market", market));
        }
        if (body.isEmpty()) return Mono.just(Collections.emptyList());

        return businessServicesClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/market-data/history/prices-on-date")
                        .queryParam("date", dateObj.toString())
                        .build())
                .bodyValue(body)
                .retrieve()
                .bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList());
    }

    /**
     * 依 stockCode + market 合併多筆 broker rows，預先計算前端表格所需欄位。
     * 預設不依收盤價重算現值（維持快照儲存值），供會「存檔」的編輯頁
     * （SnapshotDetail / SnapshotForm，以 unitPriceTwd 反推 broker currentValue）使用。
     * @param includeBrokerRows true 時保留 brokerRows 陣列（供編輯頁使用）
     */
    public List<Map<String, Object>> buildMergedStocks(
            Map<String, Object> detail,
            Map<String, BigDecimal> closeMap,
            boolean includeBrokerRows) {
        return buildMergedStocks(detail, closeMap, includeBrokerRows, false);
    }

    /**
     * 依 stockCode + market 合併多筆 broker rows，預先計算前端表格所需欄位。
     * @param includeBrokerRows true 時保留 brokerRows 陣列（供編輯頁使用）
     * @param revalueFromClose  true 時以 closeMap（stock_price_history 收盤價，與「股價」欄同源）
     *                          重算 currentValue / estimatedDividend，使「現值 = 股價 × 股數」自洽。
     *                          僅唯讀頁（Dashboard）使用；會存檔的編輯頁須傳 false 以免覆寫快照凍結值。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> buildMergedStocks(
            Map<String, Object> detail,
            Map<String, BigDecimal> closeMap,
            boolean includeBrokerRows,
            boolean revalueFromClose) {
        Object stocksObj = detail.get("stocks");
        if (!(stocksObj instanceof List<?> list) || list.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            Map<String, Object> s = (Map<String, Object>) m;
            String code = asString(s.get("stockCode"));
            String market = asString(s.get("market"));
            if (code == null || market == null) continue;
            String key = market + "_" + code;

            Map<String, Object> g = grouped.computeIfAbsent(key, k -> {
                Map<String, Object> init = new HashMap<>();
                init.put("stockCode", code);
                init.put("stockName", asString(s.get("stockName")));
                init.put("market", market);
                init.put("shares", BigDecimal.ZERO);
                init.put("investmentCost", BigDecimal.ZERO);
                init.put("investmentCostOriginal", BigDecimal.ZERO);
                init.put("currentValue", BigDecimal.ZERO);
                init.put("estimatedDividend", BigDecimal.ZERO);
                init.put("dividendRate", null);
                init.put("displayOrder", null);
                if (includeBrokerRows) init.put("brokerRows", new ArrayList<Map<String, Object>>());
                return init;
            });

            BigDecimal sh = toBigDecimal(s.get("shares"));
            BigDecimal costTwd = toBigDecimal(s.get("investmentCostTwd"));
            if (costTwd == null) costTwd = toBigDecimal(s.get("investmentCost"));
            BigDecimal costOrig = toBigDecimal(s.get("investmentCostOriginal"));
            if (costOrig == null) costOrig = toBigDecimal(s.get("investmentCost"));
            BigDecimal cv = toBigDecimal(s.get("currentValue"));
            BigDecimal ed = toBigDecimal(s.get("estimatedDividend"));

            g.put("shares", addBd(g.get("shares"), sh));
            g.put("investmentCost", addBd(g.get("investmentCost"), costTwd));
            g.put("investmentCostOriginal", addBd(g.get("investmentCostOriginal"), costOrig));
            g.put("currentValue", addBd(g.get("currentValue"), cv));
            g.put("estimatedDividend", addBd(g.get("estimatedDividend"), ed));

            if (g.get("dividendRate") == null) {
                BigDecimal dr = toBigDecimal(s.get("dividendRate"));
                if (dr != null) g.put("dividendRate", dr);
            }
            if (g.get("displayOrder") == null && s.get("displayOrder") != null) {
                g.put("displayOrder", s.get("displayOrder"));
            }
            if (g.get("stockName") == null) {
                g.put("stockName", asString(s.get("stockName")));
            }

            if (includeBrokerRows) {
                Map<String, Object> br = new HashMap<>();
                br.put("brokerId", s.get("brokerId"));
                br.put("brokerDisplayName", s.get("brokerDisplayName"));
                br.put("shares", sh);
                br.put("currency", s.get("currency"));
                br.put("investmentCost", costTwd);
                br.put("avgCost", sh != null && sh.compareTo(BigDecimal.ZERO) > 0 && costTwd != null
                        ? costTwd.divide(sh, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO);
                br.put("originalCurrencyValue", s.get("originalCurrencyValue"));
                br.put("storedCurrentValue", cv);
                ((List<Map<String, Object>>) g.get("brokerRows")).add(br);
            }
        }

        BigDecimal fxRate = toBigDecimal(detail.get("usdExchangeRate"));
        // 收盤價重算只在「基準日 == 該市場時區今日」時套用（per-market）。過去日期的快照其儲存
        // currentValue 已是該日定案收盤值（且 revalue 在缺收盤/匯率時不可靠），一律保留 stored，
        // 與「歷年資產管理」（讀 stored）逐欄同源（Task 122）。
        LocalDate basedate = null;
        Object basedateObj = detail.get("snapshotDate");
        if (basedateObj != null) {
            try { basedate = LocalDate.parse(basedateObj.toString()); } catch (Exception ignored) {}
        }
        List<Map<String, Object>> result = new ArrayList<>(grouped.values());
        for (Map<String, Object> g : result) {
            String market = asString(g.get("market"));
            String key = market + "_" + g.get("stockCode");
            BigDecimal closePrice = closeMap.get(key);
            g.put("stockPrice", closePrice);

            BigDecimal sh = (BigDecimal) g.get("shares");

            // 依 stock_price_history 收盤價（closeMap，與「股價」欄同源）重算現值，使「現值 = 股價 × 股數」
            // 自洽；否則快照當天建檔的 currentValue（盤中暫定價）會與當日收盤脫鉤，導致台股總值偏差。
            // 美股／英股收盤價為原幣（USD），乘快照匯率換算台幣，與前端 overlayLivePrice 同一套換算。
            // 僅在「基準日 == 該市場時區今日」時重算（per-market 閘門，Task 122）：過去日期的快照
            // currentValue 已是定案收盤值，保留 stored 與「歷年資產管理」同源。
            // revalueFromClose=false（編輯頁）時略過，維持儲存值供 broker row 以 unitPriceTwd 反推存檔。
            if (revalueFromClose && isCurrentBasedate(basedate, market)
                    && closePrice != null && sh != null && sh.signum() > 0) {
                BigDecimal priceTwd = ("美股".equals(market) || "英股".equals(market))
                        ? (fxRate != null ? closePrice.multiply(fxRate) : null)
                        : closePrice;
                if (priceTwd != null) {
                    BigDecimal revaluedCv = sh.multiply(priceTwd).setScale(4, RoundingMode.HALF_UP);
                    g.put("currentValue", revaluedCv);
                    BigDecimal dr = toBigDecimal(g.get("dividendRate"));
                    if (dr != null && dr.signum() > 0) {
                        g.put("estimatedDividend",
                                revaluedCv.multiply(dr).setScale(4, RoundingMode.HALF_UP));
                    }
                }
            }

            BigDecimal cv = (BigDecimal) g.get("currentValue");
            BigDecimal ic = (BigDecimal) g.get("investmentCost");
            BigDecimal icOrig = (BigDecimal) g.get("investmentCostOriginal");
            BigDecimal profit = cv.subtract(ic);
            g.put("profit", profit);
            g.put("profitRate", ic.compareTo(BigDecimal.ZERO) > 0
                    ? profit.divide(ic, 6, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);
            g.put("avgCostOriginal", sh.compareTo(BigDecimal.ZERO) > 0
                    ? icOrig.divide(sh, 4, RoundingMode.HALF_UP) : null);
            // unitPriceTwd: 每股 TWD 現值（編輯頁用來反推每筆 broker row 的 currentValue）
            g.put("unitPriceTwd", sh.compareTo(BigDecimal.ZERO) > 0
                    ? cv.divide(sh, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO);
        }

        result.sort((a, b) -> {
            Object ao = a.get("displayOrder");
            Object bo = b.get("displayOrder");
            if (ao != null && bo != null) {
                return Integer.compare(((Number) ao).intValue(), ((Number) bo).intValue());
            }
            if (ao != null) return -1;
            if (bo != null) return 1;
            BigDecimal av = (BigDecimal) a.get("currentValue");
            BigDecimal bv = (BigDecimal) b.get("currentValue");
            return bv.compareTo(av);
        });
        return result;
    }

    private static BigDecimal addBd(Object current, BigDecimal add) {
        BigDecimal c = current instanceof BigDecimal b ? b : BigDecimal.ZERO;
        return add == null ? c : c.add(add);
    }

    public static BigDecimal toBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    public static String asString(Object v) {
        return v == null ? null : v.toString();
    }
}
