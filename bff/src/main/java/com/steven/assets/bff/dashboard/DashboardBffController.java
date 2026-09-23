package com.steven.assets.bff.dashboard;

import com.steven.assets.bff.common.LiveAssetsOverlay;
import com.steven.assets.bff.common.SnapshotEnricher;
import com.steven.assets.bff.dashboard.dto.DashboardSummaryDto;
import com.steven.assets.bff.dashboard.dto.DashboardPanelResponse;
import com.steven.assets.bff.dashboard.dto.TwStockLookthroughDto;
import com.steven.assets.bff.dashboard.dto.UsStockLookthroughDto;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BFF aggregation controller for the Dashboard page (/dashboard).
 *
 * 設計原則：一個前端頁面對應一支 BFF controller。前端只 render，
 * aggregation 與計算（profit / profitRate / 收盤價對齊等）一律由 BFF 預先處理。
 */
@RestController
@RequestMapping("/api/bff/dashboard")
@RequiredArgsConstructor
public class DashboardBffController {

    private final WebClient businessServicesClient;
    private final SnapshotEnricher enricher;
    private final DashboardLookthroughService lookthroughService;
    private final DashboardPanelService panelService;

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    @GetMapping("/snapshots")
    public Mono<ResponseEntity<List<Map<String, Object>>>> getSnapshots() {
        return panelService.snapshots().map(ResponseEntity::ok);
    }

    @GetMapping("/panels/kpis/{id}")
    public Mono<ResponseEntity<DashboardPanelResponse>> getKpis(@PathVariable Long id) {
        return panelService.kpis(id).map(ResponseEntity::ok);
    }

    @GetMapping("/panels/allocation/{id}")
    public Mono<ResponseEntity<DashboardPanelResponse>> getAllocation(
            @PathVariable Long id, @RequestParam(defaultValue = "category") String tab) {
        return panelService.allocation(id, tab).map(ResponseEntity::ok);
    }

    @GetMapping("/panels/trend")
    public Mono<ResponseEntity<DashboardPanelResponse>> getTrend() {
        return panelService.trend().map(ResponseEntity::ok);
    }

    @GetMapping("/panels/deposits/{id}")
    public Mono<ResponseEntity<DashboardPanelResponse>> getDeposits(@PathVariable Long id) {
        return panelService.deposits(id).map(ResponseEntity::ok);
    }

    @GetMapping("/panels/stock-values/{id}")
    public Mono<ResponseEntity<DashboardPanelResponse>> getStockValues(@PathVariable Long id) {
        return panelService.stockValues(id).map(ResponseEntity::ok);
    }

    @GetMapping("/panels/holdings/{id}")
    public Mono<ResponseEntity<DashboardPanelResponse>> getHoldings(@PathVariable Long id) {
        return panelService.holdings(id).map(ResponseEntity::ok);
    }

    @GetMapping("/panels/funds/{id}")
    public Mono<ResponseEntity<DashboardPanelResponse>> getFunds(@PathVariable Long id) {
        return panelService.funds(id).map(ResponseEntity::ok);
    }

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

        return Mono.zip(snapshotsMono, historyMono, marketStatusMono, liveAssetsMono)
                .flatMap(tuple -> {
                    List<Map<String, Object>> snapshots = tuple.getT1();
                    List<Map<String, Object>> history = tuple.getT2();
                    Map<String, Object> marketStatus = tuple.getT3();
                    Map<String, Object> liveAssets = tuple.getT4();
                    List<Map<String, Object>> prices = DashboardStockPrices.fromLiveAssets(liveAssets);

                    // 最新一筆 history 套 per-market 基準日閘門覆蓋（僅「該市場今日」用 live，過去日期保留
                    // 凍結收盤），與「歷年資產管理」共用同一支 LiveAssetsOverlay → 兩頁 history 同義欄位同值。
                    LiveAssetsOverlay.applyToLatest(history, liveAssets);

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
                                return enricher.fetchSnapshotCloseData(detail)
                                        .map(cd -> {
                                            // 「股價基準日規則」per-market：每筆 live price 依其市場各自決定
                                            // 保留 live（basedate == 該市場時區的今日）或換成 basedate 收盤價。
                                            // 解決過去 TW 過午夜後美股盤中（TW 凌晨）被誤判為非當日 → 顯示前一交易日收盤的問題。
                                            // frozen（非當日）收盤價 entry 另帶入 changeMap 的當日漲跌，供收盤/週末頁顯示漲跌。
                                            dto.setStockPrices(SnapshotEnricher.mergePerMarketPrices(
                                                    basedate, prices, cd.closeMap(), cd.changeMap(), cd.displayRows()));
                                            dto.setMergedStocks(
                                                    enricher.buildMergedStocks(detail, cd.closeMap(), false, true));
                                            return ResponseEntity.ok(dto);
                                        });
                            });
                });
    }

    /**
     * GET /api/bff/dashboard/realtime
     * 2 分鐘輪詢用：從 live-assets 投影持股報價 + market status；同一輪只讀一次行情資料。
     * 不再從 BFF 觸發 refresh — producer 自己維護行情，BFF 只負責純讀投影。
     */
    @GetMapping("/realtime")
    public Mono<ResponseEntity<Map<String, Object>>> getRealtime() {
        return Mono.zip(
                businessServicesClient.get().uri("/api/market-data/market-status")
                        .retrieve().bodyToMono(MAP).onErrorReturn(Collections.emptyMap()),
                businessServicesClient.get().uri("/api/market-data/live-assets")
                        .retrieve().bodyToMono(MAP).onErrorReturn(Collections.emptyMap())
        ).map(t -> {
            Map<String, Object> body = new HashMap<>();
            body.put("stockPrices", DashboardStockPrices.fromLiveAssets(t.getT2()));
            body.put("marketStatus", t.getT1());
            body.put("liveAssets", t.getT2());
            return ResponseEntity.ok(body);
        });
    }

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

    /**
     * GET /api/bff/dashboard/holdings-classified/{id}
     * 「現金/債券/股票」雙層圓餅外圈 hover 用：回傳該快照逐持股的分類（assetClass/stockStyle/bondTerm）
     * + currentValue，前端據此列出某子分類底下的個別持股與金額。passthrough business-services 分類結果。
     */
    @GetMapping("/holdings-classified/{id}")
    public Mono<ResponseEntity<List<Map<String, Object>>>> getHoldingsClassified(@PathVariable Long id) {
        return businessServicesClient.get()
                .uri("/api/snapshots/{id}/holdings-classified", id)
                .retrieve()
                .bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList())
                .map(ResponseEntity::ok);
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
                                enricher.buildMergedStocks(detail, closeMap, false, true));
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
     *  - ETF（stockCode 以 00 開頭）並行（concurrency 4）呼叫 /api/market-data/etf-holdings 取得成分股權重，
     *    將 currentValue × weight% 拆解到各個成分股代號；weights 加總不足 100% 的缺口計入 ETF 自身
     *    （成分股表通常涵蓋 95–100%，剩餘是現金 / 應收款部位）
     *  - 直接持股：整筆計入該股代號（不拆解）
     *  - 同股名加總（ETF 內含台積電 + 直接持有台積電合併；成分股來源僅提供股名無代號，故以股名為聚合鍵）
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
                                enricher.buildMergedStocks(detail, closeMap, false, true);
                        return lookthroughService.legacyTaiwan(detail, merged).map(ResponseEntity::ok);
                    });
                });
    }

    // ===== 美股個股穿透（「資產配置分佈」第 3 tab）=====
    // 與台股 getTwStockLookthrough 鏡像，但穿透演算法不同：美股成分股來源 Yahoo topHoldings 僅前 10 大，
    // 故依「真實權重」分配（不正規化）、未揭露尾段歸「其它」、以代號聚合。詳見 Requirement 9 / Task 103。
    @GetMapping("/us-stock-lookthrough/{snapshotId}")
    public Mono<ResponseEntity<UsStockLookthroughDto>> getUsStockLookthrough(
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
                                enricher.buildMergedStocks(detail, closeMap, false, true);
                        return lookthroughService.legacyUnitedStates(detail, merged).map(ResponseEntity::ok);
                    });
                });
    }
}
