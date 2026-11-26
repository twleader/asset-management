package com.steven.assets.service.srpp.us;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.repository.UsReportRepository;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.srpp.report.*;
import com.steven.assets.srpp.SrppJcs;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="srpp.d236.publisher.dir",matches=".+")
class SrppD236UsPublisherWireTest {
    static void ownerHash(JsonNode raw,String hash){if(raw.isObject()){ObjectNode o=(ObjectNode)raw;if(o.has("policyBundleSha256"))o.put("policyBundleSha256",hash);for(JsonNode child:o)ownerHash(child,hash);}else if(raw.isArray())for(JsonNode child:raw)ownerHash(child,hash);}
    @Test void completeUsPublisherExportsActualGenericModuleWithoutBreakingLegacyModules()throws Exception{
        var auth=SrppD236PublisherFixtures.authority();Path out=Path.of(System.getProperty("srpp.d236.publisher.dir"));Files.createDirectories(out);LocalDate day=LocalDate.parse("2026-11-30");Instant at=Instant.parse("2026-11-30T14:45:00Z");
        MarketDataService market=mock(MarketDataService.class);when(market.isTradingDayCachedOnly("美股",day)).thenReturn(Optional.of(true));UsReportCalendar calendar=new UsReportCalendar(market);
        for(boolean paused:List.of(false,true)){
            JsonNode wrapper=SrppReportFixtures.MAPPER.readTree(getClass().getResourceAsStream("/srpp/d236-complete-synthetic-us.json"));ObjectNode sources=(ObjectNode)wrapper.path("sources").deepCopy();ownerHash(sources,auth.supported().bundleHash());var p=auth.authority().decision().policy();sources.putObject("policy").put("rawModelBytes",auth.authority().decision().modelBytes()).put("rawManifestBytes",auth.authority().decision().manifestBytes()).put("bundleHash",p.bundleHash()).put("modelInputsSha256",p.modelHash()).put("policyManifestSha256",p.manifestHash()).put("watchPolicySha256",UsCoreSellCalculator.watchPolicyFingerprint(auth.authority().model().path("execution_policy").path("core_sell_guard_d174")));
            String swagger=new com.steven.assets.service.srpp.PublishedSwaggerIdentity().sha256().orElseThrow();sources.putObject("swagger").put("sha256",swagger);if(paused)((ObjectNode)sources.path("market").path("symbols").get(0).path("radar").path("stock")).put("week52Position","0.94");sources=(ObjectNode)SrppReportModuleCalculator.normalize(sources);ObjectNode vector=SrppD236PublisherFixtures.vector(sources,at);var frozen=new UsReportCapturePort.Frozen(sources,vector,at);var state=UsReportFixtures.state(7,"Codex",false);var scope=new UsReportReadPort.Scope(7,"Codex","US",day,UsReportRequest.SLOT,p.bundleHash(),swagger,UsReportPublisher.VERSION);var publisher=new UsReportPublisher(mock(UsReportRepository.class),mock(UsReportCapturePort.class),auth.authority(),calendar);var saved=publisher.publish(scope,frozen,state,at);JsonNode wire=UsReportEnvelopeValidator.validate(saved.content());assertThat(wire.path("modules").size()).isEqualTo(3);JsonNode leg=wire.path("modules").get(2).path("data").path("legs").get(0);assertThat(leg.path("status").asText()).isEqualTo(paused?"BLOCKED":"ACTIONABLE_FOR_REPORT");assertThat(leg.path("units").asText()).isEqualTo(paused?"0":"300");Files.writeString(out.resolve(paused?"us-blocked.json":"us-actionable.json"),SrppJcs.canonicalize(wire)+"\n");
        }
    }
}
