package com.steven.assets.service.srpp.us;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.repository.UsReportRepository;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.srpp.report.*;
import com.steven.assets.srpp.SrppJcs;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;

/** Public synthetic facts only; no real owner, costs, balances or observations. */
public final class UsReportFixtures {
    private static final JsonNodeFactory J=JsonNodeFactory.instance;
    public static final LocalDate DAY=LocalDate.parse("2026-10-26");
    public static final Instant AT=Instant.parse("2026-10-26T13:46:00Z");
    public static final SrppReportAuthority AUTHORITY=SrppReportFixtures.AUTHORITY;
    public static final String SWAGGER=new com.steven.assets.service.srpp.PublishedSwaggerIdentity().sha256().orElseThrow();
    private UsReportFixtures(){}
    public static UsReportCalendar calendar(){MarketDataService market=mock(MarketDataService.class);when(market.isTradingDayCachedOnly(eq("美股"),any())).thenAnswer(a->{LocalDate d=a.getArgument(1);return Optional.of(d.getDayOfWeek()!=DayOfWeek.SATURDAY&&d.getDayOfWeek()!=DayOfWeek.SUNDAY);});return new UsReportCalendar(market);}
    public static ObjectNode parity(){try{return (ObjectNode)new ObjectMapper().readTree(UsReportFixtures.class.getResourceAsStream("/srpp/us-calculator-parity.json"));}catch(Exception failure){throw new AssertionError(failure);}}
    public static ObjectNode ledger(){ObjectNode ledger=parity().path("core").get(0).path("ledger").deepCopy();ObjectNode states=(ObjectNode)ledger.path("states");states.set("GOOGL",UsCoreSellCalculator.normalizeWatchState(states.path("GOOGL"),"GOOGL",DAY.toString(),AUTHORITY.model().path("execution_policy").path("core_sell_guard_d174")));return ledger;}
    public static UsReportReadPort.Scope scope(long owner,String consumer){return new UsReportReadPort.Scope(owner,consumer,"US",DAY,UsReportRequest.SLOT,AUTHORITY.decision().policy().bundleHash(),SWAGGER,UsReportPublisher.VERSION);}
    public static ObjectNode stateWrapper(long owner,String consumer,boolean available){ObjectNode n=J.objectNode().put("ownerUserId",owner).put("consumer",consumer).put("market","US").put("status",available?"VERIFIED":"UNAVAILABLE");n.putObject("versionVector");if(available){n.putNull("reason").put("importLedgerSha256","c".repeat(64)).put("watchPolicySha256",UsCoreSellCalculator.watchPolicyFingerprint(AUTHORITY.model().path("execution_policy").path("core_sell_guard_d174")));ObjectNode ledger=ledger();ledger.put("consumer",consumer);n.set("ledger",SrppReportModuleCalculator.normalize(ledger));n.withObject("versionVector").put("GOOGL",1);}else n.put("reason","WATCH_STATE_SOURCE_UNAVAILABLE").putNull("importLedgerSha256").putNull("watchPolicySha256").putNull("ledger");return n;}
    public static UsReportRepository.State state(long owner,String consumer,boolean available){return new UsReportRepository.State(stateWrapper(owner,consumer,available),available?Map.of("GOOGL",1L):Map.of());}
    public static UsReportCapturePort.Frozen frozen(boolean brokenUniverse){
        ObjectNode test=parity().path("core").get(0).deepCopy(),core=test.path("input").deepCopy(),sources=J.objectNode(),vector=J.objectNode();
        ObjectNode assets=assets();UsReportCaptureAdapter.source(sources,vector,"assets",assets,AT,"COMPLETE","VERIFIED");
        var p=AUTHORITY.decision().policy();ObjectNode policy=J.objectNode().put("rawModelBytes",AUTHORITY.decision().modelBytes()).put("rawManifestBytes",AUTHORITY.decision().manifestBytes()).put("bundleHash",p.bundleHash()).put("modelInputsSha256",p.modelHash()).put("policyManifestSha256",p.manifestHash()).put("watchPolicySha256",UsCoreSellCalculator.watchPolicyFingerprint(AUTHORITY.model().path("execution_policy").path("core_sell_guard_d174")));
        UsReportCaptureAdapter.source(sources,vector,"policy",policy,AT,"COMPLETE","VERIFIED");UsReportCaptureAdapter.source(sources,vector,"swagger",J.objectNode().put("sha256",SWAGGER),AT,"COMPLETE","VERIFIED");ObjectNode c=J.objectNode();c.set("days",core.path("calendar"));c.set("session",calendar().session(DAY).json());UsReportCaptureAdapter.source(sources,vector,"calendar",c,AT,"COMPLETE","VERIFIED");
        ObjectNode universe=J.objectNode();for(String key:List.of("holdingSymbols","radarSymbols","watchSymbols","symbols"))universe.putArray(key).add("GOOGL");universe.put("coverage",brokenUniverse?"PARTIAL":"COMPLETE");ArrayNode reasons=universe.putArray("reasonCodes");if(brokenUniverse)reasons.add("US_RADAR_LIST_SOURCE_UNAVAILABLE");UsReportCaptureAdapter.source(sources,vector,"universe",universe,AT,brokenUniverse?"PARTIAL":"COMPLETE",brokenUniverse?"UNAVAILABLE":"VERIFIED");
        ObjectNode market=J.objectNode();market.putObject("radarList").putArray("stocks").addObject().put("stockCode","GOOGL").put("market","美股");JsonNode original=core.path("items").get(0);ObjectNode symbol=market.putArray("symbols").addObject().put("stockCode","GOOGL").put("coreDirection","CORE_EXIT").put("historyTrusted",true);ObjectNode quote=original.path("quote").deepCopy();quote.put("currency","USD");ObjectNode daily=(ObjectNode)quote.path("marketData").path("chart").path("series").path("daily");ArrayNode opens=daily.putArray("opens"),history=symbol.putArray("priceHistory");for(int i=0;i<daily.path("dates").size();i++){opens.addNull();ObjectNode bar=history.addObject().put("symbol","GOOGL").put("market","美股").put("tradingDate",daily.path("dates").get(i).asText()).putNull("open").put("closeSource","SYNTHETIC_CANONICAL");bar.set("high",J.numberNode(new java.math.BigDecimal(daily.path("highs").get(i).asText())));bar.set("low",J.numberNode(new java.math.BigDecimal(daily.path("lows").get(i).asText())));bar.set("close",J.numberNode(new java.math.BigDecimal(daily.path("closes").get(i).asText())));}symbol.set("quote",quote);symbol.set("radar",original.path("radar"));symbol.putArray("reasonCodes");UsReportCaptureAdapter.source(sources,vector,"market",market,AT,"COMPLETE","VERIFIED");
        ObjectNode tactical=J.objectNode().put("trading_date",DAY.toString()).put("asof",AT.toString()).put("total_assets_twd","102955");tactical.set("session",calendar().session(DAY).json());tactical.putArray("candidates");tactical.putArray("reasonCodes").add("US_TACTICAL_TARGET_SOURCE_UNAVAILABLE");UsReportCaptureAdapter.source(sources,vector,"us-tactical",tactical,AT,"PARTIAL","UNAVAILABLE");return new UsReportCapturePort.Frozen(sources,vector,AT);
    }
    public static ObjectNode assets(){try{return (ObjectNode)new ObjectMapper().readTree("""
        {"generatedAt":"2026-10-26T13:46:00Z","valuationPolicy":"TARGET_SESSION_WITH_EXPLICIT_FALLBACK","targetPriceComplete":true,"marketStatus":{},
         "snapshot":{"id":9,"snapshotDate":"2026-10-26","usdExchangeRate":30,"totalDeposit":100000,"totalFundValue":0,"totalFundCost":0,"totalStockValue":3000,"totalStockCost":2400,"totalAssets":103000,"estimatedAnnualDividend":0,"realizedGain":0,"notes":null,
          "deposits":[{"id":1,"bankId":1,"bankDisplayName":"Synthetic","depositType":"SAVINGS","depositDisplayName":"Synthetic","amount":100000,"originalAmount":null,"currency":"TWD","annualInterestRate":null,"estimatedAnnualInterest":null,"notes":null,"updateMode":"MANUAL","processingDate":null}],"funds":[],
          "stocks":[{"id":1,"stockCode":"GOOGL","stockName":"Synthetic","market":"美股","brokerId":1,"brokerDisplayName":"Synthetic","shares":1,"investmentCost":80,"investmentCostTwd":2400,"currentValue":3000,"profit":600,"profitRate":25,"estimatedDividend":0,"dividendRate":0,"currency":"USD","originalCurrencyValue":100,"transactionType":null,"transactionDate":null,"transactionExchangeRate":null,"displayOrder":0}]},
         "liveAssets":{"snapshotId":9,"snapshotDate":"2026-10-26","exchangeRate":30,"totalDeposit":100000,"totalFundValue":0,"liveStockValue":2955,"liveTotalAssets":102955,"stocks":[{"stockCode":"GOOGL","stockName":"Synthetic","market":"美股","shares":1,"currentPrice":98.5,"liveValue":2955,"closed":false,"tradingDate":"2026-10-26","previousClose":100,"priceChange":-1.5,"changePercent":-1.5,"quoteStatus":"LIVE","holdingId":1,"source":"SYNTHETIC","targetTradingDate":"2026-10-26","valuationSource":"TARGET_SESSION_PRICE","updatedAt":"2026-10-26T13:46:00Z"}],"twMarketOpen":false,"usMarketOpen":true,"ukMarketOpen":true,"priceUpdatedAt":"2026-10-26T13:46:00Z"}}
        """);}catch(Exception failure){throw new AssertionError(failure);}}
}
