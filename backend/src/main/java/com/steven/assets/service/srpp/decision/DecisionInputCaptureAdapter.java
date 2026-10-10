package com.steven.assets.service.srpp.decision;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.dto.*;
import com.steven.assets.model.*;
import com.steven.assets.repository.*;
import com.steven.assets.service.*;
import com.steven.assets.service.srpp.SrppCaptureProblem;
import com.steven.assets.srpp.SrppJcs;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import static com.steven.assets.service.srpp.decision.DecisionFacts.*;

/** Canonical pure reads only. Every unavailable source is frozen, rather than inferred as zero. */
@Component
public class DecisionInputCaptureAdapter implements DecisionInputCapturePort {
    private final LatestAssetsService assets;private final PriceQueryService prices;private final MarketDataService market;
    private final TradingRadarService radar;private final StockPriceHistoryRepository histories;private final StockDividendHistoryRepository dividends;
    private final SrppDecisionSourceRepository sourceRepository;private final UsdTwdLiveRateService fx;private final RadarIntradayCandlePort minutes;
    private final DecisionPolicyAuthority authority;private final ObjectMapper mapper;private final StockRepository masters;private final FundClassOverrideRepository fundOverrides;
    public DecisionInputCaptureAdapter(LatestAssetsService assets,PriceQueryService prices,MarketDataService market,TradingRadarService radar,
            StockPriceHistoryRepository histories,StockDividendHistoryRepository dividends,SrppDecisionSourceRepository sourceRepository,
            UsdTwdLiveRateService fx,RadarIntradayCandlePort minutes,DecisionPolicyAuthority authority,ObjectMapper mapper,StockRepository masters,FundClassOverrideRepository fundOverrides){this.assets=assets;this.prices=prices;this.market=market;this.radar=radar;this.histories=histories;this.dividends=dividends;this.sourceRepository=sourceRepository;this.fx=fx;this.minutes=minutes;this.authority=authority;this.mapper=mapper;this.masters=masters;this.fundOverrides=fundOverrides;}
    @Override public Frozen capture(long ownerId,DecisionRequest request,List<ObjectNode> priorReservations){
        Policy p=authority.policy();LatestAssetsDto.Response a;
        try{a=assets.getLatestForOwner(ownerId);validateAssets(a);}catch(RuntimeException unavailable){throw new SrppCaptureProblem(HttpStatus.SERVICE_UNAVAILABLE,"CONTEXT_NOT_READY");}
        LocalDate date=request.date(),monday=date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<LocalDate> sessions=previousSessions(date,p.lookback());
        ObjectNode weekCalendar=JsonNodeFactory.instance.objectNode();boolean weekVerified=true;for(LocalDate d=monday;!d.isAfter(date);d=d.plusDays(1)){Optional<Boolean> flag=market.isTwTradingDayCachedOnly(d);if(flag==null||flag.isEmpty()){weekCalendar.putNull(d.toString());weekVerified=false;}else weekCalendar.put(d.toString(),flag.get());}
        Map<String,List<StockPriceHistory>> hs=new TreeMap<>();Map<PriceQueryService.PriceKey,List<StockPriceHistory>> historyByKey=new LinkedHashMap<>();
        for(String c:p.symbols()){
            List<StockPriceHistory> rows=histories.findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(c,"台股",date.minusDays(100),date.minusDays(1));hs.put(c,rows);historyByKey.put(new PriceQueryService.PriceKey(c,"台股"),rows);
        }
        var quotes=prices.getLiveBatch(historyByKey.keySet(),historyByKey);var navs=prices.getEtfNavBatch(historyByKey.keySet());
        ArrayNode ledger=sourceRepository.ledger(ownerId,monday,date);ArrayNode reservations=JsonNodeFactory.instance.arrayNode();priorReservations.forEach(reservations::add);
        Map<String,QuoteDetailDto.Response> books=new TreeMap<>();Map<String,TradingRadarDto.StockDetailResponse> details=new TreeMap<>();Map<String,List<StockDividendHistory>> ds=new TreeMap<>();
        for(String c:p.symbols()){
            try{books.put(c,market.getQuoteDetail(c,"台股"));}catch(RuntimeException ignored){books.put(c,null);}
            try{details.put(c,radar.getStockDetail(c,"台股"));}catch(RuntimeException ignored){details.put(c,null);}
            ds.put(c,dividends.findByStockSinceYear(c,"台股",date.getYear()-1));
        }
        UsdTwdLiveRateService.LiveRate rate=null;try{rate=fx.getLiveRate();}catch(RuntimeException ignored){}
        Map<String,String> classifications=new HashMap<>();List<Stock> stockMasters=masters.findAllByCodeIn(a.liveAssets().stocks().stream().map(StockPriceService.LiveStockItem::stockCode).toList());stockMasters.forEach(s->classifications.put(s.getCode()+":"+s.getMarket(),AssetClassifier.defaultClassifier().classifyStock(s.getCode(),s.getMarket(),s.getAssetClass())));
        List<FundClassOverride> fundClassRows=fundOverrides.findAllById(a.snapshot().funds().stream().map(AssetSnapshotDto.FundResponse::fundName).toList());Map<String,String> fundClasses=new TreeMap<>();fundClassRows.forEach(f->fundClasses.put(f.getFundName(),f.getAssetClass()));
        // generatedAt is the read transport time. Freeze only after all these source reads complete.
        Instant asOf=Instant.now();Map<String,RadarIntradayCandlePort.Capture> captures;
        try{captures=minutes.read(p.symbols(),date,asOf);if(captures==null)captures=Map.of();}catch(RuntimeException ignored){captures=Map.of();}
        ObjectNode input=JsonNodeFactory.instance.objectNode(),vector=JsonNodeFactory.instance.objectNode();
        input.put("schemaVersion",1);input.put("ownerUserId",ownerId);input.put("tradingDate",date.toString());input.put("slot",request.slot());input.put("capturedAt",asOf.toString());ObjectNode sources=input.putObject("sources");
        ObjectNode classSource=JsonNodeFactory.instance.objectNode();classSource.set("stockMasterRows",json(stockMasters));classSource.set("fundOverrideRows",json(fundClassRows));classSource.set("resolvedStockClasses",json(classifications));classSource.put("classifierVersion","ASSET_CLASSIFIER_CURRENT_SOURCE");
        source(sources,vector,"classifications",classSource,"canonical-master-overrides",asOf,"COMPLETE","VERIFIED");
        source(sources,vector,"assets",json(a),"snapshot-"+a.snapshot().id(),asOf,"COMPLETE","VERIFIED");
        ObjectNode calendar=JsonNodeFactory.instance.objectNode();calendar.put("tradingDate",date.toString());calendar.put("tw",true);calendar.set("weekCalendar",weekCalendar);ArrayNode sessionRows=calendar.putArray("completedSessions");sessions.forEach(d->sessionRows.add(d.toString()));
        source(sources,vector,"calendar",calendar,"calendar-"+date,asOf,sessions.size()==p.lookback()&&weekVerified?"COMPLETE":"PARTIAL",sessions.size()==p.lookback()&&weekVerified?"VERIFIED":"UNAVAILABLE");
        ObjectNode policy=JsonNodeFactory.instance.objectNode();policy.put("rawModelBytes",authority.modelBytes());policy.put("rawManifestBytes",authority.manifestBytes());policy.put("bundleHash",p.bundleHash());policy.set("typedPolicy",json(p));
        source(sources,vector,"policy",policy,p.bundleHash(),asOf,"COMPLETE","VERIFIED");
        source(sources,vector,"swagger",JsonNodeFactory.instance.objectNode().put("sha256",request.swaggerHash()),request.swaggerHash(),asOf,"COMPLETE","VERIFIED");
        source(sources,vector,"ledger",ledger,"owner-"+ownerId+"-"+monday+"-"+date,asOf,"COMPLETE","VERIFIED");
        source(sources,vector,"reservations",reservations,"owner-"+ownerId+"-"+monday+"-"+date,asOf,"COMPLETE","VERIFIED");
        source(sources,vector,"fx",json(rate),rate==null?"unavailable":Objects.toString(rate.polledAt(),Objects.toString(rate.date())),asOf,rate==null?"NONE":"COMPLETE",fxQualified(rate,date,asOf)?"VERIFIED":"UNAVAILABLE");
        // D-130 only pauses on a confirmed reserved bear window; absent state is disclosed, never invented.
        Evidence bear=new Evidence("UNAVAILABLE","RESERVED_BEAR_WINDOW_SOURCE_UNAVAILABLE");
        source(sources,vector,"bear-window",json(bear),"not-published",asOf,"NONE","UNAVAILABLE");
        Evidence subaccounts=new Evidence("UNAVAILABLE","SUBACCOUNT_UNVERIFIED");source(sources,vector,"subaccounts",json(subaccounts),"missing-account-and-pending-source",asOf,"NONE","UNAVAILABLE");
        Map<String,BigDecimal> values=new TreeMap<>();BigDecimal bond=BigDecimal.ZERO;Map<Long,AssetSnapshotDto.StockResponse> holding=new HashMap<>();a.snapshot().stocks().forEach(s->holding.put(s.id(),s));
        boolean valuation=true;
        for(StockPriceService.LiveStockItem live:a.liveAssets().stocks()){
            values.merge(live.stockCode(),live.liveValue(),BigDecimal::add);
            if("BOND".equals(classifications.get(live.stockCode()+":"+live.market())))bond=bond.add(live.liveValue());
            else if(classifications.get(live.stockCode()+":"+live.market())==null)valuation=false;
            if(!"TARGET_SESSION_PRICE".equals(live.valuationSource()))valuation=false;
        }
        for(var f:a.snapshot().funds())if("BOND".equals(AssetClassifier.defaultClassifier().classifyFund(f.fundName(),fundClasses.get(f.fundName()))))bond=bond.add(f.currentValue());
        BigDecimal term=BigDecimal.ZERO,total=BigDecimal.ZERO;
        for(var d:a.snapshot().deposits())if("TWD".equals(d.currency())){
            if(d.amount().signum()<0||!"TRANSIT_TWD".equals(d.depositType()))total=total.add(d.amount());
            if(d.depositType()!=null&&(d.depositType().contains("TERM")||d.depositType().contains("定存"))&&d.amount().signum()>0)term=term.add(d.amount());
        }
        Budget rawBudget=budget(p,date,ledger,reservations);Budget budget=new Budget(rawBudget.strategyToday(),rawBudget.strategyWeek(),rawBudget.emergencyToday(),rawBudget.verified()&&weekVerified,rawBudget.reservedFunds(),rawBudget.strategicReservedValues(),rawBudget.emergencyReservedValue(),rawBudget.reservedBondValue(),rawBudget.reservedFees());Map<String,Symbol> symbols=new TreeMap<>();
        for(String c:p.symbols()){
            var key=new PriceQueryService.PriceKey(c,"台股");var q=quotes.getOrDefault(key,Optional.empty()).orElse(null);var nav=navs.getOrDefault(key,Optional.empty()).orElse(null);
            boolean quoteIdentity=q!=null&&c.equals(q.stockCode())&&"台股".equals(q.market())&&Boolean.FALSE.equals(q.closed());
            boolean navIdentity=nav!=null&&c.equals(nav.stockCode())&&"台股".equals(nav.market())&&nav.nav()!=null&&nav.nav().signum()>0&&nav.source()!=null&&!nav.source().isBlank();
            Quote quote=!quoteIdentity?null:new Quote(q.price(),q.previousClose(),exactLots(q.volume()),localDate(q.tradingDate()),quoteTime(q.updatedAt()),q.source(),q.quoteStatus(),q.closed(),!navIdentity?null:nav.premiumDiscountPct(),!navIdentity?null:navDate(nav.navAsOf()));
            QuoteDetailDto.Response b=books.get(c);Book book=book(b,c);
            var detail=details.get(c);boolean exact=detail!=null&&detail.stock()!=null&&c.equals(detail.stock().stockCode())&&"台股".equals(detail.stock().market())&&TradingRadarRuleEngine.RULE_VERSION.equals(detail.ruleVersion())&&TradingRadarEvidenceGate.ACTION_POLICY_VERSION.equals(detail.actionPolicyVersion())&&detail.stock().dataComplete()&&detail.stock().action()!=null&&radarFresh(detail.generatedAt(),asOf,p.acquisitionMinutes());
            Evidence rad=new Evidence(exact?"PASS":"UNAVAILABLE",exact?"EXACT_RADAR_CURRENT_READ":"EXACT_RADAR_UNVERIFIED");
            List<Close> closes=hs.get(c).stream().filter(h->sessions.contains(h.getTradingDate())).map(h->new Close(h.getTradingDate(),h.getClosePrice(),prices.isTrustedClose(h))).toList();
            List<Distribution> distributions=ds.get(c).stream().filter(h->h.getExDividendDate()!=null&&h.getExDividendDate().isAfter(date.minusDays(100))&&!h.getExDividendDate().isAfter(date)||h.getExRightsDate()!=null&&h.getExRightsDate().isAfter(date.minusDays(100))&&!h.getExRightsDate().isAfter(date))
                    .map(h->new Distribution(h.getExDividendDate(),h.getExRightsDate(),h.getCashDividend(),h.getStockDividend(),h.getSource())).toList();
            boolean distributionVerified=p.nonDistributing().contains(c)||!ds.get(c).isEmpty()&&ds.get(c).stream().allMatch(h->h.getSource()!=null&&!h.getSource().isBlank());
            Evidence pending=new Evidence("UNAVAILABLE","00865B".equals(c)?"SUBACCOUNT_UNVERIFIED":"PENDING_ORDERS_UNAVAILABLE");
            Evidence position="00865B".equals(c)?subaccounts:new Evidence("PASS","CANONICAL_POSITION_OR_VERIFIED_SNAPSHOT_ZERO");
            Evidence tradability=new Evidence("UNAVAILABLE","TRADABILITY_SOURCE_UNAVAILABLE");
            var capture=captures.get(c);var confirmation=RadarIntradayCandlePolicy.evaluate("台股",TradingRadarRuleEngine.Horizon.MEDIUM,asOf,true,capture);
            symbols.put(c,new Symbol(c,quote,book,closes,distributions,distributionVerified,values.getOrDefault(c,BigDecimal.ZERO),position,pending,rad,new Evidence(valuation?"PASS":"UNAVAILABLE",valuation?"QUALIFIED_ASSET_VALUATIONS":"CAPACITY_VALUATION_UNVERIFIED"),tradability,exact?detail.stock().action():null,exact?detail.stock().candidateAction():null,new Evidence(confirmation.status(),confirmation.reason())));
            ObjectNode quoteSource=JsonNodeFactory.instance.objectNode();quoteSource.set("rawQuote",json(q));quoteSource.set("rawNav",json(nav));quoteSource.put("rawVolumeUnit","SHARES");quoteSource.put("normalizedVolumeUnit","LOTS");quoteSource.set("normalized",json(quote));
            source(sources,vector,"quote:"+c,quoteSource,q==null?"missing":Objects.toString(q.updatedAt(),"missing-time"),asOf,quote==null?"NONE":"COMPLETE",quote!=null&&DailyDecisionCalculator.quoteQualified(p,validationFrame(date,request.slot(),asOf),quote)?"VERIFIED":"UNAVAILABLE");
            source(sources,vector,"book:"+c,json(b),b==null?"missing":Objects.toString(b.sourceTime(),"missing-time"),asOf,b==null?"NONE":"COMPLETE",DailyDecisionCalculator.bookQualified(p,validationFrame(date,request.slot(),asOf),book)?"VERIFIED":"UNAVAILABLE");
            source(sources,vector,"radar:"+c,json(detail),detail==null?"missing":Objects.toString(detail.generatedAt(),"missing-time"),asOf,exact?"COMPLETE":"NONE",exact?"VERIFIED":"UNAVAILABLE");
            source(sources,vector,"daily:"+c,json(hs.get(c)),"daily-through-"+date.minusDays(1),asOf,closes.size()==p.lookback()?"COMPLETE":"PARTIAL",closes.size()==p.lookback()&&closes.stream().allMatch(v->v.trusted()&&v.price()!=null&&v.price().signum()>0)?"VERIFIED":"UNAVAILABLE");
            source(sources,vector,"dividends:"+c,json(ds.get(c)),"events-through-"+date,asOf,distributionVerified?"COMPLETE":"NONE",distributionVerified?"VERIFIED":"UNAVAILABLE");
            source(sources,vector,"pending:"+c,json(pending),"not-published",asOf,"NONE","UNAVAILABLE");
            source(sources,vector,"tradability:"+c,json(tradability),"not-published",asOf,"NONE","UNAVAILABLE");
            ObjectNode minute=JsonNodeFactory.instance.objectNode();minute.set("capture",json(capture));minute.set("confirmation",json(confirmation));
            source(sources,vector,"minute:"+c,minute,capture==null?"missing":Objects.toString(capture.capturedAt(),"missing-time"),asOf,capture==null?"NONE":"COMPLETE",capture==null?"UNAVAILABLE":capture.status());
        }
        boolean fxValid=fxQualified(rate,date,asOf);
        Input facts=new Input(date,request.slot(),asOf,sessions,a.liveAssets().liveTotalAssets(),total,term,bond,rate==null?null:rate.sellRate(),new Evidence(fxValid?"PASS":"UNAVAILABLE",fxValid?"CANONICAL_USD_TWD_CURRENT":"USD_TWD_SOURCE_UNAVAILABLE"),bear,Map.copyOf(symbols),budget,subaccounts,null);
        input.set("facts",json(facts));return new Frozen(facts,input,vector,a.snapshot().id(),a.generatedAt().toString());
    }
    private List<LocalDate> previousSessions(LocalDate date,int n){List<LocalDate> dates=new ArrayList<>();for(int i=1;i<=100&&dates.size()<n;i++){LocalDate d=date.minusDays(i);Optional<Boolean>known=market.isTwTradingDayCachedOnly(d);if(known.isEmpty())return List.of();if(known.get())dates.add(d);}Collections.reverse(dates);return List.copyOf(dates);}
    private static void validateAssets(LatestAssetsDto.Response a){
        if(a==null||a.generatedAt()==null||a.generatedAt().isAfter(Instant.now())||a.snapshot()==null||a.liveAssets()==null||!Objects.equals(a.snapshot().id(),a.liveAssets().snapshotId())||a.snapshot().id()==null||a.snapshot().stocks()==null||a.snapshot().deposits()==null||a.snapshot().funds()==null||a.liveAssets().stocks()==null||a.liveAssets().liveTotalAssets()==null||a.liveAssets().liveTotalAssets().signum()<=0)throw new IllegalArgumentException();
        BigDecimal deposits=BigDecimal.ZERO,funds=BigDecimal.ZERO,stocks=BigDecimal.ZERO,live=BigDecimal.ZERO;Set<Long>ids=new HashSet<>();
        for(var d:a.snapshot().deposits()){if(d.id()==null||d.amount()==null||d.originalAmount()==null||d.currency()==null||d.depositType()==null||d.bankId()==null||!ids.add(d.id()))throw new IllegalArgumentException();if(d.amount().signum()<0&&!"TRANSIT_TWD".equals(d.depositType()))throw new IllegalArgumentException();deposits=deposits.add(d.amount());}
        Set<Long>fundIds=new HashSet<>();for(var f:a.snapshot().funds()){if(f.id()==null||!fundIds.add(f.id())||f.currentValue()==null||f.currentValue().signum()<0)throw new IllegalArgumentException();funds=funds.add(f.currentValue());}
        Set<Long>holdings=new HashSet<>();for(var s:a.snapshot().stocks()){if(s.id()==null||s.shares()==null||s.shares().signum()<0||s.currentValue()==null||s.currentValue().signum()<0||!holdings.add(s.id()))throw new IllegalArgumentException();stocks=stocks.add(s.currentValue());}
        Map<Long,AssetSnapshotDto.StockResponse> identity=new HashMap<>();a.snapshot().stocks().forEach(h->identity.put(h.id(),h));
        Set<Long>liveIds=new HashSet<>();for(var s:a.liveAssets().stocks()){if(s.holdingId()==null||s.shares()==null||s.liveValue()==null||s.liveValue().signum()<0||!liveIds.add(s.holdingId()))throw new IllegalArgumentException();var h=identity.get(s.holdingId());if(h==null||!Objects.equals(h.stockCode(),s.stockCode())||!Objects.equals(h.market(),s.market())||h.shares().compareTo(s.shares())!=0)throw new IllegalArgumentException();live=live.add(s.liveValue());}
        if(!holdings.equals(liveIds)||!equal(deposits,a.snapshot().totalDeposit())||!equal(deposits,a.liveAssets().totalDeposit())||!equal(funds,a.snapshot().totalFundValue())||!equal(funds,a.liveAssets().totalFundValue())||!equal(stocks,a.snapshot().totalStockValue())||!equal(live,a.liveAssets().liveStockValue())||!equal(deposits.add(funds).add(live),a.liveAssets().liveTotalAssets()))throw new IllegalArgumentException();
    }
    static boolean fxQualified(UsdTwdLiveRateService.LiveRate rate,LocalDate date,Instant asOf){return rate!=null&&date.equals(rate.date())&&Set.of("LIVE","INDICATIVE").contains(Objects.toString(rate.quoteStatus(),""))&&rate.sellRate()!=null&&rate.sellRate().signum()>0&&rate.polledAt()!=null&&!rate.polledAt().isAfter(asOf)&&UsdTwdLiveRateService.isFresh(rate.polledAt(),asOf)&&(rate.sourceUpdatedAt()==null||!rate.sourceUpdatedAt().isAfter(asOf));}
    private static boolean radarFresh(String at,Instant asOf,int minutes){try{Instant t=Instant.parse(at);return !t.isAfter(asOf)&&Duration.between(t,asOf).compareTo(Duration.ofMinutes(minutes))<=0;}catch(RuntimeException invalid){return false;}}
    private static Input validationFrame(LocalDate date,String slot,Instant at){return new Input(date,slot,at,List.of(),BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,null,null,null,Map.of(),new Budget(0,0,0,false,BigDecimal.ZERO,Map.of(),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO),null,null);}
    static Long exactLots(Long shares){return shares!=null&&shares>0&&shares%1000==0?shares/1000:null;}
    private static boolean equal(BigDecimal a,BigDecimal b){return b!=null&&a.subtract(b).abs().compareTo(new BigDecimal("0.01"))<=0;}
    private static Book book(QuoteDetailDto.Response b,String code){boolean valid=b!=null&&b.available()&&code.equals(b.stockCode())&&"台股".equals(b.market())&&"OPEN".equals(b.marketStatus());List<Level>levels=new ArrayList<>();if(b!=null&&b.levels()!=null){int i=0;for(var l:b.levels()){valid&=l.level()==++i&&l.bidVolumeLots()!=null&&l.askVolumeLots()!=null;levels.add(new Level(l.bidPrice(),l.bidVolumeLots()==null?-1:l.bidVolumeLots(),l.askPrice(),l.askVolumeLots()==null?-1:l.askVolumeLots()));}}return new Book(b==null?null:b.source(),b==null||b.sourceTime()==null?null:b.sourceTime().atZone(ZoneId.of("Asia/Taipei")).toLocalDate(),b==null?null:b.sourceTime(),b==null?null:b.fetchedAt(),List.copyOf(levels),valid);}
    static Budget budget(Policy p,LocalDate date,ArrayNode ledger,ArrayNode reservations){int today=0,week=0,emergency=0;boolean verified=true;BigDecimal reserved=BigDecimal.ZERO,emergencyReserved=BigDecimal.ZERO,bondReserved=BigDecimal.ZERO,feesReserved=BigDecimal.ZERO;Map<String,BigDecimal> strategicReserved=new TreeMap<>();
        for(JsonNode row:ledger)if(p.symbols().contains(row.path("symbol").asText())&&"台股".equals(row.path("market").asText())&&"買".equals(row.path("direction").asText())){try{BigDecimal shares=new BigDecimal(row.path("shares").asText());if(shares.signum()<0)throw new IllegalArgumentException();int lots=shares.divide(new BigDecimal("1000"),0,RoundingMode.CEILING).intValueExact();String channel=row.path("channel").asText();boolean same=date.toString().equals(row.path("date").asText());if("國泰證券".equals(channel)&&"00865B".equals(row.path("symbol").asText())){if(same)emergency+=lots;}else if("富邦證券".equals(channel)){week+=lots;if(same)today+=lots;}else verified=false;}catch(RuntimeException e){verified=false;}}
        // No linkage exists in today's source schema: do not infer that a fill consumes a particular old suggestion.
        for(JsonNode run:reservations){boolean same=date.toString().equals(run.path("identity").path("tradingDate").asText());for(JsonNode c:run.path("decision").path("candidates")){int lots=c.path("lots").asInt();if("STRATEGIC_FUBON".equals(c.path("strategy").asText())){week+=lots;if(same)today+=lots;}else if(same)emergency+=lots;if(same&&lots>0){BigDecimal amount=new BigDecimal(c.path("amountTwd").asText());BigDecimal fee=amount.multiply(p.feeRate()).setScale(2,RoundingMode.CEILING);reserved=reserved.add(amount.add(fee));feesReserved=feesReserved.add(fee);bondReserved=bondReserved.add(amount);if("STRATEGIC_FUBON".equals(c.path("strategy").asText()))strategicReserved.merge(c.path("symbol").asText(),amount,BigDecimal::add);else emergencyReserved=emergencyReserved.add(amount);}}}
        return new Budget(today,week,emergency,verified,reserved,strategicReserved,emergencyReserved,bondReserved,feesReserved);
    }
    private JsonNode json(Object o){return normalize(mapper.valueToTree(o));}
    static JsonNode normalize(JsonNode n){if(n==null||n.isNull())return NullNode.instance;if(n.isFloatingPointNumber()||n.isBigDecimal())return TextNode.valueOf(n.decimalValue().stripTrailingZeros().toPlainString());if(n.isObject()){ObjectNode o=JsonNodeFactory.instance.objectNode();n.fields().forEachRemaining(e->o.set(e.getKey(),normalize(e.getValue())));return o;}if(n.isArray()){ArrayNode a=JsonNodeFactory.instance.arrayNode();n.forEach(v->a.add(normalize(v)));return a;}return n;}
    private static void source(ObjectNode sources,ObjectNode vector,String id,JsonNode body,String revision,Instant at,String coverage,String validation){sources.set(id,body);ObjectNode receipt=vector.putObject(id);receipt.put("sourceId",id);receipt.put("revision",revision);receipt.put("capturedAt",at.toString());receipt.put("sha256",SrppJcs.hash(body));receipt.put("coverage",coverage);receipt.put("validation",validation);}
    private static LocalDate localDate(String d){try{return LocalDate.parse(d);}catch(RuntimeException e){return null;}}
    private static Instant quoteTime(String s){try{return Instant.parse(s);}catch(RuntimeException e){try{return LocalDateTime.parse(s).atZone(ZoneId.of("Asia/Taipei")).toInstant();}catch(RuntimeException ex){return null;}}}
    private static LocalDate navDate(String s){try{return LocalDate.parse(s.substring(0,8),DateTimeFormatter.BASIC_ISO_DATE);}catch(RuntimeException e){try{return LocalDate.parse(s);}catch(RuntimeException ex){return null;}}}
}
