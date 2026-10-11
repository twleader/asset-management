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
 @Test void all_four_period_queries_and_closed_invalid_values(){
  for(String period:List.of("ALL","MORNING","NOON","US")) {
   var q=new LinkedMultiValueMap<String,String>();q.add("date","2026-10-08");q.add("consumer","Codex");q.add("period",period);
   assertThat(AiFlowProtocol.Query.parse(q,false,AiFlowFixtures.CLOCK).period()).isEqualTo(period);
  }
  for(String period:List.of("","EVENING","us")) {
   var q=new LinkedMultiValueMap<String,String>();q.add("date","2026-10-08");q.add("consumer","Codex");q.add("period",period);
   assertThatThrownBy(()->AiFlowProtocol.Query.parse(q,false,AiFlowFixtures.CLOCK)).extracting(e->((AiFlowProblem)e).code).isEqualTo("INVALID_REQUEST");
  }
  var duplicate=new LinkedMultiValueMap<String,String>();duplicate.add("date","2026-10-08");duplicate.add("consumer","Codex");duplicate.add("period","US");duplicate.add("period","US");
  assertThatThrownBy(()->AiFlowProtocol.Query.parse(duplicate,false,AiFlowFixtures.CLOCK)).extracting(e->((AiFlowProblem)e).code).isEqualTo("INVALID_REQUEST");
 }
 @Test void us_binding_is_bidirectional_and_existing_tw_consumer_id_sets_remain_compatible(){
  var us=AiFlowFixtures.batch("Codex","22:00","22-00");
  assertThat(AiFlowProtocol.batch(AiFlowFixtures.bytes(us),AiFlowFixtures.CLOCK)).isEqualTo(us);
  for(String slot:List.of("09:05","12:00","11:40")) {
   for(String automation:List.of("codex-srpp-09-30","codex-srpp")) {
    var tw=AiFlowFixtures.batch("Codex",slot,automation);
    assertThat(AiFlowProtocol.batch(AiFlowFixtures.bytes(tw),AiFlowFixtures.CLOCK)).isEqualTo(tw);
   }
   for(String automation:List.of("srpp-0905-claude","srpp-1150-claude")) {
    var tw=AiFlowFixtures.batch("Claude",slot,automation);
    assertThat(AiFlowProtocol.batch(AiFlowFixtures.bytes(tw),AiFlowFixtures.CLOCK)).isEqualTo(tw);
   }
   assertThatThrownBy(()->AiFlowProtocol.batch(AiFlowFixtures.bytes(AiFlowFixtures.batch("Codex",slot,"22-00")),AiFlowFixtures.CLOCK)).extracting(e->((AiFlowProblem)e).code).isEqualTo("INVALID_REQUEST");
  }
  for(String id:List.of("codex-srpp-09-30","codex-srpp","srpp-0905-claude","srpp-1150-claude","22-00-2","srpp-codex"))
   assertThatThrownBy(()->AiFlowProtocol.batch(AiFlowFixtures.bytes(AiFlowFixtures.batch("Codex","22:00",id)),AiFlowFixtures.CLOCK)).extracting(e->((AiFlowProblem)e).code).isEqualTo("INVALID_REQUEST");
  assertThatThrownBy(()->AiFlowProtocol.batch(AiFlowFixtures.bytes(AiFlowFixtures.batch("Claude","22:00","22-00")),AiFlowFixtures.CLOCK)).extracting(e->((AiFlowProblem)e).code).isEqualTo("INVALID_REQUEST");
 }
 @Test void period_membership_keeps_us_out_of_noon_and_all_preserves_every_slot(){
  var morning=AiFlowFixtures.run(7,"Codex","09:05","codex-srpp-09-30");
  var noon=AiFlowFixtures.run(7,"Codex","12:00","codex-srpp");
  var historical=AiFlowFixtures.run(7,"Codex","11:40","codex-srpp");
  var us=AiFlowFixtures.run(7,"Codex","22:00","22-00");
  var all=List.of(morning,noon,historical,us);
  AiFlowProtocol.list(AiFlowFixtures.list("Codex","ALL",all),new AiFlowProtocol.Query(AiFlowFixtures.DATE,"Codex","ALL"),7,AiFlowFixtures.CLOCK);
  for(String period:List.of("MORNING","NOON","US")) {
   var selected=period.equals("MORNING")?List.of(morning):period.equals("NOON")?List.of(noon,historical):List.of(us);
   var query=new AiFlowProtocol.Query(AiFlowFixtures.DATE,"Codex",period);
   AiFlowProtocol.list(AiFlowFixtures.list("Codex",period,selected),query,7,AiFlowFixtures.CLOCK);
   for(var excluded:all.stream().filter(r->!selected.contains(r)).toList())
    assertThatThrownBy(()->AiFlowProtocol.list(AiFlowFixtures.list("Codex",period,List.of(excluded)),query,7,AiFlowFixtures.CLOCK)).isInstanceOf(AiFlowProblem.class);
  }
  AiFlowProtocol.list(AiFlowFixtures.list("Claude","US",List.of()),new AiFlowProtocol.Query(AiFlowFixtures.DATE,"Claude","US"),7,AiFlowFixtures.CLOCK);
 }
}
