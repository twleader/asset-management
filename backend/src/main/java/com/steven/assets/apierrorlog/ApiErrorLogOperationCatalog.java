package com.steven.assets.apierrorlog;

import java.util.List;

/** Immutable technical allowlist shared by all Task 417 producers. */
public final class ApiErrorLogOperationCatalog {
    private ApiErrorLogOperationCatalog() {}
    public record Operation(String source, String operationKey, String apiName, String apiUrl, int displayOrder) {}
    public static final String OPEN_API = "OPEN_API";
    public static final String FUBON_API = "FUBON_API";
    public static final List<Operation> OPERATIONS = List.of(
            new Operation(OPEN_API,"OPEN_QUOTES_LIST","即時報價清單","GET /api/quotes",10), new Operation(OPEN_API,"OPEN_QUOTES_ONE","單一即時報價","GET /api/quotes/one",20),
            new Operation(OPEN_API,"OPEN_MARKET_INDEX","大盤指數","GET /api/public/market-index",30), new Operation(OPEN_API,"OPEN_LATEST_ASSETS","最新資產","GET /api/assets/latest",40),
            new Operation(OPEN_API,"OPEN_USD_TWD","美元兌台幣","GET /api/public/exchange-rate/usd-twd",50), new Operation(OPEN_API,"OPEN_MARKET_ANALYSIS_TODAY","今日市場分析","GET /api/public/market-analysis/today",60),
            new Operation(OPEN_API,"OPEN_PORTFOLIO_ADVICE_LATEST","最新資產配置建議","GET /api/public/portfolio-advice/latest",70), new Operation(OPEN_API,"OPEN_TRADING_RADAR_TODAY","今日交易雷達","GET /api/public/trading-radar/today",80),
            new Operation(OPEN_API,"OPEN_TRADING_RADAR_STOCK","單一標的交易雷達","GET /api/public/trading-radar/stock",90), new Operation(OPEN_API,"OPEN_TRANSACTIONS","公開交易紀錄","GET /api/public/transactions",100),
            new Operation(OPEN_API,"OPEN_TRADING_CALENDAR","交易日曆","GET /api/public/trading-calendar",110), new Operation(OPEN_API,"OPEN_COMMODITY_PRICES","油價金價","GET /api/public/commodity-prices",120),
            new Operation(OPEN_API,"OPEN_CRAWLER_RESCAN","公開爬蟲重新掃描","POST /api/public/crawler-data/rescan",130),
            new Operation(FUBON_API,"FUBON_PORTFOLIO_READ","庫存與未實現損益","POST /internal/portfolio/read",10), new Operation(FUBON_API,"FUBON_TW_QUOTES_INVENTORY","庫存同步台股報價","POST /internal/market-data/tw-quotes",20),
            new Operation(FUBON_API,"FUBON_FILLED_TRADES_READ","已成交交易查詢","POST /internal/trades/read",30), new Operation(FUBON_API,"FUBON_ETF_HOLDINGS_READ","ETF 成分股查詢","POST /internal/market-data/etf-holdings",40),
            new Operation(FUBON_API,"FUBON_BANK_BALANCE_READ","交割銀行餘額","POST /internal/bank-balance/read",50), new Operation(FUBON_API,"FUBON_SETTLEMENT_READ","交割款查詢","POST /internal/settlement/read",60),
            new Operation(FUBON_API,"FUBON_REALIZED_GAINS_READ","已實現損益","POST /internal/realized-gains/read",70), new Operation(FUBON_API,"FUBON_TW_QUOTES_LIVE","盤中台股即時報價","POST /internal/market-data/tw-quotes",80),
            new Operation(FUBON_API,"FUBON_TAIEX_INDEX_STREAM","加權指數串流","GET /internal/market-data/taiex-index/stream",90), new Operation(FUBON_API,"FUBON_DIVIDENDS_READ","股利資料查詢","POST /internal/market-data/dividends/read",100),
            new Operation(FUBON_API,"FUBON_TECHNICAL_INDICATORS_READ","技術指標查詢","POST /internal/market-data/technical-indicators/read",110), new Operation(FUBON_API,"FUBON_STOCK_BASIC_READ","個股基本資料查詢","POST /internal/market-data/stock-basic/read",120),
            new Operation(FUBON_API,"FUBON_INTRADAY_CANDLES_READ","分鐘 K 線查詢","POST /internal/market-data/intraday-candles/read",130), new Operation(FUBON_API,"FUBON_STOCK_PUSH_SUBSCRIPTIONS","個股推播訂閱","POST /internal/market-data/stock-push/subscriptions",140),
            new Operation(FUBON_API,"FUBON_STOCK_PUSH_STREAM","個股推播串流","GET /internal/market-data/stock-push/stream",150), new Operation(FUBON_API,"FUBON_INTRADAY_VOLUMES_READ","個股當日分價量查詢","POST /internal/market-data/intraday-volumes/read",160),
            new Operation(FUBON_API,"FUBON_HISTORICAL_DAILY_CANDLES_READ","個股歷史日K線查詢","POST /internal/market-data/historical-daily-candles/read",170));
    public static Operation require(String source, String key, String apiName) {
        return OPERATIONS.stream().filter(o -> o.source.equals(source) && o.operationKey.equals(key) && o.apiName.equals(apiName))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown API error log operation"));
    }
}
