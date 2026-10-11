package com.steven.assets.bff.aiflow;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class AiFlowProtocolTest {
 @Test void strict_batch_and_optional_reported_outcome(){
  byte[] bytes=AiFlowFixtures.bytes(AiFlowFixtures.batch());assertThat(AiFlowProtocol.batch(bytes,AiFlowFixtures.CLOCK)).isEqualTo(AiFlowFixtures.batch());
  String json=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).replace(",\"reportedOutcome\":null","");
  assertThat(AiFlowProtocol.batch(json.getBytes(java.nio.charset.StandardCharsets.UTF_8),AiFlowFixtures.CLOCK).events().getFirst().reportedOutcome()).isNull();
 }
 @Test void duplicate_any_depth_unknown_owner_and_number_coercion_rejected() throws Exception {
  String body=new String(AiFlowFixtures.bytes(AiFlowFixtures.batch()),java.nio.charset.StandardCharsets.UTF_8);
  for(String invalid:List.of(body.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1"),body.replace("\"method\":\"LOCAL\"","\"method\":\"LOCAL\",\"method\":\"LOCAL\""),body.replace("\"runId\":","\"ownerUserId\":9,\"runId\":"),body.replace("\"sequence\":0","\"sequence\":\"0\""),body.replace("\"sequence\":0","\"sequence\":0.5"))) {
   assertThatThrownBy(()->AiFlowProtocol.batch(invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8),AiFlowFixtures.CLOCK)).isInstanceOf(AiFlowProblem.class);
  }
 }
 @Test void query_dates_consumer_duplicate_and_retention_are_validated_before_IO(){
  var q=new LinkedMultiValueMap<String,String>();q.add("date","2026-10-08");q.add("consumer","Codex");assertThat(AiFlowProtocol.Query.parse(q,false,AiFlowFixtures.CLOCK).period()).isEqualTo("ALL");
  q.add("consumer","Claude");assertThatThrownBy(()->AiFlowProtocol.Query.parse(q,false,AiFlowFixtures.CLOCK)).isInstanceOf(AiFlowProblem.class);
  for(String bad:List.of("2026-02-30","2026-10-12","2026-1-1",""))assertThatThrownBy(()->AiFlowProtocol.window(bad,AiFlowFixtures.CLOCK)).extracting(e->((AiFlowProblem)e).code).isEqualTo("INVALID_REQUEST");
  AiFlowProtocol.window("2026-07-14",AiFlowFixtures.CLOCK);
  assertThatThrownBy(()->AiFlowProtocol.window("2026-07-13",AiFlowFixtures.CLOCK)).extracting(e->((AiFlowProblem)e).code).isEqualTo("RETENTION_EXPIRED");
 }
 @Test void downstream_null_keys_are_required_and_unknown_fields_reject()throws Exception {
  ObjectNode node=(ObjectNode)AiFlowProtocol.JSON.readTree(AiFlowFixtures.bytes(AiFlowFixtures.list(7)));
  AiFlowProtocol.decode(AiFlowFixtures.bytes(node),AiFlowDtos.RunList.class,false);
  ((ObjectNode)node.path("runs").get(0)).remove("reportedOutcome");
  assertThatThrownBy(()->AiFlowProtocol.decode(AiFlowFixtures.bytes(node),AiFlowDtos.RunList.class,false)).extracting(e->((AiFlowProblem)e).code).isEqualTo("UPSTREAM_INVALID");
 }
 @Test void owner_consumer_identity_unknown_state_and_false_ack_are_rejected(){
  var q=new AiFlowProtocol.Query(AiFlowFixtures.DATE,"Codex","ALL");
  AiFlowProtocol.list(AiFlowFixtures.list(7),q,7,AiFlowFixtures.CLOCK);
  assertThatThrownBy(()->AiFlowProtocol.list(AiFlowFixtures.list(8),q,7,AiFlowFixtures.CLOCK)).isInstanceOf(AiFlowProblem.class);
  assertThatThrownBy(()->AiFlowProtocol.list(AiFlowFixtures.list(7),new AiFlowProtocol.Query(AiFlowFixtures.DATE,"Claude","ALL"),7,AiFlowFixtures.CLOCK)).isInstanceOf(AiFlowProblem.class);
  assertThatThrownBy(()->AiFlowProtocol.ack(new AiFlowDtos.Ack(AiFlowFixtures.ID,1,1,List.of(AiFlowFixtures.EVENT)),AiFlowFixtures.batch(),201)).isInstanceOf(AiFlowProblem.class);
  assertThatThrownBy(()->AiFlowProtocol.ack(new AiFlowDtos.Ack(AiFlowFixtures.ID,1,0,List.of(AiFlowFixtures.EVENT)),AiFlowFixtures.batch(),200)).isInstanceOf(AiFlowProblem.class);
 }
}
