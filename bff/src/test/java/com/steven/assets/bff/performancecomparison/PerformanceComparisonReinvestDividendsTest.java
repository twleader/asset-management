package com.steven.assets.bff.performancecomparison;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 357／357.3e-2：{@link PerformanceComparisonBffController#compare} 含息模式下的股利再投入還原，
 * 對接 {@code /api/market-data/dividends-readonly} 回傳的 {@code exRightsDate} 欄。
 *
 * <p>比照 {@code CommodityPriceBffLiveTest} 的手法：以 {@link WebClient.Builder#exchangeFunction}
 * 依請求路徑餵固定 JSON，不需要 MockWebServer／WireMock 依賴。
 */
class PerformanceComparisonReinvestDividendsTest {

    private static WebClient stubClient(String stockJson, String dividendJson) {
        return WebClient.builder()
                .exchangeFunction(request -> {
                    String path = request.url().getPath();
                    String body = path.contains("dividends-readonly") ? dividendJson : stockJson;
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body(body)
                            .build());
                })
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstSeries(WebClient client) {
        var controller = new PerformanceComparisonBffController(client);
        Map<String, Object> body = controller.compare("2881:台股", null, "1y", true).block().getBody();
        List<Map<String, Object>> series = (List<Map<String, Object>>) body.get("series");
        return series.get(0);
    }

    /**
     * 純配股事件（{@code exDividendDate=null}、{@code exRightsDate} 有值）修正前的舊邏輯是整列略過
     * （只看 {@code exDividendDate}），還原後等同純價格報酬 -5.00%。修正後改用
     * {@code anchorDate = COALESCE(exDividendDate, exRightsDate)} 判斷是否套用，該事件不得被跳過：
     * 股票股利因子（1 + 1.0/10）在 {@code exRightsDate} 生效，含息報酬應為 +4.50%。
     */
    @Test
    void 純配股事件不得因exDividendDate為null被整列略過() {
        String stockJson = """
                [{"tradingDate":"2024-01-01","closePrice":100},
                 {"tradingDate":"2024-06-01","closePrice":90},
                 {"tradingDate":"2024-12-01","closePrice":95}]
                """;
        String dividendJson = """
                [{"exDividendDate":null,"exRightsDate":"2024-06-01","cashDividend":0,"stockDividend":1.0}]
                """;

        Map<String, Object> series = firstSeries(stubClient(stockJson, dividendJson));

        assertThat(series.get("priceOnly")).isEqualTo(false);
        assertThat((BigDecimal) series.get("totalReturn"))
                .as("股票股利因子未在 exRightsDate 套用（被誤判為缺日期整列略過）")
                .isEqualByComparingTo("4.50");
    }

    /**
     * 現金與股票除息除權日不同日時（357.4a），必須拆成兩筆各自套用其因子，
     * 現金套 {@code exDividendDate}（此處收盤 100，現金 5 → 因子 1.05）、
     * 股票套 {@code exRightsDate}（此處收盤 90，配股 1.0 → 因子 1.1），
     * 不得合併套在同一天或只取其一。逐位精算：1×1.05×1.1×95 = 109.725 → +9.73%。
     */
    @Test
    void 現金與股票除息除權日不同日時拆成兩筆各自套用因子() {
        String stockJson = """
                [{"tradingDate":"2024-01-01","closePrice":100},
                 {"tradingDate":"2024-03-01","closePrice":100},
                 {"tradingDate":"2024-06-01","closePrice":90},
                 {"tradingDate":"2024-12-01","closePrice":95}]
                """;
        String dividendJson = """
                [{"exDividendDate":"2024-03-01","exRightsDate":"2024-06-01","cashDividend":5,"stockDividend":1.0}]
                """;

        Map<String, Object> series = firstSeries(stubClient(stockJson, dividendJson));

        assertThat(series.get("priceOnly")).isEqualTo(false);
        assertThat((BigDecimal) series.get("totalReturn"))
                .as("現金／股票因子未各自套在除息日／除權日（被合併或只取其一）")
                .isEqualByComparingTo("9.73");
    }
}
