package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.service.srpp.decision.DecisionFacts;
import com.steven.assets.srpp.SrppJcs;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

/** Offline diagnostic only: original evidence unchanged, current policy input explicitly overlaid. */
@EnabledIfEnvironmentVariable(named="SRPP_D236_RETAINED_REPLAY_INDEX",matches=".+")
class SrppD236RetainedReplayTest {
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    @Test void currentGenericJavaPolicyDiagnosesOriginalRetainedEvidenceWithoutMovingItsEffectiveDate()throws Exception{
        Path indexPath=Path.of(System.getenv("SRPP_D236_RETAINED_REPLAY_INDEX"));byte[] indexBytes=Files.readAllBytes(indexPath);JsonNode index=SrppReportFixtures.MAPPER.readTree(indexBytes);
        var authority=SrppReportFixtures.AUTHORITY;JsonNode model=authority.model();String effective=model.at("/execution_policy/zero_target_high_zone_exit_d236/effective_date").asText();assertThat(effective).isEqualTo("2026-11-26");
        ObjectNode result=JsonNodeFactory.instance.objectNode().put("decision","D-236").put("status","OFFLINE_JAVA_DIAGNOSTIC_ONLY").put("indexSha256",sha(indexBytes)).put("effectiveDate",effective).put("policyBundleSha256",authority.decision().policy().bundleHash()).put("marketScope","ORIGINAL_TAIWAN_REPORT_PACKAGES_ONLY; other-market historical trading evidence is not synthesized").put("policyOnlyOverlay",true).put("dateQuoteAndOwnerSourcesOverlay",false).put("formalReportAdoption","NOT_ESTABLISHED").put("tradeAuthorization",false).put("placesOrders",false);
        Map<Path,String> originals=new LinkedHashMap<>();for(String section:List.of("radar_replays","retained_package_audit"))for(JsonNode item:index.path(section)){Path p=Path.of(item.path("source_path").asText());String hash=sha(Files.readAllBytes(p));assertThat(hash).isEqualTo(item.path("source_sha256").asText());originals.put(p,hash);}
        ArrayNode rows=result.putArray("packages");int calculated=0,errors=0;
        for(JsonNode item:index.path("retained_package_audit")){
            Path path=Path.of(item.path("source_path").asText());byte[] original=Files.readAllBytes(path);JsonNode envelope=SrppReportFixtures.MAPPER.readTree(original);ObjectNode row=rows.addObject().put("originalPath",path.toString()).put("originalSha256",sha(original));
            if(!envelope.at("/inputSnapshot/inputBundleJcs").isTextual()){row.put("status","ORIGINAL_ERROR_UNAVAILABLE");row.set("originalError",envelope);errors++;continue;}
            JsonNode snapshot=envelope.path("inputSnapshot");String raw=snapshot.path("inputBundleJcs").asText();ObjectNode originalInput=(ObjectNode)SrppJcs.parseStrict(raw);assertThat(SrppJcs.sha256Hex(raw)).isEqualTo(snapshot.path("inputSnapshotSha256").asText());
            ObjectNode originalVector=(ObjectNode)snapshot.path("sourceVector");String inputHash=SrppJcs.hash(originalInput),vectorHash=SrppJcs.hash(originalVector);DecisionFacts.Input facts=SrppReportFixtures.MAPPER.treeToValue(originalInput.path("facts"),DecisionFacts.Input.class);assertThat(facts.date()).isBefore(java.time.LocalDate.parse(effective));
            ObjectNode sources=originalInput.withObject("sources").deepCopy(),vector=originalVector.deepCopy();var p=authority.decision().policy();ObjectNode policy=JsonNodeFactory.instance.objectNode().put("rawModelBytes",authority.decision().modelBytes()).put("rawManifestBytes",authority.decision().manifestBytes()).put("bundleHash",p.bundleHash()).put("modelInputsSha256",p.modelHash()).put("policyManifestSha256",p.manifestHash());sources.set("policy",policy);String policyHash=SrppJcs.hash(policy);vector.putObject("policy").put("sourceId","policy").put("revision",policyHash).put("sha256",policyHash).put("coverage","COMPLETE").put("validation","VERIFIED").put("capturedAt",facts.capturedAt().toString());
            var context=new SrppZeroTargetExitContext(model,sources,vector,originalInput.path("ownerUserId").asLong(),facts.date(),facts.capturedAt(),"台股",facts.slot(),p.bundleHash());ObjectNode output=SrppZeroTargetHighZoneExitCalculator.calculate(context);assertThat(output.path("legs").size()).isPositive();
            for(JsonNode leg:output.path("legs")){assertThat(leg.path("strategy").asText()).isEqualTo("ZERO_TARGET_HIGH_ZONE_EXIT_D236");assertThat(leg.path("units").asText()).isEqualTo("0");assertThat(leg.path("status").asText()).isEqualTo("BLOCKED");assertThat(leg.path("blockingReasons").toString()).contains("POLICY_NOT_EFFECTIVE");}
            row.set("originalIdentity",envelope.path("identity"));row.put("originalInputSnapshotSha256",snapshot.path("inputSnapshotSha256").asText()).put("originalVectorSha256",vectorHash).put("originalCapturedAt",facts.capturedAt().toString()).put("originalTradingDate",facts.date().toString()).put("originalGenericOwnerStatePresent",originalInput.path("sources").has("zero-target-exit-state")).put("overlayScope","CURRENT_POLICY_INPUT_ONLY; effective date remains 2026-11-26; no current source becomes historical evidence");row.set("actualGenericJavaOutput",output);
            assertThat(SrppJcs.hash(originalInput)).isEqualTo(inputHash);assertThat(SrppJcs.hash(originalVector)).isEqualTo(vectorHash);for(var entry:originalInput.path("sources").properties())if(!entry.getKey().equals("policy"))assertThat(sources.path(entry.getKey())).isEqualTo(entry.getValue());calculated++;
        }
        assertThat(calculated).isEqualTo(2);assertThat(errors).isEqualTo(1);assertThat(originals).hasSize(13);for(var entry:originals.entrySet())assertThat(sha(Files.readAllBytes(entry.getKey()))).isEqualTo(entry.getValue());result.put("originalFileCount",13).put("originalFilesUnchanged",true).put("calculatedOriginalPackages",calculated).put("originalErrorPackages",errors);Path destination=Path.of(System.getenv("SRPP_D236_RETAINED_REPLAY_OUTPUT"));Files.writeString(destination,SrppReportFixtures.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result)+"\n");
    }
}
