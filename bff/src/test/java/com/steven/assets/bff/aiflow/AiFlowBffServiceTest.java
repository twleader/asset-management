package com.steven.assets.bff.aiflow;
import com.steven.assets.bff.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiFlowBffServiceTest {
 private static final BffUser OWNER=new BffUser(7L,"test@example.invalid","","","ADMIN","ACTIVE",true);
 private final AiFlowCredentials credentials=new AiFlowCredentials("codex","claude","collector");
 private final BusinessUserClient users=mock(BusinessUserClient.class);
 private AiFlowBffService service(ExchangeFunction exchange){return new AiFlowBffService(users,WebClient.builder().exchangeFunction(exchange).build(),credentials,AiFlowFixtures.CLOCK);}
 private ClientResponse response(int status,Object dto){return ClientResponse.create(HttpStatusCode.valueOf(status)).header(HttpHeaders.CONTENT_TYPE,"application/json").body(new String(AiFlowFixtures.bytes(dto),java.nio.charset.StandardCharsets.UTF_8)).build();}
 @Test void query_reject_and_auth_failure_have_zero_owner_or_upstream_IO(){
  var s=service(r->Mono.error(new AssertionError("no upstream")));
  assertThatThrownBy(()->s.list(MockServerHttpRequest.get("/api/bff/ai-flow/runs?date=2026-10-08&consumer=Codex&owner=8").build()).block()).isInstanceOf(AiFlowProblem.class);
  assertThatThrownBy(()->s.record(MockServerHttpRequest.post(AiFlowProducerTokenWebFilter.PATH).build(),AiFlowFixtures.bytes(AiFlowFixtures.batch())).block()).isInstanceOf(AiFlowProblem.class);
  verifyNoInteractions(users);
 }
 @Test void explicit_configured_owner_headers_replace_caller_identity_and_typed_reply_validates(){
  when(users.configuredAdmin()).thenReturn(Mono.just(OWNER));AtomicReference<ClientRequest> sent=new AtomicReference<>();
  var s=service(r->{sent.set(r);return Mono.just(response(200,AiFlowFixtures.list(7)));});
  var result=s.list(MockServerHttpRequest.get("/api/bff/ai-flow/runs?date=2026-10-08&consumer=Codex").header(AuthConstants.HDR_USER_ID,"999").build()).contextWrite(ctx->ctx.put(AuthConstants.CTX_IDENTITY,new TenantIdentity(999L,"USER","ACTIVE"))).block();
  assertThat(result.runs().getFirst().ownerUserId()).isEqualTo(7);assertThat(sent.get().headers().getFirst(AuthConstants.HDR_USER_ID)).isEqualTo("7");
  assertThat(sent.get().url().toString()).isEqualTo("/api/ai-flow/runs?date=2026-10-08&consumer=Codex&period=ALL");verify(users).configuredAdmin();verifyNoMoreInteractions(users);
 }
 @Test void wrong_owner_or_missing_body_keys_are_502(){
  when(users.configuredAdmin()).thenReturn(Mono.just(OWNER));
  var s=service(r->Mono.just(response(200,AiFlowFixtures.list(8))));
  assertThatThrownBy(()->s.list(MockServerHttpRequest.get("/api/bff/ai-flow/runs?date=2026-10-08&consumer=Codex").build()).block()).extracting(e->((AiFlowProblem)e).code).isEqualTo("UPSTREAM_INVALID");
 }
 @Test void detail_expired_date_is410_before_owner_even_after_purge(){
  var s=service(r->Mono.error(new AssertionError()));
  assertThatThrownBy(()->s.detail(AiFlowFixtures.ID,MockServerHttpRequest.get("/api/bff/ai-flow/runs/"+AiFlowFixtures.ID+"?date=2026-07-13&consumer=Codex").build()).block()).extracting(e->((AiFlowProblem)e).code).isEqualTo("RETENTION_EXPIRED");verifyNoInteractions(users);
 }
 @Test void durable_ack_counts_and_status_are_checked(){
  when(users.configuredAdmin()).thenReturn(Mono.just(OWNER));
  var request=MockServerHttpRequest.post(AiFlowProducerTokenWebFilter.PATH).header(AiFlowCredentials.HEADER,"collector").contentType(MediaType.APPLICATION_JSON).build();
  var okay=service(r->Mono.just(response(201,new AiFlowDtos.Ack(AiFlowFixtures.ID,1,0,java.util.List.of(AiFlowFixtures.EVENT)))));
  assertThat(okay.record(request,AiFlowFixtures.bytes(AiFlowFixtures.batch())).block().status()).isEqualTo(201);
  var bad=service(r->Mono.just(response(201,new AiFlowDtos.Ack(AiFlowFixtures.ID,0,1,java.util.List.of(AiFlowFixtures.EVENT)))));
  assertThatThrownBy(()->bad.record(request,AiFlowFixtures.bytes(AiFlowFixtures.batch())).block()).extracting(e->((AiFlowProblem)e).code).isEqualTo("UPSTREAM_INVALID");
 }
 @Test void ingress_forwards_original_historical_bytes_and_replay_without_time_normalization(){
  when(users.configuredAdmin()).thenReturn(Mono.just(OWNER));
  byte[] original=AiFlowFixtures.historicalBatch();AtomicReference<byte[]> forwarded=new AtomicReference<>();
  var s=service(request->{
   var output=new MockClientHttpRequest(request.method(),request.url());
   output.getHeaders().putAll(request.headers());
   return request.body().insert(output,new BodyInserter.Context(){
    public List<HttpMessageWriter<?>> messageWriters(){return ExchangeStrategies.withDefaults().messageWriters();}
    public Optional<ServerHttpRequest> serverRequest(){return Optional.empty();}
    public Map<String,Object> hints(){return Map.of();}
   }).then(Mono.defer(()->DataBufferUtils.join(output.getBody()))).map(buffer->{
    byte[] bytes=new byte[buffer.readableByteCount()];buffer.read(bytes);DataBufferUtils.release(buffer);forwarded.set(bytes);
    return response(200,new AiFlowDtos.Ack(AiFlowFixtures.ID,0,1,List.of(AiFlowFixtures.EVENT)));
   });
  });
  var request=MockServerHttpRequest.post(AiFlowProducerTokenWebFilter.PATH).header(AiFlowCredentials.HEADER,"collector").contentType(MediaType.APPLICATION_JSON).build();
  var replay=s.record(request,original).block();
  assertThat(replay.status()).isEqualTo(200);assertThat(replay.ack().inserted()).isZero();assertThat(replay.ack().replayed()).isEqualTo(1);
  assertThat(forwarded.get()).containsExactly(original);
  assertThat(new String(forwarded.get(),java.nio.charset.StandardCharsets.UTF_8)).contains("2026-10-08T09:05:00+08:00","\n");
 }
 @Test void detail_http_path_validation_accepts_templates_and_rejects_invalid_downstream_projection_as502(){
  when(users.configuredAdmin()).thenReturn(Mono.just(OWNER));
  var request=MockServerHttpRequest.get("/api/bff/ai-flow/runs/"+AiFlowFixtures.ID+"?date=2026-10-08&consumer=Codex").build();
  var valid=new AiFlowDtos.Operation("GET","/api/assets/{id}");
  var okay=service(r->Mono.just(response(200,AiFlowOperationContractTest.projection(valid,valid))));
  assertThat(okay.detail(AiFlowFixtures.ID,request).block().nodes().getFirst().operation()).isEqualTo(valid);
  var invalid=new AiFlowDtos.Operation("GET","/assets/latest");
  var bad=service(r->Mono.just(response(200,AiFlowOperationContractTest.projection(invalid,valid))));
  assertThatThrownBy(()->bad.detail(AiFlowFixtures.ID,request).block()).extracting(e->((AiFlowProblem)e).code).isEqualTo("UPSTREAM_INVALID");
 }
}
