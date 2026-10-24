package com.steven.assets.bff.aiflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AiFlowTemporalContractTest {
    @Test void historical_source_offset_payload_decodes_without_changing_source_bytes() {
        byte[] source=AiFlowFixtures.historicalBatch(), before=source.clone();
        var decoded=AiFlowProtocol.batch(source,AiFlowFixtures.CLOCK);
        assertThat(decoded).isEqualTo(AiFlowFixtures.batch());
        assertThat(decoded.run().reportDate()).isEqualTo(AiFlowFixtures.DATE);
        assertThat(decoded.run().startedAt()).isEqualTo(AiFlowFixtures.START);
        assertThat(source).containsExactly(before);
    }

    @Test void different_legal_offsets_represent_the_same_instant_and_keep_report_date() {
        String historical=new String(AiFlowFixtures.historicalBatch(),StandardCharsets.UTF_8);
        var taipei=AiFlowProtocol.batch(historical.getBytes(StandardCharsets.UTF_8),AiFlowFixtures.CLOCK);
        for(String offset:List.of("2026-10-08T01:05:00Z","2026-10-07T21:05:00-04:00")) {
            var equivalent=AiFlowProtocol.batch(historical.replace("2026-10-08T09:05:00+08:00",offset).getBytes(StandardCharsets.UTF_8),AiFlowFixtures.CLOCK);
            assertThat(equivalent).isEqualTo(taipei);
            assertThat(equivalent.run().reportDate()).isEqualTo(AiFlowFixtures.DATE);
        }
    }

    @Test void strict_json_date_and_timestamp_types_do_not_coerce_invalid_values() throws Exception {
        ObjectNode valid=(ObjectNode)AiFlowProtocol.JSON.readTree(AiFlowFixtures.historicalBatch());
        for(JsonNode value:List.of(AiFlowProtocol.JSON.readTree("123"),AiFlowProtocol.JSON.readTree("[2026,10,8]"),AiFlowProtocol.JSON.readTree("\"2026-10-08T09:05:00\""),AiFlowProtocol.JSON.readTree("\"2026-02-30T09:05:00+08:00\""))) {
            ObjectNode input=valid.deepCopy(); ((ObjectNode)input.get("run")).set("startedAt",value);
            assertThatThrownBy(()->AiFlowProtocol.batch(AiFlowFixtures.bytes(input),AiFlowFixtures.CLOCK))
                .extracting(e->((AiFlowProblem)e).code).isEqualTo("INVALID_REQUEST");
        }
        for(JsonNode value:List.of(AiFlowProtocol.JSON.readTree("20261008"),AiFlowProtocol.JSON.readTree("[2026,10,8]"),AiFlowProtocol.JSON.readTree("\"2026-1-8\""),AiFlowProtocol.JSON.readTree("\"2026-02-30\""))) {
            ObjectNode input=valid.deepCopy(); ((ObjectNode)input.get("run")).set("reportDate",value);
            assertThatThrownBy(()->AiFlowProtocol.batch(AiFlowFixtures.bytes(input),AiFlowFixtures.CLOCK))
                .extracting(e->((AiFlowProblem)e).code).isEqualTo("INVALID_REQUEST");
        }
        ObjectNode downstream=(ObjectNode)AiFlowProtocol.JSON.readTree(AiFlowFixtures.bytes(AiFlowFixtures.list(7)));
        downstream.set("earliestDate",AiFlowProtocol.JSON.readTree("[2026,7,14]"));
        assertThatThrownBy(()->AiFlowProtocol.decode(AiFlowFixtures.bytes(downstream),AiFlowDtos.RunList.class,false))
            .extracting(e->((AiFlowProblem)e).code).isEqualTo("UPSTREAM_INVALID");
    }

    @Test void iso_read_serialization_preserves_all_nullable_keys_under_non_null_global_default() throws Exception {
        var mapper=AiFlowProtocol.JSON.copy().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        ObjectNode list=(ObjectNode)mapper.readTree(mapper.writeValueAsBytes(AiFlowFixtures.list(7)));
        assertThat(list.get("date").textValue()).isEqualTo("2026-10-08");
        assertThat(list.get("earliestDate").textValue()).isEqualTo("2026-07-14");
        ObjectNode run=(ObjectNode)list.get("runs").get(0);
        assertThat(run.size()).isEqualTo(22);
        assertThat(run.get("startedAt").textValue()).isEqualTo("2026-10-08T01:05:00Z");
        for(String field:List.of("reportedOutcome","durationMillis","endedAt","reasonCode","outcomeLabel"))
            assertThat(run.has(field) && run.get(field).isNull()).as(field).isTrue();
        ObjectNode historical=(ObjectNode)AiFlowProtocol.JSON.readTree(AiFlowFixtures.historicalBatch());
        ((ObjectNode)historical.get("run")).putNull("startedAt");
        ((ObjectNode)historical.get("events").get(0)).putNull("occurredAt");
        var unknown=AiFlowProtocol.batch(AiFlowFixtures.bytes(historical),AiFlowFixtures.CLOCK);
        assertThat(unknown.run().startedAt()).isNull();
        assertThat(unknown.events().getFirst().occurredAt()).isNull();
        JsonNode wire=mapper.readTree(mapper.writeValueAsBytes(unknown));
        assertThat(wire.get("run").has("startedAt") && wire.get("run").get("startedAt").isNull()).isTrue();
        assertThat(wire.get("events").get(0).has("occurredAt") && wire.get("events").get(0).get("occurredAt").isNull()).isTrue();
    }

    @Test void detail_slow_step_duration_uses_instants_across_offset_boundaries() throws Exception {
        var event=AiFlowFixtures.batch().events().getFirst();
        var node=new AiFlowDtos.Node(event.eventId(),event.sequence(),event.stepId(),event.eventKind(),event.status(),event.occurredAt(),event.title(),event.summary(),event.operation(),event.reasonCode(),event.attemptNo(),event.parentEventIds(),event.relation(),event.evidenceRefs(),event.reportedOutcome(),"執行中","確認交易日","交易日曆","09:05:00",null,0,0,"#123456",null,null,null);
        var slow=new AiFlowDtos.SlowStep("calendar",event.eventId(),"確認交易日",event.operation(),AiFlowFixtures.START,AiFlowFixtures.START.plusSeconds(2),2000,"SUCCEEDED","成功");
        var detail=new AiFlowDtos.Detail(AiFlowFixtures.run(7),List.of(node),List.of(),List.of(slow));
        ObjectNode wire=(ObjectNode)AiFlowProtocol.JSON.readTree(AiFlowFixtures.bytes(detail));
        ObjectNode slowWire=(ObjectNode)wire.get("slowSteps").get(0);
        slowWire.put("startedAt","2026-10-08T09:05:00+08:00");
        slowWire.put("endedAt","2026-10-07T21:05:02-04:00");
        var decoded=AiFlowProtocol.decode(AiFlowFixtures.bytes(wire),AiFlowDtos.Detail.class,false);
        AiFlowProtocol.detail(decoded,new AiFlowProtocol.Query(AiFlowFixtures.DATE,"Codex","ALL"),7,AiFlowFixtures.ID,AiFlowFixtures.CLOCK);
        assertThat(decoded.slowSteps().getFirst().endedAt()).isEqualTo(Instant.parse("2026-10-08T01:05:02Z"));
        assertThat(decoded.nodes().getFirst().timeLabel()).isEqualTo("09:05:00");
        slowWire.putNull("startedAt");
        var missingStart=AiFlowProtocol.decode(AiFlowFixtures.bytes(wire),AiFlowDtos.Detail.class,false);
        assertThatThrownBy(()->AiFlowProtocol.detail(missingStart,new AiFlowProtocol.Query(AiFlowFixtures.DATE,"Codex","ALL"),7,AiFlowFixtures.ID,AiFlowFixtures.CLOCK)).isInstanceOf(AiFlowProblem.class);
    }
}
