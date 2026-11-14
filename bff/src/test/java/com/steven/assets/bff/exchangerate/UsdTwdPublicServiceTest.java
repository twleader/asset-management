package com.steven.assets.bff.exchangerate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsdTwdPublicServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-13T15:10:14Z"), ZoneId.of("Asia/Taipei"));

    @Test
    void aggregatesTwoYearQueriesWithBoundaryLookbackSortsHistoryAndComputesMidScaleFour() {
        List<URI> requests = new CopyOnWriteArrayList<>();
        UsdTwdPublicService service = service(request -> {
            requests.add(request.url());
            if (request.url().getPath().endsWith("/live")) return jsonRaw(liveMega());
            return json(List.of(
                    history("2026-08-13", "32.1000", "32.2000"),
                    history("2025-08-13", "29.8300", "29.9300")));
        });

        UsdTwdPublicDto.Response response = service.getUsdTwd().block();

        assertThat(response).isNotNull();
        assertThat(response.requestedStartDate().toString()).isEqualTo("2024-08-13");
        assertThat(response.requestedEndDate().toString()).isEqualTo("2026-08-13");
        assertThat(response.refreshIntervalSeconds()).isEqualTo(2);
        assertThat(response.timezone()).isEqualTo("Asia/Taipei");
        assertThat(response.count()).isEqualTo(2);
        assertThat(response.history()).extracting(p -> p.date().toString())
                .containsExactly("2025-08-13", "2026-08-13");
        assertThat(response.spot().midRate().toPlainString()).isEqualTo("32.1500");
        assertThat(response.history().get(0).midRate().toPlainString()).isEqualTo("29.8800");
        assertThat(requests).anyMatch(uri -> "/api/market-data/exchange-rate".equals(uri.getPath())
                && uri.getQuery().contains("currency=USD")
                && uri.getQuery().contains("start=2024-07-13")
                && uri.getQuery().contains("end=2026-08-13"));
        assertThat(requests).anyMatch(uri ->
                "/api/market-data/exchange-rate/usd-twd/live".equals(uri.getPath()));
    }

    @Test
    void responseDefensivelyCopiesHistory() {
        ArrayList<UsdTwdPublicDto.HistoryPoint> points = new ArrayList<>();
        points.add(new UsdTwdPublicDto.HistoryPoint(
                java.time.LocalDate.of(2026, 8, 13), java.math.BigDecimal.ONE,
                java.math.BigDecimal.ONE, java.math.BigDecimal.ONE));
        UsdTwdPublicDto.Response response = new UsdTwdPublicDto.Response(
                "USD/TWD", "USD", "TWD", java.time.LocalDate.of(2025, 8, 13),
                java.time.LocalDate.of(2026, 8, 13), 2, "Asia/Taipei", "ACTIVE", null, 1, points);
        points.clear();
        assertThat(response.history()).hasSize(1);
        assertThatThrownBy(() -> response.history().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyHistoryOrLive404IsTypedNotFound() {
        assertThatThrownBy(() -> service(request -> request.url().getPath().endsWith("/live")
                ? jsonRaw(liveMega()) : json(List.of())).getUsdTwd().block())
                .isInstanceOf(UsdTwdUnavailableException.class);

        assertThatThrownBy(() -> service(request -> request.url().getPath().endsWith("/live")
                ? ClientResponse.create(HttpStatus.NOT_FOUND).build()
                : json(List.of(history("2026-08-13", "32.1", "32.2"))))
                .getUsdTwd().block()).isInstanceOf(UsdTwdUnavailableException.class);
    }

    @Test
    void transportHttpDecodeDuplicateOutOfRangeAndInvalidRatesFailClosed() {
        List<Function<org.springframework.web.reactive.function.client.ClientRequest, ClientResponse>> failures = List.of(
                request -> ClientResponse.create(HttpStatus.BAD_GATEWAY).build(),
                request -> ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{broken").build(),
                request -> request.url().getPath().endsWith("/live") ? jsonRaw(liveMega()) : json(List.of(
                        history("2026-08-13", "32.1", "32.2"),
                        history("2026-08-13", "32.1", "32.2"))),
                request -> request.url().getPath().endsWith("/live") ? jsonRaw(liveMega()) : json(List.of(
                        history("2024-07-12", "32.1", "32.2"))),
                request -> request.url().getPath().endsWith("/live") ? jsonRaw(liveMega()) : json(List.of(
                        history("2026-08-13", "0", "32.2"))));

        for (var failure : failures) {
            assertThatThrownBy(() -> service(failure).getUsdTwd().block())
                    .isInstanceOf(UsdTwdBadGatewayException.class);
        }
    }

    @Test
    void liveSourceTimestampAndStatusCombinationsFailClosed() {
        List<String> invalidLives = List.of(
                liveMega().replace("\"sourceUpdatedAt\":\"2026-08-13T15:10:12Z\"",
                        "\"sourceUpdatedAt\":null"),
                liveMega().replace("\"source\":\"MEGA_BANK\"", "\"source\":\"BAD\""),
                liveMega().replace("\"quoteStatus\":\"LIVE\"", "\"quoteStatus\":\"INDICATIVE\""),
                liveMega().replace("\"liveUpdateStatus\":\"ACTIVE\"", "\"liveUpdateStatus\":\"INACTIVE\""));
        for (String live : invalidLives) {
            assertThatThrownBy(() -> service(request -> request.url().getPath().endsWith("/live")
                    ? jsonRaw(live)
                    : json(List.of(history("2026-08-13", "32.1", "32.2"))))
                    .getUsdTwd().block()).isInstanceOf(UsdTwdBadGatewayException.class);
        }
    }

    private static UsdTwdPublicService service(
            Function<org.springframework.web.reactive.function.client.ClientRequest, ClientResponse> exchange) {
        WebClient client = WebClient.builder().baseUrl("http://business")
                .exchangeFunction(request -> reactor.core.publisher.Mono.just(exchange.apply(request)))
                .build();
        return new UsdTwdPublicService(client, CLOCK);
    }

    private static ClientResponse json(Object body) {
        try {
            return jsonRaw(MAPPER.writeValueAsString(body));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test void weekendBoundarySelectsVerifiedFridayAndKeepsUnknownSaturday() throws Exception {
        var friday=qualified("2024-08-09","FINMIND_TAIWAN_EXCHANGE_RATE","a");
        var bank=qualified("2026-08-10","MEGA_BANK","b");
        Clock clock=Clock.fixed(Instant.parse("2026-08-10T15:10:14Z"),ZoneId.of("Asia/Taipei"));
        WebClient client=WebClient.builder().exchangeFunction(request->reactor.core.publisher.Mono.just(
                request.url().getPath().endsWith("/live")?jsonRaw(liveMega().replace("2026-08-13","2026-08-10")):
                json(List.of(friday,history("2024-08-10","999","1000"),bank)))).build();
        var result=new UsdTwdPublicService(client,clock).getUsdTwd().block();
        assertThat(result.historyBoundary().date().toString()).isEqualTo("2024-08-09");
        assertThat(result.historyBoundary().spotBasisVerified()).isTrue();
        assertThat(result.history()).hasSize(2);assertThat(result.history().getFirst().spotBasisVerified()).isFalse();
        assertThat(result.historySourceCaptures()).hasSize(2);
        assertThat(result.historySourceCaptures()).anyMatch(c->c.sourceKind().equals("NATIVE_QUERY")&&c.requestedStartDate()!=null);
        assertThat(result.historySourceCaptures()).anyMatch(c->c.sourceKind().equals("OBSERVATION")&&c.requestedStartDate()==null&&c.requestedEndDate()==null);
    }
    @Test void claimedVerifiedPriceTamperOrUntrustedQueryCannotBePublished() throws Exception {
        for(String kind:List.of("member","duplicateQuery","currencyQuery")) {
            var row=qualified("2026-08-10","FINMIND_TAIWAN_EXCHANGE_RATE","a");
            if(kind.equals("member"))row.put("sellRate",new java.math.BigDecimal("33.11"));
            if(kind.equals("duplicateQuery"))row.put("sourceUrl",row.get("sourceUrl")+"&data_id=USD");
            if(kind.equals("currencyQuery"))row.put("sourceUrl",row.get("sourceUrl").toString().replace("data_id=USD","data_id=EUR"));
            assertThatThrownBy(()->service(request->request.url().getPath().endsWith("/live")?jsonRaw(liveMega()):json(List.of(row))).getUsdTwd().block()).as(kind).isInstanceOf(UsdTwdBadGatewayException.class);
        }
    }
    @Test void equalRawHashesRetainDifferentLegitimateQueryContexts() throws Exception {
        var first=qualified("2024-08-13","FINMIND_TAIWAN_EXCHANGE_RATE","a");
        var second=qualified("2026-08-10","FINMIND_TAIWAN_EXCHANGE_RATE","a");
        second.put("sourceUrl",second.get("sourceUrl").toString().replace("start_date=2024-07-01","start_date=2024-08-13"));
        second.put("fetchedAt","2026-08-11T15:00:00Z");
        var result=service(request->request.url().getPath().endsWith("/live")?jsonRaw(liveMega()):json(List.of(first,second))).getUsdTwd().block();
        assertThat(result.historySourceCaptures()).hasSize(2);
        assertThat(result.historySourceCaptures().get(0).sourceSha256()).isEqualTo(result.historySourceCaptures().get(1).sourceSha256());
        assertThat(result.historySourceCaptures().get(0).sourceUrl()).isNotEqualTo(result.historySourceCaptures().get(1).sourceUrl());
    }
    private static java.util.Map<String,Object> qualified(String day,String source,String letter) throws Exception {
        var row=new java.util.HashMap<String,Object>(history(day,"32.7900","32.8900"));String raw=letter.repeat(64);
        String tuple="FX_SPOT_MEMBER_V1\n"+raw+"\nUSD\n"+day+"\n32.79\n32.89";
        String member=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(tuple.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        row.put("rateBasis","BANK_SPOT_SELLING");row.put("source",source);row.put("sourceSha256",raw);row.put("sourceMemberSha256",member);row.put("fetchedAt","2026-08-10T15:00:00Z");row.put("spotBasisVerified",true);
        row.put("sourceUrl",source.equals("FINMIND_TAIWAN_EXCHANGE_RATE")?"https://api.finmindtrade.com/api/v4/data?dataset=TaiwanExchangeRate&data_id=USD&start_date=2024-07-01&end_date=2026-08-10":"https://www.megabank.com.tw/api/client/ExchangeRate/GetRateData");
        return row;
    }

    private static ClientResponse jsonRaw(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body).build();
    }

    private static String liveMega() {
        return "{\"pair\":\"USD/TWD\",\"baseCurrency\":\"USD\",\"quoteCurrency\":\"TWD\","
                + "\"date\":\"2026-08-13\",\"buyRate\":32.1000,\"sellRate\":32.2000,"
                + "\"source\":\"MEGA_BANK\",\"polledAt\":\"2026-08-13T15:10:14Z\","
                + "\"sourceUpdatedAt\":\"2026-08-13T15:10:12Z\","
                + "\"liveUpdateStatus\":\"ACTIVE\",\"quoteStatus\":\"LIVE\"}";
    }

    private static java.util.Map<String, Object> history(String date, String buy, String sell) {
        return java.util.Map.of(
                "rateDate", date,
                "buyRate", new java.math.BigDecimal(buy),
                "sellRate", new java.math.BigDecimal(sell));
    }
}
