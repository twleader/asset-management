package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.steven.assets.service.UsdFxPurchaseContext;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.service.RadarCompletedPriceDrawdown;
import com.steven.assets.service.srpp.decision.DecisionInputCapturePort.Frozen;
import com.steven.assets.service.srpp.decision.DecisionFacts.Policy;
import com.steven.assets.srpp.*;
import java.math.*;
import java.time.*;
import java.util.*;
import static com.steven.assets.service.srpp.report.SrppReportModuleCalculator.*;

/** Frozen-source D215 review ceilings. Selection and execution are separate; no IO or risk adoption. */
public final class SrppReportMediumDipCalculator {
    public static final String PLAN_STRATEGY="NAMED_CASH_BUY_D226";
    public static final String STRATEGY="NAMED_DEEP_DRAWDOWN_D215";
    /** D-215/D-226 Taiwan broker fee: amount x feeRate rounded up to the cent (Task 626 re-sizing reuses it). */
    static BigDecimal fee(Policy p,BigDecimal amount){return amount.multiply(p.feeRate()).setScale(2,RoundingMode.CEILING);}
    public static final Set<String> REQUIRED=Set.of("D215_NAMED_PATH","QUOTE","RADAR","STRATEGIC_PRICE","ALLOCATION","FUNDING","PENDING","RESERVATIONS","DEDUPLICATION","DEPTH","EXECUTION_ACCOUNT","THESIS","TRADABILITY");
    public static final List<String> TARGET_REFS=List.of("assets","policy","classifications","fx","market:美股:VOO","market:台股:0050","market:台股:00881");
    private static final JsonNodeFactory F=JsonNodeFactory.instance;
    private static final ObjectMapper MAPPER=new ObjectMapper().registerModule(new JavaTimeModule());
    private static final BigDecimal Z=BigDecimal.ZERO,O=BigDecimal.ONE,K=new BigDecimal("1000");
    private static final Set<String> BUY=Set.of("BUY_CANDIDATE","ADD_CANDIDATE");
    private static final Set<String> KNOWN=Set.of("BUY_CANDIDATE","ADD_CANDIDATE","HOLD","HOLD_CAUTION","WATCH","WAIT","NO_TRADE","AVOID","REDUCE_CANDIDATE","EXIT_CANDIDATE");
    private SrppReportMediumDipCalculator(){}
    private static final class Gap extends IllegalArgumentException { Gap(String code){super(code);} }
    private static void require(boolean condition,String code){if(!condition)throw new Gap(code);}
    private static BigDecimal n(JsonNode v){try{require(v!=null&&!v.isNull()&&!v.isBoolean()&&(v.isNumber()||v.isTextual()),"D215_NUMERIC_SOURCE_UNAVAILABLE");return new BigDecimal(v.asText());}catch(NumberFormatException bad){throw new Gap("D215_NUMERIC_SOURCE_UNAVAILABLE");}}
    private static BigDecimal positive(JsonNode v){BigDecimal value=n(v);require(value.signum()>0,"D215_POSITIVE_SOURCE_UNAVAILABLE");return value;}
    private static BigDecimal nonnegative(JsonNode v){BigDecimal value=n(v);require(value.signum()>=0,"D215_NEGATIVE_SOURCE");return value;}
    private static String dec(BigDecimal v){return v.signum()==0?"0":v.stripTrailingZeros().toPlainString();}
    private static JsonNode source(Frozen f,String id){JsonNode raw=f.input().path("sources").path(id),v=f.sourceVector().path(id);require(raw.isObject()&&"VERIFIED".equals(v.path("validation").asText())&&id.equals(v.path("sourceId").asText())&&SrppJcs.hash(raw).equals(v.path("sha256").asText()),"D215_SOURCE_UNAVAILABLE:"+id);return raw;}
    private static JsonNode market(Frozen f,String code){return source(f,"market:"+(code.equals("VOO")?"美股":"台股")+":"+code);}
    private static JsonNode owner(Frozen f,String id,Policy p){JsonNode s=source(f,id);require(ownerSource(s,f,p.bundleHash())&&"COMPLETE".equals(s.path("coverage").asText()),"D215_OFFICIAL_SOURCE_UNAVAILABLE:"+id);return s;}
    private static Set<String> strings(JsonNode a){Set<String> out=new HashSet<>();for(JsonNode v:a)out.add(v.asText());return out;}
    public record Targets(SrppReserveAllocation.Projection projection,String reason,String selected,BigDecimal adjustment,Map<String,BigDecimal[]> ranges) { public boolean ready(){return "TARGETS_READY".equals(projection.status());} }

    /** Replay market originals, not a client-created PASS receipt. No final order gate is a selection input. */
    public static Targets targets(Frozen f,JsonNode model){
        SrppReserveAllocation.Projection baseline=SrppReserveAllocation.project(model,f.facts().totalAssets(),f.facts().date());
        if("TARGETS_READY".equals(baseline.status()))return new Targets(baseline,"D222_NO_MARKET_ADJUSTMENT",null,Z,Map.of());
        Map<String,BigDecimal[]> ranges=new TreeMap<>();
        try{
            JsonNode cfg=model.path("execution_policy").path("strategic_buy_high_gate_d188");int count=cfg.path("position_sessions").asInt();
            require(count>0,"D222_RANGE_POLICY_UNAVAILABLE");Set<String> non=strings(cfg.path("non_distributing_symbols"));
            boolean buying=baseline.delta().signum()>0;Map<String,BigDecimal> eligible=new TreeMap<>();List<String> gaps=new ArrayList<>();
            for(String code:List.of("VOO","0050","00881")){try{
                JsonNode body=market(f,code),q=body.path("quote"),stock=body.path("radar").path("stock");
                require(code.equals(q.path("stockCode").asText())&&code.equals(stock.path("stockCode").asText())&&body.path("market").equals(q.path("market"))&&body.path("market").equals(stock.path("market"))&&stock.path("dataComplete").asBoolean()&&SrppRadarPolicy.supportsVersion(body.path("radar")),"D222_DIRECTION_SOURCE_UNAVAILABLE:"+code);
                JsonNode adjusted=adjustedBody(body);List<Row> highs=adjustedWindow(adjusted,f.facts().date(),count,Set.of(code),"highPrice"),lows=adjustedWindow(adjusted,f.facts().date(),count,Set.of(code),"lowPrice");
                BigDecimal hi=highs.stream().map(Row::value).max(BigDecimal::compareTo).orElseThrow(),lo=lows.stream().map(Row::value).min(BigDecimal::compareTo).orElseThrow(),price=positive(q.get("price"));
                require(hi.compareTo(lo)>0,"D222_RANGE_UNVERIFIED:"+code);
                require(price.compareTo(lo)>=0&&price.compareTo(hi)<=0,"D222_RANGE_UNVERIFIED:"+code);BigDecimal position=price.subtract(lo).divide(hi.subtract(lo),MathContext.DECIMAL128);ranges.put(code,new BigDecimal[]{lo,hi,position});
                String action=stock.path("action").asText();require(KNOWN.contains(action),"D222_DIRECTION_SOURCE_UNAVAILABLE:"+code);
                if(!code.equals("VOO"))quoteFreshness(body,f,model,false);
                else {
                    List<String> completed=new ArrayList<>();body.path("completedSessions").forEach(d->completed.add(d.asText()));
                    require(!completed.isEmpty()&&q.path("tradingDate").asText().equals(completed.getLast())&&stock.path("asOfDate").asText().equals(completed.getLast())&&Set.of("LIVE","VERIFIED_CLOSE").contains(q.path("quoteStatus").asText()),"D222_US_COMPLETED_SESSION_UNAVAILABLE");
                }
                // Target reductions do not authorize selling now or require an executable SELL/KD reversal.
                boolean direction=buying?BUY.contains(action):!BUY.contains(action);
                if(buying&&code.equals("VOO")){
                    source(f,"fx");require(f.facts().fxEvidence().verified()&&f.facts().fx()!=null,"D222_FX_SOURCE_UNAVAILABLE");
                    UsdFxPurchaseContext fx;try{fx=MAPPER.treeToValue(body.path("usdFxPurchase"),UsdFxPurchaseContext.class);}catch(Exception invalid){throw new Gap("D222_FX_SELECTION_CONTEXT_UNAVAILABLE");}
                    require(fx!=null&&fx.evaluated()&&fx.typeVerified()&&fx.applies()&&"VOO".equals(fx.stockCode())&&"美股".equals(fx.market())&&fx.data().currentRate()!=null&&fx.data().currentRate().compareTo(f.facts().fx())==0&&"LIVE".equals(fx.data().quality()),"D222_FX_SELECTION_CONTEXT_UNAVAILABLE");
                    if(fx.preference()!=UsdFxPurchaseContext.Preference.NORMAL){
                        List<StockPriceHistory> priceRows=new ArrayList<>();require("AVAILABLE".equals(body.path("priceOnlyBasisStatus").asText())&&"STORED_PRICE_ONLY".equals(body.path("priceOnlyBasis").asText()),"D222_FX_PRICE_EXCEPTION_BASIS_UNAVAILABLE");
                        for(JsonNode raw:body.path("priceOnlyDaily")){StockPriceHistory row=new StockPriceHistory();row.setStockCode("VOO");row.setMarket("美股");row.setTradingDate(LocalDate.parse(raw.path("tradingDate").asText()));row.setHighPrice(positive(raw.get("highPrice")));row.setClosePrice(positive(raw.get("closePrice")));require(raw.path("trusted").asBoolean(),"D222_FX_PRICE_EXCEPTION_BASIS_UNAVAILABLE");priceRows.add(row);}priceRows.sort(Comparator.comparing(StockPriceHistory::getTradingDate).reversed());
                        var fact=RadarCompletedPriceDrawdown.evaluate("VOO","美股",priceRows,0,price,RadarCompletedPriceDrawdown.PriceBasis.STORED_PRICE_ONLY,Set.of(),false);direction&=fx.priceException(fact,price);
                    }
                }
                if(direction)eligible.put(code,position);
            }catch(IllegalArgumentException missing){gaps.add(missing.getMessage()+":"+code);}}
            require(gaps.isEmpty(),String.join(";",gaps));
            require(!eligible.isEmpty(),"D222_NO_ELIGIBLE_TARGET_SELECTION");
            BigDecimal extreme=eligible.values().stream().reduce(buying?BigDecimal::min:BigDecimal::max).orElseThrow();
            List<String> selected=eligible.keySet().stream().filter(c->eligible.get(c).subtract(extreme).abs().compareTo(new BigDecimal("0.000000000001"))<=0).toList();
            require(selected.size()==1,"D222_AMBIGUOUS_TARGET_SELECTION");String code=selected.getFirst();BigDecimal amount=baseline.delta().abs();
            var projection=SrppReserveAllocation.project(model,f.facts().totalAssets(),f.facts().date(),buying?null:Map.of(code,amount),buying?Map.of(code,amount):null);
            return new Targets(projection,"D222_SOURCE_VERIFIED_MARKET_TARGETS_TRANSACTION_GATES_SEPARATE",code,amount,ranges);
        }catch(IllegalArgumentException missing){return new Targets(baseline,missing.getMessage(),null,Z,ranges);}
    }

    public static ObjectNode projectedModel(Frozen f,JsonNode model){
        ObjectNode copy=SrppReserveAllocation.projectedModel(model,f.facts().totalAssets(),f.facts().date());if(!SrppReserveAllocation.enabled(model))return copy;
        Targets t=targets(f,model);if(t.ready()){ObjectNode weights=copy.putObject("detailed_target_allocation_2027");t.projection().weights().forEach((c,w)->weights.put(c,w));copy.put("d222_projection_status","TARGETS_READY");}return copy;
    }
    public static List<String> refs(String rule,String code){
        if(rule.equals("ALLOCATION"))return TARGET_REFS;
        if(rule.equals("THESIS"))return List.of("policy","market:台股:"+code,"classifications");
        if(rule.equals("TRADABILITY"))return List.of("policy","market:台股:"+code,"topup-state");
        if(Set.of("D215_NAMED_PATH","D226_BUY_PATH","QUOTE","RADAR","STRATEGIC_PRICE","DEPTH").contains(rule))return List.of("policy","market:台股:"+code);
        return List.of("assets","policy","topup-state","report-reservations","classifications","market:台股:"+code);
    }
    private interface Check { void run(); }
    private static void gate(M module,ObjectNode leg,String code,String rule,Check check){
        String status="PASS",reason="D215_"+rule+"_SOURCE_VERIFIED";
        try{check.run();}catch(IllegalArgumentException|ArithmeticException bad){reason=bad.getMessage()==null?"D215_SOURCE_UNVERIFIED":bad.getMessage();status=reason.contains("UNAVAILABLE")||reason.contains("UNVERIFIED")||reason.contains("STALE")?"UNAVAILABLE":"BLOCK";((ArrayNode)leg.path("blockingReasons")).add(reason);if(status.equals("UNAVAILABLE"))module.missing(reason+":"+code);}
        List<String> inputs=refs(rule,code);module.refs.addAll(inputs);((ArrayNode)leg.path("receipts")).add(receipt(rule,status,reason,inputs));
    }
    private static void quote(Frozen f,JsonNode model,String code){quoteFreshness(market(f,code),f,model,true);}
    private static void named(Frozen f,JsonNode model,String code){
        JsonNode p=model.path("execution_policy").path("named_deep_drawdown_accumulation_d215");
        require(p.path("enabled").isBoolean()&&p.path("enabled").asBoolean()&&"D-215".equals(p.path("decision").asText())&&"MEDIUM".equals(p.path("horizon").asText())&&strings(p.path("tw_symbols")).equals(Set.of("0050","00881"))&&n(p.get("tw_drawdown_fraction")).compareTo(new BigDecimal(".2"))==0,"D215_POLICY_UNAVAILABLE");
        JsonNode body=market(f,code);List<StockPriceHistory> rows=new ArrayList<>();
        require("AVAILABLE".equals(body.path("priceOnlyBasisStatus").asText())&&"TW_SPLIT_STOCK_PRICE".equals(body.path("priceOnlyBasis").asText())&&body.path("priceOnlyDaily").isArray(),"D215_SPLIT_PRICE_BASIS_UNAVAILABLE");
        for(JsonNode r:body.path("priceOnlyDaily")){require(r.path("trusted").asBoolean(),"D215_PRICE_HISTORY_UNVERIFIED");StockPriceHistory row=new StockPriceHistory();row.setStockCode(code);row.setMarket("台股");row.setTradingDate(LocalDate.parse(r.path("tradingDate").asText()));require(row.getTradingDate().isBefore(f.facts().date()),"D215_FUTURE_COMPLETED_PRICE");row.setHighPrice(positive(r.get("highPrice")));row.setClosePrice(positive(r.get("closePrice")));rows.add(row);}
        rows.sort(Comparator.comparing(StockPriceHistory::getTradingDate).reversed());
        var fact=RadarCompletedPriceDrawdown.evaluate(code,"台股",rows,0,positive(body.path("quote").get("price")),RadarCompletedPriceDrawdown.PriceBasis.TW_SPLIT_STOCK_PRICE,Set.of(),false);
        require(fact.status()==RadarCompletedPriceDrawdown.Status.AVAILABLE,"D215_PRICE_WINDOW_UNAVAILABLE");
        require(body.path("calendarVerified").asBoolean()&&!body.path("completedSessions").isEmpty()&&fact.completedAsOfDate().toString().equals(body.path("completedSessions").get(body.path("completedSessions").size()-1).asText()),"D215_COMPLETED_SESSION_UNVERIFIED");
        require(fact.eligible(positive(body.path("quote").get("price"))),"D215_NAMED_DRAWDOWN_NOT_REACHED");
    }
    private static JsonNode pending(Frozen f,Policy p){JsonNode s=owner(f,"topup-state",p),pending=s.path("pending");require(pending.path("verified").asBoolean()&&"COMPLETE".equals(pending.path("coverage").asText())&&pending.path("orders").isArray(),"D215_OFFICIAL_PENDING_UNAVAILABLE");validateRows(pending.path("orders"),true);return pending.path("orders");}
    private static JsonNode reservations(Frozen f,Policy p){JsonNode s=owner(f,"report-reservations",p);require(s.path("rows").isArray(),"D215_RESERVATIONS_UNAVAILABLE");validateRows(s.path("rows"),false);return s.path("rows");}
    private static void validateRows(JsonNode rows,boolean pending){Set<String> ids=new HashSet<>();for(JsonNode r:rows){String id=r.path(pending?"orderId":"reservationId").asText();require(!id.isBlank()&&ids.add(id),"D215_DUPLICATE_OR_MISSING_COMMITMENT_ID");String action=r.path(pending?"side":"action").asText();require(Set.of("BUY","SELL").contains(action)&&Set.of("台股","美股").contains(r.path("market").asText()),"D215_COMMITMENT_IDENTITY_UNAVAILABLE");if(!action.equals("BUY"))continue;if(!pending&&!"ACTIVE".equals(r.path("status").asText()))continue;require(Set.of("TWD","USD").contains(r.path("currency").asText()),"D215_COMMITMENT_CURRENCY_UNAVAILABLE");if(pending){nonnegative(r.get("remainingShares"));positive(r.get("limitPrice"));nonnegative(r.get("remainingFees"));}else{nonnegative(r.get("amount"));nonnegative(r.get("fees"));}}}
    private record Commitments(BigDecimal symbol,BigDecimal equity,BigDecimal fees){}
    private static Commitments commitments(Frozen f,Policy p,String code){BigDecimal symbol=Z,equity=Z,fees=Z;JsonNode res=reservations(f,p),orders=pending(f,p);Set<String> linked=new HashSet<>();for(JsonNode r:res)if("ACTIVE".equals(r.path("status").asText())&&r.hasNonNull("linkedOrderId"))linked.add(r.path("linkedOrderId").asText());List<JsonNode> all=new ArrayList<>();res.forEach(r->{if("ACTIVE".equals(r.path("status").asText()))all.add(r);});orders.forEach(r->{if(!linked.contains(r.path("orderId").asText()))all.add(r);});JsonNode classes=source(f,"classifications").path("resolvedStockClasses");
        for(JsonNode r:all){boolean order=r.has("orderId");if(!"BUY".equals(r.path(order?"side":"action").asText()))continue;BigDecimal amount=order?nonnegative(r.get("remainingShares")).multiply(positive(r.get("limitPrice"))):nonnegative(r.get("amount")),fee=nonnegative(r.get(order?"remainingFees":"fees"));BigDecimal fx="TWD".equals(r.path("currency").asText())?O:f.facts().fx();require(fx!=null&&fx.signum()>0,"D215_COMMITMENT_FX_UNAVAILABLE");fees=fees.add(fee.multiply(fx));String cls=classes.path(r.path("symbol").asText()+":"+r.path("market").asText()).asText();require(Set.of("STOCK","BOND","CASH").contains(cls),"D215_COMMITMENT_CLASSIFICATION_UNAVAILABLE");if(cls.equals("STOCK"))equity=equity.add(amount.multiply(fx));if(code.equals(r.path("symbol").asText())&&"台股".equals(r.path("market").asText()))symbol=symbol.add(amount);}
        return new Commitments(symbol,equity,fees);
    }
    private static BigDecimal value(JsonNode module,String name,String subject){for(JsonNode fact:module.path("data").path("facts"))if(name.equals(fact.path("name").asText())&&subject.equals(fact.path("subject").asText())&&"EXACT".equals(fact.path("quality").asText()))return nonnegative(fact.get("value"));throw new Gap("D215_FUNDING_SOURCE_UNAVAILABLE:"+name);}
    public static void append(M module,Frozen f,Policy p,JsonNode model,JsonNode funding,ArrayNode priorModules){
        appendPath(module,f,p,model,funding,priorModules,false);
    }
    public static void appendNamedCashPlan(M module,Frozen f,Policy p,JsonNode model,JsonNode funding,ArrayNode priorModules){appendPath(module,f,p,model,funding,priorModules,true);}
    private static void appendPath(M module,Frozen f,Policy p,JsonNode model,JsonNode funding,ArrayNode priorModules,boolean plan){
        JsonNode policy=model.path("execution_policy").path(plan?"named_cash_rotation_d223":"named_deep_drawdown_accumulation_d215");if(!policy.path("enabled").asBoolean())return;
        String accountName=plan?"CORE_D226_BUY":"CORE_D215";
        ObjectNode budget=plan?SrppNamedCashBuyBudget.evaluate(policy,f.input().path("sources").path("topup-state").path("namedCashRotation"),f.input().path("sources").path("topup-state").path("pending"),f.input().path("sources").path("report-reservations"),f.facts().date()):F.objectNode();
        Targets selected=targets(f,model);BigDecimal currentDebit=Z,currentFees=Z,currentEquity=Z;
        List<JsonNode> existing=new ArrayList<>();priorModules.forEach(existing::add);if(plan){ObjectNode same=F.objectNode();same.putObject("data").set("legs",module.legs.deepCopy());existing.add(same);}
        for(JsonNode prior:existing)for(JsonNode l:prior.path("data").path("legs"))if("ACTIONABLE_FOR_REPORT".equals(l.path("status").asText())&&"BUY".equals(l.path("action").asText())){
            BigDecimal factor="TWD".equals(l.path("currency").asText())?O:f.facts().fx();if(factor==null)continue;
            BigDecimal amount=n(l.get("amount")).multiply(factor),fee=n(l.get("fees")).multiply(factor);currentFees=currentFees.add(fee);
            if("TWD".equals(l.path("currency").asText()))currentDebit=currentDebit.add(amount).add(fee);
            String cls=f.input().path("sources").path("classifications").path("resolvedStockClasses").path(l.path("symbol").asText()+":"+l.path("market").asText()).asText();if("STOCK".equals(cls))currentEquity=currentEquity.add(amount);
        }
        for(String code:plan?List.of("00881"):List.of("0050","00881")){
            ObjectNode leg=leg(code,"台股",plan?PLAN_STRATEGY:STRATEGY,"BLOCKED","BUY",0);leg.put("broker","國泰證券").put("subaccount",accountName);
            gate(module,leg,code,plan?"D226_BUY_PATH":"D215_NAMED_PATH",()->{if(plan){require(SrppNamedCashRotation.active(model,f.facts().date())&&SrppNamedCashRotation.radarEntry(model),"D226_BUY_PATH_INACTIVE");require("00881".equals(policy.path("buy_symbol").asText()),"D226_BUY_SYMBOL_MISMATCH");}else named(f,model,code);});
            if(plan){gate(module,leg,code,"D223_PLAN_BUDGET",()->{owner(f,"topup-state",p);reservations(f,p);require("PASS".equals(budget.path("status").asText()),budget.path("reason").asText());});for(JsonNode receipt:leg.path("receipts"))if("D223_PLAN_BUDGET".equals(receipt.path("ruleId").asText())){ArrayNode values=(ArrayNode)receipt.path("values");for(String k:List.of("usedTwd","committedTwd","remainingTwd"))values.add(fact(k,"D223",budget.path(k).isNull()||budget.path(k).isMissingNode()?null:n(budget.path(k)),"TWD"));for(JsonNode e:budget.path("accountingEntries"))values.add(fact("planEntryDebitTwd",e.path("id").asText(),n(e.path("cash_debit_twd")),"TWD"));}}
            gate(module,leg,code,"QUOTE",()->quote(f,model,code));
            gate(module,leg,code,"RADAR",()->{JsonNode body=market(f,code);verifyExactRadar(body,f);JsonNode stock=body.path("radar").path("stock");require(plan?"ADD_CANDIDATE".equals(stock.path("action").asText()):BUY.contains(stock.path("action").asText()),"D215_RADAR_DIRECTION_BLOCK");});
            gate(module,leg,code,"STRATEGIC_PRICE",()->{JsonNode original=model.path("execution_policy").path("strategic_buy_high_gate_d188");if(plan&&!strings(original.path("price_position_symbols")).contains(code)&&!strings(original.path("ex_dividend_symbols")).contains(code))return;JsonNode gate=strategicGate(adjustedBody(market(f,code)),f.facts().date(),adjustedPricePolicy(model,code));require(gate.path("eligible").asBoolean(),gate.path("reason").asText());});
            gate(module,leg,code,"ALLOCATION",()->{require(selected.ready(),selected.reason());requireCurrentValuation(f,p);source(f,"classifications");source(f,"fx");require(selected.projection().weights().get(code)!=null,"D222_MARKET_ALLOCATION_REQUIRED");commitments(f,p,code);});
            gate(module,leg,code,"FUNDING",()->{value(funding,"deployableCapacity","TWD");require(f.facts().termDeposits().compareTo(p.termFloor().multiply(com.steven.assets.service.srpp.decision.DailyDecisionCalculator.inflation(p,f.facts().date())))>=0,"D215_PROTECTED_TERM_FLOOR");});
            gate(module,leg,code,"PENDING",()->pending(f,p));
            gate(module,leg,code,"RESERVATIONS",()->reservations(f,p));
            gate(module,leg,code,"DEDUPLICATION",()->{for(JsonNode r:pending(f,p))require(!(code.equals(r.path("symbol").asText())&&"台股".equals(r.path("market").asText())&&"BUY".equals(r.path("side").asText())&&nonnegative(r.get("remainingShares")).signum()>0),"D215_PENDING_BUY_ALREADY_PRESENT");for(JsonNode r:reservations(f,p))require(!(code.equals(r.path("symbol").asText())&&"台股".equals(r.path("market").asText())&&"BUY".equals(r.path("action").asText())&&"ACTIVE".equals(r.path("status").asText())),"D215_PRIOR_REPORT_BUY_ALREADY_RESERVED");});
            gate(module,leg,code,"DEPTH",()->{JsonNode body=market(f,code);verifyTopupBook(body,f,p.limitMinutes());require(body.path("book").path("levels").size()==5,"D215_BEST_FIVE_UNAVAILABLE");long lots=0;BigDecimal previous=Z;for(JsonNode level:body.path("book").path("levels")){BigDecimal ask=positive(level.get("askPrice"));require(ask.compareTo(previous)>0,"D215_BEST_FIVE_UNVERIFIED");lots+=nonnegative(level.get("askVolumeLots")).longValueExact();previous=ask;}require(lots>0&&lots<=Integer.MAX_VALUE,"D215_DEPTH_EMPTY_OR_TOO_LARGE");});
            gate(module,leg,code,"EXECUTION_ACCOUNT",()->{JsonNode account=owner(f,"topup-state",p).path("executionAccounts").path(accountName);require(account.path("verified").asBoolean()&&account.path("ownerUserId").asLong(-1)==f.input().path("ownerUserId").asLong()&&"國泰證券".equals(account.path("broker").asText())&&accountName.equals(account.path("subaccount").asText())&&!account.path("accountFingerprint").asText().isBlank(),"D215_EXECUTION_ACCOUNT_UNAVAILABLE");});
            gate(module,leg,code,"THESIS",()->{JsonNode body=market(f,code);source(f,"classifications");require("STOCK".equals(f.input().path("sources").path("classifications").path("resolvedStockClasses").path(code+":台股").asText())&&"EQUITY_ETF".equals(body.path("radar").path("stock").path("evidence").path("assetProfile").path("instrumentKind").asText()),"D215_NAMED_EQUITY_ETF_IDENTITY_UNAVAILABLE");});
            JsonNode tradability=f.input().path("sources").path("topup-state").path("tradability").path(code);
            if(tradability.path("verified").asBoolean()&&Set.of("BLOCKED","BLOCK","FAIL","DENIED").contains(tradability.path("status").asText()))gate(module,leg,code,"TRADABILITY",()->{throw new Gap("D144_OFFICIAL_TRADABILITY_BLOCK");});
            else if(tradability.path("verified").asBoolean()&&"PASS".equals(tradability.path("status").asText()))gate(module,leg,code,"TRADABILITY",()->owner(f,"topup-state",p));
            else {List<String> inputs=refs("TRADABILITY",code);module.refs.addAll(inputs);module.missing("D144_OFFICIAL_SOURCE_UNAVAILABLE_BEST_LEVEL_LIMIT_ONLY:"+code);((ArrayNode)leg.path("receipts")).add(receipt("TRADABILITY","UNAVAILABLE","D144_OFFICIAL_SOURCE_UNAVAILABLE_BEST_LEVEL_LIMIT_ONLY",inputs));}
            if(leg.path("blockingReasons").isEmpty()){
                try{
                    JsonNode body=market(f,code);Commitments used=commitments(f,p,code);BigDecimal total=f.facts().totalAssets(),current=Z;for(JsonNode h:f.input().path("sources").path("assets").path("liveAssets").path("stocks"))if(code.equals(h.path("stockCode").asText())&&"台股".equals(h.path("market").asText()))current=current.add(nonnegative(h.get("liveValue")));
                    ObjectNode input=F.objectNode();long depth=0;for(JsonNode row:body.path("book").path("levels"))depth+=nonnegative(row.get("askVolumeLots")).longValueExact();
                    input.put("tierLots",depth).put("remainingDailyLots",depth).put("remainingWeeklyLots",depth);
                    Map<String,BigDecimal> values=new HashMap<>();values.put("total",total);values.put("current",current);values.put("group",Z);values.put("equity",classifiedValue(f,"STOCK").add(currentEquity));values.put("targetWeight",selected.projection().weights().get(code).min(n(model.path("execution_policy").path("reserve_allocation_d222").path("ordinary_tw_core_caps").get(code))));values.put("groupCap",O);values.put("equityCap",n(model.path("execution_policy").path("dynamic_allocation_policy_d141").get("normal_equity_cap")));values.put("settledTwd",value(funding,"settledBalance","TWD"));values.put("payableTwd",value(funding,"payable","TWD"));values.put("reservedFunds",value(funding,"reservedFunds","TWD").add(currentDebit));values.put("reservedFees",used.fees().add(currentFees));values.put("reservedSymbolValue",used.symbol());values.put("reservedGroupValue",Z);values.put("reservedEquityValue",used.equity());values.put("unfilledDca",Z);values.put("termTwd",f.facts().termDeposits());values.put("termFloor",n(findFact(funding,"termFloor")));values.put("totalFloor",n(findFact(funding,"totalFloor")));values.put("feeRate",p.feeRate());values.forEach((k,v)->input.put(k,dec(v)));input.set("levels",body.path("book").path("levels"));if(plan)input.put("maximumDebitTwd",budget.path("remainingTwd").asText());
                    ObjectNode sizing=SrppReportTopupSizing.calculate(input,SrppReportStateCalculator.aggregateEquityHardCapEnabled(model.path("execution_policy").path("dynamic_allocation_policy_d141")));
                    require(sizing.path("lots").asInt()>0,"D215_NO_FUNDED_TARGET_CAPACITY");
                    if(!(tradability.path("verified").asBoolean()&&"PASS".equals(tradability.path("status").asText()))){BigDecimal limit=positive(body.path("book").path("levels").get(0).get("askPrice")),amount=limit.multiply(K).multiply(BigDecimal.valueOf(sizing.path("lots").asInt()));sizing.put("limitPrice",dec(limit)).put("amount",dec(amount)).put("fees",dec(fee(p,amount)));}
                    leg.put("status","ACTIONABLE_FOR_REPORT").put("lots",sizing.path("lots").asInt()).put("rankingReason","D215_NAMED_MEDIUM_CAPACITY_CEILING_FIXED_SYMBOL_ORDER");for(String k:List.of("limitPrice","amount","fees"))leg.set(k,sizing.path(k));currentDebit=currentDebit.add(n(leg.get("amount"))).add(n(leg.get("fees")));currentFees=currentFees.add(n(leg.get("fees")));currentEquity=currentEquity.add(n(leg.get("amount")));
                }catch(IllegalArgumentException|ArithmeticException bad){String reason=bad.getMessage()==null?"D215_CAPACITY_SOURCE_UNAVAILABLE":bad.getMessage();((ArrayNode)leg.path("blockingReasons")).add(reason);module.missing(reason+":"+code);for(JsonNode r:leg.path("receipts"))if("ALLOCATION".equals(r.path("ruleId").asText()))((ObjectNode)r).put("status","BLOCK").put("reason",reason);}
            }
            module.legs.add(leg);
        }
    }
    private static JsonNode adjustedBody(JsonNode body){require("AVAILABLE".equals(body.path("adjustedBasisStatus").asText())&&body.path("adjustedDaily").isArray(),"D222_ADJUSTED_PRICE_BASIS_UNAVAILABLE");ObjectNode copy=body.deepCopy();copy.set("daily",body.path("adjustedDaily"));return copy;}
    private static JsonNode adjustedPricePolicy(JsonNode model,String code){ObjectNode p=model.path("execution_policy").path("strategic_buy_high_gate_d188").deepCopy();Set<String> non=strings(p.path("non_distributing_symbols"));non.add(code);ArrayNode a=p.putArray("non_distributing_symbols");new TreeSet<>(non).forEach(a::add);return p;}
    private static JsonNode findFact(JsonNode module,String name){for(JsonNode f:module.path("data").path("facts"))if(name.equals(f.path("name").asText())&&"EXACT".equals(f.path("quality").asText()))return f.path("value");throw new Gap("D215_FUNDING_SOURCE_UNAVAILABLE:"+name);}
    public static boolean independent(JsonNode module,JsonNode leg){boolean plan=PLAN_STRATEGY.equals(leg.path("strategy").asText());if(!"STRATEGIC_BUY_GATES".equals(module.path("moduleId").asText())||(!STRATEGY.equals(leg.path("strategy").asText())&&!plan)||!Set.of("0050","00881").contains(leg.path("symbol").asText()))return false;Set<String> passed=new HashSet<>();for(JsonNode r:leg.path("receipts"))if("PASS".equals(r.path("status").asText())||("TRADABILITY".equals(r.path("ruleId").asText())&&"UNAVAILABLE".equals(r.path("status").asText())&&"D144_OFFICIAL_SOURCE_UNAVAILABLE_BEST_LEVEL_LIMIT_ONLY".equals(r.path("reason").asText())))passed.add(r.path("ruleId").asText());Set<String> required=new HashSet<>(REQUIRED);if(plan){required.remove("D215_NAMED_PATH");required.add("D226_BUY_PATH");required.add("D223_PLAN_BUDGET");}return passed.containsAll(required);}
}
