package com.steven.assets.bff.aiflow;
import com.steven.assets.bff.apierrorlogs.ApiErrorLogIngestClient;
import com.steven.assets.bff.security.BusinessUserClient;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.*;

@SpringBootTest
@TestPropertySource(properties={"business-services.url=http://localhost:1","AI_FLOW_CODEX_TOKEN=codex-isolated","AI_FLOW_CLAUDE_TOKEN=claude-isolated","AI_FLOW_COLLECTOR_TOKEN=collector-isolated"})
class AiFlowSecurityTest {
 @Autowired ApplicationContext context;
 @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
 @MockitoBean BusinessUserClient users;
 @MockitoBean ApiErrorLogIngestClient ingest;
 WebTestClient client;
 @BeforeEach void setup(){reset(users,ingest);client=WebTestClient.bindToApplicationContext(context).apply(springSecurity()).configureClient().build();}
 @Test void anonymous_read401_and_nonadmin403_zero_owner_IO(){
  String path="/api/bff/ai-flow/runs?date=2026-10-08&consumer=Codex";
  client.get().uri(path).exchange().expectStatus().isUnauthorized();
  client.mutateWith(mockUser().roles("USER")).get().uri(path).exchange().expectStatus().isForbidden();
  String nodePath="/api/bff/ai-flow/runs/5bd55e21-7e39-5fa7-8c35-d2b2664e54e1/nodes/6d6d7f46-c4c2-5520-8895-3f164c4f6d0e/tool-call?date=2026-10-08&consumer=Codex";
  client.get().uri(nodePath).exchange().expectStatus().isUnauthorized();
  client.mutateWith(mockUser().roles("USER")).get().uri(nodePath).exchange().expectStatus().isForbidden();
  String decisionPath=nodePath.replace("/tool-call?","/public-decision?");
  client.get().uri(decisionPath).exchange().expectStatus().isUnauthorized();
  client.mutateWith(mockUser().roles("USER")).get().uri(decisionPath).exchange().expectStatus().isForbidden();
  verifyNoInteractions(users,ingest);
 }
 @Test void producer_missing_wrong_and_multivalue_tokens401_before_owner_and_ingest(){
  for(String token:new String[]{"","wrong"})client.post().uri(AiFlowProducerTokenWebFilter.PATH).header(AiFlowCredentials.HEADER,token).exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.code").isEqualTo("TOKEN_REQUIRED");
  client.post().uri(AiFlowProducerTokenWebFilter.PATH).header(AiFlowCredentials.HEADER,"collector-isolated","collector-isolated").exchange().expectStatus().isUnauthorized();
  verifyNoInteractions(users,ingest);
 }
 @Test void wrongmethod_descendant_and_matrix_have_explicit_status_without_IO(){
  client.get().uri(AiFlowProducerTokenWebFilter.PATH).exchange().expectStatus().isEqualTo(405).expectHeader().valueEquals("Allow","POST");
  client.post().uri(AiFlowProducerTokenWebFilter.PATH+"/child").exchange().expectStatus().isNotFound();
  client.post().uri("/api/srpp/ai-flow;bad/events").exchange().expectStatus().isNotFound();
  verifyNoInteractions(users,ingest);
 }
 @Test void observation_contract_requires_login_and_keeps_financial_contract_separate(){
  client.get().uri("/api/bff/open-api/observation-contract").exchange().expectStatus().isUnauthorized();
  client.mutateWith(mockUser().roles("USER")).get().uri("/api/bff/open-api/observation-contract").exchange().expectStatus().isOk().expectBody(String.class).value(s->org.assertj.core.api.Assertions.assertThat(s).contains("AI 判斷流程觀測補充 API","recordSrppAiFlowEvents").doesNotContain("getLatestAssets"));
  verifyNoInteractions(users,ingest);
 }
 @Test void application_mapper_serves_typed_observation_times_as_iso_and_preserves_null_keys() throws Exception {
  var json=mapper.readTree(mapper.writeValueAsBytes(AiFlowFixtures.list(7)));
  org.assertj.core.api.Assertions.assertThat(json.get("date").textValue()).isEqualTo("2026-10-08");
  org.assertj.core.api.Assertions.assertThat(json.get("earliestDate").textValue()).isEqualTo("2026-07-14");
  var run=json.get("runs").get(0);
  org.assertj.core.api.Assertions.assertThat(run.get("startedAt").isTextual()).isTrue();
  org.assertj.core.api.Assertions.assertThat(java.time.OffsetDateTime.parse(run.get("startedAt").textValue()).toInstant()).isEqualTo(AiFlowFixtures.START);
  org.assertj.core.api.Assertions.assertThat(run.size()).isEqualTo(22);
  for(String field:java.util.List.of("reportedOutcome","durationMillis","endedAt","reasonCode","outcomeLabel"))
   org.assertj.core.api.Assertions.assertThat(run.has(field) && run.get(field).isNull()).as(field).isTrue();
  verifyNoInteractions(users,ingest);
 }
}
