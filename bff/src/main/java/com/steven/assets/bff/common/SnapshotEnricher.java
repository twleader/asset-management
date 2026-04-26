package com.steven.assets.bff.common;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
            if ("美股".equals(market) && !"USD".equals(currency)
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
    @SuppressWarnings("unchecked")
    public Mono<Map<String, BigDecimal>> fetchSnapshotClosePrices(Map<String, Object> detail) {
        Object stocksObj = detail.get("stocks");
        Object dateObj = detail.get("snapshotDate");
        if (!(stocksObj instanceof List<?> list) || dateObj == null || list.isEmpty()) {
            return Mono.just(Collections.emptyMap());
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
        if (body.isEmpty()) return Mono.just(Collections.emptyMap());

        return businessServicesClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/market-data/history/prices-on-date")
                        .queryParam("date", dateObj.toString())
                        .build())
                .bodyValue(body)
                .retrieve()
                .bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList())
                .map(prices -> {
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

    /**
     * 依 stockCode + market 合併多筆 broker rows，預先計算前端表格所需欄位。
     * @param includeBrokerRows true 時保留 brokerRows 陣列（供 SnapshotDetail 編輯頁使用）
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> buildMergedStocks(
            Map<String, Object> detail,
            Map<String, BigDecimal> closeMap,
            boolean includeBrokerRows) {
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

        List<Map<String, Object>> result = new ArrayList<>(grouped.values());
        for (Map<String, Object> g : result) {
            String key = g.get("market") + "_" + g.get("stockCode");
            BigDecimal closePrice = closeMap.get(key);
            g.put("stockPrice", closePrice);

            BigDecimal cv = (BigDecimal) g.get("currentValue");
            BigDecimal ic = (BigDecimal) g.get("investmentCost");
            BigDecimal sh = (BigDecimal) g.get("shares");
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
