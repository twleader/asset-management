package com.steven.assets.bff.aiflow;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import static org.assertj.core.api.Assertions.*;

class AiFlowOperationContractTest {
    private static final AiFlowProtocol.Query QUERY=new AiFlowProtocol.Query(AiFlowFixtures.DATE,"Codex","ALL");

    @Test void canonical_http_templates_and_301_or_500_character_paths_are_valid_for_ingress_and_projection() {
        for(String method:List.of("GET","POST","PUT","PATCH","DELETE")) {
            for(String path:List.of("/api/assets/{id}","/api/public/srpp/report-calculations","/api/v1.0/asset_name~sample","/api/"+"a".repeat(296),"/api/"+"a".repeat(495))) {
                var operation=new AiFlowDtos.Operation(method,path);
                var batch=batch(operation);
                assertThat(AiFlowProtocol.batch(AiFlowFixtures.bytes(batch),AiFlowFixtures.CLOCK).events().getFirst().operation()).isEqualTo(operation);
                var detail=projection(operation,operation);
                assertThatCode(()->AiFlowProtocol.detail(detail,QUERY,7,AiFlowFixtures.ID,AiFlowFixtures.CLOCK)).doesNotThrowAnyException();
            }
        }
    }

    @Test void invalid_http_paths_are_rejected_in_ingress_nodes_and_slow_steps() {
        var valid=new AiFlowDtos.Operation("GET","/api/assets/{id}");
        for(String path:List.of("/api/"+"a".repeat(496),"","/api","/assets/latest","https://example.invalid/api/assets","/api/assets?token=private","/api/assets#section","/api//assets","/api/assets/../latest","/api/a..b","/api/assets/")) {
            var invalid=new AiFlowDtos.Operation("GET",path);
            assertThatThrownBy(()->AiFlowProtocol.batch(AiFlowFixtures.bytes(batch(invalid)),AiFlowFixtures.CLOCK))
                .as(path).extracting(e->((AiFlowProblem)e).code).isEqualTo("INVALID_REQUEST");
            assertThatThrownBy(()->AiFlowProtocol.detail(projection(invalid,valid),QUERY,7,AiFlowFixtures.ID,AiFlowFixtures.CLOCK)).as("node "+path).isInstanceOf(AiFlowProblem.class);
            assertThatThrownBy(()->AiFlowProtocol.detail(projection(valid,invalid),QUERY,7,AiFlowFixtures.ID,AiFlowFixtures.CLOCK)).as("slow step "+path).isInstanceOf(AiFlowProblem.class);
        }
    }

    @Test void local_and_tool_keep_the_existing_six_classifications() {
        for(String method:List.of("LOCAL","TOOL")) {
            for(String path:List.of("calendar","policy","validation","render","artifact","tool")) {
                var operation=new AiFlowDtos.Operation(method,path);
                assertThatCode(()->AiFlowProtocol.batch(AiFlowFixtures.bytes(batch(operation)),AiFlowFixtures.CLOCK)).doesNotThrowAnyException();
                assertThatCode(()->AiFlowProtocol.detail(projection(operation,operation),QUERY,7,AiFlowFixtures.ID,AiFlowFixtures.CLOCK)).doesNotThrowAnyException();
            }
            assertThatThrownBy(()->AiFlowProtocol.batch(AiFlowFixtures.bytes(batch(new AiFlowDtos.Operation(method,"/api/assets"))),AiFlowFixtures.CLOCK)).isInstanceOf(AiFlowProblem.class);
        }
    }

    @Test void packaged_observation_schema_length_matches_the_http_boundary_and_golden_template() throws Exception {
        try(InputStream source=getClass().getResourceAsStream("/ai-flow-observation-api.yaml")) {
            assertThat(source).isNotNull();
            Map<?,?> document=new Yaml().load(source);
            Map<?,?> components=(Map<?,?>)document.get("components");
            Map<?,?> schemas=(Map<?,?>)components.get("schemas");
            Map<?,?> operation=(Map<?,?>)schemas.get("AiFlowOperation");
            Map<?,?> properties=(Map<?,?>)operation.get("properties");
            Map<?,?> path=(Map<?,?>)properties.get("path");
            assertThat(path.get("type")).isEqualTo("string");
            assertThat(path.get("maxLength")).isEqualTo(500);
            int maximum=((Number)path.get("maxLength")).intValue();
            assertThatCode(()->AiFlowProtocol.batch(AiFlowFixtures.bytes(batch(new AiFlowDtos.Operation("GET","/api/"+"a".repeat(maximum-5)))),AiFlowFixtures.CLOCK)).doesNotThrowAnyException();
            assertThatThrownBy(()->AiFlowProtocol.batch(AiFlowFixtures.bytes(batch(new AiFlowDtos.Operation("GET","/api/"+"a".repeat(maximum-4)))),AiFlowFixtures.CLOCK)).isInstanceOf(AiFlowProblem.class);
            assertThatCode(()->AiFlowProtocol.batch(AiFlowFixtures.bytes(batch(new AiFlowDtos.Operation("GET","/api/assets/{id}"))),AiFlowFixtures.CLOCK)).doesNotThrowAnyException();
        }
    }

    @Test void toolCallIngressSchemaRemainsOptionalClosedAndMatchesTheTypedValidator() throws Exception {
        try (InputStream source=getClass().getResourceAsStream("/ai-flow-observation-api.yaml")) {
            assertThat(source).isNotNull();
            Map<?,?> document=new Yaml().load(source);
            Map<?,?> schemas=(Map<?,?>)((Map<?,?>)document.get("components")).get("schemas");
            Map<?,?> event=(Map<?,?>)schemas.get("AiFlowEvent");
            assertThat(((List<?>)event.get("required")).stream().map(Object::toString).toList()).doesNotContain("toolCall");
            Map<?,?> eventProperties=(Map<?,?>)event.get("properties");
            assertThat(((Map<?,?>)eventProperties.get("toolCall")).get("$ref")).isEqualTo("#/components/schemas/AiFlowToolCall");
            Map<?,?> toolCall=(Map<?,?>)schemas.get("AiFlowToolCall");
            assertThat(toolCall.get("additionalProperties")).isEqualTo(false);
            assertThat(((List<?>)toolCall.get("required")).stream().map(Object::toString).toList()).containsExactly("name","innerNames","parameters","parametersChars","truncated","redactedCount");
            Map<?,?> fields=(Map<?,?>)toolCall.get("properties");
            assertThat(((Map<?,?>)fields.get("parameters")).get("maxLength")).isEqualTo(16384);
            assertThat(((Map<?,?>)fields.get("sourceTruncated")).get("const")).isEqualTo(true);
        }
    }

    private static AiFlowDtos.Batch batch(AiFlowDtos.Operation operation) {
        var original=AiFlowFixtures.batch();var e=original.events().getFirst();
        var event=new AiFlowDtos.Event(e.eventId(),e.sequence(),e.stepId(),e.eventKind(),e.status(),e.occurredAt(),e.title(),e.summary(),operation,e.reasonCode(),e.attemptNo(),e.parentEventIds(),e.relation(),e.evidenceRefs(),e.reportedOutcome(),e.toolCall());
        return new AiFlowDtos.Batch(original.schemaVersion(),original.run(),List.of(event));
    }

    static AiFlowDtos.Detail projection(AiFlowDtos.Operation nodeOperation,AiFlowDtos.Operation slowOperation) {
        var e=AiFlowFixtures.batch().events().getFirst();
        var node=new AiFlowDtos.Node(e.eventId(),e.sequence(),e.stepId(),e.eventKind(),e.status(),e.occurredAt(),e.title(),e.summary(),nodeOperation,e.reasonCode(),e.attemptNo(),e.parentEventIds(),e.relation(),e.evidenceRefs(),e.reportedOutcome(),"執行中","觀測步驟","API 呼叫","09:05:00",2000L,0,0,"#123456",null);
        var slow=new AiFlowDtos.SlowStep(e.stepId(),e.eventId(),e.title(),slowOperation,AiFlowFixtures.START,AiFlowFixtures.START.plusSeconds(2),2000,"SUCCEEDED","成功");
        return new AiFlowDtos.Detail(AiFlowFixtures.run(7),List.of(node),List.of(),List.of(slow));
    }
}
