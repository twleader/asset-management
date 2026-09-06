package com.steven.assets.apierrorlog;

import java.util.List;

/** Immutable technical allowlist shared by all Task 417 producers. */
public final class ApiErrorLogOperationCatalog {
    private ApiErrorLogOperationCatalog() {}
    public record Operation(String source, String operationKey, String apiName, int displayOrder) {}
    public static final String OPEN_API = "OPEN_API";
    public static final String FUBON_API = "FUBON_API";
    public static final List<Operation> OPERATIONS = List.of(
            new Operation(OPEN_API,"OPEN_QUOTES_LIST","即時報價清單",10), new Operation(OPEN_API,"OPEN_QUOTES_ONE","單一即時報價",20),
            new Operation(OPEN_API,"OPEN_MARKET_INDEX","大盤指數",30), new Operation(OPEN_API,"OPEN_LATEST_ASSETS","最新資產",40),
            new Operation(OPEN_API,"OPEN_USD_TWD","美元兌台幣",50), new Operation(OPEN_API,"OPEN_MARKET_ANALYSIS_TODAY","今日市場分析",60),
            new Operation(OPEN_API,"OPEN_PORTFOLIO_ADVICE_LATEST","最新資產配置建議",70), new Operation(OPEN_API,"OPEN_TRADING_RADAR_TODAY","今日交易雷達",80),
            new Operation(OPEN_API,"OPEN_TRADING_RADAR_STOCK","單一標的交易雷達",90), new Operation(OPEN_API,"OPEN_TRANSACTIONS","公開交易紀錄",100),
            new Operation(OPEN_API,"OPEN_TRADING_CALENDAR","交易日曆",110), new Operation(OPEN_API,"OPEN_COMMODITY_PRICES","油價金價",120),
            new Operation(OPEN_API,"OPEN_CRAWLER_RESCAN","公開爬蟲重新掃描",130),
            new Operation(FUBON_API,"FUBON_PORTFOLIO_READ","庫存與未實現損益",10), new Operation(FUBON_API,"FUBON_TW_QUOTES_INVENTORY","庫存同步台股報價",20),
            new Operation(FUBON_API,"FUBON_FILLED_TRADES_READ","已成交交易查詢",30), new Operation(FUBON_API,"FUBON_ETF_HOLDINGS_READ","ETF 成分股查詢",40),
            new Operation(FUBON_API,"FUBON_BANK_BALANCE_READ","交割銀行餘額",50), new Operation(FUBON_API,"FUBON_SETTLEMENT_READ","交割款查詢",60),
            new Operation(FUBON_API,"FUBON_REALIZED_GAINS_READ","已實現損益",70), new Operation(FUBON_API,"FUBON_TW_QUOTES_LIVE","盤中台股即時報價",80),
            new Operation(FUBON_API,"FUBON_TAIEX_INDEX_STREAM","加權指數串流",90), new Operation(FUBON_API,"FUBON_DIVIDENDS_READ","股利資料查詢",100),
            new Operation(FUBON_API,"FUBON_TECHNICAL_INDICATORS_READ","技術指標查詢",110), new Operation(FUBON_API,"FUBON_STOCK_BASIC_READ","個股基本資料查詢",120),
            new Operation(FUBON_API,"FUBON_INTRADAY_CANDLES_READ","分鐘 K 線查詢",130), new Operation(FUBON_API,"FUBON_STOCK_PUSH_SUBSCRIPTIONS","個股推播訂閱",140),
            new Operation(FUBON_API,"FUBON_STOCK_PUSH_STREAM","個股推播串流",150));
    public static Operation require(String source, String key, String apiName) {
        return OPERATIONS.stream().filter(o -> o.source.equals(source) && o.operationKey.equals(key) && o.apiName.equals(apiName))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown API error log operation"));
    }
}
