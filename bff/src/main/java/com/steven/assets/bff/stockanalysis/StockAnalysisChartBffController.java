package com.steven.assets.bff.stockanalysis;

import com.steven.assets.bff.stockanalysis.dto.ChartSeriesDto;
import com.steven.assets.bff.stockanalysis.dto.IndicatorPointDto;
import com.steven.assets.bff.stockanalysis.dto.PricePointDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;

/**
 * StockAnalysisDialog 走勢圖的 aggregation controller（Task 261）。
 *
 * 走勢圖要畫「股價 + 技術指標」，兩者分屬 business 的兩支 API 且<b>日期集合可能不等</b>
 * （股價側的今日格要求 live 的 source 不含括號、0000 台股大盤更完全不併 live；
 * 指標側則比照 computeAll() 併入）。此跨來源 join 屬 aggregation，依 CLAUDE.md
 * 「BFF 負責跨服務 aggregation、預先計算，前端只負責 render」必須在這裡完成。
 *
 * 與同前綴的 {@link StockAnalysisBffRoutes} 五條 Gateway route 並存不衝突：那些 route
 * 皆為精確路徑（無萬用），且 WebFlux RequestMappingHandlerMapping（order 0）本就先於
 * Gateway RoutePredicateHandlerMapping（order 1）。同一模式的既有先例為
 * StockAlertBffController ＋ StockAlertBffRoutes。
 *
 * 身分傳遞不必在此處理：WebClientConfig 的 tenantHeaderFilter 已掛在 businessServicesClient 上。
 * 股價與指標皆為全域公開行情（無 owner 欄位），不需 owner 過濾。
 */
@Slf4j
@RestController
@RequestMapping("/api/bff/stock-analysis")
@RequiredArgsConstructor
public class StockAnalysisChartBffController {

    private final WebClient businessServicesClient;

    private static final ParameterizedTypeReference<List<PricePointDto>> PRICE_LIST =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<IndicatorPointDto>> INDICATOR_LIST =
            new ParameterizedTypeReference<>() {};

    /**
     * 走勢圖上下兩個 pane 的完整資料，日期已聯集對齊。
     *
     * 指標上游失敗時仍回傳股價陣列（指標欄全 null、latest 為 null），走勢圖不得整張消失。
     */
    @GetMapping("/chart-series")
    public Mono<ChartSeriesDto> getChartSeries(
            @RequestParam String code,
            @RequestParam String market,
            @RequestParam String start,
            @RequestParam String end) {

        Mono<List<PricePointDto>> pricesMono = businessServicesClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/market-data/history/stock")
                        .queryParam("code", code)
                        .queryParam("market", market)
                        .queryParam("start", start)
                        .queryParam("end", end)
                        .build())
                .retrieve()
                .bodyToMono(PRICE_LIST)
                .onErrorResume(e -> {
                    log.warn("chart-series: history/stock failed for {} {}", code, market, e);
                    return Mono.just(Collections.emptyList());
                });

        Mono<List<IndicatorPointDto>> indicatorsMono = businessServicesClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/market-data/indicators/series")
                        .queryParam("code", code)
                        .queryParam("market", market)
                        .queryParam("start", start)
                        .queryParam("end", end)
                        .build())
                .retrieve()
                .bodyToMono(INDICATOR_LIST)
                .onErrorResume(e -> {
                    log.warn("chart-series: indicators/series failed for {} {}", code, market, e);
                    return Mono.just(Collections.emptyList());
                });

        return Mono.zip(pricesMono, indicatorsMono)
                .map(t -> ChartSeriesAligner.align(t.getT1(), t.getT2()));
    }
}
