package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.service.srpp.decision.DecisionFacts;
import com.steven.assets.service.srpp.decision.DecisionInputCapturePort;
import com.steven.assets.srpp.SrppJcs;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static com.steven.assets.service.srpp.report.SrppReportFixtures.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in private retained-source diagnostic; no network, production state, or invented owner evidence. */
@EnabledIfEnvironmentVariable(named="SRPP_D235_RETAINED_REPLAY_INDEX", matches=".+")
class SrppD235RetainedReplayTest {
    private static final String STRATEGY="NAMED_HIGH_ZONE_EXIT_D235";
    private static final List<String> MISSING_OWNER_FIELDS=List.of("rotationExit","sellablePositions","rotationCooldown");

    @Test void originalFrozenSourcesRemainBlockedUnderPublishedAndExplicitCounterfactualPolicies() throws Exception {
        Path indexPath=Path.of(System.getenv("SRPP_D235_RETAINED_REPLAY_INDEX"));
        byte[] indexBytes=Files.readAllBytes(indexPath);
        JsonNode index=SrppJcs.parseStrict(new String(indexBytes,java.nio.charset.StandardCharsets.UTF_8));
        Path modelPath=Path.of(System.getenv("SRPP_D235_MODEL_FILE"));
        byte[] modelBytes=Files.readAllBytes(modelPath);
        ObjectNode publishedModel=(ObjectNode)SrppJcs.parseStrict(new String(modelBytes,java.nio.charset.StandardCharsets.UTF_8));
        assertThat(publishedModel.path("execution_policy").path("named_high_zone_exit_d235").path("decision").asText()).isEqualTo("D-235");
        ObjectNode result=MAPPER.createObjectNode().put("schemaVersion",1).put("status","DIAGNOSTIC_REPLAY_ONLY")
                .put("indexSha256",sha(indexBytes)).put("currentModelFileSha256",sha(modelBytes))
                .put("currentPolicyDecision",publishedModel.path("decision").asText())
                .put("frozenDatesAndSourcesUnchanged",true).put("tradeAuthorization",false).put("placesOrders",false)
                .put("formalReportAdoption","NOT_ESTABLISHED")
                .put("scope","Actual pure Java calculator on original frozen packages; current model supplied explicitly as a hypothetical decision policy. Original source-bound typedPolicy retained for owner and quote limits. Effective-date-only overlays are test copies, never historical authorization.");
        ArrayNode rawRadarRows=result.putArray("standaloneDirectRadarThresholds");
        for(JsonNode item:index.path("radar_replays")) {
            Path path=Path.of(item.path("source_path").asText());byte[] bytes=Files.readAllBytes(path);
            assertThat(sha(bytes)).isEqualTo(item.path("source_sha256").asText());
            JsonNode radar=SrppJcs.parseStrict(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)),stock=radar.path("stock");
            boolean adjusted=stock.path("distributionAdjusted").isBoolean()&&stock.path("distributionAdjusted").asBoolean();
            BigDecimal position=new BigDecimal(stock.path("week52Position").asText());
            boolean high=adjusted&&position.compareTo(new BigDecimal("0.95"))>=0&&position.compareTo(BigDecimal.ONE)<=0;
            rawRadarRows.addObject().put("sourcePath",path.toString()).put("sourceSha256",sha(bytes))
                    .put("generatedAt",radar.path("generatedAt").asText()).put("action",stock.path("action").asText())
                    .put("week52Position",position.toPlainString()).put("distributionAdjusted",adjusted)
                    .put("thresholdMet",high).put("scope","STANDALONE_THRESHOLD_ONLY_NO_EXECUTABLE_QUANTITY")
                    .put("mergedIntoFrozenPackage",false);
        }
        ArrayNode packages=result.putArray("packages");
        int replayed=0,errors=0;
        for(JsonNode item:index.path("retained_package_audit")) {
            Path path=Path.of(item.path("source_path").asText());byte[] bytes=Files.readAllBytes(path);
            assertThat(sha(bytes)).isEqualTo(item.path("source_sha256").asText());
            JsonNode envelope=SrppJcs.parseStrict(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
            ObjectNode row=packages.addObject().put("sourcePath",path.toString()).put("sourceSha256",sha(bytes));
            if(!envelope.path("inputSnapshot").path("inputBundleJcs").isTextual()) {
                row.put("status","UNAVAILABLE").put("reason","RETAINED_RESPONSE_IS_ERROR_NOT_CALCULATION_PACKAGE");
                row.set("originalError",envelope);errors++;continue;
            }
            JsonNode snapshot=envelope.path("inputSnapshot");
            String rawBundle=snapshot.path("inputBundleJcs").asText();
            ObjectNode input=(ObjectNode)SrppJcs.parseStrict(rawBundle);
            assertThat(SrppJcs.canonicalize(input)).isEqualTo(rawBundle);
            assertThat(SrppJcs.sha256Hex(rawBundle)).isEqualTo(snapshot.path("inputSnapshotSha256").asText());
            ObjectNode vector=(ObjectNode)snapshot.path("sourceVector");
            DecisionFacts.Input facts=MAPPER.treeToValue(input.path("facts"),DecisionFacts.Input.class);
            DecisionFacts.Policy originalPolicy=MAPPER.treeToValue(input.path("sources").path("policy").path("typedPolicy"),DecisionFacts.Policy.class);
            assertThat(facts.date().toString()).isEqualTo(input.path("tradingDate").asText());
            assertThat(originalPolicy.bundleHash()).isEqualTo(envelope.path("identity").path("policyBundleSha256").asText());
            var frame=new DecisionInputCapturePort.Frozen(facts,input,vector,snapshot.path("assetSnapshotId").asLong(),snapshot.path("assetGeneratedAt").asText());
            String originalInputHash=SrppJcs.hash(frame.input()),originalVectorHash=SrppJcs.hash(frame.sourceVector());
            row.set("originalIdentity",envelope.path("identity"));
            row.put("originalInputSnapshotSha256",snapshot.path("inputSnapshotSha256").asText())
                    .put("originalCapturedAt",facts.capturedAt().toString()).put("originalVectorSha256",originalVectorHash)
                    .put("originalTypedPolicySha256",SrppJcs.hash(input.path("sources").path("policy").path("typedPolicy")));
            JsonNode market=input.path("sources").path("market:台股:2881"),stock=market.path("radar").path("stock");
            row.put("frozenRadarGeneratedAt",market.path("radar").path("generatedAt").asText())
                    .put("frozenRadarAction",stock.path("action").asText())
                    .put("frozenWeek52Position",stock.path("week52Position").asText())
                    .put("frozenDistributionAdjusted",stock.path("distributionAdjusted").asBoolean());
            ArrayNode missing=row.putArray("missingOriginalOwnerState");
            for(String key:MISSING_OWNER_FIELDS)if(!input.path("sources").path("topup-state").has(key))missing.add(key);
            assertThat(missing.size()).isEqualTo(MISSING_OWNER_FIELDS.size());
            row.set("asPublishedEffectiveDates",run(frame,originalPolicy,publishedModel));
            ObjectNode counterfactual=publishedModel.deepCopy(),overlays=row.putObject("testOnlyPolicyDateOverlays");
            for(String key:List.of("named_cash_rotation_d223","named_high_zone_exit_d235")) {
                ObjectNode cfg=counterfactual.withObject("/execution_policy/"+key);
                ObjectNode overlay=overlays.putObject(key).put("field","effective_date").put("original",cfg.path("effective_date").asText());
                LocalDate original=LocalDate.parse(cfg.path("effective_date").asText());
                String effective=original.isAfter(facts.date())?facts.date().toString():original.toString();
                overlay.put("counterfactual",effective);cfg.put("effective_date",effective);
            }
            row.put("counterfactualModelNormalizedJcsSha256",SrppJcs.hash(SrppReportModuleCalculator.normalize(counterfactual)));
            ObjectNode counterOutput=run(frame,originalPolicy,counterfactual);row.set("counterfactualEffectiveDates",counterOutput);
            JsonNode leg=counterOutput.path("data").path("legs").get(0);
            assertThat(leg.path("strategy").asText()).isEqualTo(STRATEGY);
            assertThat(leg.path("status").asText()).isEqualTo("BLOCKED");
            assertThat(leg.path("units").asText()).isEqualTo("0");
            assertThat(leg.path("limitPrice").isNull()).isTrue();
            assertThat(leg.path("amount").isNull()).isTrue();
            assertThat(leg.path("fees").isNull()).isTrue();
            JsonNode highReceipt=null;for(JsonNode r:leg.path("receipts"))if("D235_HIGH_ZONE_PATH".equals(r.path("ruleId").asText()))highReceipt=r;
            assertThat(highReceipt).isNotNull();
            boolean frozenHigh=stock.path("distributionAdjusted").isBoolean()&&stock.path("distributionAdjusted").asBoolean()
                    &&new BigDecimal(stock.path("week52Position").asText()).compareTo(new BigDecimal("0.95"))>=0;
            assertThat(highReceipt.path("status").asText()).isEqualTo(frozenHigh?"PASS":"BLOCK");
            if(!frozenHigh)assertThat(highReceipt.path("reason").asText()).isEqualTo("D235_OUTSIDE_HIGH_ZONE_PAUSED");
            for(String gate:List.of("POSITION","D152_BATCH","COOLDOWN")) {
                JsonNode receipt=null;for(JsonNode r:leg.path("receipts"))if(gate.equals(r.path("ruleId").asText()))receipt=r;
                assertThat(receipt).as(gate+" must be evaluated despite high-zone outcome").isNotNull();
                assertThat(receipt.path("status").asText()).as(gate+" retains original missing owner evidence").isEqualTo("UNAVAILABLE");
            }
            assertThat(SrppJcs.hash(frame.input())).isEqualTo(originalInputHash);
            assertThat(SrppJcs.hash(frame.sourceVector())).isEqualTo(originalVectorHash);
            row.put("rawInputsUnmodified",true).put("tradeQualification","UNAVAILABLE");replayed++;
        }
        assertThat(replayed).isEqualTo(2);assertThat(errors).isEqualTo(1);assertThat(rawRadarRows.size()).isEqualTo(10);
        result.put("frozenPackageReplays",replayed).put("retainedErrorResponses",errors).put("standaloneRadarSources",rawRadarRows.size());
        Path output=Path.of(System.getenv("SRPP_D235_REPLAY_OUTPUT"));Files.createDirectories(output.getParent());Files.writeString(output,SrppJcs.canonicalize(result)+"\n");
        System.out.println("D235 retained replay: 2 original frozen packages BLOCKED, 1 original error retained, 10 standalone radar thresholds; receipt="+output);
    }
    private static ObjectNode run(DecisionInputCapturePort.Frozen frame,DecisionFacts.Policy policy,ObjectNode model) {
        var module=new SrppReportModuleCalculator.M("STRATEGIC_BUY_GATES","D235_PRIVATE_RETAINED_COUNTERFACTUAL_DIAGNOSTIC",List.of("assets","policy"));
        SrppNamedRadarSellCalculator.append(module,frame,policy,model);ObjectNode output=module.done();
        assertThat(output.path("data").path("legs").size()).isEqualTo(1);
        assertThat(output.path("data").path("legs").get(0).path("status").asText()).isEqualTo("BLOCKED");return output;
    }
    private static String sha(byte[] bytes)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
