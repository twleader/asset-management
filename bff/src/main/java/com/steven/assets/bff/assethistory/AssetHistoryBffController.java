package com.steven.assets.bff.assethistory;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * AssetHistoryView 專屬 BFF。
 */
@RestController
@RequestMapping("/api/bff/asset-history")
@RequiredArgsConstructor
public class AssetHistoryBffController {

    private final WebClient businessServicesClient;

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};

    /**
     * GET /api/bff/asset-history
     * 回傳資產歷史，並為每筆預先標註 isLastOfYear（每年最後一筆）。
     * 若最新一筆 snapshotDate == 今日（市場時區）且 live-assets 也指向同一筆 snapshot，
     * 用 live 計算覆蓋最新列的 totalTwStockValue / totalUsStockValue / totalStockValue
     * / totalAssets / increase / increaseRate / investmentRate，使本頁與 Dashboard 顯示一致。
     */
    @GetMapping
    public Mono<ResponseEntity<List<Map<String, Object>>>> getHistory() {
        Mono<List<Map<String, Object>>> historyMono = businessServicesClient.get()
                .uri("/api/snapshots/history")
                .retrieve()
                .bodyToMono(LIST_MAP);
        Mono<Map<String, Object>> liveMono = businessServicesClient.get()
                .uri("/api/market-data/live-assets")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .onErrorReturn(Map.of());
        return Mono.zip(historyMono, liveMono).map(tuple -> {
            List<Map<String, Object>> history = tuple.getT1();
            Map<String, Object> live = tuple.getT2();

            Map<String, Long> lastIdPerYear = new TreeMap<>();
            for (Map<String, Object> r : history) {
                Object date = r.get("snapshotDate");
                Object idObj = r.get("id");
                if (date == null || idObj == null) continue;
                String year = date.toString().substring(0, 4);
                long id = ((Number) idObj).longValue();
                lastIdPerYear.put(year, id);
            }
            Set<Long> lastOfYearIds = new HashSet<>(lastIdPerYear.values());
            for (Map<String, Object> r : history) {
                Object idObj = r.get("id");
                long id = idObj == null ? -1 : ((Number) idObj).longValue();
                r.put("isLastOfYear", lastOfYearIds.contains(id));
            }

            applyLiveOverlay(history, live);
            return ResponseEntity.ok(history);
        });
    }

    @SuppressWarnings("unchecked")
    private void applyLiveOverlay(List<Map<String, Object>> history, Map<String, Object> live) {
        if (history.isEmpty() || live == null || live.isEmpty()) return;
        Map<String, Object> latest = history.get(history.size() - 1);
        Object latestDate = latest.get("snapshotDate");
        Object liveDate = live.get("snapshotDate");
        if (latestDate == null || liveDate == null
                || !latestDate.toString().equals(liveDate.toString())) return;

        // 依市場彙總 live stock value：未開盤的市場仍會回 Redis 內最後一筆收盤價，
        // 與 Dashboard liveLatest 行為一致；不另外做 market open 檢查。
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

    private static BigDecimal toBd(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return null; }
    }

    private static BigDecimal toBdOr(Object v, BigDecimal fallback) {
        BigDecimal bd = toBd(v);
        return bd != null ? bd : fallback;
    }

    @PostMapping("/recalc-dividends")
    public Mono<ResponseEntity<Map<String, Object>>> recalcDividends() {
        return businessServicesClient.post()
                .uri("/api/snapshots/recalc-dividends")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable Long id) {
        return businessServicesClient.delete()
                .uri("/api/snapshots/{id}", id)
                .retrieve()
                .toBodilessEntity()
                .map(r -> ResponseEntity.noContent().<Void>build());
    }

    @GetMapping("/export")
    public Mono<ResponseEntity<byte[]>> exportExcel() {
        return businessServicesClient.get()
                .uri("/api/snapshots/export")
                .accept(MediaType.APPLICATION_OCTET_STREAM)
                .retrieve()
                .toEntity(byte[].class)
                .map(e -> {
                    HashMap<String, String> headers = new HashMap<>();
                    e.getHeaders().forEach((k, v) -> {
                        if (v != null && !v.isEmpty()) headers.put(k, v.get(0));
                    });
                    ResponseEntity.BodyBuilder b = ResponseEntity.ok();
                    e.getHeaders().forEach((k, v) -> v.forEach(val -> b.header(k, val)));
                    return b.body(e.getBody());
                });
    }
}
