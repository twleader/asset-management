package com.steven.assets.bff.snapshotform;

import com.steven.assets.bff.common.SnapshotEnricher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BFF controller for SnapshotFormView (新增/編輯快照表單)。
 *
 * 把表單頁原本散落的 marketDataApi.* / snapshotApi.* 多步協調邏輯
 * （價格批次查 + backfill fallback + 配息率補抓 + 名稱補齊 + 匯率智慧 fallback）
 * 集中到本 controller，前端只看到一支對應的 BFF endpoint。
 */
@Slf4j
@RestController
@RequestMapping("/api/bff/snapshot-form")
@RequiredArgsConstructor
public class SnapshotFormBffController {

    private final WebClient businessServicesClient;
    private final SnapshotEnricher enricher;

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    /**
     * POST /api/bff/snapshot-form/prices?date=YYYY-MM-DD
     * Body: [{ "code": "VOO", "market": "美股" }, ...]
     *
     * 一次性回傳每筆股票：
     *  - price          快照日期歷史收盤價 (USD/TWD 原幣別，非交易日往前 fallback)；DB 缺資料時自動 backfill 重試
     *  - tradingDate    收盤價對應的實際交易日
     *  - stockName      股票名稱（從即時快取補齊）
     *  - dividendRate   配息率
     *  - priceChange    當日漲跌（即時資料）
     *  - changePercent  當日漲跌幅 %
     */
    @PostMapping("/prices")
    public Mono<ResponseEntity<List<Map<String, Object>>>> batchPrices(
            @RequestParam String date,
            @RequestBody List<Map<String, String>> stocks) {
        if (stocks == null || stocks.isEmpty()) {
            return Mono.just(ResponseEntity.ok(Collections.emptyList()));
        }
        return fetchHistoricalPrices(date, stocks)
                .flatMap(historical -> {
                    if (historical.isEmpty()) {
                        return triggerBackfillThenRefetch(date, stocks);
                    }
                    return Mono.just(historical);
                })
                .flatMap(historical -> enrichBatch(historical, stocks))
                .map(ResponseEntity::ok);
    }

    /**
     * GET /api/bff/snapshot-form/realtime
     * 5 分鐘輪詢用：先 trigger 後端刷新最新行情，再回傳 stockPrices + marketStatus。
     */
    @GetMapping("/realtime")
    public Mono<ResponseEntity<Map<String, Object>>> realtime() {
        Mono<Void> refresh = businessServicesClient.post()
                .uri("/api/market-data/prices/refresh")
                .retrieve()
                .bodyToMono(Void.class)
                .onErrorResume(e -> Mono.empty());

        return refresh.then(Mono.zip(
                businessServicesClient.get().uri("/api/market-data/prices")
                        .retrieve().bodyToMono(LIST_MAP).onErrorReturn(Collections.emptyList()),
                businessServicesClient.get().uri("/api/market-data/market-status")
                        .retrieve().bodyToMono(MAP).onErrorReturn(Collections.emptyMap())
        ).map(t -> {
            Map<String, Object> body = new HashMap<>();
            body.put("stockPrices", t.getT1());
            body.put("marketStatus", t.getT2());
            return ResponseEntity.ok(body);
        }));
    }

    /**
     * GET /api/bff/snapshot-form/exchange-rate?date=YYYY-MM-DD
     * 取得指定日期的 USD 匯率：
     *  - 今天：先 trigger 後端刷新即時匯率
     *  - 直接呼叫 /api/market-data/exchange-rate/on-date（後端已含 closest-on-or-before fallback）
     */
    @GetMapping("/exchange-rate")
    public Mono<ResponseEntity<Map<String, Object>>> exchangeRate(@RequestParam String date) {
        boolean isToday = date.equals(LocalDate.now().toString());
        Mono<Void> refresh = isToday
                ? businessServicesClient.post()
                        .uri(uri -> uri.path("/api/market-data/exchange-rate/refresh")
                                .queryParam("currency", "USD").build())
                        .retrieve()
                        .bodyToMono(Void.class)
                        .onErrorResume(e -> Mono.empty())
                : Mono.empty();

        return refresh.then(businessServicesClient.get()
                .uri(uri -> uri.path("/api/market-data/exchange-rate/on-date")
                        .queryParam("currency", "USD").queryParam("date", date).build())
                .retrieve()
                .bodyToMono(MAP)
                .onErrorReturn(Collections.emptyMap())
                .map(ResponseEntity::ok));
    }

    /**
     * GET /api/bff/snapshot-form/{id}
     * 編輯模式 bootstrap：回傳 enriched detail + mergedStocks（同 snapshot-detail）。
     * 表單頁雖然會用自己的 groupStocks 處理巢狀資料，但此端點仍提供一致的入口。
     */
    @GetMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> getDetail(
            @org.springframework.web.bind.annotation.PathVariable Long id) {
        return businessServicesClient.get()
                .uri("/api/snapshots/{id}", id)
                .retrieve()
                .bodyToMono(MAP)
                .onErrorReturn(Collections.emptyMap())
                .flatMap(detail -> {
                    enricher.enrichInvestmentCostOriginal(detail);
                    return enricher.fetchSnapshotClosePrices(detail).map(closeMap -> {
                        Map<String, Object> body = new HashMap<>(detail);
                        body.put("mergedStocks",
                                enricher.buildMergedStocks(detail, closeMap, true));
                        return ResponseEntity.ok(body);
                    });
                });
    }

    // ─────────────────────────────────────────────────────────────
    //  helpers
    // ─────────────────────────────────────────────────────────────

    private Mono<List<Map<String, Object>>> fetchHistoricalPrices(
            String date, List<Map<String, String>> stocks) {
        return businessServicesClient.post()
                .uri(uri -> uri.path("/api/market-data/history/prices-on-date")
                        .queryParam("date", date).build())
                .bodyValue(stocks)
                .retrieve()
                .bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList());
    }

    /** 歷史價無資料 → 觸發單檔回補（基準日 ±30 天）後重試一次。 */
    private Mono<List<Map<String, Object>>> triggerBackfillThenRefetch(
            String date, List<Map<String, String>> stocks) {
        LocalDate target = LocalDate.parse(date);
        String since = target.minusDays(30).toString();
        String until = target.plusDays(5).toString();

        return Flux.fromIterable(stocks)
                .flatMap(s -> businessServicesClient.post()
                        .uri(uri -> uri.path("/api/market-data/history/backfill-stock")
                                .queryParam("code", s.get("code"))
                                .queryParam("market", s.get("market"))
                                .queryParam("since", since)
                                .queryParam("until", until)
                                .build())
                        .retrieve()
                        .bodyToMono(Void.class)
                        .onErrorResume(e -> Mono.empty()), 4)
                .then(fetchHistoricalPrices(date, stocks));
    }

    /** 把歷史價、即時快取（漲跌 / 名稱）、配息率合併成一筆 enriched DTO。 */
    private Mono<List<Map<String, Object>>> enrichBatch(
            List<Map<String, Object>> historical, List<Map<String, String>> stocks) {

        Mono<List<Map<String, Object>>> liveMono = businessServicesClient.get()
                .uri("/api/market-data/prices")
                .retrieve()
                .bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList());

        Mono<Map<String, Map<String, Object>>> dividendsMono = Flux.fromIterable(stocks)
                .flatMap(s -> businessServicesClient.get()
                        .uri(uri -> uri.path("/api/market-data/dividend-rate")
                                .queryParam("code", s.get("code"))
                                .queryParam("market", s.get("market"))
                                .build())
                        .retrieve()
                        .bodyToMono(MAP)
                        .onErrorReturn(Collections.emptyMap())
                        .map(dr -> Map.entry(s.get("market") + "_" + s.get("code"), dr)), 4)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);

        return Mono.zip(liveMono, dividendsMono).map(t -> {
            Map<String, Map<String, Object>> liveMap = new HashMap<>();
            for (Map<String, Object> p : t.getT1()) {
                String key = SnapshotEnricher.asString(p.get("market")) + "_"
                        + SnapshotEnricher.asString(p.get("stockCode"));
                liveMap.put(key, p);
            }
            Map<String, Map<String, Object>> divMap = t.getT2();
            Map<String, Map<String, Object>> histMap = new HashMap<>();
            for (Map<String, Object> p : historical) {
                String key = SnapshotEnricher.asString(p.get("market")) + "_"
                        + SnapshotEnricher.asString(p.get("stockCode"));
                histMap.put(key, p);
            }

            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, String> s : stocks) {
                String code = s.get("code");
                String market = s.get("market");
                String key = market + "_" + code;
                Map<String, Object> hist = histMap.getOrDefault(key, Collections.emptyMap());
                Map<String, Object> live = liveMap.getOrDefault(key, Collections.emptyMap());
                Map<String, Object> div = divMap.getOrDefault(key, Collections.emptyMap());

                Map<String, Object> row = new HashMap<>();
                row.put("stockCode", code);
                row.put("market", market);
                row.put("price", hist.get("price"));
                row.put("tradingDate", hist.get("tradingDate"));
                row.put("priceChange", live.get("priceChange"));
                row.put("changePercent", live.get("changePercent"));
                row.put("stockName", firstNonNull(div.get("stockName"), live.get("stockName")));
                BigDecimal dr = SnapshotEnricher.toBigDecimal(div.get("dividendRate"));
                row.put("dividendRate", dr);
                result.add(row);
            }
            return result;
        });
    }

    private static Object firstNonNull(Object... values) {
        for (Object v : values) if (v != null) return v;
        return null;
    }
}
