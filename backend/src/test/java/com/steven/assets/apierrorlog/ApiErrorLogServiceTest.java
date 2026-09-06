package com.steven.assets.apierrorlog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.model.AppUser;
import com.steven.assets.security.AdminRequiredException;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.security.UnauthenticatedException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ApiErrorLogServiceTest {
    @Test void business_read_model_rejects_missing_user_and_non_admin_before_repository_access() {
        ApiErrorLogRepository repository = mock(ApiErrorLogRepository.class);
        CurrentUserContext currentUser = new CurrentUserContext();
        ApiErrorLogService service = new ApiErrorLogService(repository, currentUser);

        assertThatThrownBy(() -> service.list("ALL", null, "NEWEST")).isInstanceOf(UnauthenticatedException.class);
        currentUser.setEffectiveUserId(7L);
        currentUser.setRole(AppUser.ROLE_USER);
        assertThatThrownBy(() -> service.operations("ALL")).isInstanceOf(AdminRequiredException.class);

        verifyNoInteractions(repository);
    }

    @Test void catalog_has_all_28_fixed_source_key_name_url_and_order_values() {
        assertThat(ApiErrorLogOperationCatalog.OPERATIONS).containsExactlyElementsOf(expectedCatalog());
        assertThat(ApiErrorLogOperationCatalog.OPERATIONS).allSatisfy(operation ->
                assertThat(operation.apiUrl()).isNotBlank());
    }

    @Test void operations_keep_deterministic_all_and_single_source_order() {
        ApiErrorLogService service = adminService();
        List<ApiErrorLogOperationCatalog.Operation> expected = expectedCatalog();

        assertThat(service.operations("ALL")).containsExactlyElementsOf(expected);
        assertThat(service.operations(ApiErrorLogOperationCatalog.OPEN_API)).containsExactlyElementsOf(expected.subList(0, 13));
        assertThat(service.operations(ApiErrorLogOperationCatalog.FUBON_API)).containsExactlyElementsOf(expected.subList(13, 28));
    }

    @Test void operations_json_has_exactly_five_fields_and_list_detail_do_not_expose_api_url() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var operation = json.readTree(json.writeValueAsString(adminService().operations("ALL").getFirst()));
        assertThat(operation.size()).isEqualTo(5);
        for (String field : List.of("source", "operationKey", "apiName", "apiUrl", "displayOrder")) assertThat(operation.has(field)).isTrue();
        assertThat(operation.path("apiUrl").asText()).isEqualTo("GET /api/quotes");

        var list = json.readTree(json.writeValueAsString(new ApiErrorLogService.ListItem(1L, "OPEN_API",
                "OPEN_QUOTES_LIST", "即時報價清單", "failure", null)));
        var detail = json.readTree(json.writeValueAsString(new ApiErrorLogService.Detail(1L, "OPEN_API",
                "OPEN_QUOTES_LIST", "即時報價清單", "failure", "trace", null)));
        assertThat(list.has("apiUrl")).isFalse();
        assertThat(detail.has("apiUrl")).isFalse();
    }

    private static ApiErrorLogService adminService() {
        CurrentUserContext currentUser = new CurrentUserContext();
        currentUser.setEffectiveUserId(7L);
        currentUser.setRole(AppUser.ROLE_ADMIN);
        return new ApiErrorLogService(mock(ApiErrorLogRepository.class), currentUser);
    }

    private static List<ApiErrorLogOperationCatalog.Operation> expectedCatalog() {
        return List.of(
                operation("OPEN_API", "OPEN_QUOTES_LIST", "即時報價清單", "GET /api/quotes", 10),
                operation("OPEN_API", "OPEN_QUOTES_ONE", "單一即時報價", "GET /api/quotes/one", 20),
                operation("OPEN_API", "OPEN_MARKET_INDEX", "大盤指數", "GET /api/public/market-index", 30),
                operation("OPEN_API", "OPEN_LATEST_ASSETS", "最新資產", "GET /api/assets/latest", 40),
                operation("OPEN_API", "OPEN_USD_TWD", "美元兌台幣", "GET /api/public/exchange-rate/usd-twd", 50),
                operation("OPEN_API", "OPEN_MARKET_ANALYSIS_TODAY", "今日市場分析", "GET /api/public/market-analysis/today", 60),
                operation("OPEN_API", "OPEN_PORTFOLIO_ADVICE_LATEST", "最新資產配置建議", "GET /api/public/portfolio-advice/latest", 70),
                operation("OPEN_API", "OPEN_TRADING_RADAR_TODAY", "今日交易雷達", "GET /api/public/trading-radar/today", 80),
                operation("OPEN_API", "OPEN_TRADING_RADAR_STOCK", "單一標的交易雷達", "GET /api/public/trading-radar/stock", 90),
                operation("OPEN_API", "OPEN_TRANSACTIONS", "公開交易紀錄", "GET /api/public/transactions", 100),
                operation("OPEN_API", "OPEN_TRADING_CALENDAR", "交易日曆", "GET /api/public/trading-calendar", 110),
                operation("OPEN_API", "OPEN_COMMODITY_PRICES", "油價金價", "GET /api/public/commodity-prices", 120),
                operation("OPEN_API", "OPEN_CRAWLER_RESCAN", "公開爬蟲重新掃描", "POST /api/public/crawler-data/rescan", 130),
                operation("FUBON_API", "FUBON_PORTFOLIO_READ", "庫存與未實現損益", "POST /internal/portfolio/read", 10),
                operation("FUBON_API", "FUBON_TW_QUOTES_INVENTORY", "庫存同步台股報價", "POST /internal/market-data/tw-quotes", 20),
                operation("FUBON_API", "FUBON_FILLED_TRADES_READ", "已成交交易查詢", "POST /internal/trades/read", 30),
                operation("FUBON_API", "FUBON_ETF_HOLDINGS_READ", "ETF 成分股查詢", "POST /internal/market-data/etf-holdings", 40),
                operation("FUBON_API", "FUBON_BANK_BALANCE_READ", "交割銀行餘額", "POST /internal/bank-balance/read", 50),
                operation("FUBON_API", "FUBON_SETTLEMENT_READ", "交割款查詢", "POST /internal/settlement/read", 60),
                operation("FUBON_API", "FUBON_REALIZED_GAINS_READ", "已實現損益", "POST /internal/realized-gains/read", 70),
                operation("FUBON_API", "FUBON_TW_QUOTES_LIVE", "盤中台股即時報價", "POST /internal/market-data/tw-quotes", 80),
                operation("FUBON_API", "FUBON_TAIEX_INDEX_STREAM", "加權指數串流", "GET /internal/market-data/taiex-index/stream", 90),
                operation("FUBON_API", "FUBON_DIVIDENDS_READ", "股利資料查詢", "POST /internal/market-data/dividends/read", 100),
                operation("FUBON_API", "FUBON_TECHNICAL_INDICATORS_READ", "技術指標查詢", "POST /internal/market-data/technical-indicators/read", 110),
                operation("FUBON_API", "FUBON_STOCK_BASIC_READ", "個股基本資料查詢", "POST /internal/market-data/stock-basic/read", 120),
                operation("FUBON_API", "FUBON_INTRADAY_CANDLES_READ", "分鐘 K 線查詢", "POST /internal/market-data/intraday-candles/read", 130),
                operation("FUBON_API", "FUBON_STOCK_PUSH_SUBSCRIPTIONS", "個股推播訂閱", "POST /internal/market-data/stock-push/subscriptions", 140),
                operation("FUBON_API", "FUBON_STOCK_PUSH_STREAM", "個股推播串流", "GET /internal/market-data/stock-push/stream", 150));
    }

    private static ApiErrorLogOperationCatalog.Operation operation(String source, String key, String name, String apiUrl, int order) {
        return new ApiErrorLogOperationCatalog.Operation(source, key, name, apiUrl, order);
    }
}
