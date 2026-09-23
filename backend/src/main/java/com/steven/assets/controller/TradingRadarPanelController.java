package com.steven.assets.controller;

import com.steven.assets.dto.TradingRadarPanelDto;
import com.steven.assets.service.TradingRadarRefreshJobService;
import com.steven.assets.service.TradingRadarService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP-only adapter for the independent browser reads and explicit manual refresh jobs. */
@RestController
@RequestMapping("/api/trading-radar")
@RequiredArgsConstructor
public class TradingRadarPanelController {
    private final TradingRadarService service;
    private final TradingRadarRefreshJobService jobs;

    @GetMapping("/panels/tw-market")
    public TradingRadarPanelDto.Panel<TradingRadarPanelDto.MarketData> taiwanMarket() {
        return service.getMarketPanel("台股");
    }

    @GetMapping("/panels/us-market")
    public TradingRadarPanelDto.Panel<TradingRadarPanelDto.MarketData> usMarket() {
        return service.getMarketPanel("美股");
    }

    @GetMapping("/panels/tw-stocks")
    public TradingRadarPanelDto.Panel<TradingRadarPanelDto.StocksData> taiwanStocks() {
        return service.getStocksPanel("台股");
    }

    @GetMapping("/panels/us-stocks")
    public TradingRadarPanelDto.Panel<TradingRadarPanelDto.StocksData> usStocks() {
        return service.getStocksPanel("美股");
    }

    @GetMapping("/panels/public-information")
    public TradingRadarPanelDto.Panel<TradingRadarPanelDto.PublicInformationData> publicInformation() {
        return service.getPublicInformationPanel();
    }

    @GetMapping("/stock-evaluation")
    public TradingRadarPanelDto.StockEvaluation stockEvaluation(@RequestParam(required = false) String stockCode,
                                                               @RequestParam(required = false) String market) {
        return service.getStockEvaluation(stockCode, market);
    }

    @PostMapping("/refresh-jobs")
    public ResponseEntity<TradingRadarPanelDto.RefreshJob> startRefresh() {
        return ResponseEntity.accepted().body(jobs.start());
    }

    @GetMapping("/refresh-jobs/{jobId}")
    public TradingRadarPanelDto.RefreshJob refreshStatus(@PathVariable String jobId) {
        return jobs.get(jobId);
    }
}
