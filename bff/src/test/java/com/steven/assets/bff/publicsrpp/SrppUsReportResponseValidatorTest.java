package com.steven.assets.bff.publicsrpp;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import static org.assertj.core.api.Assertions.*;
class SrppUsReportResponseValidatorTest {
 private static final ObjectMapper M=new ObjectMapper();
 private static ObjectNode fixture() throws Exception {try(var in=SrppUsReportResponseValidatorTest.class.getResourceAsStream("/srpp/us-report-package.json")){return (ObjectNode)M.readTree(in);}}
 private static SrppUsReportQuery q(){return new SrppUsReportQuery(null,"Codex","US","2026-11-03","US_OPEN_0945","a".repeat(64),"b".repeat(64),null);}
 private static byte[] bytes(ObjectNode n) throws Exception{return M.writeValueAsBytes(n);}
 private static void seal(ObjectNode n){n.remove("contentSha256");n.put("contentSha256",SrppJcs.sha256Hex(SrppJcs.canonicalize(n)));}
 @Test void acceptsFrozenUnavailableScopeWithoutMakingItComplete() throws Exception{SrppUsReportResponseValidator.validate(q(),7L,MediaType.APPLICATION_JSON,bytes(fixture()));}
 @Test void rejectsCrossOwnerConsumerMarketSessionPolicySwaggerAndRun() throws Exception{for(String key:java.util.List.of("consumer","market","sessionDate","reportSlot","policyBundleSha256","swaggerSha256")){var n=fixture();((ObjectNode)n.get("identity")).put(key,"wrong");seal(n);assertThatThrownBy(()->SrppUsReportResponseValidator.validate(q(),7L,MediaType.APPLICATION_JSON,bytes(n))).isInstanceOf(RuntimeException.class);}assertThatThrownBy(()->SrppUsReportResponseValidator.validate(q(),8L,MediaType.APPLICATION_JSON,bytes(fixture()))).isInstanceOf(RuntimeException.class);}
 @Test void rejectsContentAndSourceHashMutationAndFlagOnlyCompletion() throws Exception{var n=fixture();n.put("contentSha256","0".repeat(64));assertThatThrownBy(()->SrppUsReportResponseValidator.validate(q(),7L,MediaType.APPLICATION_JSON,bytes(n))).isInstanceOf(RuntimeException.class);var changed=fixture();((ObjectNode)changed.get("sourceVector").get("assets")).put("sha256","0".repeat(64));seal(changed);assertThatThrownBy(()->SrppUsReportResponseValidator.validate(q(),7L,MediaType.APPLICATION_JSON,bytes(changed))).isInstanceOf(RuntimeException.class);var flag=fixture();((ObjectNode)flag.get("modules").get(0)).put("coverage","COMPLETE");seal(flag);assertThatThrownBy(()->SrppUsReportResponseValidator.validate(q(),7L,MediaType.APPLICATION_JSON,bytes(flag))).isInstanceOf(RuntimeException.class);}
 @Test void rejectsUnknownFieldsAndWrongContentType() throws Exception{var n=fixture();n.put("extra",true);seal(n);assertThatThrownBy(()->SrppUsReportResponseValidator.validate(q(),7L,MediaType.APPLICATION_JSON,bytes(n))).isInstanceOf(RuntimeException.class);assertThatThrownBy(()->SrppUsReportResponseValidator.validate(q(),7L,MediaType.TEXT_HTML,bytes(fixture()))).isInstanceOf(RuntimeException.class);}
 @Test void generatedPublisherEnvelopeCrossesTheIndependentBffBoundary() throws Exception{
  String path=System.getProperty("us.fixture");org.junit.jupiter.api.Assumptions.assumeTrue(path!=null,"publisher integration fixture supplied by isolated PG test");
  byte[] raw=java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path));var r=M.readTree(raw);var i=r.get("identity");var query=new SrppUsReportQuery(null,i.path("consumer").asText(),"US",i.path("sessionDate").asText(),"US_OPEN_0945",i.path("policyBundleSha256").asText(),i.path("swaggerSha256").asText(),r.path("calculationRunId").asText());
  SrppUsReportResponseValidator.validate(query,i.path("ownerUserId").asLong(),MediaType.APPLICATION_JSON,raw);
 }
}
