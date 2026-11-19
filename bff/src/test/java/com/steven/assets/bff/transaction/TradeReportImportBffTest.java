package com.steven.assets.bff.transaction;

import com.steven.assets.bff.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.*;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TradeReportImportBffTest {
    static final String ORIGIN = "https://example.test";
    static final String PATH = "/api/bff/transaction/report-import/preview";

    WebTestClient client(String origin, Long actual, Long effective, AtomicInteger calls, int remoteStatus) {
        var web = WebClient.builder().baseUrl("http://business").exchangeFunction(req -> {
            calls.incrementAndGet();
            assertThat(req.url().getPath()).startsWith("/api/asset-transactions/report-import/");
            return Mono.just(ClientResponse.create(HttpStatusCode.valueOf(remoteStatus))
                    .header("Content-Type", "application/json").body("{\"status\":\"RELAYED\"}").build());
        }).build();
        WebFilter login = (exchange, chain) -> {
            if (actual == null) return chain.filter(exchange);
            var oidc = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("APP_UID_" + actual),
                    new SimpleGrantedAuthority("APP_STATUS_ACTIVE")),
                    new OidcIdToken("test", Instant.now(), Instant.now().plusSeconds(60), Map.of("sub", "test")));
            var auth = UsernamePasswordAuthenticationToken.authenticated(oidc, "none", oidc.getAuthorities());
            return chain.filter(exchange)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                    .contextWrite(c -> c.put(AuthConstants.CTX_IDENTITY, new TenantIdentity(effective, "USER", "ACTIVE")));
        };
        return WebTestClient.bindToController(new TradeReportImportBffController(web))
                .webFilter(login, new TradeReportImportBoundary(origin)).build();
    }

    WebTestClient.RequestBodySpec request(WebTestClient c, String path) {
        return c.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .header("Origin", ORIGIN).header("Sec-Fetch-Site", "same-origin")
                .header("X-Trade-Report-Import", "1");
    }

    @Test void ownAuthenticatedRequestRelaysStatusAndLargeLegalBody() {
        var count = new AtomicInteger();
        var client = client(ORIGIN, 7L, 7L, count, 409);
        String base64 = Base64.getEncoder().encodeToString(new byte[512 * 1024]);
        request(client, PATH).bodyValue("{\"rawMimeBase64\":\"" + base64 + "\"}")
                .exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.status").isEqualTo("RELAYED");
        assertThat(count.get()).isEqualTo(1);
    }

    @Test void actualByteLimitAppliesWithoutContentLength() {
        var count = new AtomicInteger();
        var client = client(ORIGIN, 7L, 7L, count, 200);
        request(client, PATH).body(org.springframework.web.reactive.function.BodyInserters.fromDataBuffers(
                reactor.core.publisher.Flux.just(new org.springframework.core.io.buffer.DefaultDataBufferFactory().wrap(
                        ("{\"value\":\"" + "x".repeat(768 * 1024) + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8)))))
                .exchange().expectStatus().isEqualTo(413);
        assertThat(count.get()).isZero();
    }

    @Test void identityAndConfigurationFailClosed() {
        for (var pair : new Long[][]{{null, null}, {7L, 8L}}) {
            var count = new AtomicInteger();
            request(client(ORIGIN, pair[0], pair[1], count, 200), PATH).bodyValue("{}")
                    .exchange().expectStatus().isForbidden();
            assertThat(count.get()).isZero();
        }
        for (String origin : List.of("", "https://example.test/path", "https://user@example.test", "https://example.test?x=1")) {
            var count = new AtomicInteger();
            request(client(origin, 7L, 7L, count, 200), PATH).bodyValue("{}")
                    .exchange().expectStatus().isForbidden();
            assertThat(count.get()).isZero();
        }
    }

    @Test void crossSiteMissingHeadersAndImpersonationCookieRejected() {
        var count = new AtomicInteger();
        var client = client(ORIGIN, 7L, 7L, count, 200);
        request(client, PATH).headers(h -> h.remove("Origin")).bodyValue("{}").exchange().expectStatus().isForbidden();
        request(client, PATH).headers(h -> h.set("Origin", "https://attacker.test")).bodyValue("{}").exchange().expectStatus().isForbidden();
        request(client, PATH).headers(h -> h.set("Sec-Fetch-Site", "cross-site")).bodyValue("{}").exchange().expectStatus().isForbidden();
        request(client, PATH).headers(h -> h.remove("X-Trade-Report-Import")).bodyValue("{}").exchange().expectStatus().isForbidden();
        request(client, PATH).cookie(AuthConstants.COOKIE_IMPERSONATE,"8").bodyValue("{}").exchange().expectStatus().isForbidden();
        assertThat(count.get()).isZero();
    }

    @Test void rawPassthroughUnknownPathsAndMethodsRejected() {
        var count = new AtomicInteger();
        var client = client(ORIGIN, 7L, 7L, count, 200);
        for (String path : List.of("/api/asset-transactions/report-import", "/api/asset-transactions/report-import/apply",
                "/api/bff/transaction/report-import", PATH + "/other", PATH + "?owner=9")) {
            request(client,path).bodyValue("{}").exchange().expectStatus().isForbidden();
        }
        client.get().uri(PATH).exchange().expectStatus().isForbidden();
        assertThat(count.get()).isZero();
    }

    @org.springframework.context.annotation.Configuration
    @org.springframework.web.reactive.config.EnableWebFlux
    static class HttpConfig {}

    @Test void realHttpInboundBoundaryAcceptsNearLimitAndRejectsChunkedOverflow() {
        var calls = new AtomicInteger();
        var web = WebClient.builder().baseUrl("http://business").exchangeFunction(req -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                    .body("{\"status\":\"PREVIEW\"}").build());
        }).build();
        var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        context.register(HttpConfig.class);
        context.registerBean(TradeReportImportBffController.class, () -> new TradeReportImportBffController(web));
        context.refresh();
        var handler = org.springframework.web.server.adapter.WebHttpHandlerBuilder.applicationContext(context).build();
        var server = reactor.netty.http.server.HttpServer.create().host("127.0.0.1").port(0)
                .handle(new org.springframework.http.server.reactive.ReactorHttpHandlerAdapter(handler)).bindNow();
        try {
            var c = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + server.port()).build();
            String body = "{\"rawMimeBase64\":\"" + Base64.getEncoder().encodeToString(new byte[512 * 1024]) + "\"}";
            c.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk();
            c.post().uri(PATH).contentType(MediaType.APPLICATION_JSON)
                    .body(org.springframework.web.reactive.function.BodyInserters.fromDataBuffers(
                            reactor.core.publisher.Flux.just(new org.springframework.core.io.buffer.DefaultDataBufferFactory()
                                    .wrap(("{\"value\":\"" + "x".repeat(768 * 1024) + "\"}")
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)))))
                    .exchange().expectStatus().isEqualTo(413);
            assertThat(calls.get()).isEqualTo(1);
        } finally { server.disposeNow(); context.close(); }
    }

    @Test void invalidJsonRejectedWithoutRelay() {
        var count = new AtomicInteger(); var c = client(ORIGIN, 7L, 7L, count, 200);
        request(c,PATH).bodyValue("[]").exchange().expectStatus().isBadRequest();
        request(c,PATH).bodyValue("oops").exchange().expectStatus().isBadRequest();
        request(c,PATH).bodyValue("{} {}").exchange().expectStatus().isBadRequest();
        request(c,PATH).bodyValue("{\"x\":1,\"x\":2}").exchange().expectStatus().isBadRequest();
        assertThat(count.get()).isZero();
    }
    @Test void projectionIsConsistentAndFailClosed() throws Exception {
        var controller = new TradeReportImportBffController(WebClient.create());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String state : List.of("READY", "ALREADY_IMPORTED")) {
            String json = "{\"status\":\""+state+"\",\"previewHash\":\"hash\",\"rows\":[{\"status\":\"NEW\",\"reason\":null}]}";
            assertThat(mapper.readTree(controller.project("preview",200,json.getBytes()).getBody()).path("canApply").asBoolean()).isTrue();
            assertThat(mapper.readTree(controller.project("apply",200,json.getBytes()).getBody()).path("canApply").asBoolean()).isFalse();
            assertThat(mapper.readTree(controller.project("preview",409,json.getBytes()).getBody()).path("canApply").asBoolean()).isFalse();
        }
        String conflict="{\"status\":\"CONFLICT\",\"rows\":[{\"status\":\"NEW\"},{\"status\":\"CONFLICT\",\"transactionId\":1},{\"status\":\"NEW\",\"reason\":\"POSSIBLE_DUPLICATE\",\"transactionId\":2}]}";
        for(int status:List.of(200,409)) {
            var body=mapper.readTree(controller.project("preview",status,conflict.getBytes()).getBody());
            assertThat(body.path("canApply").asBoolean()).isFalse();assertThat(body.path("conflictRows").size()).isEqualTo(2);
            assertThat(body.path("conflictRows").get(0).path("transactionId").asInt()).isEqualTo(1);
            assertThat(body.path("conflictRows").get(1).path("transactionId").asInt()).isEqualTo(2);
        }
        for(String json:List.of("{}","{\"status\":\"READY\",\"rows\":[]}","{\"status\":\"READY\",\"previewHash\":\"x\",\"rows\":[{\"status\":\"UNKNOWN\"}]}","{\"status\":\"UNKNOWN\",\"previewHash\":\"x\",\"rows\":[{\"status\":\"NEW\"}]}"))
            assertThat(mapper.readTree(controller.project("preview",200,json.getBytes()).getBody()).path("canApply").asBoolean()).isFalse();
        assertThat(controller.project("preview",200,"not-json".getBytes()).getStatusCode().value()).isEqualTo(502);
    }
    @Test void feeProvenanceIsRenderedWithoutChangingEligibilityOrMoney() throws Exception {
        var controller = new TradeReportImportBffController(WebClient.create());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for(String status : List.of("DERIVED","UNAVAILABLE","RECORDED")){
            var report=mapper.createObjectNode().put("status","READY").put("previewHash","hash");
            var row=report.putArray("rows").addObject().put("status","NEW");
            row.putNull("fee");row.putNull("transactionTax");row.putNull("reason");
            row.putObject("feeProvenance").put("status",status).put("reason","SETTLEMENT_UNAVAILABLE");
            var actual=mapper.readTree(controller.project("preview",200,mapper.writeValueAsBytes(report)).getBody());
            assertThat(actual.path("rows")).isEqualTo(report.path("rows"));
            assertThat(actual.path("conflictRows").isEmpty()).isTrue();
            assertThat(actual.path("canApply").asBoolean()).isTrue();
        }
    }
    @Test void feeUpdateProjectionOnlyAllowsCompleteSuccessfulPreview() throws Exception {
        var controller = new TradeReportImportBffController(WebClient.create());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var report = mapper.createObjectNode().put("status", "FEE_UPDATE_READY").put("previewHash", "hash")
                .put("insertedCount", 0).put("feeUpdatedCount", 0);
        var row = report.putArray("rows").addObject().put("status", "FEE_UPDATE_READY").put("fee", 20);
        row.putObject("feeProvenance").put("status", "DERIVED");
        var preview = mapper.readTree(controller.project("preview", 200, mapper.writeValueAsBytes(report)).getBody());
        assertThat(preview.path("canApply").asBoolean()).isTrue();
        assertThat(preview.path("rows")).isEqualTo(report.path("rows"));
        assertThat(preview.path("conflictRows").isEmpty()).isTrue();
        assertThat(preview.path("feeUpdatedCount").asInt()).isZero();
        assertThat(preview.path("newRowCount").asInt(-1)).isZero();
        assertThat(preview.path("feeUpdateRowCount").asInt(-1)).isEqualTo(1);

        for (int status : List.of(200, 409)) {
            var apply = controller.project("apply", status, mapper.writeValueAsBytes(report));
            assertThat(apply.getStatusCode().value()).isEqualTo(status);
            assertThat(mapper.readTree(apply.getBody()).path("canApply").asBoolean()).isFalse();
        }
        assertThat(mapper.readTree(controller.project("preview", 409, mapper.writeValueAsBytes(report)).getBody())
                .path("canApply").asBoolean()).isFalse();
        row.put("reason", "LEDGER_CHANGED");
        var conflict = mapper.readTree(controller.project("preview", 200, mapper.writeValueAsBytes(report)).getBody());
        assertThat(conflict.path("canApply").asBoolean()).isFalse();
        assertThat(conflict.path("conflictRows").size()).isEqualTo(1);
        assertThat(conflict.path("feeUpdateRowCount").asInt(-1)).isZero();
        row.remove("reason");
        report.put("previewHash", " ");
        assertThat(mapper.readTree(controller.project("preview", 200, mapper.writeValueAsBytes(report)).getBody())
                .path("canApply").asBoolean()).isFalse();
    }

    @Test void applyAndIdempotentRetryPreserveFeeCountSeparatelyFromInsertCount() throws Exception {
        var controller = new TradeReportImportBffController(WebClient.create());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (int count : List.of(1, 0)) {
            var report = mapper.createObjectNode().put("status", count == 1 ? "FEE_UPDATED" : "ALREADY_IMPORTED")
                    .put("previewHash", "hash").put("insertedCount", 0).put("feeUpdatedCount", count);
            var result = mapper.readTree(controller.project("apply", 200, mapper.writeValueAsBytes(report)).getBody());
            assertThat(result.path("insertedCount").asInt()).isZero();
            assertThat(result.path("feeUpdatedCount").asInt()).isEqualTo(count);
            assertThat(result.path("feeUpdateRowCount").asInt(-1)).isZero();
            assertThat(result.path("canApply").asBoolean()).isFalse();
        }
    }

    @Test void reconcileModeDoesNotBypassExistingBoundary() {
        var count = new AtomicInteger();
        var c = client(ORIGIN, 7L, 7L, count, 200);
        request(c, PATH).header("Sec-Fetch-Site", "cross-site").bodyValue("{\"reconcileFees\":true}")
                .exchange().expectStatus().isForbidden();
        assertThat(count.get()).isZero();
        request(c, PATH).bodyValue("{\"reconcileFees\":true}").exchange().expectStatus().isOk();
        assertThat(count.get()).isEqualTo(1);
    }
    @Test void newPreviewCountExcludesAlreadyImportedRowsAndOverwritesUpstreamProjection() throws Exception {
        var controller = new TradeReportImportBffController(WebClient.create());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var report = mapper.createObjectNode().put("status", "READY").put("previewHash", "hash")
                .put("newRowCount", 99).put("feeUpdateRowCount", 99);
        var rows = report.putArray("rows");
        rows.addObject().put("status", "NEW");
        rows.addObject().put("status", "ALREADY_IMPORTED");
        rows.addObject().put("status", "NEW");
        var preview = mapper.readTree(controller.project("preview", 200, mapper.writeValueAsBytes(report)).getBody());
        assertThat(preview.path("newRowCount").asInt(-1)).isEqualTo(2);
        assertThat(preview.path("feeUpdateRowCount").asInt(-1)).isZero();
        var invalid = mapper.readTree(controller.project("preview", 200, new byte[0]).getBody());
        assertThat(invalid.path("newRowCount").asInt(-1)).isZero();
        assertThat(invalid.path("feeUpdateRowCount").asInt(-1)).isZero();
    }
}
