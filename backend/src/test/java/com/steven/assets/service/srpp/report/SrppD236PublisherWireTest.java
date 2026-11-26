package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.repository.SrppReportRepository;
import com.steven.assets.service.srpp.decision.*;
import com.steven.assets.srpp.SrppJcs;
import java.time.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="srpp.d236.publisher.dir",matches=".+")
class SrppD236PublisherWireTest {
    @Test void completeTaiwanPublisherExportsActionableAndPaused()throws Exception{
        var auth=SrppD236PublisherFixtures.authority();Path out=Path.of(System.getProperty("srpp.d236.publisher.dir"));Files.createDirectories(out);
        for(boolean paused:List.of(false,true)){
            LocalDate day=LocalDate.parse("2026-11-26");Instant at=Instant.parse("2026-11-26T04:00:00Z");ObjectNode nativeSources=SrppD236PublisherFixtures.sources(false,auth);if(paused)nativeSources.withObject("/market:台股:2881/radar/stock").put("week52Position","0.94");
            var unavailable=new DecisionFacts.Evidence("UNAVAILABLE","SYNTHETIC_SOURCE_NOT_PROVIDED");BigDecimal total=new BigDecimal(nativeSources.path("assets").path("liveAssets").path("liveTotalAssets").asText());var facts=new DecisionFacts.Input(day,"12:00",at,List.of(),total,new BigDecimal(nativeSources.path("assets").path("liveAssets").path("totalDeposit").asText()),BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("32"),unavailable,unavailable,Map.of(),new DecisionFacts.Budget(0,0,0,false,BigDecimal.ZERO,Map.of(),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO),unavailable,null);
            ObjectNode input=JsonNodeFactory.instance.objectNode().put("schemaVersion",1).put("ownerUserId",7).put("tradingDate",day.toString()).put("slot","12:00").put("capturedAt",at.toString());input.set("facts",SrppReportModuleCalculator.normalize(SrppReportFixtures.MAPPER.valueToTree(facts)));ObjectNode sources=(ObjectNode)SrppReportModuleCalculator.normalize(nativeSources);sources.putArray("ledger");sources.putArray("reservations");sources.putObject("calendar").put("tw",true).put("tradingDate",day.toString());input.set("sources",sources);ObjectNode vector=SrppD236PublisherFixtures.vector(sources,at);var frozen=new DecisionInputCapturePort.Frozen(facts,input,vector,1,at.toString());
            var repository=mock(SrppReportRepository.class);var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());var publisher=new SrppReportPublisher(repository,auth.authority(),new PureSrppReportCalculations(),SrppReportFixtures.MAPPER,manager,"sha256:"+"f".repeat(64));String swagger=sources.path("swagger").path("sha256").asText();var scope=new SrppReportRepository.Scope(7,day,"12:00",auth.supported().bundleHash(),swagger,SrppReportModuleCalculator.VERSION);Map<String,ObjectNode> states=new LinkedHashMap<>();for(String consumer:List.of("Codex","Claude"))states.put(consumer,new UnavailableSrppReportConsumerState().read(7,consumer,day,"12:00"));var saved=publisher.publish(scope,frozen,auth.supported(),sources,states,at);JsonNode wire=SrppReportEnvelopeValidator.validate(saved.getFirst().content());JsonNode selected=null;for(JsonNode module:wire.path("modules"))for(JsonNode leg:module.path("data").path("legs"))if(SrppZeroTargetHighZoneExitCalculator.STRATEGY.equals(leg.path("strategy").asText()))selected=leg;assertThat(selected).isNotNull();assertThat(selected.path("status").asText()).isEqualTo(paused?"BLOCKED":"ACTIONABLE_FOR_REPORT");assertThat(selected.path("units").asText()).isEqualTo(paused?"0":"300");Files.writeString(out.resolve(paused?"tw-blocked.json":"tw-actionable.json"),SrppJcs.canonicalize(wire)+"\n");
        }
    }
}
