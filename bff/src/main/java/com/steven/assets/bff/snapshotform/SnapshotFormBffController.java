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
import java.time.Duration;
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
                .flatMap(historical -> enrichBatch(date, historical, stocks))
                .map(ResponseEntity::ok);
    }

    /**
     * GET /api/bff/snapshot-form/realtime
     * 輪詢用：直接讀 Redis live cache，不再從 BFF 觸發 refresh
     * （price-service 自己 2 分鐘 cron 維護 Redis）。
     */
    @GetMapping("/realtime")
    public Mono<ResponseEntity<Map<String, Object>>> realtime() {
        return Mono.zip(
                businessServicesClient.get().uri("/api/market-data/prices")
                        .retrieve().bodyToMono(LIST_MAP).onErrorReturn(Collections.emptyList()),
                businessServicesClient.get().uri("/api/market-data/market-status")
                        .retrieve().bodyToMono(MAP).onErrorReturn(Collections.emptyMap())
        ).map(t -> {
            Map<String, Object> body = new HashMap<>();
            body.put("stockPrices", t.getT1());
            body.put("marketStatus", t.getT2());
            return ResponseEntity.ok(body);
        });
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
     * GET /api/bff/snapshot-form/lookups
     * 表單下拉一次取齊：banks（active）/ brokers（active）/ depositTypes（active）/ transitFundTypes（active payable map）。
     * 每筆 List Map 已過濾 active=true，前端不需再做 filter，避免 4 次 round-trip。
     */
    @GetMapping("/lookups")
    public Mono<ResponseEntity<Map<String, Object>>> getLookups() {
        Mono<List<Map<String, Object>>> banks = businessServicesClient.get()
                .uri("/api/settings/banks").retrieve().bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList())
                .map(this::filterActive);
        Mono<List<Map<String, Object>>> brokers = businessServicesClient.get()
                .uri("/api/settings/brokers").retrieve().bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList())
                .map(this::filterActive);
        Mono<List<Map<String, Object>>> depositTypes = businessServicesClient.get()
                .uri("/api/settings/deposit-types").retrieve().bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList())
                .map(this::filterActive);
        Mono<List<Map<String, Object>>> transitTypes = businessServicesClient.get()
                .uri("/api/settings/transit-fund-types/active").retrieve().bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList());

        return Mono.zip(banks, brokers, depositTypes, transitTypes).map(t -> {
            Map<String, Object> body = new HashMap<>();
            body.put("banks", t.getT1());
            body.put("brokers", t.getT2());
            body.put("depositTypes", t.getT3());
            body.put("transitFundTypes", t.getT4());
            return ResponseEntity.ok(body);
        });
    }

    private List<Map<String, Object>> filterActive(List<Map<String, Object>> list) {
        return list.stream().filter(m -> Boolean.TRUE.equals(m.get("active"))).toList();
    }

    /**
     * GET /api/bff/snapshot-form/funds
     * 信託基金主檔（Requirement 19）：每筆已含 latestNav / latestFxRate / latestNavDate / twdPerUnit，
     * 前端以此即時預覽 currentValue = units × twdPerUnit。
     */
    @GetMapping("/funds")
    public Mono<ResponseEntity<List<Map<String, Object>>>> listFunds(
            @RequestParam(required = false) String date) {
        // 回傳所有基金（含 inactive）。帶 date 時 NAV / FX / 配息估算改用該基準日（Requirement 21）。
        return businessServicesClient.get()
                .uri(uri -> {
                    var b = uri.path("/api/funds");
                    if (date != null && !date.isBlank()) b.queryParam("date", date);
                    return b.build();
                })
                .retrieve()
                .bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList())
                .map(ResponseEntity::ok);
    }

    /**
     * POST /api/bff/snapshot-form/fund-nav/refresh
     * 觸發後端 → external-materials-service 立即刷新所有基金 NAV，回傳 { success, failed, total }。
     */
    @PostMapping("/fund-nav/refresh")
    public Mono<ResponseEntity<Map<String, Object>>> refreshFundNav() {
        return businessServicesClient.post()
                .uri("/api/fund-nav/refresh")
                .retrieve()
                .bodyToMono(MAP)
                .onErrorReturn(Map.of("error", "external service unreachable"))
                .map(ResponseEntity::ok);
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

    /**
     * 把歷史價、即時快取（漲跌 / 名稱）、配息率合併成一筆 enriched DTO。
     *
     * 套用「股價基準日規則」（{@link SnapshotEnricher#isCurrentBasedate}），**per-market** 判斷：
     *  - basedate == 該市場時區的今日 → 用即時 price + priceChange（會持續變動）
     *  - 否則 → 用基準日的收盤價（hist.price），priceChange 留空（snapshot 已凍結在那一天）
     *
     * Dashboard、SnapshotForm（新增 / 編輯）皆套同一規則，三頁顯示一致。
     */
    private Mono<List<Map<String, Object>>> enrichBatch(
            String date, List<Map<String, Object>> historical, List<Map<String, String>> stocks) {

        LocalDate basedate = LocalDate.parse(date);

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
                        .timeout(Duration.ofSeconds(8))
                        .onErrorReturn(Collections.emptyMap())
                        .map(dr -> Map.entry(s.get("market") + "_" + s.get("code"), dr)), 16)
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
                // basedate==今日（市場時區）時，優先用 live cache 的價：
                //  - 盤中：即時價
                //  - 盤後：當日收盤（live cache 已收完盤）
                // 歷史表（stock_price_history）通常在盤後幾小時才匯入當日資料，
                // 在那之前 prices-on-date 會 fallback 到前一交易日，造成「基準日 4/28 卻顯示 4/27」。
                boolean isToday = SnapshotEnricher.isCurrentBasedate(basedate, market);
                boolean preferLive = isToday && live.get("price") != null;
                row.put("price", preferLive ? live.get("price") : hist.get("price"));
                row.put("tradingDate", preferLive ? live.get("tradingDate") : hist.get("tradingDate"));
                // frozen（非該市場當日）改用 hist 的「當日漲跌」（prices-on-date 已算好：該收盤日 vs 前一交易日），
                // 與 Dashboard 一致，讓收盤/週末頁也顯示漲跌；live 時仍用即時漲跌。
                row.put("priceChange", preferLive ? live.get("priceChange") : hist.get("priceChange"));
                row.put("changePercent", preferLive ? live.get("changePercent") : hist.get("changePercent"));
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
