package com.steven.assets.externalmaterials.client;

import com.steven.assets.externalmaterials.model.marketdata.DividendData.*;
import com.steven.assets.externalmaterials.model.marketdata.DividendNormalization;
import java.math.BigDecimal;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class DividendCanonicalProducerTest {
    private static final LocalDate TODAY = LocalDate.of(2026,11,5);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T06:00:00Z"), ZoneOffset.UTC);
    private static String fixture(String name) throws Exception {
        try (var input = DividendCanonicalProducerTest.class.getResourceAsStream("/dividend-v2/"+name)) {
            return new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    @SuppressWarnings("unchecked")
    private static DividendFetchClient client(java.util.function.Function<String,String> body, List<String> urls) throws Exception {
        HttpClient http=mock(HttpClient.class);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(inv -> {
            String url=((HttpRequest)inv.getArgument(0)).uri().toString(); urls.add(url);
            HttpResponse<String> response=mock(HttpResponse.class);
            String content=body.apply(url);
            when(response.statusCode()).thenReturn(content==null?503:200);
            when(response.body()).thenReturn(content==null?"":content);
            return response;
        });
        return new DividendFetchClient("",null,CLOCK,http);
    }
    @Test void raw2330MainDatasetKeepsOfficialEightDigitsAndDoesNotConcatenateResult() throws Exception {
        List<String> urls=new ArrayList<>(); String raw=fixture("finmind-2330-TaiwanStockDividend.json");
        var result=client(url -> url.contains("dataset=TaiwanStockDividend&")?raw:null,urls).fetchObservations("2330","台股",11).getFirst();
        assertThat(result.source()).isEqualTo("FinMind[TaiwanStockDividend]");
        assertThat(result.normalizerVersion()).isEqualTo(2);
        assertThat(result.dateGroupsComplete()).isTrue();
        assertThat(result.amountBasis()).isEqualTo("NOMINAL_PER_EX_DATE");
        assertThat(result.events().stream().filter(e -> e.exDividendDate().equals("2026-03-17")).findFirst().orElseThrow().cashDividend())
                .isEqualByComparingTo("6.00003573");
        assertThat(result.events().stream().filter(e -> e.exDividendDate().equals("2026-09-16")).findFirst().orElseThrow().cashDividend())
                .isEqualByComparingTo("7.00000137");
        assertThat(urls).hasSize(1);
    }
    @Test void combinedRightsReferenceAdjustmentCannotReplace7556CashAndStockComponents() throws Exception {
        List<String> urls=new ArrayList<>(); String raw=fixture("finmind-7556-TaiwanStockDividend.json");
        var result=client(url -> url.contains("dataset=TaiwanStockDividend&")?raw:null,urls).fetchObservations("7556","台股",11).getFirst();
        assertThat(result.events()).singleElement().satisfies(e -> {
            assertThat(e.cashDividend()).isEqualByComparingTo("4.0423906");
            assertThat(e.stockDividend()).isEqualByComparingTo("0.4929742832");
        });
        assertThat(urls).hasSize(1);
        String fallback=fixture("finmind-7556-TaiwanStockDividendResult.json");
        var fallbackOnly=client(url -> url.contains("dataset=TaiwanStockDividend&")?"{\"status\":200,\"data\":[]}":fallback,new ArrayList<>())
                .fetchObservations("7556","台股",11).getFirst();
        assertThat(fallbackOnly.events()).noneMatch(e -> e.cashDividend()!=null && e.cashDividend().compareTo(new BigDecimal("17.101219"))==0);
    }
    @Test void applicableNasdaqSuccessDoesNotProbeWrongAssetClassOrMixSplitAdjustedYahoo() throws Exception {
        List<String> urls=new ArrayList<>(); String raw=fixture("nasdaq-aapl-stocks.json");
        var result=client(url -> url.contains("assetclass=stocks")?raw:null,urls).fetchObservations("AAPL","美股",11).getFirst();
        assertThat(result.source()).isEqualTo("NASDAQ.stocks");
        assertThat(result.dateGroupsComplete()).isTrue();
        assertThat(result.events().stream().filter(e -> e.exDividendDate().equals("2020-08-07")).findFirst().orElseThrow().cashDividend())
                .isEqualByComparingTo("0.82");
        assertThat(urls).hasSize(1);
        assertThat(urls).noneMatch(url -> url.contains("yahoo")||url.contains("assetclass=etf"));
    }
    @Test void yahooNominalUsesExplicitSplitAndValidEmptySplitSetWithoutRatioGuessing() throws Exception {
        var client=client(url -> null,new ArrayList<>());
        String raw=fixture("yahoo-aapl-dividends-splits.json");
        var parsed=client.parseYahoo(raw,"AAPL",TODAY.minusYears(11),TODAY);
        assertThat(parsed.valid()).isTrue();
        assertThat(parsed.events().stream().filter(e -> e.exDividendDate().equals("2020-08-07")).findFirst().orElseThrow().cashDividend())
                .isEqualByComparingTo("0.82");
        String noSplits="{\"chart\":{\"error\":null,\"result\":[{\"meta\":{\"symbol\":\"AAPL\",\"currency\":\"USD\"},\"events\":{\"dividends\":{\"1770647400\":{\"amount\":0.260000000000000001,\"date\":1770647400}}}}]}}";
        var unchanged=client.parseYahoo(noSplits,"AAPL",TODAY.minusYears(11),TODAY);
        assertThat(unchanged.valid()).isTrue();
        assertThat(unchanged.events().getFirst().cashDividend()).isEqualByComparingTo("0.260000000000000001");
        assertThat(client.parseYahoo(noSplits.replace("\"events\":{","\"events\":{\"splits\":[],"),"AAPL",TODAY.minusYears(11),TODAY).valid()).isFalse();
        assertThat(client.parseYahoo(noSplits.replace("0.260000000000000001","0.2600000000000000001"),"AAPL",TODAY.minusYears(11),TODAY).valid()).isFalse();
        assertThat(client.parseYahoo(noSplits.replace("\"symbol\":\"AAPL\"","\"symbol\":\"OTHER\""),"AAPL",TODAY.minusYears(11),TODAY).valid()).isFalse();
        assertThat(client.parseYahoo(noSplits.replace("\"events\":{\"dividends\"","\"other\":{\"dividends\""),"AAPL",TODAY.minusYears(11),TODAY).valid()).isFalse();
    }
    @Test void repeatedYearEtfUsesUniqueReturnedDatesWithoutClaimingCrossDateRevisionIdentity() throws Exception {
        String raw="{\"status\":200,\"data\":[{\"year\":\"115\",\"CashEarningsDistribution\":0.12,\"CashStatutorySurplus\":0,\"StockEarningsDistribution\":0,\"StockStatutorySurplus\":0,\"CashExDividendTradingDate\":\"2026-03-01\"},{\"year\":\"115\",\"CashEarningsDistribution\":0.13,\"CashStatutorySurplus\":0,\"StockEarningsDistribution\":0,\"StockStatutorySurplus\":0,\"CashExDividendTradingDate\":\"2026-06-01\"}]}";
        var result=client(url->raw,new ArrayList<>()).fetchObservations("00679B","台股",11).getFirst();
        assertThat(result.dateGroupsComplete()).isTrue();
        assertThat(result.events()).hasSize(2);
        assertThat(result.events()).extracting(DividendEvent::upstreamComponentId).allMatch(id->id.startsWith("FINMIND_PRIMARY_DATE:"));
    }
    @Test void dateGroupAmbiguityDoesNotPoisonAnotherDateAndScopesKeepCashRightsLegsTogether() throws Exception {
        String raw="{\"status\":200,\"data\":[{\"id\":\"same\",\"year\":\"115\",\"CashEarningsDistribution\":1,\"CashStatutorySurplus\":0,\"StockEarningsDistribution\":0,\"StockStatutorySurplus\":0,\"CashExDividendTradingDate\":\"2026-03-01\"},{\"id\":\"same\",\"year\":\"115\",\"CashEarningsDistribution\":2,\"CashStatutorySurplus\":0,\"StockEarningsDistribution\":0,\"StockStatutorySurplus\":0,\"CashExDividendTradingDate\":\"2026-03-01\"},{\"id\":\"good\",\"year\":\"115b\",\"CashEarningsDistribution\":3,\"CashStatutorySurplus\":0,\"StockEarningsDistribution\":0.5,\"StockStatutorySurplus\":0,\"CashExDividendTradingDate\":\"2026-06-01\",\"StockExDividendTradingDate\":\"2026-06-10\"}]}";
        var results=client(url->raw,new ArrayList<>()).fetchObservations("2330","台股",11);
        var good=results.stream().filter(r->r.events().size()==1 && "2026-06-01".equals(r.events().getFirst().exDividendDate())).findFirst().orElseThrow();
        assertThat(good.dateGroupsComplete()).isTrue();
        assertThat(good.scopeFrom()).isEqualTo(LocalDate.of(2026,6,1));
        assertThat(good.scopeTo()).isEqualTo(LocalDate.of(2026,6,10));
        assertThat(results.stream().filter(r->r.events().size()==2).findFirst().orElseThrow().dateGroupsComplete()).isFalse();
    }
    @Test void exactNumericBoundaryRejectsBothFractionAndIntegerOverflow() {
        assertThat(DividendNormalization.exact(new BigDecimal("999999999999.999999999999999999")))
                .isEqualByComparingTo("999999999999.999999999999999999");
        assertThatThrownBy(()->DividendNormalization.exact(new BigDecimal("1000000000000"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->DividendNormalization.exact(new BigDecimal("0.0000000000000000001"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void irrelevantOldNasdaqNaAmountOutsideRequestedScopeDoesNotForceYahooFallback() throws Exception {
        String raw="{\"status\":{\"rCode\":200},\"data\":{\"dividends\":{\"rows\":[{\"exOrEffDate\":\"01/01/2000\",\"amount\":\"N/A\"},{\"exOrEffDate\":\"03/01/2026\",\"amount\":\"$1.123456789012345678\",\"type\":\"Cash\",\"declarationDate\":\"02/01/2026\"}]}}}";
        List<String> urls=new ArrayList<>();
        var result=client(url->raw,urls).fetchObservations("AAPL","美股",11).getFirst();
        assertThat(result.events()).singleElement().satisfies(e->assertThat(e.cashDividend()).isEqualByComparingTo("1.123456789012345678"));
        assertThat(urls).hasSize(1);
    }
    @Test void numericStockOrUnknownTypeCannotBecomeNasdaqCash() throws Exception {
        for (String type : List.of("Stock", "Split", "Rights", "Unknown")) {
            String raw="{\"status\":{\"rCode\":200},\"data\":{\"dividends\":{\"rows\":[{\"exOrEffDate\":\"03/01/2026\",\"amount\":\"$4\",\"type\":\""+type+"\",\"declarationDate\":\"02/01/2026\"}]}}}";
            var result=client(url->raw,new ArrayList<>()).fetchObservations("AAPL","美股",11).getFirst();
            assertThat(result.dateGroupsComplete()).isFalse();
            assertThat(result.events()).singleElement().satisfies(e->{
                assertThat(e.cashDividend()).isNull(); assertThat(e.stockDividend()).isNull();
            });
        }
    }
    @Test void missingFinMindSuccessEnvelopeCannotVerifyAmounts() throws Exception {
        String raw=fixture("finmind-2330-TaiwanStockDividend.json").replace("\"status\":200,", "").replace("\"status\": 200,", "");
        var result=client(url->raw,new ArrayList<>()).fetchObservations("2330","台股",11).getFirst();
        assertThat(result.dateGroupsComplete()).isFalse();
        assertThat(result.events()).isEmpty();
    }
    @Test void actualEtfPrimaryPayloadsHaveDistinctDateGroupsDespiteRepeatedPeriods() throws Exception {
        for (String code : List.of("00679B","006208")) {
            String raw=fixture("finmind-"+code+"-TaiwanStockDividend.json");
            var result=client(url->raw,new ArrayList<>()).fetchObservations(code,"台股",11).getFirst();
            assertThat(result.dateGroupsComplete()).isTrue();
            assertThat(result.events()).hasSize(code.equals("00679B")?4:2);
            assertThat(result.events()).extracting(DividendEvent::upstreamComponentId).doesNotHaveDuplicates();
        }
    }

    @Test void actualWholeNasdaqPayloadsResolveUniqueDaysWithoutInventingDeclarationChains() throws Exception {
        for (String code : List.of("QQQ","ASML","AVGO")) {
            String cls=code.equals("QQQ")?"etf":"stocks";
            String raw=fixture("nasdaq-"+code.toLowerCase(Locale.ROOT)+"-"+cls+"-full.json");
            List<String> urls=new ArrayList<>();
            var result=client(url->code.equals("QQQ") && url.contains("assetclass=stocks")
                    ? "{\"status\":{\"rCode\":400},\"data\":null}" : raw,urls)
                    .fetchObservations(code,"美股",11).getFirst();
            assertThat(result.source()).isEqualTo("NASDAQ."+cls);
            assertThat(result.dateGroupsComplete()).isTrue();
            int count=code.equals("QQQ")?45:code.equals("ASML")?28:44;
            assertThat(result.events()).hasSize(count);
            assertThat(result.events()).extracting(DividendEvent::exDividendDate).doesNotHaveDuplicates();
            assertThat(result.events()).allMatch(e->e.cashDividend()!=null && e.cashDividend().signum()>0);
            long dateIdentities=result.events().stream().filter(e->e.upstreamComponentId().startsWith("NASDAQ_DATE:")).count();
            assertThat(dateIdentities).isEqualTo(code.equals("QQQ")?44:code.equals("ASML")?9:1);
            assertThat(urls).noneMatch(url->url.contains("yahoo"));
        }
    }
    @Test void missingDeclarationSameDayMultiplicityStaysUnknownRatherThanGuessingByAmount() throws Exception {
        String raw="""
                {"status":{"rCode":200},"data":{"dividends":{"rows":[
                {"exOrEffDate":"03/01/2026","amount":"$1","type":"Cash","declarationDate":"N/A"},
                {"exOrEffDate":"03/01/2026","amount":"$1","type":"Cash","declarationDate":"null"}]}}}
                """;
        var result=client(url->raw,new ArrayList<>()).fetchObservations("QQQ","美股",11).getFirst();
        assertThat(result.events()).hasSize(2); assertThat(result.dateGroupsComplete()).isFalse();
        assertThat(result.events()).allMatch(e->e.upstreamComponentId()==null);
    }
    @Test void genuineNativeComponentContradictionDoesNotDowngradeIntoTwoResolvedDateFacts() throws Exception {
        String raw="""
                {"status":{"rCode":200},"data":{"dividends":{"rows":[
                {"id":"native","exOrEffDate":"03/01/2026","amount":"$1","type":"Cash","declarationDate":"N/A"},
                {"id":"native","exOrEffDate":"06/01/2026","amount":"$2","type":"Cash","declarationDate":"N/A"}]}}}
                """;
        var result=client(url->raw,new ArrayList<>()).fetchObservations("QQQ","美股",11).getFirst();
        assertThat(result.events()).hasSize(2); assertThat(result.dateGroupsComplete()).isFalse();
        assertThat(result.events()).allMatch(e->e.upstreamComponentId().startsWith("NASDAQ:"));
        assertThat(result.events()).extracting(DividendEvent::upstreamComponentId).containsOnly(result.events().getFirst().upstreamComponentId());
    }

}
