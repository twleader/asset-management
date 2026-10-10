package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class SrppDailyDecisionCaptureTest {
    static ObjectNode fixture()throws Exception{return (ObjectNode)new ObjectMapper().readTree(SrppDailyDecisionCaptureTest.class.getResourceAsStream("/srpp/daily-decision-positive-v1.json"));}
    static JsonNode request(ObjectNode n){ObjectNode r=JsonNodeFactory.instance.objectNode();for(String f:new String[]{"tradingDate","slot","policyBundleSha256","swaggerSha256"})r.set(f,n.path("identity").get(f));return r;}
    static void sign(ObjectNode n){ObjectNode content=n.deepCopy();content.remove("created");content.remove("decisionContentSha256");n.put("decisionContentSha256",SrppJcs.sha256Hex(SrppJcs.canonicalize(content)));}
    static void validate(ObjectNode n){SrppDailyDecisionCapture.validate(201,MediaType.APPLICATION_JSON,n.toString().getBytes(StandardCharsets.UTF_8),request(n),7);}
    @Test void strictRequestRejectsInjectedFactsDuplicateAndInvalidOwnerBeforeOutbound(){for(String s:new String[]{"[]","{\"slot\":\"09:05\",\"slot\":\"11:40\"}","{\"ownerEmail\":null}"})assertThatThrownBy(()->SrppDailyDecisionCapture.parseRequest(s.getBytes(StandardCharsets.UTF_8))).isInstanceOf(SrppCaptureProblemException.class);}
    @Test void realPositiveFrozenBackendReceiptIsAccepted()throws Exception{validate(fixture());}
    @Test void unknownRootOrInputTamperingEvenWithRehashedContentIsRejected()throws Exception{ObjectNode n=fixture();n.put("unexpected",true);sign(n);assertThatThrownBy(()->validate(n)).isInstanceOf(RuntimeException.class);ObjectNode changed=fixture();((ObjectNode)changed.get("inputSnapshot")).put("inputBundleJcs","{}");sign(changed);assertThatThrownBy(()->validate(changed)).isInstanceOf(RuntimeException.class);}
    @Test void missingRequiredGateCannotMasqueradeAsActionable()throws Exception{ObjectNode n=fixture();ArrayNode rs=(ArrayNode)n.path("decision").path("candidates").get(0).get("receipts");for(int i=rs.size()-1;i>=0;i--)if(rs.get(i).path("ruleId").asText().equals("PENDING"))rs.remove(i);sign(n);assertThatThrownBy(()->validate(n)).isInstanceOf(RuntimeException.class);}
    @Test void hardUnavailableRequiresBlockingReasonAndCannotRemainActionable()throws Exception{ObjectNode n=fixture();for(JsonNode r:n.path("decision").path("candidates").get(0).get("receipts"))if(r.path("ruleId").asText().equals("PENDING")){((ObjectNode)r).put("status","UNAVAILABLE");((ObjectNode)r).put("reason","PENDING_ORDERS_UNAVAILABLE");}sign(n);assertThatThrownBy(()->validate(n)).isInstanceOf(RuntimeException.class);}
    @Test void passGateBoundToUnavailableSourceIsRejected()throws Exception{ObjectNode n=fixture();((ObjectNode)n.path("inputSnapshot").path("sourceVector").get("pending:00865B")).put("validation","UNAVAILABLE");sign(n);assertThatThrownBy(()->validate(n)).isInstanceOf(RuntimeException.class);}
    @Test void badCandidateAmountAndAuthorityVersionCannotPass()throws Exception{ObjectNode n=fixture();((ObjectNode)n.path("decision").path("candidates").get(0)).put("amountTwd","1");sign(n);assertThatThrownBy(()->validate(n)).isInstanceOf(RuntimeException.class);ObjectNode v=fixture();((ObjectNode)v.get("authorityRevision")).put("calculatorVersion","DIAGNOSTIC_ONLY");sign(v);assertThatThrownBy(()->validate(v)).isInstanceOf(RuntimeException.class);}
    @Test void substitutedPendingReferenceAndMissingPositiveCapacityAreRejected()throws Exception{ObjectNode n=fixture();for(JsonNode receipt:n.path("decision").path("candidates").get(0).get("receipts"))if(receipt.path("ruleId").asText().equals("PENDING"))((ObjectNode)receipt).putArray("inputRefs").add("assets");sign(n);assertThatThrownBy(()->validate(n)).isInstanceOf(RuntimeException.class);ObjectNode omitted=fixture();ArrayNode receipts=(ArrayNode)omitted.path("decision").path("candidates").get(0).get("receipts");for(int i=receipts.size()-1;i>=0;i--)if(receipts.get(i).path("ruleId").asText().equals("D-130_CAPACITY"))receipts.remove(i);sign(omitted);assertThatThrownBy(()->validate(omitted)).isInstanceOf(RuntimeException.class);}

}
