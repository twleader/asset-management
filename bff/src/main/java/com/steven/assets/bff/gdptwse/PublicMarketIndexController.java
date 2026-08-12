package com.steven.assets.bff.gdptwse;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Docker host 與 Compose network 均可匿名讀取的單一股市大盤圖表 API。 */
@RestController
@RequestMapping("/api/public/market-index")
@RequiredArgsConstructor
public class PublicMarketIndexController {

    private final MarketIndexChartService marketIndexChartService;

    @GetMapping
    public Mono<ResponseEntity<MarketIndexChartDto.Response>> getMarketIndex(
            @RequestParam(required = false) String market,
            @RequestParam(required = false) String range) {
        return marketIndexChartService.getPublicChart(market, range).map(ResponseEntity::ok);
    }
}
