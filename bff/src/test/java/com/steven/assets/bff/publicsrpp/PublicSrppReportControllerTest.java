package com.steven.assets.bff.publicsrpp;
import com.steven.assets.bff.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class PublicSrppReportControllerTest {
 private static final String URI=SrppReportQuery.PATH+"?consumer=Codex&tradingDate=2026-10-12&reportSlot=12:00&policyBundleSha256="+"a".repeat(64)+"&swaggerSha256="+"b".repeat(64);
 private WebTestClient client(BusinessUserClient users,WebClient business){
  return WebTestClient.bindToController(new PublicSrppReportController(new PublicSrppReportService(users,business,java.time.Clock.fixed(java.time.Instant.parse("2026-10-12T05:00:00Z"),java.time.ZoneId.of("Asia/Taipei")))))
   .controllerAdvice(new PublicSrppReportExceptionAdvice())
   .webFilter((exchange,chain)->chain.filter(exchange).contextWrite(ctx->ctx.put(AuthConstants.CTX_IDENTITY,"caller-tenant-must-be-cleared"))).build();
 }
 @Test void invalidQueriesNeverLookupOwnerOrReadBusiness(){
  var users=mock(BusinessUserClient.class);var calls=new AtomicInteger();
  var business=WebClient.builder().exchangeFunction(r->{calls.incrementAndGet();return Mono.error(new AssertionError());}).build();
  var client=client(users,business);
  for(String suffix:java.util.List.of("&email=","&consumer=Claude","&unknown=1","&calculationRunId=bad","&reportSlot=11:40")){
   client.get().uri(URI+suffix).exchange().expectStatus().isBadRequest().expectHeader().valueEquals("Cache-Control","private, no-store")
     .expectBody().jsonPath("$.code").isEqualTo("INVALID_REQUEST");
  }
  verifyNoInteractions(users);assertThat(calls).hasValue(0);
 }
 @Test void activeSelectedOwnerIsExplicitAndCallerContextIsCleared(){
  var users=mock(BusinessUserClient.class);when(users.byEmail("selected@example.invalid")).thenReturn(Mono.just(new BffUser(2L,"selected@example.invalid",null,null,"USER","ACTIVE",false)));
  var calls=new AtomicInteger();var business=WebClient.builder().exchangeFunction(r->Mono.deferContextual(ctx->{
   calls.incrementAndGet();assertThat(r.method()).isEqualTo(HttpMethod.GET);assertThat(r.url().getPath()).isEqualTo(SrppReportQuery.INTERNAL);
   assertThat(r.url().getQuery()).doesNotContain("email");assertThat(r.headers().getFirst(AuthConstants.HDR_USER_ID)).isEqualTo("2");
   assertThat(ctx.hasKey(AuthConstants.CTX_IDENTITY)).isFalse();
   return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).header("Content-Type","application/problem+json")
    .body("{\"type\":\"about:blank\",\"title\":\"pending\",\"status\":503,\"detail\":\"pending\",\"instance\":\""+SrppReportQuery.PATH+"\",\"code\":\"CONTEXT_NOT_READY\",\"retryable\":true}").build());
  })).build();
  client(users,business).get().uri(URI+"&email=selected@example.invalid").exchange().expectStatus().isEqualTo(503)
   .expectBody().jsonPath("$.retryable").isEqualTo(true);
  assertThat(calls).hasValue(1);verify(users).byEmail("selected@example.invalid");verify(users,never()).configuredAdmin();
 }
 @Test void verifiedSuccessfulPackageRelaysExactBytesAndRejectsWrongTenant() throws Exception {
  var users=mock(BusinessUserClient.class);when(users.configuredAdmin()).thenReturn(Mono.just(new BffUser(1L,null,null,null,"ADMIN","ACTIVE",true)));
  byte[] bytes=getClass().getResourceAsStream("/srpp-report/report-synthetic.json").readAllBytes();
  var business=WebClient.builder().exchangeFunction(r->Mono.just(ClientResponse.create(HttpStatus.OK)
   .header("Content-Type","application/json").body(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).build())).build();
  var result=client(users,business).get().uri(URI).exchange().expectStatus().isOk()
   .expectHeader().valueEquals("Cache-Control","private, no-store").expectBody().returnResult();
  assertThat(result.getResponseBody()).isEqualTo(bytes);
  when(users.configuredAdmin()).thenReturn(Mono.just(new BffUser(2L,null,null,null,"ADMIN","ACTIVE",true)));
  client(users,business).get().uri(URI).exchange().expectStatus().isEqualTo(502).expectBody().jsonPath("$.code").isEqualTo("UPSTREAM_INVALID");
 }
 @Test void configuredAndSelectedOwnerTimeoutIsFiveSecondsWithoutBusinessRead(){
  var users=mock(BusinessUserClient.class);when(users.configuredAdmin()).thenReturn(Mono.never());when(users.byEmail("selected@example.invalid")).thenReturn(Mono.never());
  var calls=new AtomicInteger();var business=WebClient.builder().exchangeFunction(r->{calls.incrementAndGet();return Mono.error(new AssertionError());}).build();
  var service=new PublicSrppReportService(users,business,java.time.Clock.fixed(java.time.Instant.parse("2026-10-12T05:00:00Z"),java.time.ZoneId.of("Asia/Taipei")));
  for(String suffix:java.util.List.of("","&email=selected@example.invalid")){
   reactor.test.StepVerifier.withVirtualTime(()->service.read(org.springframework.mock.http.server.reactive.MockServerHttpRequest.get(URI+suffix).build()))
    .thenAwait(java.time.Duration.ofSeconds(5)).expectErrorSatisfies(e->assertThat(((SrppReportProblemException)e).problem()).isEqualTo(SrppReportProblemCatalog.OWNER_UNAVAILABLE)).verify();
  }
  assertThat(calls).hasValue(0);
 }
 @Test void requestErrorsAndUpstreamErrorsNeverTriggerErrorLogIngest(){
  var users=mock(BusinessUserClient.class);when(users.configuredAdmin()).thenReturn(Mono.just(new BffUser(1L,null,null,null,"ADMIN","ACTIVE",true)));
  var ingest=mock(com.steven.assets.bff.apierrorlogs.ApiErrorLogIngestClient.class);
  var business=WebClient.builder().exchangeFunction(r->Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json").body("{}").build())).build();
  var client=WebTestClient.bindToController(new PublicSrppReportController(new PublicSrppReportService(users,business,
   java.time.Clock.fixed(java.time.Instant.parse("2026-10-12T05:00:00Z"),java.time.ZoneId.of("Asia/Taipei")))))
   .controllerAdvice(new PublicSrppReportExceptionAdvice())
   .webFilter(new com.steven.assets.bff.apierrorlogs.PublicApiErrorCaptureWebFilter(ingest,new com.steven.assets.bff.apierrorlogs.ApiErrorLogDiagnosticRenderer(""))).build();
  client.get().uri(URI+"&unexpected=1").exchange().expectStatus().isBadRequest();
  verify(users,never()).configuredAdmin();verifyNoInteractions(ingest);
  client.get().uri(URI).exchange().expectStatus().isEqualTo(502);verifyNoInteractions(ingest);
  when(users.configuredAdmin()).thenReturn(Mono.empty());client.get().uri(URI).exchange().expectStatus().isEqualTo(503);verifyNoInteractions(ingest);
 }
 @Test void malformedProblemAndInactiveOwnerFailClosed(){
  var users=mock(BusinessUserClient.class);when(users.configuredAdmin()).thenReturn(Mono.just(new BffUser(1L,null,null,null,"ADMIN","ACTIVE",true)));
  var business=WebClient.builder().exchangeFunction(r->Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
   .header("Content-Type","application/problem+json").body("{\"type\":\"about:blank\",\"title\":\"bad\",\"status\":503,\"detail\":\"bad\",\"instance\":\""+SrppReportQuery.PATH+"\",\"code\":\"OWNER_UNAVAILABLE\",\"retryable\":true}").build())).build();
  client(users,business).get().uri(URI).exchange().expectStatus().isEqualTo(502).expectBody().jsonPath("$.code").isEqualTo("UPSTREAM_INVALID");
  when(users.configuredAdmin()).thenReturn(Mono.just(new BffUser(1L,null,null,null,"ADMIN","DISABLED",true)));
  client(users,business).get().uri(URI).exchange().expectStatus().isEqualTo(503).expectBody().jsonPath("$.code").isEqualTo("OWNER_UNAVAILABLE");
 }
}
