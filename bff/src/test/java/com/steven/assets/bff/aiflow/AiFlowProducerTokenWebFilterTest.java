package com.steven.assets.bff.aiflow;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiFlowProducerTokenWebFilterTest {
 private AiFlowCredentials credentials(){return new AiFlowCredentials("codex-test","claude-test","collector-test");}
 @Test void no_missing_wrong_multivalue_token_ever_calls_chain_or_owner(){
  var filter=new AiFlowProducerTokenWebFilter(credentials(),AiFlowProtocol.JSON);
  for(List<String> headers:List.of(List.<String>of(),List.of(""),List.of("wrong"),List.of("codex-test","codex-test"))){
   var request=MockServerHttpRequest.post(AiFlowProducerTokenWebFilter.PATH);
   for(String h:headers)request.header(AiFlowCredentials.HEADER,h);
   var x=MockServerWebExchange.from(request.build());WebFilterChain chain=mock(WebFilterChain.class);
   filter.filter(x,chain).block();assertThat(x.getResponse().getStatusCode().value()).isEqualTo(401);
   assertThat(x.getResponse().getBodyAsString().block()).contains("TOKEN_REQUIRED").doesNotContain("codex-test");verifyNoInteractions(chain);
  }
 }
 @Test void missing_configuration_503_before_chain_and_missing_token_still401(){
  var filter=new AiFlowProducerTokenWebFilter(new AiFlowCredentials("same","same",""),AiFlowProtocol.JSON);
  var x=MockServerWebExchange.from(MockServerHttpRequest.post(AiFlowProducerTokenWebFilter.PATH).header(AiFlowCredentials.HEADER,"same"));
  WebFilterChain chain=mock(WebFilterChain.class);filter.filter(x,chain).block();assertThat(x.getResponse().getStatusCode().value()).isEqualTo(503);verifyNoInteractions(chain);
 }
 @Test void wrong_method_or_descendant_matrix_have_no_side_effect(){
  var filter=new AiFlowProducerTokenWebFilter(credentials(),AiFlowProtocol.JSON);
  for(String path:List.of(AiFlowProducerTokenWebFilter.PATH+"/child","/api/srpp/ai-flow;matrix/events")){
   var x=MockServerWebExchange.from(MockServerHttpRequest.post(path));WebFilterChain chain=mock(WebFilterChain.class);filter.filter(x,chain).block();assertThat(x.getResponse().getStatusCode().value()).isEqualTo(404);verifyNoInteractions(chain);
  }
  var x=MockServerWebExchange.from(MockServerHttpRequest.get(AiFlowProducerTokenWebFilter.PATH));filter.filter(x,z->Mono.error(new AssertionError())).block();assertThat(x.getResponse().getStatusCode().value()).isEqualTo(405);assertThat(x.getResponse().getHeaders().getAllow()).containsExactly(HttpMethod.POST);
 }
 @Test void grant_binding_and_source_eligibility(){
  var c=credentials();var b=AiFlowFixtures.batch();c.authorize(c.authenticate(List.of("collector-test")),b.run());
  assertThatThrownBy(()->c.authorize(c.authenticate(List.of("codex-test")),b.run())).extracting(e->((AiFlowProblem)e).code).isEqualTo("FORBIDDEN");
  var schedule=new AiFlowDtos.RunMetadata(b.run().runId(),"Codex",b.run().reportDate(),b.run().reportSlot(),"SCHEDULE_EVENTS",b.run().sourceRunId(),b.run().automationId(),null,b.run().workflowVersion());
  c.authorize(c.authenticate(List.of("codex-test")),schedule);
  assertThatThrownBy(()->c.authorize(c.authenticate(List.of("claude-test")),schedule)).isInstanceOf(AiFlowProblem.class);
  assertThatThrownBy(()->c.authorize(c.authenticate(List.of("collector-test")),schedule)).isInstanceOf(AiFlowProblem.class);
 }
}
