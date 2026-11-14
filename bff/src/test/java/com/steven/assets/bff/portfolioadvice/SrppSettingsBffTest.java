package com.steven.assets.bff.portfolioadvice;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class SrppSettingsBffTest {
    @Test void rawDuplicateKeysReachActualDownstreamHttpUnchangedAndErrorStatusSurvives()throws Exception {
        AtomicReference<String> received=new AtomicReference<>();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/portfolio-advice/srpp/drafts",exchange->{received.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] response="{\"code\":\"CONFIG_INVALID\",\"message\":\"重複欄位\"}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(400,response.length);exchange.getResponseBody().write(response);exchange.close();});
        server.start();
        try {
            WebClient backend=WebClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build();
            WebTestClient client=WebTestClient.bindToController(new SrppSettingsBffController(backend)).build();
            String raw="{\"name\":\"first\",\"name\":\"second\",\"values\":{\"inflationRate\":0.0225}}";
            client.post().uri("/api/bff/portfolio-advice/srpp/drafts").contentType(MediaType.APPLICATION_JSON).bodyValue(raw).exchange()
                    .expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("CONFIG_INVALID");
            assertThat(received.get()).isEqualTo(raw);
        }finally{server.stop(0);}
    }
    @Test void actualHttpCenterHasSortedFieldGroupsAndNestedScenarioAssumptionsWithoutChangingEvidence()throws Exception {
        var json=SrppSettingsPresentation.JSON;
        var original=json.readTree(getClass().getResourceAsStream("/srpp/settings-result-wire.json"));
        var run=json.createObjectNode().put("id","11111111-1111-4111-8111-111111111111").put("draftId","22222222-2222-4222-8222-222222222222").put("draftRevision",1).put("mode","LOCAL").put("status","SUCCEEDED").put("configSha256",original.path("configSha256").asText()).put("inputSha256",original.path("inputSha256").asText()).put("resultSha256","79d17b6863696707c5c0733c7c214fcbb7136aa78d8a25592697c5f7a3288f9f").put("aiStatus","NOT_REQUESTED").put("publishEligible",false);
        run.set("deterministicResult",original);run.putArray("changes");run.putArray("blockedReasons");
        var root=json.createObjectNode();var cat=root.putObject("catalog").put("schemaVersion",1).put("catalogVersion","SRPP_SETTINGS_CATALOG_V1");
        cat.putObject("currentValues").put("livingExpense",new java.math.BigDecimal("750000.15"));
        var fields=cat.putArray("fields");fields.addObject().put("id","late").put("group","退休規劃").put("order",20);fields.addObject().put("id","first").put("group","資產配置").put("order",0);fields.addObject().put("id","middle").put("group","退休規劃").put("order",10);
        root.putArray("drafts");root.putArray("releases");root.putArray("simulations").add(run);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/portfolio-advice/srpp",exchange->{byte[] bytes=json.writeValueAsBytes(root);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        try {
            WebTestClient client=WebTestClient.bindToController(new SrppSettingsBffController(WebClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build())).build();
            byte[] response=client.get().uri("/api/bff/portfolio-advice/srpp").exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
            var body=json.readTree(response);
            assertThat(body.path("fieldGroups").get(0).path("name").asText()).isEqualTo("資產配置");
            assertThat(body.path("fieldGroups").get(1).path("fields").get(0).path("id").asText()).isEqualTo("middle");
            assertThat(body.path("fieldGroups").get(1).path("fields").get(1).path("id").asText()).isEqualTo("late");
            var actual=body.path("simulations").get(0);sameJson(original,actual.path("deterministicResult"),"deterministicResult");
            assertThat(body.path("catalog").path("currentValues").path("livingExpense").isNumber()).isTrue();
            java.util.Map<String,com.fasterxml.jackson.databind.JsonNode> rows=new java.util.HashMap<>();actual.path("presentation").path("scenario").path("assumptions").forEach(row->rows.put(row.path("key").asText(),row));
            assertThat(rows).hasSize(25);assertThat(rows.get("referenceInflation").path("value").decimalValue()).isEqualByComparingTo("0.0225");assertThat(rows.get("referenceInflation").path("unit").asText()).isEqualTo("fraction");
            assertThat(rows.get("protectedReserveRealTwd").path("value").isTextual()).isTrue();assertThat(rows.get("protectedReserveRealTwd").path("unit").asText()).isEqualTo("TWD");
            for(String key:java.util.List.of("equityRealCompoundPlanningTarget","equityArithmeticPlanningMean","bondNominalPlanningYield","bondReferenceRealArithmeticMean","cashReferenceRealArithmeticMean","fxVolatility","fxVolatilitySource"))assertThat(rows).containsKey(key);
            assertThat(rows.get("returnMeanSource").path("value").asText()).contains("未重新取得本日");assertThat(rows.get("equityMeanConversion").path("value").asText()).contains("近似換算");
            assertThat(actual.path("presentation").path("scenario").path("modeledFields")).isEqualTo(original.path("result").path("scenario").path("modeledFields"));
            assertThat(actual.path("presentation").path("baseline").path("assumptions").size()).isEqualTo(25);
        }finally{server.stop(0);}
    }

    @Test void fullPythonV2SingleRunRoundTripsAndPresentationExposesDatedMoneyWithoutChangingEvidence()throws Exception {
        var json=SrppSettingsPresentation.JSON;var original=json.readTree(getClass().getResourceAsStream("/srpp/settings-result-wire-v2.json"));
        var run=json.createObjectNode().put("id","11111111-1111-4111-8111-111111111111").put("draftId","22222222-2222-4222-8222-222222222222")
                .put("draftRevision",1).put("mode","LOCAL").put("status","SUCCEEDED").put("configSha256",original.path("configSha256").asText())
                .put("inputSha256",original.path("inputSha256").asText()).put("resultSha256","source-fixture-hash").put("aiStatus","NOT_REQUESTED").put("publishEligible",false);
        run.set("deterministicResult",original);run.putArray("changes");run.putArray("blockedReasons");
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/portfolio-advice/srpp/simulations/",exchange->{byte[] bytes=json.writeValueAsBytes(run);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        try {
            // Match the production business client policy; full monthly evidence exceeds the framework's 256 KiB default.
            WebClient backend=WebClient.builder().codecs(c->c.defaultCodecs().maxInMemorySize(16*1024*1024)).baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build();
            WebTestClient client=WebTestClient.bindToController(new SrppSettingsBffController(backend)).configureClient().codecs(c->c.defaultCodecs().maxInMemorySize(16*1024*1024)).build();
            byte[] response=client.get().uri("/api/bff/portfolio-advice/srpp/simulations/11111111-1111-4111-8111-111111111111").exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
            var body=json.readTree(response);sameJson(original,body.path("deterministicResult"),"V2 evidence");
            java.util.Map<String,com.fasterxml.jackson.databind.JsonNode> rows=new java.util.HashMap<>();body.path("presentation").path("scenario").path("assumptions").forEach(row->rows.put(row.path("key").asText(),row));
            assertThat(rows.get("laborInsurance.monthlyNominalTwd").path("value").textValue()).isEqualTo("16013.60");
            assertThat(rows.get("payroll.receivableAfterRetirementNominalTwd").path("value").isTextual()).isTrue();
            assertThat(rows.get("reserves.livingReserveCeilingRealTwd").path("value").textValue()).isEqualTo("2250000.00");
            assertThat(rows.get("strategy.signalBasis").path("value").asText()).contains("不是每日MA200");
            assertThat(body.path("deterministicResult").path("result").path("scenario").path("reserveStrategyAudit").path("monthlyAudit").size()).isGreaterThan(100);
        }finally{server.stop(0);}
    }

    @Test void fullPythonV3AnnualTaxSurvivesRealBffHttpWithoutEvidenceChanges()throws Exception {
        var json=SrppSettingsPresentation.JSON;var original=json.readTree(getClass().getResourceAsStream("/srpp/settings-result-wire-v3.json"));
        var run=json.createObjectNode().put("id","11111111-1111-4111-8111-111111111111").put("draftId","22222222-2222-4222-8222-222222222222")
                .put("draftRevision",1).put("mode","LOCAL").put("status","SUCCEEDED").put("configSha256",original.path("configSha256").asText())
                .put("inputSha256",original.path("inputSha256").asText()).put("resultSha256","6ec13ab59d0995329b5be83bde7d2724ce370814ec9268b5e734335507d69c57").put("aiStatus","NOT_REQUESTED").put("publishEligible",false);
        run.set("deterministicResult",original);run.putArray("changes");run.putArray("blockedReasons");
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/portfolio-advice/srpp/simulations/",exchange->{byte[] bytes=json.writeValueAsBytes(run);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        try {
            WebClient backend=WebClient.builder().codecs(c->c.defaultCodecs().maxInMemorySize(16*1024*1024)).baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build();
            WebTestClient client=WebTestClient.bindToController(new SrppSettingsBffController(backend)).configureClient().codecs(c->c.defaultCodecs().maxInMemorySize(16*1024*1024)).build();
            byte[] response=client.get().uri("/api/bff/portfolio-advice/srpp/simulations/11111111-1111-4111-8111-111111111111").exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
            var body=json.readTree(response);sameJson(original,body.path("deterministicResult"),"V3 canonical evidence");
            var tax=body.at("/deterministicResult/result/scenario/assumptions/cashflowSchedule/payroll/annualIncomeTax");
            assertThat(tax.path("scope").asText()).isEqualTo("SALARY_ONLY_SINGLE_RESIDENT_PLANNING");
            assertThat(tax.path("years").get(0).path("settlementDate").asText()).isEqualTo("2027-05-31");
            assertThat(tax.path("years").get(0).path("brackets").get(4).path("upperNominalTwd").isNull()).isTrue();
            assertThat(tax.path("years").get(0).path("marginalRate").doubleValue()).isEqualTo(.2);
            java.util.Map<String,com.fasterxml.jackson.databind.JsonNode> rows=new java.util.HashMap<>();body.path("presentation").path("scenario").path("assumptions").forEach(row->rows.put(row.path("key").asText(),row));
            assertThat(rows.get("payroll.annualIncomeTax.scope").path("value").asText()).contains("單身居民規劃稅");
            assertThat(rows.get("payroll.annualIncomeTax.futureYearPolicy").path("value").asText()).contains("並非未來已公告法規");
        }finally{server.stop(0);}
    }

    private static void sameJson(com.fasterxml.jackson.databind.JsonNode expected,com.fasterxml.jackson.databind.JsonNode actual,String path) {
        if(expected.isNumber()){assertThat(actual.isNumber()).as(path).isTrue();assertThat(actual.decimalValue()).as(path).isEqualByComparingTo(expected.decimalValue());return;}
        if(expected.isObject()){java.util.Set<String> a=new java.util.TreeSet<>(),b=new java.util.TreeSet<>();expected.fieldNames().forEachRemaining(a::add);actual.fieldNames().forEachRemaining(b::add);assertThat(b).as(path).isEqualTo(a);for(String key:a)sameJson(expected.get(key),actual.get(key),path+"."+key);return;}
        if(expected.isArray()){assertThat(actual.size()).as(path).isEqualTo(expected.size());for(int i=0;i<expected.size();i++)sameJson(expected.get(i),actual.get(i),path+"["+i+"]");return;}
        assertThat(actual).as(path).isEqualTo(expected);
    }

}
