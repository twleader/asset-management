package com.steven.assets.bff.portfolioadvice;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.bff.portfolioadvice.SrppShortTermDto.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class SrppShortTermBffTest {
    @Test void exactGetQueriesAndTypedNullablePricesSurvivePageProxy()throws Exception {
        AtomicReference<String> query=new AtomicReference<>();AtomicReference<String> method=new AtomicReference<>();
        String list="{\"configurationStatus\":\"DRAFT_PARAMETER_PREVIEW_ONLY\",\"thresholdsAppliedToRadar\":false,\"tradeAuthorization\":false,\"markets\":[],\"policies\":[{\"group\":\"TW_EQUITY\",\"label\":\"台股股票短線\",\"formalValues\":{\"symbols\":[\"0050\"],\"reboundPct\":3,\"netRewardRisk\":1.5,\"sleeveCap\":0.01,\"positionCap\":0.005,\"maxHoldingSessions\":10},\"draftValues\":null,\"entryRule\":\"source entry\",\"exitRule\":\"source exit\",\"retirementImpact\":\"NOT_MODELED\",\"entrySummary\":\"繁中買進條件\",\"exitSummary\":\"繁中退出條件\"}],\"rows\":[{\"stockCode\":\"0050\",\"market\":\"台股\",\"group\":\"TW_EQUITY\",\"shortAction\":\"NO_TRADE\",\"shortActionLabel\":\"等待\",\"shortScore\":null,\"observationType\":\"WAIT\",\"status\":\"WAITING_DATA\",\"price\":null}],\"missingSymbols\":[],\"limitations\":[\"草稿尚未套用\"]}";
        String detail="{\"stockCode\":\"0050\",\"market\":\"台股\",\"group\":\"TW_EQUITY\",\"observationType\":\"WAIT\",\"status\":\"WAITING_DATA\",\"dataComplete\":false,\"shortAction\":\"NO_TRADE\",\"shortReasons\":[\"SHORT only\"],\"shortRisks\":[\"source risk\"],\"price\":\"12.345600\",\"shortScore\":null,\"priceReference\":{\"buyLower\":\"11.1234\",\"buyUpper\":null,\"sellLower\":null,\"sellUpper\":null,\"asOfDate\":\"2026-11-13\",\"applicableSide\":\"BUY\"},\"tradeAuthorization\":false,\"limitations\":[]}";
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/portfolio-advice/srpp/short-opportunities",exchange->{query.set(exchange.getRequestURI().getRawQuery());method.set(exchange.getRequestMethod());byte[] bytes=(exchange.getRequestURI().getPath().endsWith("/stock")?detail:list).getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        try {
            WebTestClient client=WebTestClient.bindToController(new SrppShortTermBffController(WebClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build())).build();
            client.get().uri("/api/bff/portfolio-advice/srpp/short-opportunities?draftId=abc&revision=3").exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.thresholdsAppliedToRadar").isEqualTo(false).jsonPath("$.tradeAuthorization").isEqualTo(false)
                    .jsonPath("$.rows[0].price").isEmpty().jsonPath("$.policies[0].formalValues.reboundPct").isEqualTo(3.0)
                    .jsonPath("$.observationBuckets.length()").isEqualTo(3)
                    .jsonPath("$.observationBuckets[0].rows.length()").isEqualTo(0)
                    .jsonPath("$.observationBuckets[2].rows[0].stockCode").isEqualTo("0050")
                    .jsonPath("$.observationBuckets[2].rows[0].price").isEmpty();
            assertThat(method.get()).isEqualTo("GET");assertThat(query.get()).contains("draftId=abc","revision=3");
            client.get().uri(b->b.path("/api/bff/portfolio-advice/srpp/short-opportunities/stock").queryParam("stockCode","0050").queryParam("market","台股").build())
                    .exchange().expectStatus().isOk().expectBody().jsonPath("$.price").isEqualTo("12.345600")
                    .jsonPath("$.priceReference.buyLower").isEqualTo("11.1234").jsonPath("$.priceReference.sellLower").isEmpty()
                    .jsonPath("$.shortScore").isEmpty().jsonPath("$.shortReasons[0]").isEqualTo("SHORT only");
            assertThat(query.get()).contains("stockCode=0050");assertThat(java.net.URLDecoder.decode(query.get(),StandardCharsets.UTF_8)).contains("market=台股");
        }finally{server.stop(0);}
    }
    @Test void pageBucketsPreserveEverySourceRowOrderNullsAndImmutability()throws Exception {
        Row sell=row("SELL1","SELL_WATCH"),buy=row("BUY1","BUY_WATCH"),wait=row("WAIT1","WAIT"),
                buy2=row("BUY2","BUY_WATCH"),unclassified=row("NULL",null),unknown=row("UNKNOWN","FUTURE_SOURCE_TYPE");
        ListEnvelope source=list(List.of(sell,buy,wait,buy2,unclassified,unknown));
        JsonNode before=SrppSettingsPresentation.JSON.valueToTree(source);
        var page=SrppShortTermPresentation.list(source);
        assertThat(page.source()).isSameAs(source);
        assertThat(page.observationBuckets()).extracting(SrppShortTermPresentation.ObservationBucket::key)
                .containsExactly("BUY_WATCH","SELL_WATCH","WAIT");
        assertThat(page.observationBuckets().get(0).rows()).containsExactly(buy,buy2);
        assertThat(page.observationBuckets().get(1).rows()).containsExactly(sell);
        assertThat(page.observationBuckets().get(2).rows()).containsExactly(wait,unclassified,unknown);
        assertThat(page.observationBuckets().get(2).rows().get(1)).isSameAs(unclassified);
        assertThat(page.observationBuckets().get(2).rows().get(1).observationType()).isNull();
        assertThat((JsonNode)SrppSettingsPresentation.JSON.valueToTree(source)).isEqualTo(before);
        assertThatThrownBy(()->page.observationBuckets().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->page.observationBuckets().get(0).rows().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(SrppShortTermPresentation.list(list(null)).observationBuckets()).allSatisfy(bucket->assertThat(bucket.rows()).isEmpty());
        assertThatThrownBy(()->SrppShortTermPresentation.list(null)).isInstanceOf(NullPointerException.class);
    }
    @Test void actualHttpListAddsPageBucketsWithoutChangingTheSourceEnvelope()throws Exception {
        ListEnvelope source=list(List.of(row("BUY1","BUY_WATCH"),row("SELL1","SELL_WATCH"),row("BUY2","BUY_WATCH"),row("WAIT1",null)));
        byte[] bytes=SrppSettingsPresentation.JSON.writeValueAsBytes(source);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/portfolio-advice/srpp/short-opportunities",exchange->{exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        try {
            WebTestClient client=WebTestClient.bindToController(new SrppShortTermBffController(WebClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build())).build();
            byte[] response=client.get().uri("/api/bff/portfolio-advice/srpp/short-opportunities").exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
            ObjectNode tree=(ObjectNode)SrppSettingsPresentation.JSON.readTree(response);
            JsonNode buckets=tree.remove("observationBuckets");
            assertThat(tree).isEqualTo(SrppSettingsPresentation.JSON.readTree(bytes));
            assertThat(buckets.get(0).path("label").asText()).isEqualTo("買進觀察（僅來源短線方向）");
            assertThat(buckets.get(0).path("emptyText").asText()).isEqualTo("目前沒有來源短線買進觀察；不由分數或中長線理由推導。");
            assertThat(buckets.get(0).path("rows")).isEqualTo(SrppSettingsPresentation.JSON.valueToTree(List.of(source.rows().get(0),source.rows().get(2))));
            assertThat(buckets.get(1).path("rows")).isEqualTo(SrppSettingsPresentation.JSON.valueToTree(List.of(source.rows().get(1))));
            assertThat(buckets.get(2).path("rows")).isEqualTo(SrppSettingsPresentation.JSON.valueToTree(List.of(source.rows().get(3))));
        }finally{server.stop(0);}
    }
    private static Row row(String code,String observation){return new Row(code,"來源名稱","台股","TW_EQUITY",true,"NO_TRADE","來源方向",null,observation,"WAITING_DATA","12.345600",null,null,null,true);}
    private static ListEnvelope list(List<Row> rows){return new ListEnvelope("FORMAL",false,"bundle","model","rules","actions",null,null,null,rows,null,null,false);}
    @Test void upstreamOwnerErrorsRetainStatusAndCannotMasqueradeAsAnEmptySuccessfulList()throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/portfolio-advice/srpp/short-opportunities",exchange->{byte[] bytes="{\"code\":\"NOT_FOUND\",\"message\":\"找不到目前帳號的草稿\"}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(404,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        try {
            WebTestClient client=WebTestClient.bindToController(new SrppShortTermBffController(WebClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build())).build();
            client.get().uri("/api/bff/portfolio-advice/srpp/short-opportunities?draftId=x&revision=1").exchange().expectStatus().isNotFound().expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
        }finally{server.stop(0);}
    }
}
