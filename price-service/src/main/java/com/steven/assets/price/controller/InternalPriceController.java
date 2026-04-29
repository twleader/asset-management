package com.steven.assets.price.controller;

import com.steven.assets.price.service.MarketClock;
import com.steven.assets.price.service.PricePoller;
import com.steven.assets.price.service.PricePoller.RefreshSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 僅 docker network 內 business-services 呼叫；不對外暴露。
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalPriceController {

    private final PricePoller poller;
    private final MarketClock clock;

    /**
     * 同步抓所有持股報價、寫 Redis 後回傳統計。
     * 對應原 backend POST /api/market-data/prices/refresh 的內部觸發。
     */
    @PostMapping("/refresh")
    public RefreshSummary refresh() {
        return poller.refreshAll();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "twMarketOpen", clock.isTwMarketOpen(),
                "usMarketOpen", clock.isUsMarketOpen()
        );
    }
}
