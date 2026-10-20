package com.steven.assets.bff.aiflow;
import com.steven.assets.bff.security.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 private AiFlowDtos.ChoiceSetSummary choiceSummary(AiFlowDtos.ChoiceSet choices){
  return new AiFlowDtos.ChoiceSetSummary(choices.title(),choices.selectedOptionId(),choices.options().stream()
   .map(option->new AiFlowDtos.ChoiceOptionSummary(option.optionId(),option.label(),option.toolName(),option.optionId().equals(choices.selectedOptionId()))).toList());
 }
 private ObjectNode projectionWithChoice(AiFlowDtos.ChoiceSet choices,String eventKind,AiFlowDtos.ChoiceSetSummary summary){
  try {
   var operation=new AiFlowDtos.Operation("GET","/api/assets/{id}");
   ObjectNode detail=(ObjectNode)AiFlowProtocol.JSON.readTree(AiFlowFixtures.bytes(AiFlowOperationContractTest.projection(operation,operation)));
   ObjectNode node=(ObjectNode)detail.path("nodes").get(0);
   node.put("eventKind",eventKind);node.set("choiceSetSummary",AiFlowProtocol.JSON.valueToTree(summary));
   return detail;
  } catch(Exception invalid){throw new AssertionError(invalid);}
 }
 @Test void query_reject_and_auth_failure_have_zero_owner_or_upstream_IO(){
  var s=service(r->Mono.error(new AssertionError("no upstream")));
  assertThatThrownBy(()->s.list(MockServerHttpRequest.get("/api/bff/ai-flow/runs?date=2026-10-08&consumer=Codex&owner=8").build()).block()).isInstanceOf(AiFlowProblem.class);
  assertThatThrownBy(()->s.record(MockServerHttpRequest.post(AiFlowProducerTokenWebFilter.PATH).build(),AiFlowFixtures.bytes(AiFlowFixtures.batch())).block()).isInstanceOf(AiFlowProblem.class);
  var original=AiFlowFixtures.batch().events().getFirst();var call=AiFlowFixtures.choiceToolCall();
  var choiceEvent=new AiFlowDtos.Event(original.eventId(),original.sequence(),original.stepId(),original.eventKind(),original.status(),original.occurredAt(),original.title(),original.summary(),original.operation(),original.reasonCode(),original.attemptNo(),original.parentEventIds(),original.relation(),original.evidenceRefs(),original.reportedOutcome(),call,AiFlowFixtures.choiceSet(call));
  var choiceBatch=new AiFlowDtos.Batch(1,AiFlowFixtures.batch().run(),List.of(choiceEvent));
  var consumerToken=MockServerHttpRequest.post(AiFlowProducerTokenWebFilter.PATH).header(AiFlowCredentials.HEADER,"codex").contentType(MediaType.APPLICATION_JSON).build();
  assertThatThrownBy(()->s.record(consumerToken,AiFlowFixtures.bytes(choiceBatch)).block()).extracting(e->((AiFlowProblem)e).code).isEqualTo("FORBIDDEN");
  var claude=AiFlowFixtures.batch("Claude","09:05","srpp-0905-claude").run();
  var wrongSource=new AiFlowDtos.Batch(1,claude,List.of(choiceEvent));
  var collector=MockServerHttpRequest.post(AiFlowProducerTokenWebFilter.PATH).header(AiFlowCredentials.HEADER,"collector").contentType(MediaType.APPLICATION_JSON).build();
  assertThatThrownBy(()->s.record(collector,AiFlowFixtures.bytes(wrongSource)).block()).extracting(e->((AiFlowProblem)e).code).isEqualTo("FORBIDDEN");
  verifyNoInteractions(users);
 }
 @Test void invalid_periods_and_us_producer_bindings_reject_before_owner_or_upstream_IO(){
  var s=service(r->Mono.error(new AssertionError("no upstream")));
  for(String query:List.of("period=","period=EVENING","period=US&period=US"))
   assertThatThrownBy(()->s.list(MockServerHttpRequest.get("/api/bff/ai-flow/runs?date=2026-10-08&consumer=Codex&"+query).build()).block()).extracting(e->((AiFlowProblem)e).code).isEqualTo("INVALID_REQUEST");
  var request=MockServerHttpRequest.post(AiFlowProducerTokenWebFilter.PATH).header(AiFlowCredentials.HEADER,"collector").contentType(MediaType.APPLICATION_JSON).build();
  for(var batch:List.of(AiFlowFixtures.batch("Codex","09:05","22-00"),AiFlowFixtures.batch("Codex","22:00","codex-srpp"),AiFlowFixtures.batch("Claude","22:00","22-00"),AiFlowFixtures.batch("Codex","22:00","22-00-2"),AiFlowFixtures.batch("Codex","22:00","srpp-codex")))
   assertThatThrownBy(()->s.record(request,AiFlowFixtures.bytes(batch)).block()).extracting(e->((AiFlowProblem)e).code).isEqualTo("INVALID_REQUEST");
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
 @Test void us_query_is_forwarded_and_claude_us_empty_is_valid(){
  when(users.configuredAdmin()).thenReturn(Mono.just(OWNER));
  for(String consumer:List.of("Codex","Claude")) {
   var expected=AiFlowFixtures.list(consumer,"US",consumer.equals("Codex")?List.of(AiFlowFixtures.run(7,"Codex","22:00","22-00")):List.of());
   AtomicReference<ClientRequest> sent=new AtomicReference<>();
   var s=service(r->{sent.set(r);return Mono.just(response(200,expected));});
   assertThat(s.list(MockServerHttpRequest.get("/api/bff/ai-flow/runs?date=2026-10-08&consumer="+consumer+"&period=US").build()).block()).isEqualTo(expected);
   assertThat(sent.get().url().toString()).isEqualTo("/api/ai-flow/runs?date=2026-10-08&consumer="+consumer+"&period=US");
   assertThat(sent.get().headers().getFirst(AuthConstants.HDR_USER_ID)).isEqualTo("7");
  }
 }
 @Test void downstream_period_date_owner_consumer_or_us_metadata_mismatch_is502()throws Exception {
  when(users.configuredAdmin()).thenReturn(Mono.just(OWNER));
  var valid=AiFlowFixtures.list("Codex","US",List.of(AiFlowFixtures.run(7,"Codex","22:00","22-00")));
  var request=MockServerHttpRequest.get("/api/bff/ai-flow/runs?date=2026-10-08&consumer=Codex&period=US").build();
  List<java.util.function.Consumer<ObjectNode>> mutations=List.of(
   n->n.put("period","ALL"),n->n.put("date","2026-10-07"),n->n.put("consumer","Claude"),
   n->((ObjectNode)n.path("runs").get(0)).put("ownerUserId",8),
   n->((ObjectNode)n.path("runs").get(0)).put("reportDate","2026-10-07"),
   n->{var run=(ObjectNode)n.path("runs").get(0);run.put("consumer","Claude");run.put("sourceKind","CLAUDE_ARTIFACT");},
   n->{var run=(ObjectNode)n.path("runs").get(0);run.put("reportSlot","12:00");run.put("automationId","codex-srpp");},
   n->((ObjectNode)n.path("runs").get(0)).put("reportSlot","09:05"),
   n->((ObjectNode)n.path("runs").get(0)).put("automationId","codex-srpp"),
   n->((ObjectNode)n.path("runs").get(0)).put("automationId","22-00-2"),
   n->((ObjectNode)n.path("runs").get(0)).put("automationId","srpp-codex"));
  for(var mutate:mutations) {
   ObjectNode node=(ObjectNode)AiFlowProtocol.JSON.readTree(AiFlowFixtures.bytes(valid));mutate.accept(node);
   var s=service(r->Mono.just(response(200,node)));
   assertThatThrownBy(()->s.list(request).block()).extracting(e->((AiFlowProblem)e).code).isEqualTo("UPSTREAM_INVALID");
  }
  var usInNoon=AiFlowFixtures.list("Codex","NOON",valid.runs());
  var s=service(r->Mono.just(response(200,usInNoon)));
  assertThatThrownBy(()->s.list(MockServerHttpRequest.get("/api/bff/ai-flow/runs?date=2026-10-08&consumer=Codex&period=NOON").build()).block()).extracting(e->((AiFlowProblem)e).code).isEqualTo("UPSTREAM_INVALID");
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
 @Test void us_ingress_forwards_original_bytes_and_offset_for_durable_ack(){
  when(users.configuredAdmin()).thenReturn(Mono.just(OWNER));
  byte[] original=new String(AiFlowFixtures.historicalBatch(),java.nio.charset.StandardCharsets.UTF_8)
   .replace("\"reportSlot\": \"09:05\"","\"reportSlot\": \"22:00\"")
   .replace("\"automationId\": \"codex-srpp-09-30\"","\"automationId\": \"22-00\"")
   .replace("2026-10-08T09:05:00+08:00","2026-10-08T21:57:00+08:00").getBytes(java.nio.charset.StandardCharsets.UTF_8);
  AtomicReference<byte[]> forwarded=new AtomicReference<>();
  var s=service(request->{
   var output=new MockClientHttpRequest(request.method(),request.url());
   return request.body().insert(output,new BodyInserter.Context(){
    public List<HttpMessageWriter<?>> messageWriters(){return ExchangeStrategies.withDefaults().messageWriters();}
    public Optional<ServerHttpRequest> serverRequest(){return Optional.empty();}
    public Map<String,Object> hints(){return Map.of();}
   }).then(Mono.defer(()->DataBufferUtils.join(output.getBody()))).map(buffer->{
    byte[] bytes=new byte[buffer.readableByteCount()];buffer.read(bytes);DataBufferUtils.release(buffer);forwarded.set(bytes);
    return response(201,new AiFlowDtos.Ack(AiFlowFixtures.ID,1,0,List.of(AiFlowFixtures.EVENT)));
   });
  });
  var request=MockServerHttpRequest.post(AiFlowProducerTokenWebFilter.PATH).header(AiFlowCredentials.HEADER,"collector").contentType(MediaType.APPLICATION_JSON).build();
  assertThat(s.record(request,original).block().status()).isEqualTo(201);
  assertThat(forwarded.get()).containsExactly(original);
  assertThat(new String(forwarded.get(),java.nio.charset.StandardCharsets.UTF_8)).contains("2026-10-08T21:57:00+08:00","\n");
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
 @Test void detail_accepts_us_metadata_and_rejects_wrong_us_binding_as502()throws Exception {
  when(users.configuredAdmin()).thenReturn(Mono.just(OWNER));
  var request=MockServerHttpRequest.get("/api/bff/ai-flow/runs/"+AiFlowFixtures.ID+"?date=2026-10-08&consumer=Codex").build();
  var operation=new AiFlowDtos.Operation("GET","/api/assets/{id}");
  ObjectNode detail=(ObjectNode)AiFlowProtocol.JSON.readTree(AiFlowFixtures.bytes(AiFlowOperationContractTest.projection(operation,operation)));
  var run=(ObjectNode)detail.path("run");run.put("reportSlot","22:00");run.put("automationId","22-00");run.put("slotLabel","美股22:00");
  var okay=service(r->Mono.just(response(200,detail)));
  assertThat(okay.detail(AiFlowFixtures.ID,request).block().run().reportSlot()).isEqualTo("22:00");
  for(String id:List.of("codex-srpp","22-00-2","srpp-codex")) {
   ObjectNode invalid=detail.deepCopy();((ObjectNode)invalid.path("run")).put("automationId",id);
   var bad=service(r->Mono.just(response(200,invalid)));
   assertThatThrownBy(()->bad.detail(AiFlowFixtures.ID,request).block()).extracting(e->((AiFlowProblem)e).code).isEqualTo("UPSTREAM_INVALID");
  }
 }
 @Test void scopedToolCallReadForwardsConfiguredOwnerAndRejectsMismatchedUpstreamIdentity(){
  when(users.configuredAdmin()).thenReturn(Mono.just(OWNER));AtomicReference<ClientRequest> sent=new AtomicReference<>();
  var call=new AiFlowDtos.ToolCall("exec",List.of("exec_command"),"{\"cmd\":\"date\"}",14,false,0,null);
  var expected=new AiFlowDtos.ToolCallDetail(AiFlowFixtures.ID,AiFlowFixtures.EVENT,call);
  var service=service(request->{sent.set(request);return Mono.just(response(200,expected));});
  var request=MockServerHttpRequest.get("/api/bff/ai-flow/runs/"+AiFlowFixtures.ID+"/nodes/"+AiFlowFixtures.EVENT+"/tool-call?date=2026-10-08&consumer=Codex").build();
  assertThat(service.toolCall(AiFlowFixtures.ID,AiFlowFixtures.EVENT,request).block()).isEqualTo(expected);
  assertThat(sent.get().url().toString()).isEqualTo("/api/ai-flow/runs/"+AiFlowFixtures.ID+"/nodes/"+AiFlowFixtures.EVENT+"/tool-call?date=2026-10-08&consumer=Codex");
  assertThat(sent.get().headers().getFirst(AuthConstants.HDR_USER_ID)).isEqualTo("7");

  var bad=service(requestUpstream->Mono.just(response(200,new AiFlowDtos.ToolCallDetail(AiFlowFixtures.ID,AiFlowFixtures.ID,call))));
  assertThatThrownBy(()->bad.toolCall(AiFlowFixtures.ID,AiFlowFixtures.EVENT,request).block())
   .extracting(e->((AiFlowProblem)e).code).isEqualTo("UPSTREAM_INVALID");
 }
 @Test void scopedChoiceSetReadForwardsConfiguredOwnerValidatesDetailAndKeepsLegacyNull(){
  when(users.configuredAdmin()).thenReturn(Mono.just(OWNER));var sent=new java.util.ArrayList<ClientRequest>();
  var call=AiFlowFixtures.choiceToolCall();var choices=AiFlowFixtures.choiceSet(call);
  var expected=new AiFlowDtos.ChoiceSetDetail(AiFlowFixtures.ID,AiFlowFixtures.EVENT,choices);
  var summary=choiceSummary(choices);var projection=projectionWithChoice(choices,"START",summary);
  var service=service(request->{sent.add(request);String path=request.url().getPath();
   if(path.endsWith("/choice-set"))return Mono.just(response(200,expected));
   if(path.endsWith("/tool-call"))return Mono.just(response(200,new AiFlowDtos.ToolCallDetail(AiFlowFixtures.ID,AiFlowFixtures.EVENT,call)));
   if(path.equals("/api/ai-flow/runs/"+AiFlowFixtures.ID))return Mono.just(response(200,projection));
   return Mono.error(new AssertionError("unexpected upstream path "+path));});
  var request=MockServerHttpRequest.get("/api/bff/ai-flow/runs/"+AiFlowFixtures.ID+"/nodes/"+AiFlowFixtures.EVENT+"/choice-set?date=2026-10-08&consumer=Codex").build();
  assertThat(service.choiceSet(AiFlowFixtures.ID,AiFlowFixtures.EVENT,request).block()).isEqualTo(expected);
  assertThat(sent).extracting(value->value.url().toString()).containsExactlyInAnyOrder(
   "/api/ai-flow/runs/"+AiFlowFixtures.ID+"/nodes/"+AiFlowFixtures.EVENT+"/choice-set?date=2026-10-08&consumer=Codex",
   "/api/ai-flow/runs/"+AiFlowFixtures.ID+"/nodes/"+AiFlowFixtures.EVENT+"/tool-call?date=2026-10-08&consumer=Codex",
   "/api/ai-flow/runs/"+AiFlowFixtures.ID+"?date=2026-10-08&consumer=Codex");
  assertThat(sent).allSatisfy(value->assertThat(value.headers().getFirst(AuthConstants.HDR_USER_ID)).isEqualTo("7"));

  var legacy=new AiFlowDtos.ChoiceSetDetail(AiFlowFixtures.ID,AiFlowFixtures.EVENT,null);
  assertThat(service(requestUpstream->Mono.just(response(200,legacy))).choiceSet(AiFlowFixtures.ID,AiFlowFixtures.EVENT,request).block()).isEqualTo(legacy);
  var bad=service(requestUpstream->Mono.just(response(200,new AiFlowDtos.ChoiceSetDetail(AiFlowFixtures.ID,AiFlowFixtures.ID,choices))));
  assertThatThrownBy(()->bad.choiceSet(AiFlowFixtures.ID,AiFlowFixtures.EVENT,request).block())
   .extracting(e->((AiFlowProblem)e).code).isEqualTo("UPSTREAM_INVALID");
 }
 @Test void choiceSetReadsAndNodeSummaryRejectHostileActualCallMetadataAndEventKind(){
  when(users.configuredAdmin()).thenReturn(Mono.just(OWNER));
  var actual=AiFlowFixtures.choiceToolCall();var base=AiFlowFixtures.choiceSet(actual);
  var request=MockServerHttpRequest.get("/api/bff/ai-flow/runs/"+AiFlowFixtures.ID+"/nodes/"+AiFlowFixtures.EVENT+"/choice-set?date=2026-10-08&consumer=Codex").build();
  for(String attack:List.of("parameters","metadata","wrong-event-kind","missing-tool-call")){
   AiFlowDtos.ChoiceSet choices=base;AiFlowDtos.ToolCall tool=actual;String kind="START";
   if(attack.equals("parameters")){
    String changed="{\"cmd\":\"date -u\"}";choices=new AiFlowDtos.ChoiceSet(base.title(),base.selectedOptionId(),List.of(
     new AiFlowDtos.ChoiceOption("current-date","目前日期","exec",changed,changed.codePointCount(0,changed.length()),false,0,null),base.options().get(1)));
   } else if(attack.equals("metadata")){
    var selected=base.options().getFirst();choices=new AiFlowDtos.ChoiceSet(base.title(),base.selectedOptionId(),List.of(
     new AiFlowDtos.ChoiceOption(selected.optionId(),selected.label(),selected.toolName(),selected.parameters(),selected.parametersChars(),selected.truncated(),1,selected.sourceTruncated()),base.options().get(1)));
   } else if(attack.equals("wrong-event-kind")) kind="END";
   else tool=null;
   var detail=new AiFlowDtos.ChoiceSetDetail(AiFlowFixtures.ID,AiFlowFixtures.EVENT,choices);
   var projection=projectionWithChoice(choices,kind,choiceSummary(choices));var upstreamTool=tool;
   var service=service(upstream->{String path=upstream.url().getPath();
    if(path.endsWith("/choice-set"))return Mono.just(response(200,detail));
    if(path.endsWith("/tool-call"))return Mono.just(response(200,new AiFlowDtos.ToolCallDetail(AiFlowFixtures.ID,AiFlowFixtures.EVENT,upstreamTool)));
    if(path.equals("/api/ai-flow/runs/"+AiFlowFixtures.ID))return Mono.just(response(200,projection));
    return Mono.error(new AssertionError("unexpected upstream path "+path));});
   assertThatThrownBy(()->service.choiceSet(AiFlowFixtures.ID,AiFlowFixtures.EVENT,request).block()).as(attack)
    .extracting(error->((AiFlowProblem)error).code).isEqualTo("UPSTREAM_INVALID");
  }
 }
 @Test void detailRevalidatesChoiceSummaryAgainstPrivateSameEventReadsWithoutExposingParameters(){
  when(users.configuredAdmin()).thenReturn(Mono.just(OWNER));
  var call=AiFlowFixtures.choiceToolCall();var choices=AiFlowFixtures.choiceSet(call);
  var projection=projectionWithChoice(choices,"START",choiceSummary(choices));var requests=new java.util.ArrayList<ClientRequest>();
  var service=service(upstream->{requests.add(upstream);String path=upstream.url().getPath();
   if(path.equals("/api/ai-flow/runs/"+AiFlowFixtures.ID))return Mono.just(response(200,projection));
   if(path.endsWith("/choice-set"))return Mono.just(response(200,new AiFlowDtos.ChoiceSetDetail(AiFlowFixtures.ID,AiFlowFixtures.EVENT,choices)));
   if(path.endsWith("/tool-call"))return Mono.just(response(200,new AiFlowDtos.ToolCallDetail(AiFlowFixtures.ID,AiFlowFixtures.EVENT,call)));
   return Mono.error(new AssertionError("unexpected upstream path "+path));});
  var request=MockServerHttpRequest.get("/api/bff/ai-flow/runs/"+AiFlowFixtures.ID+"?date=2026-10-08&consumer=Codex").build();
  var response=service.detail(AiFlowFixtures.ID,request).block();
  assertThat(response.nodes().getFirst().toolCall()).isNull();
  assertThat(new String(AiFlowFixtures.bytes(response),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("{\"cmd\":\"date\"}","date -u");
  assertThat(requests).hasSize(3);
 }
 @Test void choiceSetControllerKeepsDetailPrivateAndNoStore(){
  var service=mock(AiFlowBffService.class);var request=MockServerHttpRequest.get("/").build();
  var detail=new AiFlowDtos.ChoiceSetDetail(AiFlowFixtures.ID,AiFlowFixtures.EVENT,null);
  when(service.choiceSet(AiFlowFixtures.ID,AiFlowFixtures.EVENT,request)).thenReturn(Mono.just(detail));
  var response=new AiFlowBffController(service).choiceSet(AiFlowFixtures.ID,AiFlowFixtures.EVENT,request).block();
  assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("private, no-store");
  assertThat(response.getBody()).isEqualTo(detail);
 }
}
