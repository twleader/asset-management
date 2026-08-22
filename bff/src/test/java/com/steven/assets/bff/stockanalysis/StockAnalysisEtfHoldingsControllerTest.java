package com.steven.assets.bff.stockanalysis;

import com.steven.assets.bff.stockanalysis.dto.EtfHoldingsDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StockAnalysisChartBffController#getEtfHoldings} — Task 359.4。
 *
 * 比照 {@code CommodityPriceBffLiveTest} 的手法：以 {@link WebClient.Builder#exchangeFunction}
 * 直接餵固定 JSON／模擬上游失敗，不需要額外的 MockWebServer／WireMock 依賴。
 * 排序＋截斷本身的完整案例在 {@link EtfHoldingsAggregatorTest}；這裡只驗證 controller
 * 有把 business 回應接上 {@link EtfHoldingsAggregator}，以及上游失敗時的 fail-soft 降級。
 */
class StockAnalysisEtfHoldingsControllerTest {

    private static final String HOLDINGS_JSON_12 = """
            {"stockCode":"00919","market":"台股","supported":true,"source":"MoneyDJ","asOfDate":"2026/08/14","message":null,
             "holdings":[
               {"stockCode":"2882","stockName":"國泰金","weight":3.0,"shares":1000},
               {"stockCode":"2330","stockName":"台積電","weight":9.5,"shares":2000},
               {"stockCode":"2881","stockName":"富邦金","weight":5.0,"shares":3000},
               {"stockCode":"2891","stockName":"中信金","weight":4.5,"shares":4000},
               {"stockCode":"2886","stockName":"兆豐金","weight":4.0,"shares":5000},
               {"stockCode":"2884","stockName":"玉山金","weight":3.5,"shares":6000},
               {"stockCode":"5880","stockName":"合庫金","weight":3.2,"shares":7000},
               {"stockCode":"2885","stockName":"元大金","weight":3.1,"shares":8000},
               {"stockCode":"2887","stockName":"台新金","weight":2.9,"shares":9000},
               {"stockCode":"2892","stockName":"第一金","weight":2.8,"shares":10000},
               {"stockCode":"2880","stockName":"華南金","weight":2.7,"shares":11000},
               {"stockCode":"2883","stockName":"凱基金","weight":2.6,"shares":12000}
             ]}
            """;

    private static WebClient stubClient(String json) {
        return WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body(json)
                        .build()))
                .build();
    }

    private static WebClient erroringClient() {
        return WebClient.builder()
                .exchangeFunction(request -> Mono.error(new RuntimeException("business-services 不可達")))
                .build();
    }

    @Test
    void 業者回傳超過10檔時controller接上聚合回傳前10大加其它() {
        var controller = new StockAnalysisChartBffController(stubClient(HOLDINGS_JSON_12));

        EtfHoldingsDto body = controller.getEtfHoldings("00919", "台股").block();

        assertThat(body.supported()).isTrue();
        assertThat(body.source()).isEqualTo("MoneyDJ");
        assertThat(body.asOfDate()).isEqualTo("2026/08/14");
        assertThat(body.holdings()).hasSize(11);
        assertThat(body.holdings().get(10).stockName()).isEqualTo("其它");
        assertThat(body.holdings().get(10).stockCode()).isNull();
    }

    /** 上游失敗（網路錯誤／逾時／非 2xx）時降級回傳 supported=false，不拋出例外給呼叫端。 */
    @Test
    void 業者上游失敗時降級回傳supportedFalse不拋例外() {
        var controller = new StockAnalysisChartBffController(erroringClient());

        EtfHoldingsDto body = controller.getEtfHoldings("0056", "台股").block();

        assertThat(body.supported()).isFalse();
        assertThat(body.holdings()).isEmpty();
        assertThat(body.stockCode()).isEqualTo("0056");
        assertThat(body.market()).isEqualTo("台股");
    }
}
