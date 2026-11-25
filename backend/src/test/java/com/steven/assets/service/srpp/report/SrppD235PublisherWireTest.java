package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.repository.SrppReportRepository;
import com.steven.assets.service.srpp.decision.DecisionInputCapturePort.Frozen;
import com.steven.assets.service.srpp.decision.DecisionFacts;
import com.steven.assets.srpp.SrppJcs;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static com.steven.assets.service.srpp.report.SrppReportFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs only after final embedded D235 authority generation. Synthetic market/account evidence, real publisher/calculators. */
@EnabledIfSystemProperty(named="srpp.d235.publisher.dir",matches=".+")
class SrppD235PublisherWireTest {
    private static void shift(JsonNode node){
        if(node.isObject()){
            ObjectNode object=(ObjectNode)node;List<String> keys=new ArrayList<>();object.fieldNames().forEachRemaining(keys::add);
            for(String key:keys){JsonNode value=object.get(key);if(value.isTextual()){
                String text=value.asText();if(text.matches("\\d{4}-\\d{2}-\\d{2}"))object.put(key,LocalDate.parse(text).plusDays(7).toString());
                else if(text.matches("2026-11-19T.*"))object.put(key,OffsetDateTime.parse(text).plusDays(7).toInstant().toString());
            }else shift(value);}
        }else if(node.isArray()){
            ArrayNode array=(ArrayNode)node;for(int i=0;i<array.size();i++){JsonNode value=array.get(i);if(value.isTextual()&&value.asText().matches("\\d{4}-\\d{2}-\\d{2}"))array.set(i,TextNode.valueOf(LocalDate.parse(value.asText()).plusDays(7).toString()));else shift(value);}
        }
    }
    private static JsonNode publish(boolean blocked){
        JsonNode model=AUTHORITY.model();assertThat(model.path("execution_policy").path("named_high_zone_exit_d235").path("decision").asText()).isEqualTo("D-235");
        var original=SrppNamedRadarSellCalculatorTest.frame((ObjectNode)model,s->{
            SrppNamedRadarSellCalculatorTest.shares(s,3000,3000,new java.math.BigDecimal("450000"));
            s.withObject("/market:台股:2881/radar/stock").put("action","HOLD_CAUTION").put("distributionAdjusted",true).put("week52Position",blocked?"0.94":"0.97");
        });
        ObjectNode input=original.input();shift(input);ObjectNode sources=input.withObject("sources"),vector=JsonNodeFactory.instance.objectNode();
        String actualSwagger=new com.steven.assets.service.srpp.PublishedSwaggerIdentity().sha256().orElseThrow();sources.withObject("swagger").put("sha256",actualSwagger);
        sources.withObject("/assets/liveAssets").put("exchangeRate","32");
        for(JsonNode raw:sources.path("assets").path("snapshot").path("deposits"))((ObjectNode)raw).put("bankDisplayName","SYNTHETIC_BANK").put("depositDisplayName","SYNTHETIC_DEPOSIT").put("annualInterestRate","0").put("notes","SYNTHETIC_TEST_ONLY");
        Instant at=SrppNamedRadarSellCalculatorTest.AT.plus(Duration.ofDays(7));
        sources.fields().forEachRemaining(e->vector.putObject(e.getKey()).put("sourceId",e.getKey()).put("revision",SrppJcs.hash(e.getValue())).put("sha256",SrppJcs.hash(e.getValue())).put("capturedAt",at.toString()).put("coverage","COMPLETE").put("validation","VERIFIED"));
        var facts=MAPPER.convertValue(input.path("facts"),DecisionFacts.Input.class);var frozen=new Frozen(facts,input,vector,1,at.toString());
        var repository=mock(SrppReportRepository.class);var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());
        var publisher=new SrppReportPublisher(repository,AUTHORITY,new PureSrppReportCalculations(),MAPPER,manager,"sha256:"+"f".repeat(64));
        var scope=new SrppReportRepository.Scope(7,facts.date(),"12:00",supported().bundleHash(),actualSwagger,SrppReportModuleCalculator.VERSION);
        Map<String,ObjectNode> states=new LinkedHashMap<>();for(String c:List.of("Codex","Claude"))states.put(c,new UnavailableSrppReportConsumerState().read(7,c,facts.date(),"12:00"));
        var saved=publisher.publish(scope,frozen,supported(),sources,states,at);return SrppReportEnvelopeValidator.validate(saved.getFirst().content());
    }
    @Test void actualPublisherExportsCompleteActionableAndPausedD235Wire()throws Exception{
        Path dir=Path.of(System.getProperty("srpp.d235.publisher.dir"));Files.createDirectories(dir);
        for(boolean blocked:List.of(false,true)){
            JsonNode root=publish(blocked);JsonNode found=null;for(JsonNode module:root.path("modules"))for(JsonNode leg:module.path("data").path("legs"))if("NAMED_HIGH_ZONE_EXIT_D235".equals(leg.path("strategy").asText())){assertThat(found).isNull();found=leg;}
            assertThat(found).isNotNull();assertThat(found.path("status").asText()).isEqualTo(blocked?"BLOCKED":"ACTIONABLE_FOR_REPORT");assertThat(found.path("units").asText()).isEqualTo(blocked?"0":"1000");
            Files.writeString(dir.resolve(blocked?"blocked.json":"actionable.json"),SrppJcs.canonicalize(root)+"\n");
        }
    }
}
