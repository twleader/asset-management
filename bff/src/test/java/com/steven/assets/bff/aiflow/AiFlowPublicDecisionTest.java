package com.steven.assets.bff.aiflow;
import com.fasterxml.jackson.databind.node.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.steven.assets.bff.aiflow.AiFlowFixtures.*;
class AiFlowPublicDecisionTest {
    static ObjectNode rawDecision() throws Exception {
        ObjectNode raw=(ObjectNode)AiFlowProtocol.JSON.valueToTree(batch());var event=(ObjectNode)raw.at("/events/0");
        event.put("eventKind","OBSERVATION");event.put("status","UNKNOWN");
        event.set("publicDecision",AiFlowProtocol.JSON.readTree(DECISION));return raw;
    }
    static Object parse(ObjectNode raw) {return AiFlowProtocol.batch(bytes(raw),CLOCK);}
    @Test void deepNullUnknownAndDuplicateKeysFailClosed() throws Exception {
        var raw=rawDecision();((ObjectNode)raw.at("/events/0/publicDecision/options/0")).putNull("reason");
        assertThatThrownBy(()->parse(raw)).isInstanceOf(AiFlowProblem.class);
        byte[] duplicate=rawDecision().toString().replace("\"decisionId\":\"read-source\"","\"decisionId\":\"read-source\",\"decisionId\":\"other\"").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(()->AiFlowProtocol.batch(duplicate,CLOCK)).isInstanceOf(AiFlowProblem.class);
    }

    @Test void sharedCollectorFinalWireVectorsMatchExactly() throws Exception {
        var fixture=AiFlowProtocol.JSON.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("../scripts/tests/fixtures/ai_flow_public_decision_vectors.json")));
        for(var vector:fixture.get("cases")) {
            var raw=rawDecision();var event=(ObjectNode)raw.at("/events/0");
            event.set("publicDecision",vector.get("publicDecision"));event.put("eventKind",vector.get("eventKind").asText());event.put("status",vector.get("status").asText());
            if(vector.get("valid").asBoolean()) assertThat(parse(raw)).as(vector.get("id").asText()).isNotNull();
            else assertThatThrownBy(()->parse(raw)).as(vector.get("id").asText()).isInstanceOf(RuntimeException.class);
        }
    }
    static final String DECISION = """
        {"source":"LLM_PUBLIC_SUMMARY_V1","decisionId":"read-source","topic":"資料來源選擇",
         "selectedOptionId":"api","options":[
          {"id":"api","label":"系統資料","action":"採用","reason":"已驗證數值 amount: 12000 USD",
           "evidenceRefs":[{"role":"API_RECEIPT","id":"receipt-one","hash":null}],
           "toolCall":{"name":"exec","innerNames":[],"parameters":"date","parametersChars":4,"truncated":false,"redactedCount":0}},
          {"id":"wait","label":"等待","action":"等待補齊","reason":"資料暫缺則等待","evidenceRefs":[],"toolCall":null}]}
        """;
    @Test void keepsAllOptionsAndOnlySafeSummary() throws Exception {
        var raw=rawDecision(); var accepted=parse(raw);
        assertThat(accepted).isNotNull();
        ((ObjectNode)raw.at("/events/0/publicDecision")).putNull("selectedOptionId");
        assertThat(parse(raw)).isNotNull();
    }
    @Test void rejectsUnknownDeepFieldsNullDuplicateIdsInvalidSelectionPrivateMarkersAndOverflow() throws Exception {
        for(String mutation:List.of("unknown","null","duplicate","selected","private","reason","count","inner","params")) {
            var raw=rawDecision();var d=(ObjectNode)raw.at("/events/0/publicDecision");var options=(ArrayNode)d.get("options");var first=(ObjectNode)options.get(0);
            switch(mutation) {
                case "unknown" -> ((ObjectNode)first.get("toolCall")).put("extra",true);
                case "null" -> ((ObjectNode)raw.at("/events/0")).putNull("publicDecision");
                case "duplicate" -> ((ObjectNode)options.get(1)).put("id","api");
                case "selected" -> d.put("selectedOptionId","missing");
                case "private" -> first.put("reason","<analysis>private</analysis>");
                case "reason" -> first.put("reason","x".repeat(501));
                case "count" -> { while(options.size()<33) {var copy=first.deepCopy();copy.put("id","id"+options.size());options.add(copy);} }
                case "inner" -> ((ArrayNode)first.at("/toolCall/innerNames")).add("exec_command");
                case "params" -> {for(int i=0;i<3;i++){var copy=first.deepCopy();copy.put("id","full"+i);var call=(ObjectNode)copy.get("toolCall");call.put("parameters","x".repeat(16384));call.put("parametersChars",16384);options.add(copy);}}
            }
            assertThatThrownBy(()->parse(raw)).as(mutation).isInstanceOf(RuntimeException.class);
        }
    }
    @Test void accepts32OptionsAndAstralReasonsByCodePoints() throws Exception {
        var raw=rawDecision();var options=(ArrayNode)raw.at("/events/0/publicDecision/options");var first=(ObjectNode)options.get(0);
        first.put("reason","😀".repeat(500));
        while(options.size()<32) {var copy=first.deepCopy();copy.put("id","id"+options.size());options.add(copy);}
        assertThat(parse(raw)).isNotNull();
    }
}
