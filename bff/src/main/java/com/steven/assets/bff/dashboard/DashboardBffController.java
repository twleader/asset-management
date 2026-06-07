package com.steven.assets.bff.dashboard;

import com.steven.assets.bff.common.SnapshotEnricher;
import com.steven.assets.bff.dashboard.dto.DashboardSummaryDto;
import com.steven.assets.bff.dashboard.dto.TwStockLookthroughDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BFF aggregation controller for the Dashboard page (/dashboard).
 *
 * 設計原則：一個前端頁面對應一支 BFF controller。前端只 render，
 * aggregation 與計算（profit / profitRate / 收盤價對齊等）一律由 BFF 預先處理。
 */
@Slf4j
@RestController
@RequestMapping("/api/bff/dashboard")
@RequiredArgsConstructor
public class DashboardBffController {

    private final WebClient businessServicesClient;
    private final SnapshotEnricher enricher;

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

        Mono<Map<String, Object>> liveAssetsMono = businessServicesClient.get()
                .uri("/api/market-data/live-assets")
                .retrieve()
                .bodyToMono(MAP)
                .onErrorReturn(Collections.emptyMap());

        return Mono.zip(snapshotsMono, historyMono, pricesMono, marketStatusMono, liveAssetsMono)
                .flatMap(tuple -> {
                    List<Map<String, Object>> snapshots = tuple.getT1();
                    List<Map<String, Object>> history = tuple.getT2();
                    List<Map<String, Object>> prices = tuple.getT3();
                    Map<String, Object> marketStatus = tuple.getT4();
                    Map<String, Object> liveAssets = tuple.getT5();

                    DashboardSummaryDto dto = new DashboardSummaryDto();
                    dto.setSnapshots(snapshots);
                    dto.setHistory(history);
                    dto.setStockPrices(prices);
                    dto.setMarketStatus(marketStatus);
                    dto.setLiveAssets(liveAssets);

                    if (snapshots.isEmpty()) {
                        dto.setLatestSnapshotDetail(Collections.emptyMap());
                        dto.setMergedStocks(Collections.emptyList());
                        return Mono.just(ResponseEntity.ok(dto));
                    }

                    Object latestId = snapshots.get(0).get("id");
                    Object basedateObj = snapshots.get(0).get("snapshotDate");
                    LocalDate basedate = basedateObj != null ? LocalDate.parse(basedateObj.toString()) : null;
                    return businessServicesClient.get()
                            .uri("/api/snapshots/{id}", latestId)
                            .retrieve()
                            .bodyToMono(MAP)
                            .onErrorReturn(Collections.emptyMap())
                            .flatMap(detail -> {
                                enricher.enrichInvestmentCostOriginal(detail);
                                dto.setLatestSnapshotDetail(detail);
                                return enricher.fetchSnapshotClosePrices(detail)
                                        .map(closeMap -> {
                                            // 「股價基準日規則」per-market：每筆 live price 依其市場各自決定
                                            // 保留 live（basedate == 該市場時區的今日）或換成 basedate 收盤價。
                                            // 解決過去 TW 過午夜後美股盤中（TW 凌晨）被誤判為非當日 → 顯示前一交易日收盤的問題。
                                            dto.setStockPrices(SnapshotEnricher.mergePerMarketPrices(
                                                    basedate, prices, closeMap));
                                            dto.setMergedStocks(
                                                    enricher.buildMergedStocks(detail, closeMap, false));
                                            return ResponseEntity.ok(dto);
                                        });
                            });
                });
    }

    /**
     * GET /api/bff/dashboard/realtime
     * 2 分鐘輪詢用：直接從 business-services 讀 Redis live cache + market status。
     * 不再從 BFF 觸發 refresh — price-service 自己 2 分鐘 cron 維護 Redis，盤中各市場開盤期間
     * 自動寫入最新報價；BFF 只負責讀。
     */
    @GetMapping("/realtime")
    public Mono<ResponseEntity<Map<String, Object>>> getRealtime() {
        return Mono.zip(
                businessServicesClient.get().uri("/api/market-data/prices")
                        .retrieve().bodyToMono(LIST_MAP).onErrorReturn(Collections.emptyList()),
                businessServicesClient.get().uri("/api/market-data/market-status")
                        .retrieve().bodyToMono(MAP).onErrorReturn(Collections.emptyMap()),
                businessServicesClient.get().uri("/api/market-data/live-assets")
                        .retrieve().bodyToMono(MAP).onErrorReturn(Collections.emptyMap())
        ).map(t -> {
            Map<String, Object> body = new HashMap<>();
            body.put("stockPrices", t.getT1());
            body.put("marketStatus", t.getT2());
            body.put("liveAssets", t.getT3());
            return ResponseEntity.ok(body);
        });
    }

    /**
     * GET /api/bff/dashboard/snapshot/{id}
     * 切換快照時用：取得指定快照的 enriched detail + mergedStocks。
     */
    /**
     * POST /api/bff/dashboard/enrich-dividend-rates
     * 背景補齊所有快照缺漏的配息率（fire-and-forget，不阻塞 UI）。
     */
    @PostMapping("/enrich-dividend-rates")
    public Mono<ResponseEntity<Void>> enrichDividendRates() {
        return businessServicesClient.post()
                .uri("/api/snapshots/enrich-all-dividend-rates")
                .retrieve()
                .bodyToMono(Void.class)
                .then(Mono.just(ResponseEntity.ok().<Void>build()));
    }

    /**
     * PATCH /api/bff/dashboard/snapshot/{id}/stock-order
     * 持股顯示順序拖曳後寫回。
     */
    @PatchMapping("/snapshot/{id}/stock-order")
    public Mono<ResponseEntity<Void>> updateStockOrder(
            @PathVariable Long id,
            @RequestBody List<Map<String, Object>> orders) {
        return businessServicesClient.patch()
                .uri("/api/snapshots/{id}/stock-order", id)
                .bodyValue(orders)
                .retrieve()
                .bodyToMono(Void.class)
                .then(Mono.just(ResponseEntity.ok().<Void>build()));
    }

    @GetMapping("/snapshot/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> getSnapshot(@PathVariable Long id) {
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
                                enricher.buildMergedStocks(detail, closeMap, false));
                        return ResponseEntity.ok(body);
                    });
                });
    }

    /**
     * GET /api/bff/dashboard/tw-stock-lookthrough/{snapshotId}
     * 「資產配置分佈」第 2 tab「台股個股」用：ETF 穿透 + 直接持股合併、取前 10 大個股 + 「其它」。
     *
     * 規則（見 Requirement 9 + Task 86）：
     *  - 篩 market = 台股 的 mergedStocks
     *  - ETF（stockCode 以 00 開頭）並行（concurrency 8）呼叫 /api/market-data/etf-holdings 取得成分股權重，
     *    將 currentValue × weight% 拆解到各個成分股代號；weights 加總不足 100% 的缺口計入 ETF 自身
     *    （成分股表通常涵蓋 95–100%，剩餘是現金 / 應收款部位）
     *  - 直接持股：整筆計入該股代號（不拆解）
     *  - 同 stockCode 加總（ETF 內含 2330 + 直接持有 2330 合併）
     *  - 排序取前 10 + 1 個「其它」
     *  - ETF 抓取失敗（supported=false / holdings empty / IO error）→ 該 ETF 整筆退回以代號自身計入，
     *    並記錄於 degradedEtfs 供前端顯示降級註記
     *
     * Lazy fetch：前端只在使用者切到第 2 tab 才呼叫；避免拖慢 /summary 首屏。
     */
    @GetMapping("/tw-stock-lookthrough/{snapshotId}")
    public Mono<ResponseEntity<TwStockLookthroughDto>> getTwStockLookthrough(
            @PathVariable Long snapshotId) {
        return businessServicesClient.get()
                .uri("/api/snapshots/{id}", snapshotId)
                .retrieve()
                .bodyToMono(MAP)
                .onErrorReturn(Collections.emptyMap())
                .flatMap(detail -> {
                    enricher.enrichInvestmentCostOriginal(detail);
                    return enricher.fetchSnapshotClosePrices(detail).flatMap(closeMap -> {
                        List<Map<String, Object>> merged =
                                enricher.buildMergedStocks(detail, closeMap, false);
                        return buildLookthrough(detail, merged);
                    });
                });
    }

    private Mono<ResponseEntity<TwStockLookthroughDto>> buildLookthrough(
            Map<String, Object> detail, List<Map<String, Object>> merged) {
        List<Map<String, Object>> twEtfs = new ArrayList<>();
        List<Map<String, Object>> twStocks = new ArrayList<>();
        BigDecimal totalTwValue = BigDecimal.ZERO;
        for (Map<String, Object> r : merged) {
            if (!"台股".equals(SnapshotEnricher.asString(r.get("market")))) continue;
            BigDecimal cv = SnapshotEnricher.toBigDecimal(r.get("currentValue"));
            if (cv == null) cv = BigDecimal.ZERO;
            totalTwValue = totalTwValue.add(cv);
            String code = SnapshotEnricher.asString(r.get("stockCode"));
            if (code != null && code.startsWith("00")) twEtfs.add(r);
            else twStocks.add(r);
        }
        final BigDecimal totalFinal = totalTwValue;

        // 並行抓 ETF holdings，concurrency 限 4：成分股來源 Yahoo quoteSummary 對單一 IP 有 rate limit，
        // 8 並行易觸發 429（ext-materials 端 getYahooCrumb 已 synchronized 共用 crumb，但 quoteSummary 仍各自打）
        Mono<List<EtfHoldingsFetched>> etfMono = twEtfs.isEmpty()
                ? Mono.just(Collections.emptyList())
                : Flux.fromIterable(twEtfs)
                        .flatMap(this::fetchEtfHoldings, 4)
                        .collectList();

        return etfMono.map(fetched -> {
            Map<String, Aggregate> agg = new LinkedHashMap<>();
            List<TwStockLookthroughDto.DegradedEtf> degraded = new ArrayList<>();

            // 直接持股（以股名為聚合鍵：MoneyDJ 成分股只給名稱無代號，故統一用股名合併）
            for (Map<String, Object> row : twStocks) {
                String code = SnapshotEnricher.asString(row.get("stockCode"));
                String name = SnapshotEnricher.asString(row.get("stockName"));
                BigDecimal cv = SnapshotEnricher.toBigDecimal(row.get("currentValue"));
                if (cv == null) continue;
                mergeInto(agg, name, code, cv);
            }

            // ETF 穿透
            for (EtfHoldingsFetched ef : fetched) {
                BigDecimal cv = ef.currentValue() == null ? BigDecimal.ZERO : ef.currentValue();
                List<Map<String, Object>> holdings = ef.holdings();
                if (holdings == null || holdings.isEmpty()) {
                    // 退回以 ETF 自身計入
                    mergeInto(agg, ef.etfName(), ef.etfCode(), cv);
                    TwStockLookthroughDto.DegradedEtf d = new TwStockLookthroughDto.DegradedEtf();
                    d.setCode(ef.etfCode());
                    d.setMessage(ef.message() != null ? ef.message() : "查無成分股資料");
                    degraded.add(d);
                    continue;
                }
                // 先加總已揭露成分股權重，再依比例把整筆 ETF 市值正規化分配給成分股。
                // 成分股來源可能只揭露前 N 大（未滿 100%），正規化後 ETF 完全穿透、不殘留「ETF 自身」slice
                // （ETF 不該出現在「個股」圖中）。代價：未揭露尾段假設與已揭露分布相同（誤差小且為穿透標準作法）。
                BigDecimal weightSum = BigDecimal.ZERO;
                List<Map<String, Object>> valid = new ArrayList<>();
                for (Map<String, Object> h : holdings) {
                    String name = SnapshotEnricher.asString(h.get("stockName"));
                    BigDecimal w = SnapshotEnricher.toBigDecimal(h.get("weight"));
                    if (name == null || name.isBlank() || w == null
                            || w.compareTo(BigDecimal.ZERO) <= 0) continue;
                    weightSum = weightSum.add(w);
                    valid.add(h);
                }
                if (weightSum.compareTo(BigDecimal.ZERO) <= 0) {
                    // 理論上不會走到（holdings 非空但無有效權重）：退回以 ETF 自身計入
                    mergeInto(agg, ef.etfName(), ef.etfCode(), cv);
                    continue;
                }
                for (Map<String, Object> h : valid) {
                    String code = SnapshotEnricher.asString(h.get("stockCode"));
                    String name = SnapshotEnricher.asString(h.get("stockName"));
                    BigDecimal w = SnapshotEnricher.toBigDecimal(h.get("weight"));
                    BigDecimal share = cv.multiply(w).divide(weightSum, 4, RoundingMode.HALF_UP);
                    mergeInto(agg, name, code, share);
                }
            }

            // 排序 + 切 top10
            List<Aggregate> sorted = new ArrayList<>(agg.values());
            sorted.sort(Comparator.comparing((Aggregate a) -> a.value).reversed());
            List<TwStockLookthroughDto.Item> items = new ArrayList<>();
            BigDecimal others = BigDecimal.ZERO;
            int othersCount = 0;
            for (int i = 0; i < sorted.size(); i++) {
                Aggregate a = sorted.get(i);
                if (i < 10) {
                    TwStockLookthroughDto.Item item = new TwStockLookthroughDto.Item();
                    item.setStockCode(a.code);
                    item.setStockName(a.name);
                    item.setValue(a.value.setScale(2, RoundingMode.HALF_UP));
                    item.setPercent(pct(a.value, totalFinal));
                    items.add(item);
                } else {
                    others = others.add(a.value);
                    othersCount++;
                }
            }
            TwStockLookthroughDto.Others othersDto = new TwStockLookthroughDto.Others();
            othersDto.setValue(others.setScale(2, RoundingMode.HALF_UP));
            othersDto.setPercent(pct(others, totalFinal));
            othersDto.setConstituentCount(othersCount);

            TwStockLookthroughDto dto = new TwStockLookthroughDto();
            dto.setSnapshotDate(SnapshotEnricher.asString(detail.get("snapshotDate")));
            dto.setTotalTwStockValue(totalFinal.setScale(2, RoundingMode.HALF_UP));
            dto.setItems(items);
            dto.setOthers(othersDto);
            dto.setDegradedEtfs(degraded);
            return ResponseEntity.ok(dto);
        });
    }

    private static BigDecimal pct(BigDecimal value, BigDecimal total) {
        if (total == null || total.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        return value.multiply(BigDecimal.valueOf(100))
                .divide(total, 4, RoundingMode.HALF_UP);
    }

    @SuppressWarnings("unchecked")
    private Mono<EtfHoldingsFetched> fetchEtfHoldings(Map<String, Object> etfRow) {
        String code = SnapshotEnricher.asString(etfRow.get("stockCode"));
        String name = SnapshotEnricher.asString(etfRow.get("stockName"));
        BigDecimal cv = SnapshotEnricher.toBigDecimal(etfRow.get("currentValue"));
        return businessServicesClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/market-data/etf-holdings")
                        .queryParam("code", code)
                        .queryParam("market", "台股")
                        .build())
                .retrieve()
                .bodyToMono(MAP)
                .onErrorResume(e -> {
                    log.warn("ETF 成分股查詢失敗 {}: {}", code, e.getMessage());
                    return Mono.just(Collections.emptyMap());
                })
                .map(resp -> {
                    Object holdingsObj = resp.get("holdings");
                    List<Map<String, Object>> holdings = (holdingsObj instanceof List<?> l)
                            ? (List<Map<String, Object>>) l : Collections.emptyList();
                    Boolean supported = resp.get("supported") instanceof Boolean b ? b : null;
                    String message = SnapshotEnricher.asString(resp.get("message"));
                    boolean effective = holdings != null && !holdings.isEmpty()
                            && !Boolean.FALSE.equals(supported);
                    return new EtfHoldingsFetched(
                            code, name, cv,
                            effective ? holdings : Collections.emptyList(),
                            effective ? null : (message != null ? message : "查無成分股資料"));
                });
    }

    /** 以股名為聚合鍵；code 為 best-effort（直接持股有、MoneyDJ 成分股無）。 */
    private static void mergeInto(Map<String, Aggregate> agg, String name, String code, BigDecimal v) {
        if (name == null || name.isBlank() || v == null) return;
        Aggregate a = agg.computeIfAbsent(name, Aggregate::new);
        if ((a.code == null || a.code.isBlank()) && code != null && !code.isBlank()) {
            a.code = code;
        }
        a.value = a.value.add(v);
    }

    private record EtfHoldingsFetched(
            String etfCode, String etfName, BigDecimal currentValue,
            List<Map<String, Object>> holdings, String message) {}

    private static final class Aggregate {
        final String name;
        String code;
        BigDecimal value = BigDecimal.ZERO;
        Aggregate(String name) { this.name = name; }
    }
}
