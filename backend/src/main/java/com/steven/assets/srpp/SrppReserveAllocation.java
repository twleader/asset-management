package com.steven.assets.srpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** D222 pure projection. A baseline never authorizes a trade or invents a market allocation. */
public final class SrppReserveAllocation {
    private SrppReserveAllocation() {}
    private static final MathContext MC=MathContext.DECIMAL128;
    private static final JsonNodeFactory F=JsonNodeFactory.instance;
    public static final String SCHEMA="SRPP_POLICY_DOCUMENT_V2";
    public static final String SEMANTICS="BASELINE_NOT_EXECUTABLE";
    public static final String EMERGENCY="emergency_00865B";
    private static final Set<String> NUMBERS=Set.of("annual_inflation_rate","living_term_deposit_twd_2026_real","living_demand_deposit_twd_2026_real","emergency_term_deposit_twd_2026_real","emergency_etf_twd_2026_real","reference_cash_weight");
    public static final Set<String> FIELDS=Set.of("decision","enabled","inflation_base_date","annual_inflation_rate","living_term_deposit_twd_2026_real","living_demand_deposit_twd_2026_real","emergency_term_deposit_twd_2026_real","emergency_etf_twd_2026_real","emergency_etf_symbol","reference_cash_weight","excess_symbols","excess_requires_explicit_allocation","shortfall_symbols","shortfall_requires_explicit_allocation","market_selector");
    public record Projection(String status,SortedMap<String,BigDecimal> weights,BigDecimal reserve,BigDecimal delta,BigDecimal factor,BigDecimal equityTarget) {}
    public static boolean enabled(JsonNode model){return model.path("execution_policy").path("reserve_allocation_d222").path("enabled").asBoolean(false);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("D222_RESERVE_POLICY_INVALID");}
    private static BigDecimal n(JsonNode v){if(v==null||!v.isNumber()&&!v.isTextual())throw invalid();try{return new BigDecimal(v.asText());}catch(NumberFormatException e){throw invalid();}}
    private static String decimal(BigDecimal v){return v.signum()==0?"0":v.stripTrailingZeros().toPlainString();}
    public static ObjectNode documentConfig(JsonNode model){
        JsonNode source=model.path("execution_policy").path("reserve_allocation_d222");ObjectNode out=F.objectNode();
        for(String key:new TreeSet<>(FIELDS)){JsonNode value=source.get(key);if(value==null)throw invalid();if(NUMBERS.contains(key)){if(!value.isNumber())throw invalid();out.put(key,decimal(n(value)));}else out.set(key,value.deepCopy());}
        validateDocumentConfig(out);return out;
    }
    public static void validateDocumentConfig(JsonNode config){
        Set<String> keys=new HashSet<>();config.fieldNames().forEachRemaining(keys::add);if(!config.isObject()||!keys.equals(FIELDS))throw invalid();
        if(!config.path("decision").isTextual()||!"D-222".equals(config.path("decision").asText())||!config.path("enabled").isBoolean()||!config.path("enabled").asBoolean()||!config.path("excess_requires_explicit_allocation").isBoolean()||!config.path("excess_requires_explicit_allocation").asBoolean()||!config.path("shortfall_requires_explicit_allocation").isBoolean()||!config.path("shortfall_requires_explicit_allocation").asBoolean())throw invalid();
        for(String key:NUMBERS){JsonNode value=config.get(key);if(!value.isTextual()||!SrppDecimal.isCanonical(value.asText()))throw invalid();BigDecimal amount=n(value);if(amount.signum()<0||!key.equals("annual_inflation_rate")&&amount.signum()==0)throw invalid();if(Set.of("annual_inflation_rate","reference_cash_weight").contains(key)&&amount.compareTo(BigDecimal.ONE)>=0)throw invalid();}
        if(!"00865B".equals(config.path("emergency_etf_symbol").asText()))throw invalid();
        for(String key:List.of("shortfall_symbols","excess_symbols")){JsonNode symbols=config.path(key);if(!symbols.isArray()||symbols.size()!=3)throw invalid();for(int i=0;i<3;i++)if(!symbols.get(i).isTextual()||!List.of("VOO","0050","00881").get(i).equals(symbols.get(i).asText()))throw invalid();}
        JsonNode selector=config.path("market_selector");Set<String> selectorKeys=new HashSet<>();selector.fieldNames().forEachRemaining(selectorKeys::add);
        if(!selectorKeys.equals(Set.of("symbols","position_basis","buy_selection","sell_selection","requires_all_three_verified","tie_action","no_capacity_reflow","fx_policy"))||!selector.path("symbols").equals(config.path("shortfall_symbols"))||!"ADJUSTED_52_WEEK_RANGE".equals(selector.path("position_basis").asText())||!"LOWEST_POSITION".equals(selector.path("buy_selection").asText())||!"HIGHEST_POSITION".equals(selector.path("sell_selection").asText())||!selector.path("requires_all_three_verified").isBoolean()||!selector.path("requires_all_three_verified").asBoolean()||!selector.path("no_capacity_reflow").isBoolean()||!selector.path("no_capacity_reflow").asBoolean()||!"AMBIGUOUS_TIE".equals(selector.path("tie_action").asText())||!"execution_policy.usd_asset_fx_preference_d215".equals(selector.path("fx_policy").asText()))throw invalid();
        try{String day=config.path("inflation_base_date").asText();if(!config.path("inflation_base_date").isTextual()||!LocalDate.parse(day).toString().equals(day))throw invalid();}catch(java.time.DateTimeException e){throw invalid();}
    }
    public static Projection project(JsonNode model,BigDecimal total,LocalDate date){return project(model,total,date,null,null);}
    public static Projection project(JsonNode model,BigDecimal total,LocalDate date,Map<String,BigDecimal> reductions){
        return project(model,total,date,reductions,null);
    }
    public static Projection project(JsonNode model,BigDecimal total,LocalDate date,Map<String,BigDecimal> reductions,Map<String,BigDecimal> additions){
        if(total==null||total.signum()<=0||date==null)throw new IllegalArgumentException("D222_TOTAL_ASSETS_UNVERIFIED");
        ObjectNode config=documentConfig(model);LocalDate base=LocalDate.parse(config.path("inflation_base_date").asText());if(date.isBefore(base))throw new IllegalArgumentException("D222_DATE_BEFORE_REAL_BASE");
        BigDecimal factor=BigDecimal.valueOf(StrictMath.pow(BigDecimal.ONE.add(n(config.get("annual_inflation_rate"))).doubleValue(),ChronoUnit.DAYS.between(base,date)/365.25));
        SortedMap<String,BigDecimal> weights=new TreeMap<>();model.path("detailed_target_allocation_2027").fields().forEachRemaining(e->{BigDecimal v=n(e.getValue());if(v.signum()<0||v.compareTo(BigDecimal.ONE)>0)throw invalid();weights.put(e.getKey(),v);});
        BigDecimal term=n(config.get("living_term_deposit_twd_2026_real")).add(n(config.get("emergency_term_deposit_twd_2026_real"))).multiply(factor),demand=n(config.get("living_demand_deposit_twd_2026_real")).multiply(factor),emergency=n(config.get("emergency_etf_twd_2026_real")).multiply(factor),reserve=term.add(demand).add(emergency),delta=total.multiply(n(config.get("reference_cash_weight"))).subtract(reserve);
        if(reserve.compareTo(total)>0)throw new IllegalArgumentException("D222_INSUFFICIENT_ASSETS");
        weights.put("TWD_term_deposits",term.divide(total,MC));weights.put("demand_deposits_and_cash",demand.divide(total,MC));weights.put(EMERGENCY,emergency.divide(total,MC));
        BigDecimal equity=BigDecimal.ZERO;for(String code:List.of("VOO","VT","009826","0050","00881","00713","0056","00878","00919")){if(weights.get(code)==null)throw invalid();equity=equity.add(weights.get(code));}equity=equity.add(delta.divide(total,MC));
        if(equity.signum()<0)throw new IllegalArgumentException("D222_INSUFFICIENT_ASSETS");
        BigDecimal adjustable=BigDecimal.ZERO;for(String code:List.of("VOO","0050","00881"))adjustable=adjustable.add(weights.get(code).multiply(total));
        if(delta.signum()<0&&delta.negate().subtract(adjustable).compareTo(new BigDecimal("0.0000001"))>0)throw new IllegalArgumentException("D222_INSUFFICIENT_ADJUSTMENT_CAPACITY");
        String status="TARGETS_READY";
        if(delta.abs().compareTo(new BigDecimal("0.0000001"))<=0)delta=BigDecimal.ZERO;
        if(delta.signum()<0){if(additions!=null&&!additions.isEmpty())throw new IllegalArgumentException("D222_REDUCTION_ALLOCATION_UNVERIFIED");
            if(reductions==null){status="MARKET_ALLOCATION_REQUIRED";for(String code:List.of("VOO","0050","00881"))weights.put(code,null);}
            else{BigDecimal allocated=BigDecimal.ZERO;for(var row:reductions.entrySet()){BigDecimal value=row.getValue();if(!List.of("VOO","0050","00881").contains(row.getKey())||value==null||value.signum()<0||value.compareTo(weights.get(row.getKey()).multiply(total))>0)throw new IllegalArgumentException("D222_REDUCTION_ALLOCATION_UNVERIFIED");allocated=allocated.add(value);weights.put(row.getKey(),weights.get(row.getKey()).subtract(value.divide(total,MC)));}if(allocated.add(delta).abs().compareTo(new BigDecimal("0.000001"))>0)throw new IllegalArgumentException("D222_REDUCTION_ALLOCATION_DOES_NOT_RECONCILE");}
        }else{if(reductions!=null&&!reductions.isEmpty())throw new IllegalArgumentException("D222_NO_SHORTFALL_REDUCTION_ALLOWED");
            if(delta.signum()>0){if(additions==null){status="MARKET_ALLOCATION_REQUIRED";for(String code:List.of("VOO","0050","00881"))weights.put(code,null);}
                else{BigDecimal allocated=BigDecimal.ZERO;for(var row:additions.entrySet()){BigDecimal value=row.getValue();if(!List.of("VOO","0050","00881").contains(row.getKey())||value==null||value.signum()<0)throw new IllegalArgumentException("D222_ADDITION_ALLOCATION_UNVERIFIED");allocated=allocated.add(value);BigDecimal target=weights.get(row.getKey()).add(value.divide(total,MC));if(!row.getKey().equals("VOO")){JsonNode cap=model.path("execution_policy").path("reserve_allocation_d222").path("ordinary_tw_core_caps").get(row.getKey());if(cap==null||target.compareTo(n(cap))>0)throw new IllegalArgumentException("D222_ORDINARY_CORE_CAP_EXCEEDED");}weights.put(row.getKey(),target);}if(allocated.subtract(delta).abs().compareTo(new BigDecimal("0.000001"))>0)throw new IllegalArgumentException("D222_ADDITION_ALLOCATION_DOES_NOT_RECONCILE");}}
            else if(additions!=null&&!additions.isEmpty())throw new IllegalArgumentException("D222_NO_EXCESS_ADDITION_ALLOWED");
        }
        if(status.equals("TARGETS_READY")){if(reserve.compareTo(total)>0)throw new IllegalArgumentException("D222_INSUFFICIENT_ASSETS");BigDecimal sum=weights.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add);if(sum.subtract(BigDecimal.ONE).abs().compareTo(new BigDecimal("0.00000001"))>0)throw new IllegalArgumentException("D222_TARGETS_DO_NOT_CONSERVE_ASSETS");}
        return new Projection(status,Collections.unmodifiableSortedMap(weights),reserve,delta,factor,equity);
    }
    private static JsonNode sorted(JsonNode node){if(node.isObject()){ObjectNode out=F.objectNode();new TreeSet<>(node.properties().stream().map(Map.Entry::getKey).toList()).forEach(k->out.set(k,sorted(node.get(k))));return out;}if(node.isArray()){ArrayNode out=F.arrayNode();node.forEach(v->out.add(sorted(v)));return out;}if(node.isFloatingPointNumber())return F.numberNode(node.doubleValue());return node.deepCopy();}
    private static JsonNode payload(JsonNode wrapper,String reason){JsonNode body=wrapper.get("payload");if(body==null||!wrapper.path("source_ref").isTextual()||wrapper.path("source_ref").asText().isBlank()||!SrppJcs.sha256Hex(sorted(body).toString()).equals(wrapper.path("sha256").asText()))throw new IllegalArgumentException(reason);return body;}
    private static BigDecimal positiveRange(JsonNode value){if(value==null||!value.isNumber())throw new IllegalArgumentException("D222_RANGE_UNVERIFIED");BigDecimal number=n(value);if(number.signum()<=0)throw new IllegalArgumentException("D222_RANGE_UNVERIFIED");return number;}
    /** Hash consistency only. Caller must independently verify original market/FX/gate/capacity evidence. */
    public static ObjectNode selectMarketAdjustment(JsonNode model,BigDecimal total,LocalDate date,JsonNode candidates){
        return selectMarketAdjustment(model,total,date,candidates,"INITIAL_ALLOCATION",null);
    }
    public static ObjectNode selectMarketAdjustment(JsonNode model,BigDecimal total,LocalDate date,JsonNode candidates,String mode,JsonNode livingReserve){
        if(total==null||total.signum()<=0||date==null)throw new IllegalArgumentException("D222_TOTAL_ASSETS_UNVERIFIED");
        ObjectNode config=documentConfig(model);LocalDate base=LocalDate.parse(config.path("inflation_base_date").asText());if(date.isBefore(base))throw new IllegalArgumentException("D222_DATE_BEFORE_REAL_BASE");
        BigDecimal factor=BigDecimal.valueOf(StrictMath.pow(BigDecimal.ONE.add(n(config.get("annual_inflation_rate"))).doubleValue(),ChronoUnit.DAYS.between(base,date)/365.25));
        // Actual living-reserve cash flow is independent of initial target feasibility.
        Projection projection=mode.equals("INITIAL_ALLOCATION")?project(model,total,date):null;
        BigDecimal delta=projection==null?BigDecimal.ZERO:projection.delta(),remaining=delta.abs();String side=delta.signum()>0?"BUY":delta.signum()<0?"SELL":"NONE";
        ObjectNode out=F.objectNode();out.put("decision","D-222").put("side",side).put("status","UNVERIFIED").putNull("selected_symbol");ObjectNode allocations=out.putObject("allocation_twd");out.put("remaining_adjustment_twd",remaining).put("transaction_authorized",false).put("source_consistency_only",true).put("api_capacity_acceptance",false);
        BigDecimal settledLiving=null;out.put("adjustment_mode",mode);
        if(mode.equals("LIVING_RESERVE_REBALANCE")){
            try{if(livingReserve==null)throw new IllegalArgumentException("D222_LIVING_SUBLEDGER_UNAVAILABLE");JsonNode living=payload(livingReserve,"D222_LIVING_SUBLEDGER_UNAVAILABLE");if(!date.toString().equals(living.path("valuation_date").asText())||!"LIVING_RESERVE_EXCLUDING_EMERGENCY".equals(living.path("ledger_scope").asText()))throw new IllegalArgumentException("D222_LIVING_SUBLEDGER_UNVERIFIED");JsonNode ids=living.path("reconciled_deposit_ids");Set<String> seen=new HashSet<>();if(!ids.isArray()||ids.isEmpty())throw new IllegalArgumentException("D222_LIVING_SUBLEDGER_UNVERIFIED");for(JsonNode id:ids)if((!id.isTextual()&&!id.isIntegralNumber())||id.asText().isBlank()||!seen.add(id.asText()))throw new IllegalArgumentException("D222_LIVING_SUBLEDGER_UNVERIFIED");
                BigDecimal term=n(living.get("living_term_twd")),demand=n(living.get("living_demand_twd"));settledLiving=n(living.get("settled_unrestricted_available_twd"));if(!living.path("living_term_twd").isNumber()||!living.path("living_demand_twd").isNumber()||!living.path("settled_unrestricted_available_twd").isNumber()||term.signum()<0||demand.signum()<0||settledLiving.signum()<0)throw new IllegalArgumentException("D222_LIVING_SUBLEDGER_UNVERIFIED");JsonNode cfg=model.path("execution_policy").path("reserve_allocation_d222");BigDecimal target=n(cfg.get("living_term_deposit_twd_2026_real")).add(n(cfg.get("living_demand_deposit_twd_2026_real"))).multiply(factor);delta=term.add(demand).subtract(target);if(delta.abs().compareTo(new BigDecimal("0.0000001"))<=0)delta=BigDecimal.ZERO;remaining=delta.abs();side=delta.signum()>0?"BUY":delta.signum()<0?"SELL":"NONE";out.put("side",side).put("remaining_adjustment_twd",remaining);
            }catch(IllegalArgumentException absent){out.remove(List.of("side","selected_symbol","allocation_twd","remaining_adjustment_twd","source_consistency_only"));out.put("reason",absent.getMessage());return out;}
        }else if(!mode.equals("INITIAL_ALLOCATION")){out.put("reason","D222_ADJUSTMENT_MODE_INVALID");return out;}
        if(side.equals("NONE")){out.put("status","NO_ADJUSTMENT");return out;}
        try{
            if(!candidates.isArray()||candidates.size()!=3)throw new IllegalArgumentException("D222_ALL_THREE_CANDIDATES_REQUIRED");
            Map<String,BigDecimal> positions=new TreeMap<>(),capacities=new TreeMap<>();Set<String> eligible=new TreeSet<>();
            for(JsonNode row:candidates){JsonNode facts=payload(row.path("source"),"D222_SOURCE_HASH_UNVERIFIED");String symbol=facts.path("stock_code").asText();if(!List.of("VOO","0050","00881").contains(symbol)||positions.containsKey(symbol))throw new IllegalArgumentException("D222_CANDIDATE_IDENTITY_UNVERIFIED");
                if(!date.toString().equals(facts.path("trading_date").asText())||!"DISTRIBUTION_ADJUSTED_52_WEEK".equals(facts.path("adjustment_basis").asText()))throw new IllegalArgumentException("D222_RANGE_BASIS_UNVERIFIED");
                BigDecimal price=positiveRange(facts.get("price")),low=positiveRange(facts.get("low52")),high=positiveRange(facts.get("high52"));if(high.compareTo(low)<=0||price.compareTo(low)<0||price.compareTo(high)>0)throw new IllegalArgumentException("D222_RANGE_UNVERIFIED");positions.put(symbol,price.subtract(low).divide(high.subtract(low),MC));
                boolean pass=true;for(String gate:List.of("FX",side)){JsonNode receipt=payload(row.path("gate_receipts").path(gate),"D222_GATE_HASH_UNVERIFIED");if(!symbol.equals(receipt.path("stock_code").asText())||!date.toString().equals(receipt.path("trading_date").asText())||!gate.equals(receipt.path("scope").asText()))throw new IllegalArgumentException("D222_GATE_IDENTITY_UNVERIFIED");String status=receipt.path("status").asText();if(!Set.of("VERIFIED_PASS","VERIFIED_BLOCKED").contains(status))throw new IllegalArgumentException("D222_GATE_UNVERIFIED");JsonNode raw=receipt.path("source_evidence");if(!raw.isArray()||raw.isEmpty())throw new IllegalArgumentException("D222_GATE_SOURCE_EVIDENCE_REQUIRED");for(JsonNode original:raw)payload(original,"D222_GATE_SOURCE_HASH_UNVERIFIED");pass&=status.equals("VERIFIED_PASS");}
                JsonNode capacity=row.get("capacity_twd");if(capacity==null||!capacity.isNumber()||n(capacity).signum()<0)throw new IllegalArgumentException("D222_CAPACITY_UNVERIFIED");capacities.put(symbol,n(capacity));if(pass)eligible.add(symbol);
            }
            if(positions.size()!=3)throw new IllegalArgumentException("D222_ALL_THREE_CANDIDATES_REQUIRED");ObjectNode rows=out.putObject("candidates");positions.forEach((symbol,position)->rows.putObject(symbol).put("position",position).put("eligible",eligible.contains(symbol)).put("capacity_twd",capacities.get(symbol)));
            if(eligible.isEmpty()){out.put("status","NO_ELIGIBLE_CANDIDATE");return out;}
            BigDecimal extreme=eligible.stream().map(positions::get).reduce(side.equals("BUY")?BigDecimal::min:BigDecimal::max).orElseThrow();List<String> selected=eligible.stream().filter(s->positions.get(s).subtract(extreme).abs().compareTo(new BigDecimal("0.000000000001"))<=0).toList();if(selected.size()!=1){out.put("status","AMBIGUOUS_TIE");return out;}
            String symbol=selected.get(0);BigDecimal amount=remaining.min(capacities.get(symbol));if(side.equals("BUY")&&settledLiving!=null)amount=amount.min(settledLiving);BigDecimal baseline=n(model.path("detailed_target_allocation_2027").get(symbol));
            if(mode.equals("INITIAL_ALLOCATION")&&side.equals("SELL"))amount=amount.min(baseline.multiply(total));else if(mode.equals("INITIAL_ALLOCATION")&&!symbol.equals("VOO"))amount=amount.min(n(model.path("execution_policy").path("reserve_allocation_d222").path("ordinary_tw_core_caps").get(symbol)).subtract(baseline).multiply(total).max(BigDecimal.ZERO));
            out.put("selected_symbol",symbol);allocations.put(symbol,amount);remaining=remaining.subtract(amount).max(BigDecimal.ZERO);out.put("remaining_adjustment_twd",remaining).put("status",remaining.compareTo(new BigDecimal("0.000001"))<=0?"ALLOCATION_REVIEW":"PARTIAL_CAPACITY_WAIT");
            return out;
        }catch(IllegalArgumentException absent){out.put("reason",absent.getMessage());return out;}
    }
    /** Project only verified computable targets; absent market split stays null, never a baseline fallback. */
    public static ObjectNode projectedModel(JsonNode model,BigDecimal total,LocalDate date){
        ObjectNode copy=model.deepCopy();if(!enabled(model))return copy;Projection p=project(model,total,date);ObjectNode targets=copy.putObject("detailed_target_allocation_2027");p.weights().forEach((key,value)->{if(value==null)targets.putNull(key);else targets.put(key,value);});
        ObjectNode dynamic=(ObjectNode)copy.path("execution_policy").path("dynamic_allocation_policy_d141");dynamic.put("normal_equity_target",p.equityTarget());
        copy.put("d222_projection_status",p.status());return copy;
    }
}
