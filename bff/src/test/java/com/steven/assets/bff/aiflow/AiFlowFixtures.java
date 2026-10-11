package com.steven.assets.bff.aiflow;
import java.time.*; import java.util.List;
final class AiFlowFixtures {
 static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"),ZoneId.of("Asia/Taipei"));
 static final String ID="5bd55e21-7e39-5fa7-8c35-d2b2664e54e1", EVENT="6d6d7f46-c4c2-5520-8895-3f164c4f6d0e";
 static final LocalDate DATE=LocalDate.parse("2026-10-08");
 static final Instant START=Instant.parse("2026-10-08T01:05:00Z");
 static AiFlowDtos.Batch batch(){return new AiFlowDtos.Batch(1,new AiFlowDtos.RunMetadata(ID,"Codex",DATE,"09:05","CODEX_ROLLOUT","thread:turn","codex-srpp-09-30",START,"AI_FLOW_V1"),List.of(new AiFlowDtos.Event(EVENT,0,"calendar","START","RUNNING",START,"確認交易日","讀取日曆",new AiFlowDtos.Operation("LOCAL","calendar"),null,1,List.of(),null,List.of(),null)));}
 static AiFlowDtos.RunView run(long owner){return new AiFlowDtos.RunView(ID,owner,"Codex",DATE,"09:05","CODEX_ROLLOUT","thread:turn","codex-srpp-09-30",START,"AI_FLOW_V1","STARTED",null,"UNCONFIRMED","PARTIAL_OBSERVED",1,null,null,null,"早盤09:05","部分紀錄","執行已開始",null);}
 static AiFlowDtos.RunList list(long owner){return new AiFlowDtos.RunList(DATE,"Codex","ALL","Asia/Taipei",90,LocalDate.parse("2026-07-14"),List.of(run(owner)));}
 static byte[] historicalBatch(){return """
  {
    "schemaVersion": 1,
    "run": {
      "runId": "5bd55e21-7e39-5fa7-8c35-d2b2664e54e1", "consumer": "Codex",
      "reportDate": "2026-10-08", "reportSlot": "09:05", "sourceKind": "CODEX_ROLLOUT",
      "sourceRunId": "thread:turn", "automationId": "codex-srpp-09-30",
      "startedAt": "2026-10-08T09:05:00+08:00", "workflowVersion": "AI_FLOW_V1"
    },
    "events": [{
      "eventId": "6d6d7f46-c4c2-5520-8895-3f164c4f6d0e", "sequence": 0,
      "stepId": "calendar", "eventKind": "START", "status": "RUNNING",
      "occurredAt": "2026-10-08T09:05:00+08:00", "title": "確認交易日", "summary": "讀取日曆",
      "operation": {"method": "LOCAL", "path": "calendar"}, "reasonCode": null,
      "attemptNo": 1, "parentEventIds": [], "relation": null, "evidenceRefs": [], "reportedOutcome": null
    }]
  }
  """.getBytes(java.nio.charset.StandardCharsets.UTF_8);}
 static byte[] bytes(Object value){try{return AiFlowProtocol.JSON.writeValueAsBytes(value);}catch(Exception e){throw new RuntimeException(e);}}
}
