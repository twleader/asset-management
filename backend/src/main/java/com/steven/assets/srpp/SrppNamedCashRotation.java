package com.steven.assets.srpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.TreeSet;

/** D223 additional constraints on frozen evidence. No ledger is inferred or written here. */
public final class SrppNamedCashRotation {
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    public static final String PLAN_ID = "D223_00881_CASH_2881_EXIT_20261118";
    public static final String LEDGER_UNAVAILABLE = "D223_CUMULATIVE_BUDGET_LEDGER_UNAVAILABLE";
    public static final String PRICE_NOT_MET = "D223_BUY_PRICE_NOT_STRICTLY_BELOW_38";
    private static final Set<String> FIELDS = Set.of(
        "decision", "enabled", "plan_id", "effective_date", "market", "buy_symbol",
        "buy_price_exclusive_upper_twd", "total_buy_budget_twd", "budget_includes_buy_fees",
        "budget_includes_pending_orders", "budget_includes_immutable_reservations", "sell_symbol",
        "sell_price_exclusive_lower_twd", "retained_symbols", "buy_requires_prior_sell",
        "sell_requires_prior_buy", "batch_policy", "missing_attribution_action",
        "reset_budget_automatically", "original_gates_required", "independent_existing_exits_unchanged",
        "trade_authorization");
    private SrppNamedCashRotation() {}

    /** Absence preserves historical bundles; a malformed present plan cannot silently disable its gates. */
    public static JsonNode config(JsonNode model) {
        JsonNode p = model.path("execution_policy").path("named_cash_rotation_d223");
        if (p.isMissingNode()) return p;
        Set<String> fields = new TreeSet<>();
        p.fieldNames().forEachRemaining(fields::add);
        Set<String> expected=new TreeSet<>(FIELDS);boolean radar=p.has("entry_rule_decision");
        if(radar){expected.remove("buy_price_exclusive_upper_twd");expected.addAll(Set.of("entry_rule_decision","buy_radar_action","buy_requires_llm_adoption"));if(p.has("sell_radar_actions")){expected.remove("sell_price_exclusive_lower_twd");expected.addAll(Set.of("sell_radar_actions","sell_requires_llm_adoption"));}}
        require(p.isObject() && fields.equals(expected));
        if(radar)require(text(p,"entry_rule_decision","D-226")&&text(p,"buy_radar_action","ADD_CANDIDATE")&&p.path("buy_requires_llm_adoption").isBoolean()&&p.path("buy_requires_llm_adoption").asBoolean());
        if(p.has("sell_radar_actions"))require(radar&&p.path("sell_requires_llm_adoption").isBoolean()&&p.path("sell_requires_llm_adoption").asBoolean()&&p.path("sell_radar_actions").isArray()&&p.path("sell_radar_actions").size()==2&&"EXIT_CANDIDATE".equals(p.path("sell_radar_actions").get(0).asText())&&"REDUCE_CANDIDATE".equals(p.path("sell_radar_actions").get(1).asText()));
        for (String key : Set.of("enabled", "budget_includes_buy_fees", "budget_includes_pending_orders",
                "budget_includes_immutable_reservations", "original_gates_required", "independent_existing_exits_unchanged"))
            require(p.path(key).isBoolean() && p.path(key).booleanValue());
        for (String key : Set.of("buy_requires_prior_sell", "sell_requires_prior_buy", "reset_budget_automatically", "trade_authorization"))
            require(p.path(key).isBoolean() && !p.path(key).booleanValue());
        require(text(p,"decision","D-223") && text(p,"plan_id",PLAN_ID)
            && text(p,"effective_date","2026-11-18") && text(p,"market","台股")
            && text(p,"buy_symbol","00881") && text(p,"sell_symbol","2881")
            && (radar || text(p,"buy_price_exclusive_upper_twd","38")) && (p.has("sell_radar_actions") || text(p,"sell_price_exclusive_lower_twd","152"))
            && text(p,"total_buy_budget_twd","400000")
            && text(p,"batch_policy","EXISTING_QUOTA_AND_CAPACITY_NO_NEW_FIXED_SPLIT")
            && text(p,"missing_attribution_action","WAIT")
            && p.path("retained_symbols").isArray() && p.path("retained_symbols").size()==1
            && p.path("retained_symbols").get(0).isTextual() && "2885".equals(p.path("retained_symbols").get(0).asText()));
        return p;
    }

    public static boolean active(JsonNode model, LocalDate date) {
        JsonNode p = config(model);
        return !p.isMissingNode() && !date.isBefore(LocalDate.parse(p.path("effective_date").asText()));
    }

    public static boolean radarEntry(JsonNode model){return "D-226".equals(config(model).path("entry_rule_decision").asText());}

    public static boolean radarSellEntry(JsonNode model){return config(model).has("sell_radar_actions");}

    /** Price may be observed while used/remaining budget remain unknown. This never grants capacity. */
    public static ObjectNode buyObservation(JsonNode model, String symbol, String market, LocalDate date, BigDecimal price) {
        return buyObservation(model,symbol,market,date,price,null);
    }

    public static ObjectNode buyObservation(JsonNode model,String symbol,String market,LocalDate date,BigDecimal price,JsonNode radar){
        ObjectNode out = F.objectNode().put("applies", false);
        if (!active(model,date) || !"00881".equals(symbol) || !"台股".equals(market)) return out;
        out.put("applies",true).put("planId",PLAN_ID).put("totalBudgetTwd","400000");
        out.putNull("spentTwd").putNull("remainingTwd").put("capacityAccepted",false);
        if(radarEntry(model)){
            out.putNull("priceTriggered");JsonNode stock=radar==null?F.nullNode():radar.path("stock");
            boolean exact=symbol.equals(stock.path("stockCode").asText())&&market.equals(stock.path("market").asText())&&date.toString().equals(stock.path("asOfDate").asText())&&stock.path("dataComplete").asBoolean();
            if(!exact)out.putNull("radarTriggered").put("reason","D226_EXACT_ADD_RADAR_UNVERIFIED");
            else {boolean add="ADD_CANDIDATE".equals(stock.path("action").asText());out.put("radarTriggered",add).put("reason",add?LEDGER_UNAVAILABLE:"D226_RADAR_NOT_ADD_CANDIDATE");}
        } else if (price==null || price.signum()<=0) {
            out.putNull("priceTriggered").put("reason","D223_PRICE_UNVERIFIED");
        } else {
            boolean triggered = price.compareTo(new BigDecimal("38"))<0;
            out.put("priceTriggered",triggered).put("reason",triggered?LEDGER_UNAVAILABLE:PRICE_NOT_MET);
        }
        return out;
    }

    public static String observationReason(JsonNode observation) {
        if(observation.has("radarTriggered"))return "D223_PLAN="+PLAN_ID+";D226_RADAR_TRIGGER="+observation.path("radarTriggered").asText("UNKNOWN")+";TOTAL_BUDGET_TWD=400000;SPENT_TWD=UNKNOWN;REMAINING_TWD=UNKNOWN;BUY_REQUIRES_PRIOR_SELL=false;"+observation.path("reason").asText();
        return "D223_PLAN="+PLAN_ID+";PRICE_TRIGGER="
            +(observation.path("priceTriggered").isNull()?"UNKNOWN":observation.path("priceTriggered").asText())
            +";TOTAL_BUDGET_TWD=400000;SPENT_TWD=UNKNOWN;REMAINING_TWD=UNKNOWN;BUY_REQUIRES_PRIOR_SELL=false;"
            +observation.path("reason").asText();
    }

    /** Ordinary core exits only. Existing independent short/risk exits do not enter this method. */
    public static ObjectNode coreOverlay(JsonNode model, String symbol, String market, LocalDate date,
                                         BigDecimal last, BigDecimal executableBid, JsonNode original) {
        ObjectNode out = original.deepCopy();
        if (!active(model,date) || !"台股".equals(market)) return out;
        String reason = null;
        if("2881".equals(symbol)&&radarSellEntry(model))return out;
        if ("2885".equals(symbol)) reason = "D223_2885_RETAIN_ORDINARY_CORE";
        if ("2881".equals(symbol) && (last==null || executableBid==null || last.signum()<=0 || executableBid.signum()<=0))
            reason = "D223_SELL_PRICE_UNVERIFIED";
        else if ("2881".equals(symbol) && (last.compareTo(new BigDecimal("152"))<=0 || executableBid.compareTo(new BigDecimal("152"))<=0))
            reason = "D223_SELL_PRICE_NOT_STRICTLY_ABOVE_152";
        if ("2881".equals(symbol) || "2885".equals(symbol))
            out.put("d223Reason",reason==null?"D223_SELL_PRICE_CONDITION_PASSED_ORIGINAL_GATES_STILL_REQUIRED":reason);
        // Preserve the original rejection as the primary reason; an overlay can only revoke a pass.
        if (reason!=null && original.path("timingPass").asBoolean()) out.put("timingPass",false).put("reason",reason);
        return out;
    }

    private static boolean text(JsonNode p,String key,String expected) {
        return p.path(key).isTextual() && expected.equals(p.path(key).textValue());
    }
    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("D223_POLICY_UNVERIFIED");
    }
}
