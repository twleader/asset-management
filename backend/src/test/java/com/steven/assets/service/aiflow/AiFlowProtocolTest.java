package com.steven.assets.service.aiflow;

import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.dto.aiflow.AiFlowDtos.*;
import com.steven.assets.srpp.SrppJcs;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.steven.assets.service.aiflow.AiFlowFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiFlowProtocolTest {
    @Test void closedMarketSessionBindingsPreserveTwSnapshotsAndRejectEveryUsMismatch() {
        for (String consumer : List.of("Codex", "Claude")) {
            for (String slot : List.of("09:05", "12:00", "11:40", "22:00", "US", "23:00")) {
                for (String automation : List.of("codex-srpp-09-30", "codex-srpp", "srpp-0905-claude",
                        "srpp-1150-claude", "22-00", "22-00-2", "srpp-codex")) {
                    ObjectNode input = raw(complete());
                    ((ObjectNode) input.path("run")).put("consumer", consumer).put("reportSlot", slot)
                            .put("automationId", automation).put("sourceKind", consumer.equals("Codex") ? "CODEX_ROLLOUT" : "CLAUDE_ARTIFACT");
                    boolean valid = slot.equals("22:00") ? consumer.equals("Codex") && automation.equals("22-00")
                            : List.of("09:05", "12:00", "11:40").contains(slot)
                            && automation.equals(consumer.equals("Codex") ? (slot.equals("09:05") ? "codex-srpp-09-30" : "codex-srpp")
                                    : (slot.equals("09:05") ? "srpp-0905-claude" : "srpp-1150-claude"));
                    String metadata = SrppJcs.canonicalize(JSON.createObjectNode().put("schemaVersion", 1).set("run", input.path("run")));
                    if (valid) {
                        var parsed = AiFlowProtocol.parse(input.toString());
                        assertThat(parsed.metadataJson()).isEqualTo(metadata);
                        assertThat(parsed.metadataHash()).isEqualTo(SrppJcs.sha256Hex(metadata));
                        assertThat(AiFlowProtocol.readRun(metadata)).isEqualTo(parsed.batch().run());
                    } else {
                        assertCode(input.toString(), "INVALID_REQUEST");
                        assertThatThrownBy(() -> AiFlowProtocol.readRun(metadata)).isInstanceOfSatisfying(AiFlowProblem.class,
                                problem -> assertThat(problem.code()).isEqualTo("UPSTREAM_INVALID"));
                    }
                }
            }
        }
    }
    @Test void usPlannedSlotAndReportDateDoNotDependOnActualStartClock() {
        for (String source : List.of("CODEX_ROLLOUT", "SCHEDULE_EVENTS")) {
            ObjectNode input = raw(source.equals("SCHEDULE_EVENTS") ? complete().subList(0, 5) : complete());
            String afterMidnight = DATE.plusDays(1) + "T01:05:00+08:00";
            ((ObjectNode) input.path("run")).put("reportSlot", "22:00").put("automationId", "22-00")
                    .put("sourceKind", source).put("startedAt", afterMidnight);
            var parsed = AiFlowProtocol.parse(input.toString());
            assertThat(parsed.batch().run().reportDate()).isEqualTo(DATE);
            assertThat(parsed.batch().run().reportSlot()).isEqualTo("22:00");
            assertThat(parsed.batch().run().startedAt()).isEqualTo(instant(afterMidnight));
            assertThat(parsed.metadataJson()).contains(afterMidnight);
        }
    }
    @Test void canonicalHttpPathsHonor500CharacterBoundaryWithoutChangingSnapshot() {
        for(String path:List.of("/api/"+"a".repeat(296),"/api/"+"a".repeat(495),"/api/widgets/{id}")) {
            ObjectNode input=raw(complete());ObjectNode operation=(ObjectNode)input.path("events").get(0).path("operation");
            operation.put("method","GET").put("path",path);
            var parsed=AiFlowProtocol.parse(input.toString());
            assertThat(parsed.batch().events().getFirst().operation().path()).isEqualTo(path);
            String expected=SrppJcs.canonicalize(input.path("events").get(0));
            assertThat(parsed.payloads().getFirst()).isEqualTo(expected);
            assertThat(parsed.hashes().getFirst()).isEqualTo(SrppJcs.sha256Hex(expected));
        }
        for(String path:List.of("/api/"+"a".repeat(496),"https://host/api/widgets","/api/widgets?q=1",
                "/api/widgets#fragment","/widgets/id","/api//widgets","/api/../widgets","/api/widgets..old")) {
            ObjectNode input=raw(complete());((ObjectNode)input.path("events").get(0).path("operation")).put("method","GET").put("path",path);
            assertCode(input.toString(),"INVALID_REQUEST");
        }
    }
    @Test void offsetsAndMinutePrecisionProduceSameInstantButKeepOriginalSnapshotLiterals() {
        ObjectNode original=raw(complete());((ObjectNode)original.path("run")).put("startedAt",AT);
        ((ObjectNode)original.path("events").get(0)).put("occurredAt",AT);
        var parsed=AiFlowProtocol.parse(original.toString());
        String metadata=SrppJcs.canonicalize(JSON.createObjectNode().put("schemaVersion",1).set("run",original.path("run")));
        String event=SrppJcs.canonicalize(original.path("events").get(0));
        assertThat(parsed.metadataJson()).isEqualTo(metadata);assertThat(parsed.metadataHash()).isEqualTo(SrppJcs.sha256Hex(metadata));
        assertThat(parsed.payloads().getFirst()).isEqualTo(event);assertThat(parsed.hashes().getFirst()).isEqualTo(SrppJcs.sha256Hex(event));
        assertThat(AiFlowProtocol.readRun(metadata).startedAt()).isEqualTo(instant(AT));
        assertThat(AiFlowProtocol.readEvent(event).occurredAt()).isEqualTo(instant(AT));
        assertThat(parsed.batch().run().reportDate()).isEqualTo(DATE);
        for(String time:List.of(instant(AT).toString(),OffsetDateTime.parse(AT).withOffsetSameInstant(ZoneOffset.ofHours(-5)).toString(),DATE+"T09:05+08:00")) {
            ObjectNode alternative=original.deepCopy();((ObjectNode)alternative.path("run")).put("startedAt",time);
            ((ObjectNode)alternative.path("events").get(0)).put("occurredAt",time);
            var equivalent=AiFlowProtocol.parse(alternative.toString());
            assertThat(equivalent.batch().run().startedAt()).isEqualTo(parsed.batch().run().startedAt());
            assertThat(equivalent.batch().events().getFirst().occurredAt()).isEqualTo(parsed.batch().events().getFirst().occurredAt());
            assertThat(equivalent.metadataJson()).contains(time).isNotEqualTo(metadata);
            assertThat(equivalent.payloads().getFirst()).contains(time).isNotEqualTo(event);
        }
        var replay=AiFlowProtocol.parse(original.toString());assertThat(replay.metadataJson()).isEqualTo(metadata);
        assertThat(replay.hashes()).isEqualTo(parsed.hashes());
    }
    @Test void canonicalReplayIgnoresObjectOrderAndIncludesOptionalNull() {
        ObjectNode raw = raw(complete()); ((ObjectNode)raw.path("events").get(0)).remove("reportedOutcome");
        var a = AiFlowProtocol.parse(raw.toString()); var b = AiFlowProtocol.parse(JSON.valueToTree(a.batch()).toString());
        assertThat(a.metadataJson()).isEqualTo(b.metadataJson()); assertThat(a.payloads()).isEqualTo(b.payloads());
        assertThat(a.batch().events().getFirst().reportedOutcome()).isNull();
    }
    @Test void rejectsDuplicateAtEveryDepthAndTrailingTokens() {
        String raw = raw(complete()).toString();
        for (String bad : List.of(raw.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
                raw.replace("\"method\":\"TOOL\"", "\"method\":\"TOOL\",\"method\":\"TOOL\""), raw + "{}"))
            assertCode(bad,"INVALID_REQUEST");
    }
    @Test void rejectsUnknownFieldsAtEveryReachableLevel() {
        for (String level : List.of("root","run","event","operation","evidence")) {
            ObjectNode r = raw(complete());
            ObjectNode target = switch(level) {case "run" -> (ObjectNode)r.path("run"); case "event" -> (ObjectNode)r.path("events").get(0);
                case "operation" -> (ObjectNode)r.path("events").get(0).path("operation"); case "evidence" -> (ObjectNode)r.path("events").get(0).path("evidenceRefs").get(0); default -> r;};
            target.put("ownerUserId",8); assertCode(r.toString(),"INVALID_REQUEST");
        }
    }
    @Test void strictClosedNumbersDatesIdsPathsConsumersAndSourceRoles() {
        for (String field : List.of("sequence","attemptNo")) { ObjectNode r = raw(complete()); ((ObjectNode)r.path("events").get(0)).put(field,1.0); assertCode(r.toString(),"INVALID_REQUEST"); }
        for (String date : List.of("2026-02-30","2026-1-01","not-date")) { ObjectNode r = raw(complete()); ((ObjectNode)r.path("run")).put("reportDate",date); assertCode(r.toString(),"INVALID_REQUEST"); }
        for (String path : List.of("https://host/api/foo","/api/foo?token=x","/api/foo#x","/api/foo;id=x","/api/../foo")) {
            ObjectNode r = raw(complete()); ObjectNode op=(ObjectNode)r.path("events").get(0).path("operation");op.put("method","GET").put("path",path);assertCode(r.toString(),"INVALID_REQUEST");
        }
        ObjectNode r = raw(complete()); ((ObjectNode)r.path("run")).put("consumer","Claude");assertCode(r.toString(),"INVALID_REQUEST");
        r=raw(complete());((ObjectNode)r.path("events").get(0)).put("occurredAt",DATE+"T09:00:00");assertCode(r.toString(),"INVALID_REQUEST");
        r=raw(complete());((ObjectNode)r.path("events").get(0).path("evidenceRefs").get(0)).put("id","https://host/a");assertCode(r.toString(),"INVALID_REQUEST");
    }
    @Test void onlyAllowlistedSummariesPersistAndNoHtmlSecretsCommandsOrAccounts() {
        for (String secret : List.of("authorization: Bearer abc","curl https://host","token=abc","帳號 user@example.com","<img src=x onerror=1>","USD 10000","持股 1000","price: 12.34")) {
            ObjectNode r = raw(complete()); ((ObjectNode)r.path("events").get(0)).put("summary",secret);
            var parsed = AiFlowProtocol.parse(r.toString());assertThat(parsed.batch().events().getFirst().summary()).isEqualTo("[已省略敏感內容]");assertThat(parsed.payloads().getFirst()).doesNotContain(secret);
        }
    }
    @Test void enforcesBodyAndBatchAndOpaqueLimits() {
        assertCode(" ".repeat(256*1024)+"{}","PAYLOAD_TOO_LARGE");
        ObjectNode r=raw(List.of());assertCode(r.toString(),"INVALID_REQUEST");
        r=raw(complete());((ObjectNode)r.path("run")).put("sourceRunId","x".repeat(201));assertCode(r.toString(),"INVALID_REQUEST");
        r=raw(List.of(complete().getFirst(),complete().getFirst()));assertCode(r.toString(),"INVALID_REQUEST");
    }
    @Test void coverageEvidenceCannotMasqueradeAsFinancialValidation() {
        for (String id : List.of("TRACE_V1:2:1","TRACE_V1:-1:10","TRACE_V1:100:1","TRACE_V1:1:10:extra")) {
            ObjectNode r=raw(complete());((ObjectNode)r.path("events").get(5).path("evidenceRefs").get(0)).put("id",id);assertCode(r.toString(),"INVALID_GRAPH");
        }
        ObjectNode r=raw(complete());((ObjectNode)r.path("events").get(5)).put("occurredAt",AT);assertCode(r.toString(),"INVALID_GRAPH");
    }
    static void assertCode(String raw,String code){assertThatThrownBy(()->AiFlowProtocol.parse(raw)).isInstanceOfSatisfying(AiFlowProblem.class,p->assertThat(p.code()).isEqualTo(code));}
}
