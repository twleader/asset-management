package com.steven.assets.service.srpp.us;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.dto.*;
import com.steven.assets.model.*;
import com.steven.assets.repository.*;
import com.steven.assets.service.*;
import com.steven.assets.service.srpp.PublishedSwaggerIdentity;
import com.steven.assets.service.srpp.report.*;
import com.steven.assets.service.srpp.decision.DecisionInputCaptureAdapter;
import com.steven.assets.srpp.SrppJcs;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Existing canonical source reads only: Redis prices and persisted OHLC/dividends. No book or vendor client. */
@Component
public class UsReportCaptureAdapter implements UsReportCapturePort {
    private static final JsonNodeFactory J=JsonNodeFactory.instance;
    private final LatestAssetsService assets;private final PriceQueryService prices;private final TradingRadarService radar;
    private final StockPriceHistoryRepository history;private final StockDividendHistoryRepository dividends;
    private final TechnicalIndicatorService indicators;private final UsReportCalendar calendar;private final SrppReportAuthority authority;
    private final PublishedSwaggerIdentity swagger;private final ObjectMapper mapper;private final Clock clock;
    @Autowired public UsReportCaptureAdapter(LatestAssetsService assets,PriceQueryService prices,TradingRadarService radar,StockPriceHistoryRepository history,StockDividendHistoryRepository dividends,TechnicalIndicatorService indicators,UsReportCalendar calendar,SrppReportAuthority authority,PublishedSwaggerIdentity swagger,ObjectMapper mapper){this(assets,prices,radar,history,dividends,indicators,calendar,authority,swagger,mapper,Clock.systemUTC());}
    UsReportCaptureAdapter(LatestAssetsService assets,PriceQueryService prices,TradingRadarService radar,StockPriceHistoryRepository history,StockDividendHistoryRepository dividends,TechnicalIndicatorService indicators,UsReportCalendar calendar,SrppReportAuthority authority,PublishedSwaggerIdentity swagger,ObjectMapper mapper,Clock clock){this.assets=assets;this.prices=prices;this.radar=radar;this.history=history;this.dividends=dividends;this.indicators=indicators;this.calendar=calendar;this.authority=authority;this.swagger=swagger;this.mapper=mapper;this.clock=clock;}
    @Override
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Frozen capture(long owner,LocalDate date,Set<String> watchedSymbols,LocalDate earliestBasis){
        ObjectNode sources=J.objectNode(),vector=J.objectNode();JsonNode model=authority.model();
        SortedSet<String> held=new TreeSet<>(),listed=new TreeSet<>(),watched=new TreeSet<>(watchedSymbols),universe=new TreeSet<>();ArrayNode gaps=J.arrayNode();
        LatestAssetsDto.Response asset=null;JsonNode rawAssets=NullNode.instance;
        try{LatestAssetsDto.Response candidate=assets.getLatestForOwner(owner);if(candidate!=null&&candidate.generatedAt()!=null&&!freshAssets(candidate,clock.instant()))throw new AssetsStale();DecisionInputCaptureAdapter.validateCanonicalAssets(candidate);JsonNode candidateRaw=mapper.valueToTree(candidate);SortedSet<String> candidateHeld=new TreeSet<>();for(var row:candidate.snapshot().stocks())if("美股".equals(row.market())&&row.shares().signum()>0){if(row.stockCode()==null||row.stockCode().isBlank())throw new IllegalArgumentException("US_HOLDING_SELECTOR_UNVERIFIED");candidateHeld.add(row.stockCode());}asset=candidate;rawAssets=candidateRaw;held.addAll(candidateHeld);}catch(RuntimeException missing){asset=null;rawAssets=NullNode.instance;held.clear();gaps.add(missing instanceof AssetsStale?"ASSETS_CURRENT_ROUND_STALE":"ASSETS_L0_SOURCE_UNAVAILABLE");}
        TradingRadarDto.ListResponse list=null;JsonNode rawRadar=NullNode.instance;
        try{list=radar.getListForOwner(owner);rawRadar=mapper.valueToTree(list);for(var row:list.stocks())if("美股".equals(row.market())){if(row.stockCode()==null||row.stockCode().isBlank()||!listed.add(row.stockCode()))throw new IllegalArgumentException();}}catch(RuntimeException missing){listed.clear();gaps.add("US_RADAR_LIST_SOURCE_UNAVAILABLE");}
        universe.addAll(held);universe.addAll(listed);universe.addAll(watched);
        ObjectNode universeSource=J.objectNode();array(universeSource,"holdingSymbols",held);array(universeSource,"radarSymbols",listed);array(universeSource,"watchSymbols",watched);array(universeSource,"symbols",universe);universeSource.put("coverage",gaps.isEmpty()?"COMPLETE":"PARTIAL");universeSource.set("reasonCodes",gaps.deepCopy());
        LocalDate start=date.minusDays(35);if(earliestBasis!=null&&earliestBasis.isBefore(start))start=earliestBasis;
        ObjectNode calendarSource=J.objectNode();ArrayNode days;
        try{days=calendar.days(start,date);}catch(UsReportProblem unavailable){days=J.arrayNode();gaps.add("US_COMPLETED_CALENDAR_SOURCE_UNAVAILABLE");}
        calendarSource.set("days",days);calendarSource.set("session",calendar.session(date).json());
        ObjectNode market=J.objectNode();market.set("radarList",rawRadar);ArrayNode symbols=market.putArray("symbols");
        ObjectNode tactical=J.objectNode().put("trading_date",date.toString());tactical.set("session",calendar.session(date).json());ArrayNode candidates=tactical.putArray("candidates"),tacticalGaps=tactical.putArray("reasonCodes");
        if(asset==null)tactical.putNull("total_assets_twd");else tactical.put("total_assets_twd",asset.liveAssets().liveTotalAssets());
        JsonNode policy=model.path("execution_policy").path("core_sell_guard_d174");
        Map<PriceQueryService.PriceKey,List<StockPriceHistory>> histories=new LinkedHashMap<>();
        for(String code:universe)histories.put(new PriceQueryService.PriceKey(code,"美股"),history.findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(code,"美股",date.minusYears(2),date.minusDays(1)));
        Map<PriceQueryService.PriceKey,Optional<PriceQueryService.LivePrice>> quotes;
        try{quotes=prices.getLiveBatch(histories.keySet(),histories);}catch(RuntimeException absent){quotes=Map.of();}
        for(String code:universe){
            ObjectNode row=symbols.addObject().put("stockCode",code);ArrayNode rowGaps=row.putArray("reasonCodes");rowGaps.add("US_QUOTE_CROSSCHECK_CANONICAL_SOURCE_UNAVAILABLE");var key=new PriceQueryService.PriceKey(code,"美股");var hs=histories.get(key);ArrayNode canonical=row.putArray("priceHistory");for(var h:hs){ObjectNode bar=canonical.addObject().put("symbol",h.getStockCode()).put("market",h.getMarket()).put("tradingDate",h.getTradingDate().toString());bar.set("open",mapper.valueToTree(h.getOpenPrice()));bar.set("high",mapper.valueToTree(h.getHighPrice()));bar.set("low",mapper.valueToTree(h.getLowPrice()));bar.set("close",mapper.valueToTree(h.getClosePrice()));if(h.getCloseSource()==null)bar.putNull("closeSource");else bar.put("closeSource",h.getCloseSource());}boolean trusted=!hs.isEmpty()&&hs.stream().allMatch(prices::isTrustedClose);row.put("historyTrusted",trusted);if(!trusted)rowGaps.add("COMPLETED_PRICE_SOURCE_UNVERIFIED");
            PriceQueryService.LivePrice live=quotes.getOrDefault(key,Optional.empty()).orElse(null);JsonNode detail=NullNode.instance;
            try{detail=mapper.valueToTree(radar.getStockDetailForOwner(owner,code,"美股"));}catch(RuntimeException absent){rowGaps.add("US_EXACT_RADAR_SOURCE_UNAVAILABLE");}
            row.set("radar",detail);ObjectNode quote=live==null?null:mapper.valueToTree(live);
            if(quote==null){row.putNull("quote");rowGaps.add("US_LIVE_QUOTE_SOURCE_UNAVAILABLE");}
            else{
                quote.put("currency","USD");ObjectNode chart=quote.putObject("marketData").putObject("chart");chart.put("status",trusted?"AVAILABLE":"UNAVAILABLE").put("requestedStart",date.minusYears(2).toString()).put("requestedEnd",date.toString());
                ObjectNode daily=chart.putObject("series").putObject("daily");for(String field:List.of("dates","opens","highs","lows","closes","volumes","k","d","rsi5","bias10","wr9"))daily.putArray(field);
                Map<LocalDate,TechnicalIndicatorService.IndicatorPoint> points=new HashMap<>();try{indicators.indicatorSeries(code,"美股",date.minusYears(2),date.minusDays(1)).forEach(p->points.put(p.tradingDate(),p));}catch(RuntimeException absent){rowGaps.add("US_COMPLETED_KD_SOURCE_UNAVAILABLE");}
                for(var h:hs){((ArrayNode)daily.path("dates")).add(h.getTradingDate().toString());number(daily,"opens",h.getOpenPrice());number(daily,"highs",h.getHighPrice());number(daily,"lows",h.getLowPrice());number(daily,"closes",h.getClosePrice());((ArrayNode)daily.path("volumes")).add(h.getVolume()==null?NullNode.instance:J.numberNode(h.getVolume()));var p=points.get(h.getTradingDate());number(daily,"k",p==null?null:p.k());number(daily,"d",p==null?null:p.d());number(daily,"rsi5",p==null?null:p.rsi5());number(daily,"bias10",p==null?null:p.bias10());number(daily,"wr9",p==null?null:p.wr9());}
                var events=dividends.findByStockSinceYear(code,"美股",date.getYear()-2);
                var dividendQuality=CanonicalDividendFinancialFacts.assess(events);
                boolean comparable=true;try{CanonicalDividendFinancialFacts.assess(events,date.minusYears(2),date).requireComparablePriceBasis();}
                catch(com.steven.assets.model.CanonicalDividendFinancialGuard.Unavailable unavailable){comparable=false;}
                ObjectNode distribution=quote.putObject("dividendHistory").put("stockCode",code).put("market","美股");
                ArrayNode eventRows=distribution.putArray("rows");SortedSet<String> eventSources=new TreeSet<>();
                boolean nonDistributing=false;for(JsonNode declared:policy.path("non_distributing_symbols"))if(code.equals(declared.asText()))nonDistributing=true;
                if(!dividendQuality.available()||!comparable||events.isEmpty()&&!nonDistributing){
                    distribution.putNull("source").put("message",dividendQuality.available()&&!comparable
                            ?"US_DIVIDEND_PRICE_BASIS_UNVERIFIED":"CANONICAL_DIVIDEND_HISTORY_UNVERIFIED");
                    rowGaps.add("US_DISTRIBUTION_HISTORY_UNVERIFIED");
                }else{
                    events.forEach(e->eventRows.add(mapper.valueToTree(e)));
                    events.stream().map(StockDividendHistory::getSource).filter(Objects::nonNull).filter(s->!s.isBlank()).forEach(eventSources::add);
                    if(eventSources.isEmpty()){distribution.putNull("source").put("message",events.isEmpty()?"查無資料":"CANONICAL_DIVIDEND_SOURCE_UNAVAILABLE");}
                    else distribution.put("source",String.join("|",eventSources)).putNull("message");
                }
                row.set("quote",quote);
            }
            String direction=direction(code,asset,model);if(direction==null)row.putNull("coreDirection");else row.put("coreDirection",direction);
            // The strategic whitelist is authored policy; every other universe row stays in the tactical analysis.
            if(!strategic(model,code)){
                ObjectNode candidate=candidates.addObject().put("stock_code",code).put("qualification_path",listed.contains(code)?"RADAR":"FALLBACK");candidate.set("session",calendar.session(date).json());
                if(quote==null)candidate.putNull("quote");else{ObjectNode q=candidate.putObject("quote");for(String field:List.of("stockCode","market","currency","tradingDate","quoteStatus","source","closed"))q.set(field,quote.path(field));q.set("last",quote.path("price"));q.set("bid",quote.path("buyPrice"));q.set("ask",quote.path("sellPrice"));q.set("asOf",quote.path("updatedAt"));}
                candidate.set("radar",detail.has("stock")?detail.get("stock"):NullNode.instance);for(String field:List.of("target_price","target_price_basis","proposed_notional_twd","existing_company_cost_twd","open_tactical_total_twd","fallback_evidence"))candidate.putNull(field);ObjectNode costs=candidate.putObject("costs");for(String field:List.of("commission_each_side_pct","spread_each_side_pct","fx_spread_each_side_pct","tax_on_gain_pct"))costs.putNull(field);candidate.putObject("formalSources");
            }
        }
        Instant at=clock.instant();tactical.put("asof",at.toString());
        if(asset!=null&&!freshAssets(asset,at)){
            asset=null;rawAssets=NullNode.instance;held.clear();gaps.add("ASSETS_CURRENT_ROUND_STALE");universe.clear();universe.addAll(listed);universe.addAll(watched);array(universeSource,"holdingSymbols",held);array(universeSource,"symbols",universe);tactical.putNull("total_assets_twd");
            ArrayNode retainedSymbols=J.arrayNode();for(JsonNode row:symbols)if(universe.contains(row.path("stockCode").asText())){((ObjectNode)row).putNull("coreDirection");retainedSymbols.add(row);}market.set("symbols",retainedSymbols);
            ArrayNode retainedCandidates=J.arrayNode();for(JsonNode candidate:candidates)if(universe.contains(candidate.path("stock_code").asText()))retainedCandidates.add(candidate);tactical.set("candidates",retainedCandidates);candidates=retainedCandidates;
        }
        boolean valuationComplete=asset!=null;
        if(asset!=null)for(var h:asset.liveAssets().stocks())if(h.shares().signum()>0)try{LocalDate expected=calendar.valuationDate(h.market(),at);if(!expected.toString().equals(h.tradingDate())||!expected.toString().equals(h.targetTradingDate())||!"TARGET_SESSION_PRICE".equals(h.valuationSource()))valuationComplete=false;}catch(RuntimeException unavailable){valuationComplete=false;}
        if(!valuationComplete)gaps.add("ASSET_MARKET_VALUATION_DATE_UNVERIFIED");
        universeSource.set("reasonCodes",gaps.deepCopy());universeSource.put("coverage",gaps.isEmpty()?"COMPLETE":"PARTIAL");gaps.forEach(tacticalGaps::add);
        ObjectNode authored=J.objectNode().put("rawModelBytes",authority.decision().modelBytes()).put("rawManifestBytes",authority.decision().manifestBytes()).put("bundleHash",authority.decision().policy().bundleHash()).put("modelInputsSha256",authority.decision().policy().modelHash()).put("policyManifestSha256",authority.decision().policy().manifestHash()).put("watchPolicySha256",UsCoreSellCalculator.watchPolicyFingerprint(policy));
        source(sources,vector,"assets",rawAssets,at,asset==null?"NONE":valuationComplete?"COMPLETE":"PARTIAL",asset==null?"UNAVAILABLE":"VERIFIED");
        source(sources,vector,"policy",authored,at,"COMPLETE","VERIFIED");source(sources,vector,"swagger",J.objectNode().put("sha256",swagger.sha256().orElseThrow(()->new UsReportProblem("CONTEXT_NOT_READY"))),at,"COMPLETE","VERIFIED");
        source(sources,vector,"calendar",calendarSource,at,days.isEmpty()?"NONE":"COMPLETE",days.isEmpty()?"UNAVAILABLE":"VERIFIED");
        source(sources,vector,"universe",universeSource,at,gaps.isEmpty()?"COMPLETE":"PARTIAL",gaps.isEmpty()?"VERIFIED":"UNAVAILABLE");source(sources,vector,"market",market,at,"PARTIAL","VERIFIED");source(sources,vector,"us-tactical",tactical,at,candidates.isEmpty()&&gaps.isEmpty()?"COMPLETE":"PARTIAL",candidates.isEmpty()&&gaps.isEmpty()?"VERIFIED":"UNAVAILABLE");
        return new Frozen(sources,vector,at);
    }
    private static final class AssetsStale extends RuntimeException {}
    private static boolean freshAssets(LatestAssetsDto.Response a,Instant at){return a.generatedAt()!=null&&!a.generatedAt().isAfter(at)&&Duration.between(a.generatedAt(),at).compareTo(Duration.ofSeconds(300))<=0;}
    private static boolean strategic(JsonNode model,String code){return Set.of("VOO","VT","SGOV").contains(code)&&model.path("detailed_target_allocation_2027").has(code);}
    private static String direction(String code,LatestAssetsDto.Response a,JsonNode model){if(a==null)return null;BigDecimal value=a.liveAssets().stocks().stream().filter(h->"美股".equals(h.market())&&code.equals(h.stockCode())&&h.shares().signum()>0).map(StockPriceService.LiveStockItem::liveValue).reduce(BigDecimal.ZERO,BigDecimal::add);if(value.signum()<=0)return null;for(JsonNode zero:model.path("zero_target_do_not_rebuy").path("zero_target"))if(code.equals(zero.asText()))return "CORE_EXIT";JsonNode band=model.path("execution_policy_overrides").path("global_equity_wrapper_policy_d141").get(code+"_upper_band");if(band!=null&&band.isNumber()&&value.compareTo(a.liveAssets().liveTotalAssets().multiply(band.decimalValue()))>0)return "CORE_REDUCE";return null;}
    private static void array(ObjectNode n,String field,Collection<String> values){ArrayNode a=n.putArray(field);values.forEach(a::add);}
    private static void number(ObjectNode daily,String field,BigDecimal value){((ArrayNode)daily.path(field)).add(value==null?NullNode.instance:J.numberNode(value));}
    public static void source(ObjectNode sources,ObjectNode vector,String id,JsonNode raw,Instant at,String coverage,String validation){sources.set(id,raw);JsonNode wire=SrppReportModuleCalculator.normalize(raw);String hash=SrppJcs.hash(wire);vector.putObject(id).put("sourceId",id).put("revision",hash).put("capturedAt",at.toString()).put("sha256",hash).put("coverage",coverage).put("validation",validation);}
}
