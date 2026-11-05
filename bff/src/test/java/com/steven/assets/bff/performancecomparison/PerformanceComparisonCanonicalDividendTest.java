package com.steven.assets.bff.performancecomparison;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PerformanceComparisonCanonicalDividendTest {
    @SuppressWarnings("unchecked") private Map<String,Object> series(String code,String dividends,boolean fail){
        return series(code,"台股",dividends,fail);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> series(String code,String market,String dividends,boolean fail){
        var client=WebClient.builder().baseUrl("http://business").exchangeFunction(req->{
            boolean dividend=req.url().getPath().contains("dividends-readonly");
            if(dividend&&fail)return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json").body(dividend?dividends:
                    "[{\"tradingDate\":\"2026-01-01\",\"closePrice\":100},{\"tradingDate\":\"2026-03-17\",\"closePrice\":100},{\"tradingDate\":\"2026-10-01\",\"closePrice\":100}]").build());}).build();
        var body=new PerformanceComparisonBffController(client).compare(code+":"+market,null,"1y",true).block().getBody();
        return ((List<Map<String,Object>>)body.get("series")).getFirst();
    }
    @Test void ordinaryAndSpecialCashWithStockOnOneDateAreSummedBeforeReinvestment(){
        var out=series("2330","""
                [{"quality":"RESOLVED","cashDividend":2,"stockDividend":0,"exDividendDate":"2026-03-17"},
                 {"quality":"RESOLVED","cashDividend":3,"stockDividend":1,"exDividendDate":"2026-03-17","exRightsDate":"2026-03-17"}]
                """,false);
        assertThat(out.get("priceOnly")).isEqualTo(false);assertThat((BigDecimal)out.get("totalReturn")).isEqualByComparingTo("15.00");
    }
    @Test void unresolvedPositiveRowsUseTheExistingExplicitPriceOnlyFallback(){
        var out=series("2330","[{\"quality\":\"UNRESOLVED\",\"cashDividend\":6,\"stockDividend\":0,\"exDividendDate\":\"2026-03-17\"}]",false);
        assertThat(out.get("priceOnly")).isEqualTo(true);
        var undated=series("2330","[{\"quality\":\"RESOLVED\",\"cashDividend\":6,\"stockDividend\":0,\"exRightsDate\":\"2010-03-17\"}]",false);
        assertThat(undated.get("priceOnly")).isEqualTo(true);
    }
    @Test void failedReadIsNotAnEmptyCompleteFeedForAnExemptNonDistributingSymbol(){
        assertThat(series("00865B","[]",true).get("priceOnly")).isEqualTo(true);
    }
    @Test void usNominalAmountsWithoutSplitBasisProofUsePriceOnlyOnlyForEventsInTheActualWindow(){
        String row="[{\"quality\":\"RESOLVED\",\"cashDividend\":0.82,\"stockDividend\":0,\"exDividendDate\":\"%s\"}]";
        assertThat(series("AAPL","美股",row.formatted("2026-03-17"),false).get("priceOnly")).isEqualTo(true);
        assertThat(series("AAPL","美股",row.formatted("2010-03-17"),false).get("priceOnly")).isEqualTo(false);
        assertThat(series("AAPL","美股",row.formatted("2099-03-17"),false).get("priceOnly")).isEqualTo(false);
    }
}
