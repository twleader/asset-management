package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.repository.*;
import com.steven.assets.service.*;
import com.steven.assets.srpp.SrppJcs;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

/** Canonical DB/cache reads only; never logs into a broker, refreshes a cache, or reads L0 again. */
@Component
public final class SrppReportExtraCaptureAdapter implements SrppReportExtraCapturePort {
    private final PriceQueryService prices;private final MarketDataService market;private final TradingRadarService radar;
    private final StockPriceHistoryRepository history;private final StockDividendHistoryRepository dividends;
    private final TechnicalIndicatorService indicators;private final ObjectMapper mapper;
    public SrppReportExtraCaptureAdapter(PriceQueryService prices,MarketDataService market,TradingRadarService radar,
            StockPriceHistoryRepository history,StockDividendHistoryRepository dividends,TechnicalIndicatorService indicators,ObjectMapper mapper){
        this.prices=prices;this.market=market;this.radar=radar;this.history=history;this.dividends=dividends;this.indicators=indicators;this.mapper=mapper;
    }
    public ExtraCapture capture(long ownerId,LocalDate date,String slot,Instant at,JsonNode model,JsonNode assets,JsonNode frozenSources){
        ObjectNode sources=JsonNodeFactory.instance.objectNode(),vector=JsonNodeFactory.instance.objectNode();
        SortedSet<String> universe=new TreeSet<>();
        model.path("detailed_target_allocation_2027").fieldNames().forEachRemaining(c->{if(!Set.of("TWD_term_deposits","demand_deposits_and_cash").contains(c))universe.add(market(c)+":"+c);});
        assets.path("snapshot").path("stocks").forEach(h->{String m=h.path("market").asText(),c=h.path("stockCode").asText();if(Set.of("台股","美股").contains(m)&&!c.isBlank())universe.add(m+":"+c);});
        JsonNode e=model.path("execution_policy");for(String key:List.of("short_term_opportunity_d171","bond_short_term_opportunity_d185"))e.path(key).path("symbols").forEach(c->universe.add("台股:"+c.asText()));
        Map<String,List<LocalDate>> calendarSessions=new HashMap<>();Map<String,Boolean> calendarQuality=new HashMap<>(),calendarComplete=new HashMap<>();Map<String,LocalDate>calendarStart=new HashMap<>(),calendarUnknown=new HashMap<>();
        for(String m:List.of("台股","美股")){List<LocalDate> dates=new ArrayList<>();boolean known=false,complete=true;LocalDate start=null,unknown=null;for(int d=1;d<=550;d++){LocalDate day=date.minusDays(d);Optional<Boolean> flag=market.isTradingDayCachedOnly(m,day);if(flag.isEmpty()){complete=false;unknown=day;break;}known=true;start=day;if(flag.get())dates.add(day);}Collections.reverse(dates);calendarSessions.put(m,dates);calendarQuality.put(m,known);calendarComplete.put(m,complete);calendarStart.put(m,start);calendarUnknown.put(m,unknown);}
        Set<String> reused=new HashSet<>();model.path("execution_policy").path("bond_price_selection_d195").path("strategic_symbols").forEach(c->reused.add(c.asText()));
        for(String key:universe){String m=key.substring(0,key.indexOf(':')),code=key.substring(key.indexOf(':')+1);ObjectNode body=JsonNodeFactory.instance.objectNode();body.put("market",m);body.put("symbol",code);body.put("volumeUnit","SHARES");
            boolean reuse="台股".equals(m)&&reused.contains(code)&&frozenSources.has("daily:"+code);
            if(reuse){JsonNode q=frozenSources.path("quote:"+code);body.set("quote",q.path("rawQuote"));body.set("nav",q.path("rawNav"));body.set("book",frozenSources.path("book:"+code));body.set("radar",frozenSources.path("radar:"+code));body.set("dividends",frozenSources.path("dividends:"+code));body.set("daily",frozenSources.path("daily:"+code).deepCopy());for(JsonNode row:body.path("daily")){if(row instanceof ObjectNode object){boolean trusted=false;try{trusted=prices.isTrustedClose(mapper.treeToValue(row,StockPriceHistory.class));}catch(Exception invalid){}object.put("trusted",trusted);}}body.set("indicators",JsonNodeFactory.instance.arrayNode());}
            List<StockPriceHistory> rows=reuse?List.of():history.findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(code,m,date.minusDays(730),date.minusDays(1));
            if(!reuse){ArrayNode daily=body.putArray("daily");for(var row:rows){ObjectNode n=(ObjectNode)json(row);n.put("trusted",prices.isTrustedClose(row));daily.add(n);}
            try{body.set("quote",json(prices.getLiveBatch(Set.of(new PriceQueryService.PriceKey(code,m)),Map.of(new PriceQueryService.PriceKey(code,m),rows)).getOrDefault(new PriceQueryService.PriceKey(code,m),Optional.empty()).orElse(null)));}catch(RuntimeException absent){body.putNull("quote");}
            try{body.set("nav",json(prices.getEtfNav(code,m).orElse(null)));}catch(RuntimeException absent){body.putNull("nav");}
            if("台股".equals(m))try{body.set("book",json(market.getQuoteDetail(code,m)));}catch(RuntimeException absent){body.putNull("book");}else body.putNull("book");
            try{body.set("radar",json(radar.getStockDetailForOwner(ownerId,code,m)));}catch(RuntimeException absent){body.putNull("radar");}
            body.set("dividends",json(dividends.findByStockSinceYear(code,m,date.getYear()-2)));
            // Existing business indicator-series computes from canonical persisted rows; no vendor IO.
            try{body.set("indicators",json(indicators.indicatorSeries(code,m,date.minusDays(730),date.minusDays(1))));}catch(RuntimeException absent){body.set("indicators",JsonNodeFactory.instance.arrayNode());}
            }
            ArrayNode sessions=body.putArray("completedSessions");calendarSessions.get(m).forEach(d->sessions.add(d.toString()));body.put("calendarVerified",calendarQuality.get(m));body.put("lookbackComplete",calendarComplete.get(m));if(calendarStart.get(m)==null)body.putNull("calendarCoverageStart");else body.put("calendarCoverageStart",calendarStart.get(m).toString());body.put("calendarCoverageEnd",date.minusDays(1).toString());if(calendarUnknown.get(m)==null)body.putNull("calendarUnknownDate");else body.put("calendarUnknownDate",calendarUnknown.get(m).toString());
            String sourceId="market:"+key;sources.set(sourceId,body);SourceQuality quality=assess(body,date,Instant.now());source(vector,sourceId,body,at,quality.coverage(),quality.validation());
        }
        for(String id:List.of("payroll","tax","core-state","global-pace","regime","short-sleeve","us-tactical","dca-identity","topup-state","report-reservations")){if(frozenSources.has(id))continue;ObjectNode unavailable=sources.putObject(id);unavailable.put("status","UNAVAILABLE");unavailable.put("reason",id.toUpperCase(Locale.ROOT).replace('-','_')+"_CANONICAL_SOURCE_UNAVAILABLE");source(vector,id,unavailable,at,"NONE","UNAVAILABLE");}
        Instant completedAt=Instant.now();vector.forEach(v->((ObjectNode)v).put("capturedAt",completedAt.toString()));return new ExtraCapture(sources,vector);
    }
    public record SourceQuality(String coverage,String validation){}
    /** Composite validation proves captured identity and provenance; each calculator still checks its own horizon/age/gates. */
    public static SourceQuality assess(JsonNode body,LocalDate date,Instant at){
        String symbol=body.path("symbol").asText(),market=body.path("market").asText();JsonNode quote=body.path("quote"),radar=body.path("radar"),stock=radar.path("stock");
        boolean known=body.path("calendarVerified").asBoolean()&&body.path("completedSessions").isArray()&&!body.path("completedSessions").isEmpty()&&body.path("daily").isArray()&&!body.path("daily").isEmpty();
        boolean valid=known&&!symbol.isBlank()&&Set.of("台股","美股").contains(market)&&symbol.equals(quote.path("stockCode").asText())&&market.equals(quote.path("market").asText())&&!quote.path("source").asText().isBlank()&&Set.of("LIVE","VERIFIED_CLOSE").contains(quote.path("quoteStatus").asText())&&positive(quote.path("price"))&&quote.path("closed").isBoolean()&&validTime(quote.path("updatedAt").asText(),at)&&date.toString().equals(quote.path("tradingDate").asText())&&symbol.equals(stock.path("stockCode").asText())&&market.equals(stock.path("market").asText())&&date.toString().equals(stock.path("asOfDate").asText())&&stock.path("dataComplete").asBoolean()&&!radar.path("ruleVersion").asText().isBlank()&&"EVIDENCE_GATE_V1".equals(radar.path("actionPolicyVersion").asText())&&validTime(radar.path("generatedAt").asText(),at);
        Set<String> sessionDates=new HashSet<>(),dailyDates=new HashSet<>();try{for(JsonNode session:body.path("completedSessions")){String day=session.asText();valid&=LocalDate.parse(day).isBefore(date)&&sessionDates.add(day);}for(JsonNode row:body.path("daily")){String day=row.path("tradingDate").asText();valid&=LocalDate.parse(day).isBefore(date)&&dailyDates.add(day)&&row.path("trusted").isBoolean()&&positive(row.path("closePrice"));if(row.hasNonNull("stockCode"))valid&=symbol.equals(row.path("stockCode").asText());if(row.hasNonNull("market"))valid&=market.equals(row.path("market").asText());}}catch(RuntimeException malformed){valid=false;}
        boolean complete=valid&&body.path("lookbackComplete").asBoolean(true)&&dailyDates.containsAll(sessionDates)&&body.path("dividends").isArray()&&body.path("indicators").isArray()&&!body.path("indicators").isEmpty();if("台股".equals(market))complete&=body.path("book").path("available").asBoolean()&&body.path("book").path("levels").size()==5&&positive(body.path("nav").path("nav"));
        return new SourceQuality(!known?"NONE":complete?"COMPLETE":"PARTIAL",valid?"VERIFIED":"UNAVAILABLE");
    }
    private static boolean positive(JsonNode n){try{return new java.math.BigDecimal(n.asText()).signum()>0;}catch(RuntimeException absent){return false;}}
    private static boolean validTime(String time,Instant at){try{Instant value;try{value=Instant.parse(time);}catch(DateTimeException ignored){value=LocalDateTime.parse(time).atZone(ZoneId.of("Asia/Taipei")).toInstant();}return !value.isAfter(at);}catch(DateTimeException absent){return false;}}
    private JsonNode json(Object value){return SrppReportModuleCalculator.normalize(mapper.valueToTree(value));}
    private static void source(ObjectNode vector,String id,JsonNode body,Instant at,String coverage,String validation){ObjectNode n=vector.putObject(id);n.put("sourceId",id);n.put("revision",SrppJcs.hash(body));n.put("capturedAt",at.toString());n.put("sha256",SrppJcs.hash(body));n.put("coverage",coverage);n.put("validation",validation);}
    private static String market(String code){return Set.of("VOO","VT","SGOV","GOOGL").contains(code)||code.matches("[A-Z.]+")?"美股":"台股";}
}
