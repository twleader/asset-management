package com.steven.assets.externalmaterials.controller;

import com.steven.assets.externalmaterials.service.DividendPersister;
import com.steven.assets.externalmaterials.service.FundNavPoller;
import com.steven.assets.externalmaterials.service.MarketClock;
import com.steven.assets.externalmaterials.service.PricePoller;
import com.steven.assets.externalmaterials.service.PricePoller.RefreshSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final DividendPersister dividendPersister;
    private final FundNavPoller fundNavPoller;

    /**
     * 同步抓所有持股報價、寫 Redis 後回傳統計。
     * 對應原 backend POST /api/market-data/prices/refresh 的內部觸發。
     */
    @PostMapping("/refresh")
    public RefreshSummary refresh() {
        return poller.refreshAll();
    }

    /**
     * Backend cold-cache fallback：抓單檔股利 + 寫 stock_dividend_history，回傳寫入筆數。
     */
    @PostMapping("/dividend/sync")
    public Map<String, Object> syncDividend(@RequestParam String code, @RequestParam String market) {
        int n = dividendPersister.syncOne(code, market);
        return Map.of("written", n);
    }

    /**
     * 同步全抓信託基金 NAV 寫入 fund_nav 表（Requirement 19）。
     * Backend / BFF 「刷新最新淨值」按鈕對應內部觸發。
     */
    @PostMapping("/fund-nav/refresh")
    public FundNavPoller.RefreshSummary refreshFundNav() {
        return fundNavPoller.refreshAll();
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
