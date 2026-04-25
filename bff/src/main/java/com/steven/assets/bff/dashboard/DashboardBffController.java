package com.steven.assets.bff.dashboard;

import com.steven.assets.bff.dashboard.dto.DashboardSummaryDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
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

    /**
     * GET /api/bff/dashboard/summary
     *
     * Parallel fan-out to:
     *  1. GET /api/snapshots          → snapshot list
     *  2. GET /api/snapshots/history  → history trend
     *  3. GET /api/market-data/prices → cached stock prices
     *  4. GET /api/market-data/market-status → market open/close
     *
     * Then fetches the latest snapshot detail using the first snapshot's ID.
     */
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
                        return Mono.just(ResponseEntity.ok(dto));
                    }

                    // Fetch detail for the latest snapshot
                    Object latestId = snapshots.get(0).get("id");
                    return businessServicesClient.get()
                            .uri("/api/snapshots/{id}", latestId)
                            .retrieve()
                            .bodyToMono(MAP)
                            .onErrorReturn(Collections.emptyMap())
                            .map(detail -> {
                                enrichStockHoldings(detail);
                                dto.setLatestSnapshotDetail(detail);
                                return ResponseEntity.ok(dto);
                            });
                });
    }

    /**
     * 為每筆持股加上 investmentCostOriginal（買入均價計算用）：
     * 美股以 USD 為基準，台股維持 TWD。
     * Legacy 美股記錄 currency='TWD' 時用 transactionExchangeRate 換回 USD，
     * 讓前端各頁面共用同一個欄位、不需各自處理 currency。
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
