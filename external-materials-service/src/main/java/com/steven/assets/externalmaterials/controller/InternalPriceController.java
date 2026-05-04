package com.steven.assets.externalmaterials.controller;

import com.steven.assets.externalmaterials.service.ClosePersister;
import com.steven.assets.externalmaterials.service.DividendPersister;
import com.steven.assets.externalmaterials.service.FundDividendBackfillService;
import com.steven.assets.externalmaterials.service.FundDividendPoller;
import com.steven.assets.externalmaterials.service.FundNavBackfillService;
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
    private final FundDividendPoller fundDividendPoller;
    private final FundNavBackfillService fundNavBackfillService;
    private final FundDividendBackfillService fundDividendBackfillService;
    private final ClosePersister closePersister;

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

    /**
     * 同步全抓信託基金配息歷史 (Requirement 20)。
     */
    @PostMapping("/fund-dividend/refresh")
    public FundDividendPoller.RefreshSummary refreshFundDividend() {
        return fundDividendPoller.refreshAll();
    }

    /** 信託基金 NAV 歷史回補 (Requirement 21)。 */
    @PostMapping("/fund-nav/backfill")
    public FundNavBackfillService.BackfillSummary backfillFundNav(
            @RequestParam(defaultValue = "10") int years) {
        return fundNavBackfillService.backfillAll(years);
    }

    /** 信託基金配息歷史回補 (Requirement 21)。 */
    @PostMapping("/fund-dividend/backfill")
    public FundDividendBackfillService.BackfillSummary backfillFundDividend(
            @RequestParam(defaultValue = "10") int years) {
        return fundDividendBackfillService.backfillAll(years);
    }

    /** 手動觸發 FinMind 校正當日台股收盤價（同 16:00 排程），覆寫 stock_price_history。 */
    @PostMapping("/close/verify-tw")
    public Map<String, Object> verifyTwClose() {
        int ok = closePersister.verifyTwCloseWithFinMind();
        return Map.of("verified", ok);
    }

    /** 手動觸發 FinMind 校正當日美股收盤價（同 18:00 ET 排程）。 */
    @PostMapping("/close/verify-us")
    public Map<String, Object> verifyUsClose() {
        int ok = closePersister.verifyUsCloseWithFinMind();
        return Map.of("verified", ok);
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
