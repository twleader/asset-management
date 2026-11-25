package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.service.srpp.decision.DecisionInputCapturePort.Frozen;
import com.steven.assets.service.srpp.decision.DecisionFacts.Policy;
import com.steven.assets.srpp.SrppJcs;
import java.math.*;
import java.time.*;
import java.util.*;
import static com.steven.assets.service.srpp.report.SrppReportModuleCalculator.*;

/** D226 named zero-target exit ceiling from frozen originals. No IO, order, or LLM timing adoption. */
public final class SrppNamedRadarSellCalculator {
    public static final String STRATEGY="NAMED_RADAR_ROTATION_D226";
    public static final String HIGH_STRATEGY="NAMED_HIGH_ZONE_EXIT_D235";
    public static final String HIGH_GATE="D235_HIGH_ZONE_PATH";
    private static final Set<String> HIGH_ACTIONS=Set.of("HOLD","HOLD_CAUTION","EXIT_CANDIDATE","REDUCE_CANDIDATE");
    private static final BigDecimal HIGH_THRESHOLD=new BigDecimal("0.95");
    public static final String SYMBOL="2881";
    public static final String SUBACCOUNT="CORE_D226";
    public static final String PLAN_ID="D223_00881_CASH_2881_EXIT_20261118";
    public static final String D144_FALLBACK="D144_OFFICIAL_SOURCE_UNAVAILABLE_BEST_LEVEL_LIMIT_ONLY";
    public static final Set<String> REQUIRED=Set.of("D226_SELL_PATH","QUOTE","RADAR","POSITION","PENDING","RESERVATIONS","DEDUPLICATION","CORE_RETENTION","D152_BATCH","COOLDOWN","DEPTH","EXECUTION_ACCOUNT","COSTS","TRADABILITY");
    private static final List<String> ORDERED=List.of("D226_SELL_PATH","QUOTE","RADAR","POSITION","PENDING","RESERVATIONS","DEDUPLICATION","CORE_RETENTION","D152_BATCH","COOLDOWN","DEPTH","EXECUTION_ACCOUNT","COSTS","TRADABILITY");
    private static final Set<String> SELL_ACTIONS=Set.of("EXIT_CANDIDATE","REDUCE_CANDIDATE");
    private static final JsonNodeFactory F=JsonNodeFactory.instance;
    private static final BigDecimal ZERO=BigDecimal.ZERO,ONE=BigDecimal.ONE,LOT=new BigDecimal("1000");
    private SrppNamedRadarSellCalculator(){}
    private static final class Gap extends IllegalArgumentException { Gap(String code){super(code);} }
    private static void require(boolean valid,String reason){if(!valid)throw new Gap(reason);}
    private static BigDecimal number(JsonNode value){try{require(value!=null&&!value.isNull()&&!value.isBoolean()&&(value.isNumber()||value.isTextual()),"D226_NUMERIC_SOURCE_UNAVAILABLE");return new BigDecimal(value.asText());}catch(NumberFormatException invalid){throw new Gap("D226_NUMERIC_SOURCE_UNAVAILABLE");}}
    private static BigDecimal nonnegative(JsonNode value){BigDecimal n=number(value);require(n.signum()>=0,"D226_NEGATIVE_SOURCE");return n;}
    private static BigDecimal positive(JsonNode value){BigDecimal n=number(value);require(n.signum()>0,"D226_POSITIVE_SOURCE_UNAVAILABLE");return n;}
    private static long whole(JsonNode value){try{long n=nonnegative(value).longValueExact();require(n<=9007199254740991L,"D226_UNSAFE_SHARE_COUNT");return n;}catch(ArithmeticException invalid){throw new Gap("D226_WHOLE_SHARES_UNVERIFIED");}}
    private static String decimal(BigDecimal value){return value.signum()==0?"0":value.stripTrailingZeros().toPlainString();}
    private static LocalDate date(JsonNode value){try{String text=value.asText();LocalDate d=LocalDate.parse(text);require(d.toString().equals(text),"D226_DATE_UNVERIFIED");return d;}catch(DateTimeException invalid){throw new Gap("D226_DATE_UNVERIFIED");}}
    private static Instant instant(JsonNode value){try{return OffsetDateTime.parse(value.asText()).toInstant();}catch(DateTimeException invalid){throw new Gap("D226_SOURCE_TIME_UNVERIFIED");}}
    private static String text(JsonNode row,String key){JsonNode value=row.path(key);require(value.isTextual()&&!value.asText().isBlank(),"D226_SOURCE_IDENTITY_UNAVAILABLE:"+key);return value.asText();}
    private static String id(JsonNode value){require((value.isIntegralNumber()||value.isTextual())&&value.asText().matches("[1-9][0-9]*"),"D226_HOLDING_OR_BROKER_ID_UNVERIFIED");return value.asText();}
    public static List<String> refs(String rule){
        if(Set.of("QUOTE","RADAR","DEPTH",HIGH_GATE).contains(rule))return List.of("policy","market:台股:2881");
        if(rule.equals("TRADABILITY"))return List.of("policy","market:台股:2881","topup-state");
        return List.of("assets","policy","topup-state","report-reservations","classifications","market:台股:2881");
    }
    private interface Check {void run();}
    private static void gate(M module,ObjectNode leg,String rule,Check check){
        String status="PASS",reason=HIGH_GATE.equals(rule)?"D235_HIGH_ZONE_SOURCE_VERIFIED":"D226_"+rule+"_SOURCE_VERIFIED";
        try{check.run();}catch(IllegalArgumentException|ArithmeticException invalid){reason=invalid.getMessage()==null?"D226_SOURCE_UNVERIFIED":invalid.getMessage();status=unavailable(reason)?"UNAVAILABLE":"BLOCK";((ArrayNode)leg.path("blockingReasons")).add(reason);if(status.equals("UNAVAILABLE"))module.missing(reason+":2881");}
        module.refs.addAll(refs(rule));((ArrayNode)leg.path("receipts")).add(receipt(rule,status,reason,refs(rule)));
    }
    private static boolean unavailable(String reason){return reason.contains("UNAVAILABLE")||reason.contains("UNVERIFIED")||reason.contains("STALE");}
    private static JsonNode source(Frozen f,String key){
        JsonNode raw=f.input().path("sources").path(key),v=f.sourceVector().path(key);
        require(raw.isObject()&&key.equals(v.path("sourceId").asText())&&"VERIFIED".equals(v.path("validation").asText())&&SrppJcs.hash(raw).equals(v.path("sha256").asText()),"D226_SOURCE_UNAVAILABLE:"+key);
        require(!instant(v.path("capturedAt")).isAfter(f.facts().capturedAt()),"D226_SOURCE_TIME_UNVERIFIED");return raw;
    }
    private static JsonNode owner(Frozen f,Policy p,String key){JsonNode s=source(f,key);require(ownerSource(s,f,p.bundleHash())&&"COMPLETE".equals(s.path("coverage").asText()),"D226_OFFICIAL_SOURCE_UNAVAILABLE:"+key);return s;}
    private record Account(String brokerId,String broker,String fingerprint,JsonNode costs){}
    private record Holding(String id,String brokerId,String broker,long shares,BigDecimal value){}
    private record Plan(long baseline,BigDecimal openingValue,int batches,LocalDate lastFill){}
    private record Capacity(long held,long settled,long brokerSellable,long pending,long reserved,long available){}
    private record Costs(BigDecimal brokerage,BigDecimal minimum,BigDecimal tax){}
    private record Book(JsonNode levels,boolean odd,long depth,BigDecimal best){}

    /** Scalar port of execution_policy.sell_shares; external capacities have already been independently verified. */
    static long sellQuantity(long holdings,long capacity,long baseline,int confirmedBatches,BigDecimal baselineValue){
        require(holdings>=0&&capacity>=0&&baseline>=0&&confirmedBatches>=0,"D226_D152_INPUT_UNVERIFIED");
        boolean small=baseline<=5000&&baselineValue!=null&&baselineValue.compareTo(new BigDecimal("1000000"))<0;
        boolean finalBatch=small||confirmedBatches>=2;long step=!small&&baseline<3000?1:1000;
        long tranche=Math.max((baseline/3/step)*step,step),requested=finalBatch?holdings:tranche,available=Math.min(holdings,capacity);
        if(finalBatch&&available>=holdings)return holdings;
        return Math.min(requested,available)/step*step;
    }
    static long highZoneQuantity(long holdings,long capacity,long baseline,int batches,BigDecimal value){
        if(batches!=0)return sellQuantity(holdings,capacity,baseline,batches,value);
        require(holdings>1&&baseline>1,"D235_FIRST_BATCH_MUST_RETAIN_SHARES");
        long step=baseline<3000?1:1000;
        long quantity=Math.min(Math.min(holdings,capacity),Math.max(baseline/3/step*step,step))/step*step;
        require(quantity<holdings,"D235_FIRST_BATCH_MUST_RETAIN_SHARES");return quantity;
    }
    private static boolean validHighPolicy(JsonNode cfg){
        try{
            require(cfg.isObject()&&cfg.path("enabled").isBoolean()&&cfg.path("enabled").asBoolean()&&"D-235".equals(cfg.path("decision").asText())&&SYMBOL.equals(cfg.path("symbol").asText())&&"台股".equals(cfg.path("market").asText()),"D235_POLICY_UNVERIFIED");
            require(number(cfg.path("minimum_week52_position_inclusive")).compareTo(HIGH_THRESHOLD)==0,"D235_POLICY_UNVERIFIED");date(cfg.path("effective_date"));
            for(String flag:List.of("require_distribution_adjusted","first_batch_disallow_small_clear","pause_outside_high_zone","requires_llm_adoption"))require(cfg.path(flag).isBoolean()&&cfg.path(flag).asBoolean(),"D235_POLICY_UNVERIFIED");
            Set<String> actions=new HashSet<>();require(cfg.path("radar_actions").isArray(),"D235_POLICY_UNVERIFIED");for(JsonNode a:cfg.path("radar_actions"))require(a.isTextual()&&actions.add(a.asText()),"D235_POLICY_UNVERIFIED");return actions.equals(HIGH_ACTIONS);
        }catch(IllegalArgumentException invalid){return false;}
    }
    private static boolean highRoute(Frozen f,JsonNode model){
        JsonNode cfg=model.path("execution_policy").path("named_high_zone_exit_d235");
        if("D-235".equals(model.path("decision").asText())&&!validHighPolicy(cfg))return true;
        if(cfg.isMissingNode()||cfg.path("enabled").isBoolean()&&!cfg.path("enabled").asBoolean())return false;
        try{if(f.facts().date().isBefore(date(cfg.path("effective_date"))))return false;}catch(IllegalArgumentException invalid){return true;}
        JsonNode stock=f.input().path("sources").path("market:台股:2881").path("radar").path("stock");
        if(Set.of("HOLD","HOLD_CAUTION").contains(stock.path("action").asText()))return true;
        try{return stock.path("distributionAdjusted").isBoolean()&&stock.path("distributionAdjusted").asBoolean()&&number(stock.path("week52Position")).compareTo(HIGH_THRESHOLD)>=0&&number(stock.path("week52Position")).compareTo(ONE)<=0;}catch(IllegalArgumentException invalid){return false;}
    }
    private static final class Data {
        final Frozen frame;final Policy policy;final JsonNode model;
        Data(Frozen f,Policy p,JsonNode m){frame=f;policy=p;model=m;}
        JsonNode state(){return owner(frame,policy,"topup-state");}
        JsonNode market(){return source(frame,"market:台股:2881");}
        JsonNode assets(){JsonNode a=source(frame,"assets");require(a.path("targetPriceComplete").asBoolean()&&a.path("snapshot").path("id").equals(a.path("liveAssets").path("snapshotId")),"D226_ASSETS_UNVERIFIED");Instant generated=instant(a.path("generatedAt"));require(!generated.isAfter(frame.facts().capturedAt())&&generated.atZone(ZoneId.of("Asia/Taipei")).toLocalDate().equals(frame.facts().date()),"D226_ASSET_TIME_UNVERIFIED");return a;}
        void path(){JsonNode c=model.path("execution_policy").path("named_cash_rotation_d223");Set<String> actions=new HashSet<>();for(JsonNode a:c.path("sell_radar_actions"))require(a.isTextual()&&actions.add(a.asText()),"D226_SELL_POLICY_UNVERIFIED");require(c.path("enabled").isBoolean()&&c.path("enabled").asBoolean()&&"D-226".equals(c.path("entry_rule_decision").asText())&&SYMBOL.equals(c.path("sell_symbol").asText())&&PLAN_ID.equals(c.path("plan_id").asText())&&c.path("sell_requires_llm_adoption").isBoolean()&&c.path("sell_requires_llm_adoption").asBoolean()&&actions.equals(SELL_ACTIONS)&&!c.has("sell_price_exclusive_lower_twd")&&!frame.facts().date().isBefore(date(c.path("effective_date"))),"D226_SELL_POLICY_UNVERIFIED");}
        BigDecimal highZone(){
            path();JsonNode c=model.path("execution_policy").path("named_high_zone_exit_d235");
            require(validHighPolicy(c),"D235_POLICY_UNVERIFIED");
            require(!frame.facts().date().isBefore(date(c.path("effective_date"))),"D235_POLICY_NOT_EFFECTIVE");
            JsonNode body=market();verifyExactRadar(body,frame);JsonNode stock=body.path("radar").path("stock");
            require(stock.path("distributionAdjusted").isBoolean()&&stock.path("distributionAdjusted").asBoolean(),"D235_DISTRIBUTION_ADJUSTMENT_UNVERIFIED");
            BigDecimal position=number(stock.path("week52Position"));require(position.signum()>=0&&position.compareTo(ONE)<=0,"D235_POSITION_UNVERIFIED");
            require(position.compareTo(HIGH_THRESHOLD)>=0,"D235_OUTSIDE_HIGH_ZONE_PAUSED");return position;
        }
        Account account(){JsonNode a=state().path("executionAccounts").path(SUBACCOUNT);require(a.path("verified").isBoolean()&&a.path("verified").asBoolean()&&a.path("ownerUserId").asLong(-1)==frame.input().path("ownerUserId").asLong()&&SUBACCOUNT.equals(a.path("subaccount").asText()),"D226_EXECUTION_ACCOUNT_UNAVAILABLE");return new Account(id(a.path("brokerId")),text(a,"broker"),text(a,"accountFingerprint"),a.path("costs"));}
        Map<String,Holding> holdings(){
            JsonNode a=assets(),snapshot=a.path("snapshot"),live=a.path("liveAssets");require(snapshot.path("stocks").isArray()&&live.path("stocks").isArray(),"D226_HOLDINGS_UNAVAILABLE");Map<String,JsonNode> old=new HashMap<>();for(JsonNode row:snapshot.path("stocks"))require(old.put(id(row.path("id")),row)==null,"D226_DUPLICATE_HOLDING");Map<String,Holding> selected=new TreeMap<>();Set<String> seen=new HashSet<>();
            for(JsonNode row:live.path("stocks")){String key=id(row.path("holdingId"));JsonNode prior=old.get(key);require(prior!=null&&seen.add(key)&&row.path("stockCode").equals(prior.path("stockCode"))&&row.path("market").equals(prior.path("market"))&&nonnegative(row.path("shares")).compareTo(nonnegative(prior.path("shares")))==0,"D226_HOLDINGS_IDENTITY_UNVERIFIED");if(!SYMBOL.equals(row.path("stockCode").asText())||!"台股".equals(row.path("market").asText()))continue;require(frame.facts().date().toString().equals(row.path("tradingDate").asText()),"D226_HOLDING_DATE_UNVERIFIED");selected.put(key,new Holding(key,id(prior.path("brokerId")),text(prior,"brokerDisplayName"),whole(row.path("shares")),nonnegative(row.path("liveValue"))));}
            require(seen.equals(old.keySet()),"D226_HOLDINGS_IDENTITY_UNVERIFIED");require(!selected.isEmpty(),"D226_NO_2881_HOLDING");return selected;
        }
        List<Holding> own(){Account a=account();List<Holding> rows=holdings().values().stream().filter(h->h.brokerId.equals(a.brokerId)).toList();require(!rows.isEmpty(),"D226_ACCOUNT_HOLDING_UNAVAILABLE");for(Holding h:rows)require(h.broker.equals(a.broker),"D226_ACCOUNT_CUSTODY_MISMATCH");return rows;}
        void position(){Account a=account();Map<String,Holding> own=new TreeMap<>();own().forEach(h->own.put(h.id,h));JsonNode all=state().path("sellablePositions");require(all.path("verified").isBoolean()&&all.path("verified").asBoolean()&&"COMPLETE".equals(all.path("coverage").asText())&&all.path("rows").isArray(),"D226_OFFICIAL_SELLABLE_POSITION_UNAVAILABLE");Set<String> seen=new HashSet<>();for(JsonNode row:all.path("rows")){String key=id(row.path("holdingId"));require(seen.add(key),"D226_DUPLICATE_OFFICIAL_POSITION");if(!own.containsKey(key))continue;Holding h=own.get(key);require(SYMBOL.equals(row.path("symbol").asText())&&"台股".equals(row.path("market").asText())&&a.brokerId.equals(id(row.path("brokerId")))&&a.fingerprint.equals(text(row,"accountFingerprint"))&&!text(row,"source_ref").isBlank(),"D226_OFFICIAL_POSITION_IDENTITY_UNVERIFIED");long settled=whole(row.path("settledShares")),sellable=whole(row.path("sellableShares"));require(settled<=h.shares&&sellable<=settled,"D226_OFFICIAL_POSITION_OVERSELL");}require(seen.containsAll(own.keySet()),"D226_OFFICIAL_SELLABLE_POSITION_UNAVAILABLE");}
        JsonNode orders(){JsonNode p=state().path("pending");require(p.path("verified").isBoolean()&&p.path("verified").asBoolean()&&"COMPLETE".equals(p.path("coverage").asText())&&p.path("orders").isArray(),"D226_OFFICIAL_PENDING_UNAVAILABLE");Set<String> ids=new HashSet<>();for(JsonNode row:p.path("orders")){require(ids.add(text(row,"orderId"))&&Set.of("BUY","SELL").contains(row.path("side").asText())&&Set.of("台股","美股").contains(row.path("market").asText())&&!text(row,"symbol").isBlank(),"D226_PENDING_IDENTITY_UNVERIFIED");nonnegative(row.path("remainingShares"));if(SYMBOL.equals(row.path("symbol").asText())&&"台股".equals(row.path("market").asText())){whole(row.path("remainingShares"));text(row,"accountFingerprint");}}return p.path("orders");}
        JsonNode reservations(){JsonNode r=owner(frame,policy,"report-reservations");require(r.path("rows").isArray(),"D226_REPORT_RESERVATIONS_UNAVAILABLE");Set<String> ids=new HashSet<>();for(JsonNode row:r.path("rows")){require(ids.add(text(row,"reservationId"))&&Set.of("BUY","SELL").contains(row.path("action").asText()),"D226_RESERVATION_IDENTITY_UNVERIFIED");if("SELL".equals(row.path("action").asText())&&"ACTIVE".equals(row.path("status").asText())&&SYMBOL.equals(row.path("symbol").asText())&&"台股".equals(row.path("market").asText()))reservedShares(row);}return r.path("rows");}
        long reservedShares(JsonNode row){boolean units=row.hasNonNull("units"),lots=row.hasNonNull("lots");require(units!=lots,"D226_RESERVED_QUANTITY_UNVERIFIED");return units?whole(row.path("units")):Math.multiplyExact(whole(row.path("lots")),1000);}
        Capacity capacity(){position();Account account=account();long held=0,settled=0,brokerSellable=0,pending=0,reserved=0;Set<String> ownIds=new HashSet<>();for(Holding h:own()){held=Math.addExact(held,h.shares);ownIds.add(h.id);}for(JsonNode r:state().path("sellablePositions").path("rows"))if(ownIds.contains(r.path("holdingId").asText())){settled=Math.addExact(settled,whole(r.path("settledShares")));brokerSellable=Math.addExact(brokerSellable,whole(r.path("sellableShares")));}
            Map<String,JsonNode> official=new HashMap<>();for(JsonNode row:orders())if("SELL".equals(row.path("side").asText())&&SYMBOL.equals(row.path("symbol").asText())&&"台股".equals(row.path("market").asText())&&account.fingerprint.equals(row.path("accountFingerprint").asText())){official.put(row.path("orderId").asText(),row);pending=Math.addExact(pending,whole(row.path("remainingShares")));}
            Set<String> linked=new HashSet<>();for(JsonNode row:reservations()){if(!"ACTIVE".equals(row.path("status").asText())||!"SELL".equals(row.path("action").asText())||!SYMBOL.equals(row.path("symbol").asText())||!"台股".equals(row.path("market").asText()))continue;long shares=reservedShares(row);if(row.hasNonNull("linkedOrderId")&&official.containsKey(row.path("linkedOrderId").asText())){String link=row.path("linkedOrderId").asText();require(linked.add(link)&&shares<=whole(official.get(link).path("remainingShares")),"D226_LINKED_RESERVATION_UNVERIFIED");}else reserved=Math.addExact(reserved,shares);}
            require(pending<=settled&&reserved<=settled-pending,"D226_ALREADY_COMMITTED_SELL_EXCEEDS_HOLDINGS");long available=Math.min(brokerSellable,settled-pending-reserved);return new Capacity(held,settled,brokerSellable,pending,reserved,available);
        }
        void retention(){source(frame,"classifications");require("STOCK".equals(frame.input().path("sources").path("classifications").path("resolvedStockClasses").path("2881:台股").asText()),"D226_STOCK_CLASSIFICATION_UNAVAILABLE");boolean zero=false;for(JsonNode code:model.path("zero_target_do_not_rebuy").path("zero_target"))zero|=SYMBOL.equals(code.asText());JsonNode target=model.path("detailed_target_allocation_2027").get(SYMBOL);require(zero&&(target==null||!target.isNull()&&number(target).signum()==0),"D226_ZERO_TARGET_CORE_RETENTION_UNVERIFIED");holdings();}
        Plan plan(){JsonNode plan=state().path("rotationExit").path(SYMBOL);require(plan.path("verified").isBoolean()&&plan.path("verified").asBoolean()&&PLAN_ID.equals(plan.path("planId").asText())&&!text(plan,"source_ref").isBlank(),"D226_D152_PLAN_UNAVAILABLE");JsonNode baseline=plan.path("baselineSnapshot");id(baseline.path("id"));LocalDate start=date(baseline.path("snapshotDate"));require(!start.isAfter(frame.facts().date())&&baseline.path("stocks").isArray(),"D226_D152_BASELINE_UNVERIFIED");Account account=account();long opening=0;BigDecimal value=ZERO;boolean valueKnown=true;Set<String> ids=new HashSet<>();for(JsonNode row:baseline.path("stocks")){require(ids.add(id(row.path("id"))),"D226_D152_DUPLICATE_BASELINE_HOLDING");if(!SYMBOL.equals(row.path("stockCode").asText())||!"台股".equals(row.path("market").asText())||!account.brokerId.equals(id(row.path("brokerId"))))continue;opening=Math.addExact(opening,whole(row.path("shares")));if(!row.hasNonNull("currentValue"))valueKnown=false;else value=value.add(nonnegative(row.path("currentValue")));}require(opening>0,"D226_D152_BASELINE_UNAVAILABLE");
            JsonNode fills=plan.path("fills");require(fills.path("verified").isBoolean()&&fills.path("verified").asBoolean()&&"COMPLETE".equals(fills.path("coverage").asText())&&fills.path("rows").isArray(),"D226_D152_CONFIRMED_FILLS_UNAVAILABLE");Set<String> fillIds=new HashSet<>(),batches=new HashSet<>();long sold=0;LocalDate last=null;for(JsonNode row:fills.path("rows")){require(fillIds.add(text(row,"fillId"))&&row.path("confirmed").isBoolean()&&row.path("confirmed").asBoolean()&&SYMBOL.equals(row.path("symbol").asText())&&"台股".equals(row.path("market").asText())&&"SELL".equals(row.path("side").asText())&&PLAN_ID.equals(row.path("planId").asText())&&account.brokerId.equals(id(row.path("brokerId")))&&account.fingerprint.equals(text(row,"accountFingerprint")),"D226_D152_FILL_IDENTITY_UNVERIFIED");LocalDate day=date(row.path("tradeDate"));require(!day.isBefore(start)&&!day.isAfter(frame.facts().date()),"D226_D152_FILL_DATE_UNVERIFIED");long quantity=whole(row.path("shares"));require(quantity>0,"D226_D152_ZERO_FILL");sold=Math.addExact(sold,quantity);batches.add(text(row,"batchId"));if(last==null||day.isAfter(last))last=day;}
            long held=0;for(Holding h:own())held=Math.addExact(held,h.shares);require(sold<=opening&&opening-sold==held,"D226_D152_BASELINE_FILL_HOLDING_MISMATCH");return new Plan(opening,valueKnown?value:null,batches.size(),last);
        }
        void batch(){JsonNode cfg=model.path("execution_policy").path("zero_target_liquidation_d152");require(cfg.path("minimum_batches").asInt(-1)==3&&cfg.path("large_share_threshold_lots").asInt(-1)==5&&number(cfg.path("large_market_value_twd")).compareTo(new BigDecimal("1000000"))==0,"D226_D152_POLICY_UNVERIFIED");plan();}
        void cooldown(){
            JsonNode market=market();require(market.path("calendarVerified").asBoolean()&&market.path("completedSessions").isArray(),"D226_COOLDOWN_CALENDAR_UNAVAILABLE");
            List<LocalDate> completed=new ArrayList<>();LocalDate previous=null;for(JsonNode raw:market.path("completedSessions")){LocalDate day=date(raw);require(day.isBefore(frame.facts().date())&&(previous==null||day.isAfter(previous)),"D226_COOLDOWN_CALENDAR_UNVERIFIED");completed.add(day);previous=day;}
            require(model.path("simulation_contract").path("transition_cooldown_sessions").isIntegralNumber()&&model.path("simulation_contract").path("transition_cooldown_sessions").asInt()==5,"D226_COOLDOWN_POLICY_UNVERIFIED");require(completed.size()>=5,"D226_COOLDOWN_CALENDAR_UNAVAILABLE");
            JsonNode raw=state().path("rotationCooldown").path(SYMBOL);require(raw.path("verified").isBoolean()&&raw.path("verified").asBoolean()&&"COMPLETE".equals(raw.path("coverage").asText())&&"OWNER_ALL_BROKERS_SAME_SYMBOL_DIRECTION".equals(raw.path("scope").asText())&&SYMBOL.equals(raw.path("symbol").asText())&&"台股".equals(raw.path("market").asText())&&"SELL".equals(raw.path("side").asText())&&raw.path("rows").isArray()&&!text(raw,"source_ref").isBlank(),"D226_COOLDOWN_SOURCE_UNAVAILABLE");
            LocalDate start=date(raw.path("coverageStart")),end=date(raw.path("coverageEnd"));require(!start.isAfter(completed.get(completed.size()-5))&&end.equals(frame.facts().date()),"D226_COOLDOWN_COVERAGE_UNVERIFIED");
            Map<String,JsonNode> observed=new HashMap<>();LocalDate last=null;for(JsonNode fill:raw.path("rows")){require(observed.put(text(fill,"fillId"),fill)==null&&fill.path("confirmed").isBoolean()&&fill.path("confirmed").asBoolean()&&SYMBOL.equals(fill.path("symbol").asText())&&"台股".equals(fill.path("market").asText())&&"SELL".equals(fill.path("side").asText()),"D226_COOLDOWN_FILL_IDENTITY_UNVERIFIED");id(fill.path("brokerId"));require(whole(fill.path("shares"))>0,"D226_COOLDOWN_ZERO_FILL");LocalDate day=date(fill.path("tradeDate"));require(!day.isBefore(start)&&!day.isAfter(end),"D226_COOLDOWN_FILL_DATE_UNVERIFIED");if(last==null||day.isAfter(last))last=day;}
            JsonNode planFills=state().path("rotationExit").path(SYMBOL).path("fills");if(planFills.path("verified").asBoolean()&&"COMPLETE".equals(planFills.path("coverage").asText())&&planFills.path("rows").isArray())for(JsonNode fill:planFills.path("rows")){LocalDate day=date(fill.path("tradeDate"));if(day.isBefore(start)||day.isAfter(end))continue;JsonNode original=observed.get(fill.path("fillId").asText());require(original!=null&&original.path("tradeDate").equals(fill.path("tradeDate"))&&whole(original.path("shares"))==whole(fill.path("shares"))&&id(original.path("brokerId")).equals(id(fill.path("brokerId"))),"D226_COOLDOWN_FILL_COVERAGE_UNVERIFIED");}
            int since=0;if(last!=null)for(LocalDate day:completed)if(day.isAfter(last))since++;require(last==null||since>=5,"D226_CONFIRMED_SELL_FIVE_SESSION_COOLDOWN");
        }
        Costs costs(){JsonNode c=account().costs;require(c.path("verified").isBoolean()&&c.path("verified").asBoolean()&&!text(c,"source_ref").isBlank(),"D226_EXECUTION_COSTS_UNAVAILABLE");BigDecimal fee=nonnegative(c.path("brokerageRate")),minimum=nonnegative(c.path("minimumFeeTwd")),tax=positive(c.path("sellTaxRate"));require(fee.compareTo(ONE)<0&&tax.compareTo(ONE)<0,"D226_EXECUTION_COST_DOMAIN_UNVERIFIED");JsonNode formal=model.path("execution_policy").path("tactical_profit_policy");require(formal.hasNonNull("stock_sell_tax")&&formal.path("stock_sell_tax_source").isTextual()&&!formal.path("stock_sell_tax_source").asText().isBlank(),"D226_STOCK_TAX_POLICY_UNAVAILABLE");require(tax.compareTo(positive(formal.path("stock_sell_tax")))==0,"D226_STOCK_SELL_TAX_AUTHORITY_MISMATCH");return new Costs(fee,minimum,tax);}
        Book book(boolean odd){JsonNode b=market().path(odd?"oddLotBook":"book");require(b.isObject()&&b.path("available").isBoolean()&&b.path("available").asBoolean()&&SYMBOL.equals(b.path("stockCode").asText())&&"台股".equals(b.path("market").asText())&&"OPEN".equals(b.path("marketStatus").asText())&&Set.of("FUBON_BOOKS","YAHOO_TW").contains(b.path("source").asText())&&b.path("levels").isArray()&&b.path("levels").size()==5,odd?"D226_ODD_LOT_BOOK_UNAVAILABLE":"D226_REGULAR_BOOK_UNAVAILABLE");if(odd)require("ODD_LOT".equals(b.path("bookType").asText())&&"SHARES".equals(b.path("volumeUnit").asText()),"D226_ODD_LOT_MARKET_UNVERIFIED");for(String key:List.of("sourceTime","fetchedAt")){Instant at=instant(b.path(key));require(!at.isAfter(frame.facts().capturedAt())&&at.atZone(ZoneId.of("Asia/Taipei")).toLocalDate().equals(frame.facts().date())&&Duration.between(at,frame.facts().capturedAt()).compareTo(Duration.ofMinutes(policy.limitMinutes()))<=0,"D226_SELL_BOOK_STALE");}BigDecimal previous=null,best=null;long depth=0;for(JsonNode row:b.path("levels")){BigDecimal bid=positive(row.path("bidPrice"));require((previous==null||bid.compareTo(previous)<0)&&bid.remainder(stockTick(bid)).signum()==0,"D226_SELL_BOOK_PRICE_UNVERIFIED");long shares=whole(row.path(odd?"bidVolumeShares":"bidVolumeLots"));if(!odd)shares=Math.multiplyExact(shares,1000);depth=Math.addExact(depth,shares);if(best==null)best=bid;previous=bid;}require(depth>0,"D226_SELL_BOOK_EMPTY");return new Book(b.path("levels"),odd,depth,best);}
        void depth(){JsonNode m=market();if(m.path("book").path("available").asBoolean())book(false);else book(true);}
        boolean officialTradability(){JsonNode t=state().path("tradability").path(SYMBOL);return t.path("verified").isBoolean()&&t.path("verified").asBoolean()&&"PASS".equals(t.path("status").asText());}
        boolean banned(){JsonNode t=state().path("tradability").path(SYMBOL);return t.path("verified").isBoolean()&&t.path("verified").asBoolean()&&Set.of("BLOCK","BLOCKED","FAIL","DENIED").contains(t.path("status").asText());}
    }
    private static BigDecimal stockTick(BigDecimal price){if(price.compareTo(new BigDecimal("10"))<0)return new BigDecimal("0.01");if(price.compareTo(new BigDecimal("50"))<0)return new BigDecimal("0.05");if(price.compareTo(new BigDecimal("100"))<0)return new BigDecimal("0.1");if(price.compareTo(new BigDecimal("500"))<0)return new BigDecimal("0.5");if(price.compareTo(new BigDecimal("1000"))<0)return ONE;return new BigDecimal("5");}
    private static void values(ObjectNode leg,String rule,List<ObjectNode> values){for(JsonNode r:leg.path("receipts"))if(rule.equals(r.path("ruleId").asText())){ArrayNode a=((ObjectNode)r).putArray("values");values.forEach(a::add);}}
    private static void revoke(ObjectNode leg,M module,String rule,String reason){((ArrayNode)leg.path("blockingReasons")).add(reason);if(unavailable(reason))module.missing(reason+":2881");for(JsonNode r:leg.path("receipts"))if(rule.equals(r.path("ruleId").asText()))((ObjectNode)r).put("status",unavailable(reason)?"UNAVAILABLE":"BLOCK").put("reason",reason);}
    public static void append(M module,Frozen f,Policy p,JsonNode model){
        JsonNode cfg=model.path("execution_policy").path("named_cash_rotation_d223");if(!"D-226".equals(cfg.path("entry_rule_decision").asText())||!cfg.has("sell_radar_actions"))return;
        boolean high=highRoute(f,model);Data data=new Data(f,p,model);ObjectNode leg=leg(SYMBOL,"台股",high?HIGH_STRATEGY:STRATEGY,"BLOCKED","SELL",null);leg.put("subaccount",SUBACCOUNT);
        for(String original:ORDERED){
            String rule=high&&original.equals("D226_SELL_PATH")?HIGH_GATE:original;
            if(rule.equals("TRADABILITY")){
                try{if(data.banned()){gate(module,leg,rule,()->{throw new Gap("D226_OFFICIAL_SELL_PROHIBITED");});continue;}if(data.officialTradability()){gate(module,leg,rule,()->{});continue;}}catch(IllegalArgumentException invalid){/* The original D144 missing-source rule remains L2. */}
                module.refs.addAll(refs(rule));module.missing(D144_FALLBACK+":2881");((ArrayNode)leg.path("receipts")).add(receipt(rule,"UNAVAILABLE",D144_FALLBACK,refs(rule)));continue;
            }
            gate(module,leg,rule,()->{switch(rule){
                case "D226_SELL_PATH" -> data.path();
                case HIGH_GATE -> data.highZone();
                case "QUOTE" -> quoteFreshness(data.market(),f,model,true);
                case "RADAR" -> {JsonNode body=data.market();verifyExactRadar(body,f);require((high?HIGH_ACTIONS:SELL_ACTIONS).contains(body.path("radar").path("stock").path("action").asText()),"D226_EXACT_RADAR_NOT_SELL");}
                case "POSITION" -> data.position();
                case "PENDING" -> data.orders();
                case "RESERVATIONS" -> data.reservations();
                case "DEDUPLICATION" -> data.capacity();
                case "CORE_RETENTION" -> data.retention();
                case "D152_BATCH" -> data.batch();
                case "COOLDOWN" -> data.cooldown();
                case "DEPTH" -> data.depth();
                case "EXECUTION_ACCOUNT" -> leg.put("broker",data.account().broker);
                case "COSTS" -> data.costs();
                default -> throw new Gap("D226_UNSUPPORTED_SELL_GATE");
            }});
        }
        if(high)for(JsonNode r:leg.path("receipts"))if(HIGH_GATE.equals(r.path("ruleId").asText())&&"PASS".equals(r.path("status").asText()))values(leg,HIGH_GATE,List.of(fact("d235Week52Position",SYMBOL,data.highZone(),"RATIO"),fact("d235Threshold",SYMBOL,HIGH_THRESHOLD,"RATIO"),fact("d235DistributionAdjusted",SYMBOL,ONE,"BOOLEAN")));
        if(leg.path("blockingReasons").isEmpty()){
            try{
                Capacity cap=data.capacity();Plan plan=data.plan();long desired=high?highZoneQuantity(cap.held,cap.available,plan.baseline,plan.batches,plan.openingValue):sellQuantity(cap.held,cap.available,plan.baseline,plan.batches,plan.openingValue);require(desired>0,"D226_NO_UNCOMMITTED_SELL_CAPACITY");boolean odd=desired<1000;Book book=data.book(odd);long capacity=Math.min(cap.available,book.depth);if(!odd)capacity=Math.min(capacity,desired/1000*1000);long quantity=high?highZoneQuantity(cap.held,capacity,plan.baseline,plan.batches,plan.openingValue):sellQuantity(cap.held,capacity,plan.baseline,plan.batches,plan.openingValue);require(quantity>0,"D226_NO_MATCHING_MARKET_DEPTH");if(odd)require(quantity<1000,"D226_ODD_LOT_QUANTITY_UNVERIFIED");else require(quantity%1000==0,"D226_REGULAR_LOT_QUANTITY_UNVERIFIED");
                long depth=0;BigDecimal limit=null;for(JsonNode row:book.levels){long shares=whole(row.path(odd?"bidVolumeShares":"bidVolumeLots"));depth=Math.addExact(depth,odd?shares:Math.multiplyExact(shares,1000));limit=positive(row.path("bidPrice"));if(depth>=quantity)break;}require(depth>=quantity,"D226_NO_MATCHING_MARKET_DEPTH");boolean official=data.officialTradability();if(!official)limit=book.best;
                Costs costs=data.costs();BigDecimal gross=limit.multiply(BigDecimal.valueOf(quantity)),commission=gross.multiply(costs.brokerage).setScale(2,RoundingMode.CEILING).max(costs.minimum),tax=gross.multiply(costs.tax).setScale(2,RoundingMode.CEILING),fees=commission.add(tax),net=gross.subtract(fees);require(net.signum()>0,"D226_COSTS_EXCEED_SELL_PROCEEDS");
                leg.put("status","ACTIONABLE_FOR_REPORT").put("units",Long.toString(quantity)).put("limitPrice",decimal(limit)).put("amount",decimal(gross)).put("fees",decimal(fees)).put("rankingReason",high?"D235_HIGH_ZONE_SELL_CEILING_LLM_TIMING_STILL_REQUIRED":"D226_NAMED_SELL_CEILING_LLM_TIMING_STILL_REQUIRED");
                values(leg,"POSITION",List.of(fact("settledShares",SYMBOL,BigDecimal.valueOf(cap.settled),"SHARES"),fact("officialSellableShares",SYMBOL,BigDecimal.valueOf(cap.brokerSellable),"SHARES"),fact("pendingSellShares",SYMBOL,BigDecimal.valueOf(cap.pending),"SHARES"),fact("unlinkedReservedSellShares",SYMBOL,BigDecimal.valueOf(cap.reserved),"SHARES")));
                values(leg,"D152_BATCH",List.of(fact("baselineShares",SYMBOL,BigDecimal.valueOf(plan.baseline),"SHARES"),fact("confirmedBatches",SYMBOL,BigDecimal.valueOf(plan.batches),"COUNT"),fact("openingValueTwd",SYMBOL,plan.openingValue,"TWD")));
                if(high)for(JsonNode r:leg.path("receipts"))if("D152_BATCH".equals(r.path("ruleId").asText()))((ArrayNode)r.path("values")).add(fact("firstBatchSmallClearAllowed",SYMBOL,ZERO,"BOOLEAN"));
                values(leg,"COSTS",List.of(fact("sellCommissionTwd",SYMBOL,commission,"TWD"),fact("sellTaxTwd",SYMBOL,tax,"TWD"),fact("netProceedsTwd",SYMBOL,net,"TWD")));
            }catch(IllegalArgumentException|ArithmeticException invalid){String reason=invalid.getMessage()==null?"D226_SELL_CAPACITY_UNAVAILABLE":invalid.getMessage();String rule=reason.contains("BOOK")||reason.contains("DEPTH")||reason.contains("LOT")?"DEPTH":reason.contains("COST")?"COSTS":"POSITION";revoke(leg,module,rule,reason);}
        }
        module.legs.add(leg);
    }
}
