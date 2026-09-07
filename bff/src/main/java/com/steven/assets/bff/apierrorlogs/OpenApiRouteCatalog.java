package com.steven.assets.bff.apierrorlogs;

import java.util.Map;

/**
 * Exact 13-route allowlist shared by {@link PublicApiErrorCaptureWebFilter} (in-process 4xx/5xx
 * capture) and {@link NginxGatewayFailureLogTailer} (Nginx-side connection-failure capture). Both
 * producers must agree on exactly the same route set and operation identity; keeping a single
 * shared copy means a future route addition/removal only needs to change one place.
 */
public final class OpenApiRouteCatalog {
    private OpenApiRouteCatalog() {}
    public record Operation(String key, String name) {}
    public static final Map<String, Operation> ROUTES = Map.ofEntries(
      Map.entry("GET /api/quotes",new Operation("OPEN_QUOTES_LIST","即時報價清單")),Map.entry("GET /api/quotes/one",new Operation("OPEN_QUOTES_ONE","單一即時報價")),Map.entry("GET /api/public/market-index",new Operation("OPEN_MARKET_INDEX","大盤指數")),Map.entry("GET /api/assets/latest",new Operation("OPEN_LATEST_ASSETS","最新資產")),Map.entry("GET /api/public/exchange-rate/usd-twd",new Operation("OPEN_USD_TWD","美元兌台幣")),Map.entry("GET /api/public/market-analysis/today",new Operation("OPEN_MARKET_ANALYSIS_TODAY","今日市場分析")),Map.entry("GET /api/public/portfolio-advice/latest",new Operation("OPEN_PORTFOLIO_ADVICE_LATEST","最新資產配置建議")),Map.entry("GET /api/public/trading-radar/today",new Operation("OPEN_TRADING_RADAR_TODAY","今日交易雷達")),Map.entry("GET /api/public/trading-radar/stock",new Operation("OPEN_TRADING_RADAR_STOCK","單一標的交易雷達")),Map.entry("GET /api/public/transactions",new Operation("OPEN_TRANSACTIONS","公開交易紀錄")),Map.entry("GET /api/public/trading-calendar",new Operation("OPEN_TRADING_CALENDAR","交易日曆")),Map.entry("GET /api/public/commodity-prices",new Operation("OPEN_COMMODITY_PRICES","油價金價")),Map.entry("POST /api/public/crawler-data/rescan",new Operation("OPEN_CRAWLER_RESCAN","公開爬蟲重新掃描")));
}
