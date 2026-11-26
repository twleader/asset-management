package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.service.srpp.decision.*;
import com.steven.assets.srpp.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;

/** Explicit test-only live-source substitute. Uses exact candidate authority files, no API or production writes. */
public final class SrppD236PublisherFixtures {
    public record Auth(SrppReportAuthority authority,SupportedPolicy supported){}
    private SrppD236PublisherFixtures(){}
    public static Auth authority()throws Exception{
        Path root=Path.of(System.getProperty("srpp.d236.source.root"));String model=Files.readString(root.resolve("assumptions/model_inputs.json")),manifest=Files.readString(root.resolve("assumptions/policy_manifest.json"));JsonNode m=SrppJcs.parseStrict(model),mft=SrppJcs.parseStrict(manifest);String hash=mft.path("POLICY_BUNDLE_SHA256").asText();
        var old=SrppReportFixtures.AUTHORITY.decision().policy();RecordComponent[] fields=DecisionFacts.Policy.class.getRecordComponents();Object[] values=new Object[fields.length];Class<?>[] types=new Class<?>[fields.length];
        for(int i=0;i<fields.length;i++){types[i]=fields[i].getType();values[i]=switch(fields[i].getName()){case "bundleHash"->hash;case "modelHash"->SrppJcs.sha256Hex(model);case "manifestHash"->SrppJcs.sha256Hex(manifest);case "decision"->m.path("decision").asText();case "policyVersion"->m.path("policy_version").asText();case "modelVersion"->m.path("model_version").asText();default->fields[i].getAccessor().invoke(old);};}
        var policy=DecisionFacts.Policy.class.getDeclaredConstructor(types).newInstance(values);DecisionPolicyAuthority decision=mock(DecisionPolicyAuthority.class);when(decision.policy()).thenReturn(policy);when(decision.modelBytes()).thenReturn(model);when(decision.manifestBytes()).thenReturn(manifest);when(decision.fundClassifications()).thenReturn(SrppReportFixtures.AUTHORITY.decision().fundClassifications());
        SrppReportAuthority authority=new SrppReportAuthority(decision);var doc=SrppPolicyDocument.parse(SrppJcs.canonicalize(SrppReportModuleCalculator.registryDocument(authority.model())));String version=SrppFormulaCatalog.FORMULA_VERSION_V3;
        SupportedPolicy supported=new SupportedPolicy(hash,version,SrppJcs.hash(doc.document()),SrppFormulaCatalog.formulaSetSha256(version),doc.document(),SrppJcs.parseStrict(SrppFormulaCatalog.manifestJcs(version)),doc.targets(),Instant.parse("2026-11-30T14:45:00Z"));return new Auth(authority,supported);
    }
    public static ObjectNode sources(boolean us,Auth auth)throws Exception{
        ObjectNode fixture=SrppZeroTargetHighZoneExitCalculatorTest.fixture(us);ObjectNode sources=fixture.withObject("sources");String day=us?"2026-11-30":"2026-11-26",at=us?"2026-11-30T14:45:00Z":"2026-11-26T04:00:00Z";
        ObjectNode assets=sources.withObject("assets"),snapshot=assets.withObject("snapshot"),live=assets.withObject("liveAssets");BigDecimal originalStock=new BigDecimal(live.path("stocks").get(0).path("liveValue").asText()),stock=originalStock.add(new BigDecimal("5000000")),total=new BigDecimal("10000000"),cash=total.subtract(stock);live.put("liveTotalAssets",total.toPlainString());
        snapshot.withArray("stocks").addObject().put("id",2).put("stockCode","0050").put("market","台股").put("brokerId",3).put("brokerDisplayName","FUBON").put("shares","50000").put("currentValue","5000000");
        live.withArray("stocks").addObject().put("holdingId",2).put("stockCode","0050").put("market","台股").put("shares","50000").put("liveValue","5000000").put("tradingDate",day);sources.withObject("/classifications/resolvedStockClasses").put("0050:台股","STOCK");
        ObjectNode base=(ObjectNode)SrppReportFixtures.MAPPER.readTree(SrppD236PublisherFixtures.class.getResourceAsStream("/srpp/d236-complete-synthetic-base.json"));for(var e:sources.properties())base.set(e.getKey(),e.getValue());sources=base;
        snapshot.put("totalStockValue",stock.toPlainString()).put("totalFundValue","0").put("totalDeposit",cash.toPlainString()).put("totalAssets",total.toPlainString());live.put("liveStockValue",stock.toPlainString()).put("totalFundValue","0").put("totalDeposit",cash.toPlainString()).put("exchangeRate",us?"32":"32");
        ObjectNode deposit=snapshot.putArray("deposits").addObject();deposit.put("id",1).put("bankId",1).put("bankDisplayName","SYNTHETIC_ONLY").put("depositType","SAVINGS").put("depositDisplayName","SYNTHETIC_ONLY").put("currency","TWD").put("amount",cash.toPlainString()).put("originalAmount",cash.toPlainString()).put("annualInterestRate","0").put("estimatedAnnualInterest","0").put("notes","SYNTHETIC_TEST_NOT_API");
        for(JsonNode row:live.path("stocks"))((ObjectNode)row).put("targetTradingDate",day).put("valuationSource","TARGET_SESSION_PRICE").put("quoteStatus","LIVE").put("updatedAt",at).put("currentPrice","100").put("source","SYNTHETIC_ONLY").put("closed",false);
        sources.withObject("/zero-target-exit-state/positionAttributions").withArray("rows").addObject().put("holdingId",2).put("symbol","0050").put("market","台股").put("brokerId",3).put("accountFingerprint","A").put("subaccount","CORE").put("coreShares","0").put("protectedShares","50000").put("source_ref","SYNTHETIC_NON_EXIT_CUSTODY");
        for(String key:List.of("zero-target-exit-state","report-reservations"))sources.withObject(key).put("policyBundleSha256",auth.supported.bundleHash());
        var p=auth.authority.decision().policy();ObjectNode policy=sources.putObject("policy").put("bundleHash",p.bundleHash()).put("rawModelBytes",auth.authority.decision().modelBytes()).put("rawManifestBytes",auth.authority.decision().manifestBytes()).put("modelInputsSha256",p.modelHash()).put("policyManifestSha256",p.manifestHash());
        String swagger=new com.steven.assets.service.srpp.PublishedSwaggerIdentity().sha256().orElseThrow();sources.putObject("swagger").put("sha256",swagger);return sources;
    }
    public static ObjectNode vector(ObjectNode sources,Instant at){ObjectNode out=JsonNodeFactory.instance.objectNode();for(var e:sources.properties()){String hash=SrppJcs.hash(SrppReportModuleCalculator.normalize(e.getValue()));boolean unavailable="UNAVAILABLE".equals(e.getValue().path("status").asText());out.putObject(e.getKey()).put("sourceId",e.getKey()).put("revision",hash).put("sha256",hash).put("capturedAt",at.toString()).put("coverage",unavailable?"NONE":"COMPLETE").put("validation",unavailable?"UNAVAILABLE":"VERIFIED");}return out;}
}
