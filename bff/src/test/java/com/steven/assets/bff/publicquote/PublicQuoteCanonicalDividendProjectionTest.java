package com.steven.assets.bff.publicquote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.bff.stockanalysis.StockAnalysisChartDataService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PublicQuoteCanonicalDividendProjectionTest {
    private PublicQuoteMarketDataDto.DetailedLatestQuote read(String quality){
        return read(quality,"2330","台股","6.00003573","100");
    }
    private PublicQuoteMarketDataDto.DetailedLatestQuote read(String quality,String code,String marketName,String cash,String previousClose){
        WebClient raw=WebClient.builder().baseUrl("http://raw").exchangeFunction(req->Mono.just(ok("""
                {"stockCode":"2330","stockName":"台積電","market":"台股","price":100,"previousClose":99,
                 "priceChange":1,"changePercent":1,"tradingDate":"2026-11-05","updatedAt":"2026-11-05T12:00:00+08:00",
                 "closed":false,"source":"REDIS","quoteStatus":"LIVE"}
                """.replace("2330",code).replace("台股",marketName)))).build();
        String json="""
                {"stockCode":"2330","market":"台股","source":"FinMind[TaiwanStockDividend]","message":null,
                 "quality":"QUALITY","annualSummaries":[],"rows":[{"year":2026,"cashDividend":6.00003573,
                 "stockDividend":0,"exDividendDate":"2026-03-17","previousClose":100,"yieldPct":99,"quality":"QUALITY",
                 "reason":"internal provenance","source":"FinMind[TaiwanStockDividend]","observedAt":"2026-11-05T00:00:00Z"}]}
                """.replace("QUALITY",quality).replace("2330",code).replace("台股",marketName)
                        .replace("6.00003573",cash).replace("\"previousClose\":100","\"previousClose\":"+previousClose);
        WebClient market=WebClient.builder().baseUrl("http://business").exchangeFunction(req->Mono.just(
                req.url().getPath().equals("/internal/public-market-data/dividends-readonly-result")?ok(json)
                        :ClientResponse.create(HttpStatus.NOT_FOUND).build())).build();
        Duration d=Duration.ofSeconds(1);var service=new PublicQuoteMarketDataService(raw,market,new StockAnalysisChartDataService(),
                Clock.fixed(Instant.parse("2026-11-05T04:00:00Z"),ZoneOffset.UTC),d,d,d,d,d,d,d,d,2);
        return service.one(code,marketName,"2026-01-01","2026-11-05").block();
    }
    @Test void publicProjectionRetainsOriginalClosedShapeWithoutPrivateQualityFields(){
        var quote=read("RESOLVED");assertThat(quote.dividendHistory()).isSameAs(quote.marketData().dividends());
        assertThat(quote.dividendHistory().rows()).singleElement().satisfies(row->assertThat(row.cashDividend()).isEqualByComparingTo("6.00003573"));
        var json=new ObjectMapper().valueToTree(quote.dividendHistory());Set<String> names=new HashSet<>();json.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("stockCode","market","source","message","rows","annualSummaries");
        for(String field:List.of("quality","reason","observedAt","sourceSnapshotId"))assertThat(json.path("rows").get(0).has(field)).isFalse();
        assertThat(quote.dividendHistory().annualSummaries()).singleElement().satisfies(year->assertThat(year.cashDividend()).isEqualByComparingTo("6.00003573"));
    }
    @Test void unresolvedLegacyAndFuturePartialAreNotPublishedAsFinancialRowsOrZeroSummaries(){
        for(String quality:List.of("UNRESOLVED","LEGACY","FUTURE_PARTIAL")){
            var history=read(quality).dividendHistory();assertThat(history.rows()).isEmpty();
            assertThat(history.annualSummaries()).isEmpty();assertThat(history.source()).isNull();
            assertThat(history.message()).contains("不可採用");
        }
    }
    @Test void usNominalCashRemainsVisibleButAnAdjustedPreviousCloseCannotProduceOrAuthenticateYields(){
        var history=read("RESOLVED","AAPL","美股","0.82","110").dividendHistory();
        assertThat(history.source()).isNotBlank();assertThat(history.message()).isNull();
        assertThat(history.rows()).singleElement().satisfies(row->{
            assertThat(row.cashDividend()).isEqualByComparingTo("0.82");
            assertThat(row.previousClose()).isEqualByComparingTo("110");
            assertThat(row.yieldPct()).isNull();assertThat(row.cashYieldPct()).isNull();
        });
        assertThat(history.annualSummaries()).singleElement().satisfies(year->{
            assertThat(year.cashDividend()).isEqualByComparingTo("0.82");assertThat(year.cashYieldPct()).isNull();
        });
        var json=new ObjectMapper().valueToTree(history);
        assertThat(json.path("rows").get(0).has("cashYieldPct")).isTrue();
        assertThat(json.path("rows").get(0).path("cashYieldPct").isNull()).isTrue();
    }
    @Test void twCanonicalCashStillUsesItsPreviousCloseForRowAndAnnualYield(){
        var history=read("RESOLVED","2330","台股","0.82","110").dividendHistory();
        assertThat(history.rows()).singleElement().satisfies(row->{
            assertThat(row.cashDividend()).isEqualByComparingTo("0.82");
            assertThat(row.yieldPct()).isEqualByComparingTo("99");
            assertThat(row.cashYieldPct()).isEqualByComparingTo("0.745455");
        });
        assertThat(history.annualSummaries()).singleElement().satisfies(year->
                assertThat(year.cashYieldPct()).isEqualByComparingTo("0.745455"));
    }
    private static ClientResponse ok(String body){return ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json").body(body).build();}
}
