package com.steven.assets.bff.dashboard;

import com.steven.assets.bff.dashboard.dto.DashboardSummaryDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BFF aggregation controller for the Dashboard page (/dashboard).
 *
 * Instead of the frontend making 4 separate API calls, this endpoint
 * fans out to business-services in parallel and returns everything
 * the dashboard needs in a single response.
 */
@Slf4j
@RestController
@RequestMapping("/api/bff/dashboard")
@RequiredArgsConstructor
public class DashboardBffController {

    private final WebClient businessServicesClient;

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    @GetMapping("/summary")
    public Mono<ResponseEntity<DashboardSummaryDto>> getSummary() {
        Mono<List<Map<String, Object>>> snapshotsMono = businessServicesClient.get()
                .uri("/api/snapshots")
                .retrieve()
                .bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList());

        Mono<List<Map<String, Object>>> historyMono = businessServicesClient.get()
                .uri("/api/snapshots/history")
                .retrieve()
                .bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList());

        Mono<List<Map<String, Object>>> pricesMono = businessServicesClient.get()
                .uri("/api/market-data/prices")
                .retrieve()
                .bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList());

        Mono<Map<String, Object>> marketStatusMono = businessServicesClient.get()
                .uri("/api/market-data/market-status")
                .retrieve()
                .bodyToMono(MAP)
                .onErrorReturn(Collections.emptyMap());

        return Mono.zip(snapshotsMono, historyMono, pricesMono, marketStatusMono)
                .flatMap(tuple -> {
                    List<Map<String, Object>> snapshots = tuple.getT1();
                    List<Map<String, Object>> history = tuple.getT2();
                    List<Map<String, Object>> prices = tuple.getT3();
                    Map<String, Object> marketStatus = tuple.getT4();

                    DashboardSummaryDto dto = new DashboardSummaryDto();
                    dto.setSnapshots(snapshots);
                    dto.setHistory(history);
                    dto.setStockPrices(prices);
                    dto.setMarketStatus(marketStatus);

                    if (snapshots.isEmpty()) {
                        dto.setLatestSnapshotDetail(Collections.emptyMap());
                        dto.setMergedStocks(Collections.emptyList());
                        return Mono.just(ResponseEntity.ok(dto));
                    }

                    Object latestId = snapshots.get(0).get("id");
                    return businessServicesClient.get()
                            .uri("/api/snapshots/{id}", latestId)
                            .retrieve()
                            .bodyToMono(MAP)
                            .onErrorReturn(Collections.emptyMap())
                            .flatMap(detail -> {
                                enrichStockHoldings(detail);
                                dto.setLatestSnapshotDetail(detail);
                                return fetchSnapshotClosePrices(detail)
                                        .map(closeMap -> {
                                            dto.setMergedStocks(buildMergedStocks(detail, closeMap));
                                            return ResponseEntity.ok(dto);
                                        });
                            });
                });
    }

    /**
     * GET /api/bff/dashboard/realtime
     * 5 分鐘輪詢用：只回傳即時股價與市場開盤狀態。
     */
    @GetMapping("/realtime")
    public Mono<ResponseEntity<Map<String, Object>>> getRealtime() {
        Mono<List<Map<String, Object>>> pricesMono = businessServicesClient.get()
                .uri("/api/market-data/prices")
                .retrieve()
                .bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList());
        Mono<Map<String, Object>> statusMono = businessServicesClient.get()
                .uri("/api/market-data/market-status")
                .retrieve()
                .bodyToMono(MAP)
                .onErrorReturn(Collections.emptyMap());
        return Mono.zip(pricesMono, statusMono).map(t -> {
            Map<String, Object> body = new HashMap<>();
            body.put("stockPrices", t.getT1());
            body.put("marketStatus", t.getT2());
            return ResponseEntity.ok(body);
        });
    }

    /**
     * GET /api/bff/dashboard/snapshot/{id}
     * 切換快照時用：取得指定快照的 enriched detail + mergedStocks。
     */
    @GetMapping("/snapshot/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> getSnapshot(@PathVariable Long id) {
        return businessServicesClient.get()
                .uri("/api/snapshots/{id}", id)
                .retrieve()
                .bodyToMono(MAP)
                .onErrorReturn(Collections.emptyMap())
                .flatMap(detail -> {
                    enrichStockHoldings(detail);
                    return fetchSnapshotClosePrices(detail).map(closeMap -> {
                        Map<String, Object> body = new HashMap<>(detail);
                        body.put("mergedStocks", buildMergedStocks(detail, closeMap));
                        return ResponseEntity.ok(body);
                    });
                });
    }

    /**
     * 為每筆持股加上 investmentCostOriginal（買入均價計算用）：
     * 美股以 USD 為基準，台股維持 TWD。
     */
    @SuppressWarnings("unchecked")
    private void enrichStockHoldings(Map<String, Object> detail) {
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
    private Mono<Map<String, BigDecimal>> fetchSnapshotClosePrices(Map<String, Object> detail) {
        Object stocksObj = detail.get("stocks");
        Object dateObj = detail.get("snapshotDate");
        if (!(stocksObj instanceof List<?> list) || dateObj == null || list.isEmpty()) {
            return Mono.just(Collections.emptyMap());
        }
        List<Map<String, String>> body = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
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
     * 排序：有 displayOrder 的優先依序，其餘依 currentValue 降冪。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildMergedStocks(
            Map<String, Object> detail, Map<String, BigDecimal> closeMap) {
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
                return init;
            });

            g.put("shares", addBd(g.get("shares"), toBigDecimal(s.get("shares"))));
            BigDecimal costTwd = toBigDecimal(s.get("investmentCostTwd"));
            if (costTwd == null) costTwd = toBigDecimal(s.get("investmentCost"));
            g.put("investmentCost", addBd(g.get("investmentCost"), costTwd));
            BigDecimal costOrig = toBigDecimal(s.get("investmentCostOriginal"));
            if (costOrig == null) costOrig = toBigDecimal(s.get("investmentCost"));
            g.put("investmentCostOriginal", addBd(g.get("investmentCostOriginal"), costOrig));
            g.put("currentValue", addBd(g.get("currentValue"), toBigDecimal(s.get("currentValue"))));
            g.put("estimatedDividend",
                    addBd(g.get("estimatedDividend"), toBigDecimal(s.get("estimatedDividend"))));

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
            // 買入均價（原幣別，美股 USD / 台股 TWD）
            g.put("avgCostOriginal", sh.compareTo(BigDecimal.ZERO) > 0
                    ? icOrig.divide(sh, 4, RoundingMode.HALF_UP)
                    : null);
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

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }
}
