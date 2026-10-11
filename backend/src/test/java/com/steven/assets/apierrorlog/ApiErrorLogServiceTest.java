package com.steven.assets.apierrorlog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.model.AppUser;
import com.steven.assets.security.AdminRequiredException;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.security.UnauthenticatedException;
import com.steven.assets.controller.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class ApiErrorLogServiceTest {
    @Test void business_read_model_rejects_missing_user_and_non_admin_before_repository_access() {
        ApiErrorLogRepository repository = mock(ApiErrorLogRepository.class);
        CurrentUserContext currentUser = new CurrentUserContext();
        ApiErrorLogService service = new ApiErrorLogService(repository, currentUser);

        assertThatThrownBy(() -> service.list("ALL", null, "NEWEST", 0)).isInstanceOf(UnauthenticatedException.class);
        currentUser.setEffectiveUserId(7L);
        currentUser.setRole(AppUser.ROLE_USER);
        assertThatThrownBy(() -> service.list("ALL", null, "NEWEST", 0)).isInstanceOf(AdminRequiredException.class);
        assertThatThrownBy(() -> service.operations("ALL")).isInstanceOf(AdminRequiredException.class);
        assertThatThrownBy(() -> service.detail(1L)).isInstanceOf(AdminRequiredException.class);

        verifyNoInteractions(repository);
    }

    @Test void list_uses_fixed_100_row_pages_and_preserves_metadata_and_full_headers() {
        ApiErrorLogRepository repository = mock(ApiErrorLogRepository.class);
        ApiErrorLogService service = adminService(repository);
        Instant occurredAt = Instant.parse("2026-10-11T01:00:00Z");
        String header = "完整錯誤標題".repeat(300);
        List<ApiErrorLog> rows = IntStream.range(0, 5).mapToObj(i ->
                new ApiErrorLog("FUBON_API", "FUBON_PORTFOLIO_READ", "庫存與未實現損益", header,
                        "private trace", occurredAt, null, "private dedupe")).toList();
        when(repository.oldest("FUBON_API", "FUBON_PORTFOLIO_READ", PageRequest.of(2, 100)))
                .thenReturn(new PageImpl<>(rows, PageRequest.of(2, 100), 205));

        var result = service.list("FUBON_API", "FUBON_PORTFOLIO_READ", "OLDEST", 2);

        assertThat(result.items()).hasSize(5).allSatisfy(item -> {
            assertThat(item.messageHeader()).isEqualTo(header);
            assertThat(item.httpStatus()).isNull();
            assertThat(item.occurredAt()).isEqualTo(occurredAt);
        });
        assertThat(result.totalElements()).isEqualTo(205);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.pageSize()).isEqualTo(100);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThatThrownBy(() -> result.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        verify(repository).oldest("FUBON_API", "FUBON_PORTFOLIO_READ", PageRequest.of(2, 100));
        verifyNoMoreInteractions(repository);
    }

    @Test void list_returns_empty_and_out_of_range_pages_with_their_original_totals() {
        ApiErrorLogRepository repository = mock(ApiErrorLogRepository.class);
        ApiErrorLogService service = adminService(repository);
        when(repository.newest("ALL", null, PageRequest.of(0, 100))).thenReturn(Page.empty(PageRequest.of(0, 100)));
        when(repository.newest("ALL", null, PageRequest.of(4, 100)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(4, 100), 205));

        assertThat(service.list("ALL", null, "NEWEST", 0))
                .isEqualTo(new ApiErrorLogService.PageResult(List.of(), 0, 0, 100, 0));
        assertThat(service.list("ALL", null, "NEWEST", 4))
                .isEqualTo(new ApiErrorLogService.PageResult(List.of(), 205, 4, 100, 3));
    }

    @Test void invalid_page_source_sort_and_operation_are_rejected_before_repository_access() {
        ApiErrorLogRepository repository = mock(ApiErrorLogRepository.class);
        ApiErrorLogService service = adminService(repository);

        assertThatThrownBy(() -> service.list("ALL", null, "NEWEST", -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list("UNKNOWN", null, "NEWEST", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list("ALL", null, "UNKNOWN", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list("ALL", "ALL", "NEWEST", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list("OPEN_API", "FUBON_PORTFOLIO_READ", "NEWEST", 0)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test void controller_defaults_to_page_zero_and_returns_400_for_malformed_or_negative_page() throws Exception {
        ApiErrorLogRepository repository = mock(ApiErrorLogRepository.class);
        when(repository.newest("ALL", null, PageRequest.of(0, 100))).thenReturn(Page.empty(PageRequest.of(0, 100)));
        MockMvc client = MockMvcBuilders.standaloneSetup(new ApiErrorLogController(adminService(repository)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        client.perform(get("/api/api-error-logs")).andExpect(status().isOk());
        for (String invalidPage : List.of("not-an-integer", "2147483648")) {
            String body = client.perform(get("/api/api-error-logs").param("page", invalidPage))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("Invalid request parameter"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain(invalidPage, "NumberFormatException", "java.lang.Integer", "Failed to convert");
        }
        client.perform(get("/api/api-error-logs").param("page", "-1")).andExpect(status().isBadRequest());
        verify(repository).newest("ALL", null, PageRequest.of(0, 100));
        verifyNoMoreInteractions(repository);
    }

    @Test void global_advice_handles_typed_query_and_path_conversion_without_exposing_rejected_values() throws Exception {
        MockMvc client = MockMvcBuilders.standaloneSetup(new ParameterConversionStub())
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        for (String invalidValue : List.of("REJECTED_VALUE_SENTINEL", "9223372036854775808")) {
            for (var request : List.of(
                    get("/parameter-conversion-stub/query").param("value", invalidValue),
                    get("/parameter-conversion-stub/path/{value}", invalidValue))) {
                String body = client.perform(request).andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.detail").value("Invalid request parameter"))
                        .andExpect(jsonPath("$.instance").value("about:blank"))
                        .andReturn().getResponse().getContentAsString();
                assertThat(body).doesNotContain(invalidValue, "NumberFormatException", "java.lang.Long", "Failed to convert");
            }
        }
    }

    @RestController
    static class ParameterConversionStub {
        @GetMapping("/parameter-conversion-stub/query")
        public Long query(@RequestParam("value") Long value) { return value; }
        @GetMapping("/parameter-conversion-stub/path/{value}")
        public Long path(@PathVariable("value") Long value) { return value; }
    }

    @Test void catalog_has_all_42_fixed_source_key_name_url_and_order_values() {
        assertThat(ApiErrorLogOperationCatalog.OPERATIONS).containsExactlyElementsOf(expectedCatalog());
        assertThat(ApiErrorLogOperationCatalog.OPERATIONS).allSatisfy(operation ->
                assertThat(operation.apiUrl()).isNotBlank());
    }

    @Test void operations_keep_deterministic_all_and_single_source_order() {
        ApiErrorLogService service = adminService();
        List<ApiErrorLogOperationCatalog.Operation> expected = expectedCatalog();

        assertThat(service.operations("ALL")).containsExactlyElementsOf(expected);
        assertThat(service.operations(ApiErrorLogOperationCatalog.OPEN_API)).containsExactlyElementsOf(expected.subList(0, 25));
        assertThat(service.operations(ApiErrorLogOperationCatalog.FUBON_API)).containsExactlyElementsOf(expected.subList(25, 42));
    }

    @Test void operations_json_has_exactly_five_fields_and_list_detail_do_not_expose_api_url() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var operation = json.readTree(json.writeValueAsString(adminService().operations("ALL").getFirst()));
        assertThat(operation.size()).isEqualTo(5);
        for (String field : List.of("source", "operationKey", "apiName", "apiUrl", "displayOrder")) assertThat(operation.has(field)).isTrue();
        assertThat(operation.path("apiUrl").asText()).isEqualTo("GET /api/quotes");

        var list = json.readTree(json.writeValueAsString(new ApiErrorLogService.ListItem(1L, "OPEN_API",
                "OPEN_QUOTES_LIST", "即時報價清單", "failure", null, null)));
        var detail = json.readTree(json.writeValueAsString(new ApiErrorLogService.Detail(1L, "OPEN_API",
                "OPEN_QUOTES_LIST", "即時報價清單", "failure", "trace", null, null)));
        assertThat(list.has("apiUrl")).isFalse();
        assertThat(list.has("stackTrace")).isFalse();
        assertThat(list.has("dedupeKey")).isFalse();
        assertThat(list.size()).isEqualTo(7);
        assertThat(detail.has("apiUrl")).isFalse();
    }

    @Test void list_and_detail_from_carry_http_status_including_null_for_fubon_api_rows() {
        ApiErrorLog withStatus = new ApiErrorLog("OPEN_API", "OPEN_MARKET_INDEX", "大盤指數", "header", "trace",
                java.time.Instant.parse("2026-09-07T01:00:00Z"), 400, null);
        ApiErrorLog withoutStatus = new ApiErrorLog("FUBON_API", "FUBON_PORTFOLIO_READ", "庫存與未實現損益", "header", "trace",
                java.time.Instant.parse("2026-09-07T01:00:00Z"), null, null);

        ApiErrorLogService.ListItem listWithStatus = ApiErrorLogService.ListItem.from(withStatus);
        ApiErrorLogService.ListItem listWithoutStatus = ApiErrorLogService.ListItem.from(withoutStatus);
        ApiErrorLogService.Detail detailWithStatus = ApiErrorLogService.Detail.from(withStatus);
        ApiErrorLogService.Detail detailWithoutStatus = ApiErrorLogService.Detail.from(withoutStatus);

        assertThat(listWithStatus.httpStatus()).isEqualTo(400);
        assertThat(listWithoutStatus.httpStatus()).isNull();
        assertThat(detailWithStatus.httpStatus()).isEqualTo(400);
        assertThat(detailWithoutStatus.httpStatus()).isNull();
    }

    private static ApiErrorLogService adminService() {
        return adminService(mock(ApiErrorLogRepository.class));
    }

    private static ApiErrorLogService adminService(ApiErrorLogRepository repository) {
        CurrentUserContext currentUser = new CurrentUserContext();
        currentUser.setEffectiveUserId(7L);
        currentUser.setRole(AppUser.ROLE_ADMIN);
        return new ApiErrorLogService(repository, currentUser);
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
                operation("OPEN_API", "OPEN_SRPP_DAILY_CONTEXT", "SRPP 共用計算結果", "GET /api/public/srpp/daily-context", 140),
                operation("OPEN_API", "OPEN_SRPP_CALCULATION_CONTEXT", "SRPP 計算脈絡", "GET /api/public/srpp/calculation-context", 150),
                operation("OPEN_API", "OPEN_SRPP_CALCULATIONS", "SRPP 按需計算", "GET /api/public/srpp/calculations", 160),
                operation("OPEN_API", "OPEN_SRPP_MARKET_FACTS", "SRPP 批次市場事實", "GET /api/public/srpp/market-facts", 170),
                operation("OPEN_API", "OPEN_SRPP_COMPLETED_TECHNICALS", "SRPP 完成日技術事實", "GET /api/public/srpp/completed-technicals", 180),
                operation("OPEN_API", "OPEN_SRPP_TECHNICAL_SERIES", "SRPP 逐日技術序列", "GET /api/public/srpp/technical-series", 190),
                operation("OPEN_API", "OPEN_SRPP_DAILY_REPORT_MAIL", "SRPP 日報寄送", "POST /api/srpp/daily-report-mail", 200),
                operation("OPEN_API", "OPEN_SRPP_DAILY_REPORT_MAIL_STATUS", "SRPP 日報寄送狀態", "GET /api/srpp/daily-report-mail/{idempotencyKey}", 210),
                operation("OPEN_API", "OPEN_SRPP_EVENT_EVIDENCE", "SRPP 事件證據收據", "POST /api/public/srpp/event-evidence/capture", 220),
                operation("OPEN_API", "OPEN_SRPP_DAILY_DECISION", "SRPP 日報決策收據", "POST /api/public/srpp/daily-decision/evaluate", 230),
                operation("OPEN_API", "OPEN_SRPP_REPORT_CALCULATIONS", "SRPP 不可變報告計算包", "GET /api/public/srpp/report-calculations", 240),
                operation("OPEN_API", "OPEN_SRPP_AI_FLOW_EVENTS", "AI 判斷流程紀錄", "POST /api/srpp/ai-flow/events", 250),
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
                operation("FUBON_API", "FUBON_TECHNICAL_INDICATORS_READ", "技術指標查詢", "POST /internal/market-data/technical-indicators/read (D/W)；POST /internal/market-data/intraday-technical-indicators/read (1m/5m)", 110),
                operation("FUBON_API", "FUBON_STOCK_BASIC_READ", "個股基本資料查詢", "POST /internal/market-data/stock-basic/read", 120),
                operation("FUBON_API", "FUBON_INTRADAY_CANDLES_READ", "分鐘 K 線查詢", "POST /internal/market-data/intraday-candles/read", 130),
                operation("FUBON_API", "FUBON_STOCK_PUSH_SUBSCRIPTIONS", "個股推播訂閱", "POST /internal/market-data/stock-push/subscriptions", 140),
                operation("FUBON_API", "FUBON_STOCK_PUSH_STREAM", "個股推播串流", "GET /internal/market-data/stock-push/stream", 150),
                operation("FUBON_API", "FUBON_INTRADAY_VOLUMES_READ", "個股當日分價量查詢", "POST /internal/market-data/intraday-volumes/read", 160),
                operation("FUBON_API", "FUBON_HISTORICAL_DAILY_CANDLES_READ", "個股歷史日K線查詢", "POST /internal/market-data/historical-daily-candles/read", 170));
    }

    private static ApiErrorLogOperationCatalog.Operation operation(String source, String key, String name, String apiUrl, int order) {
        return new ApiErrorLogOperationCatalog.Operation(source, key, name, apiUrl, order);
    }
}
