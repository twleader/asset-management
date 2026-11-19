package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Pure D223 cross-day debit ledger. Caller verifies frozen source identities and hashes. */
public final class SrppNamedCashBuyBudget {
    public static final String STRATEGY="NAMED_CASH_BUY_D226";
    public static final String PLAN_ID="D223_00881_CASH_2881_EXIT_20261118";
    private static final BigDecimal CAP=new BigDecimal("400000"),ZERO=BigDecimal.ZERO;
    private static final LocalDate START=LocalDate.of(2026,11,18);
    private static final Set<String> INDEPENDENT=Set.of("NAMED_DEEP_DRAWDOWN_D215","SHORT_TERM_D173");
    private static final JsonNodeFactory F=JsonNodeFactory.instance;
    private SrppNamedCashBuyBudget(){}
    private static void need(boolean ok,String reason){if(!ok)throw new IllegalArgumentException(reason);}
    private static String text(JsonNode n,String k){JsonNode v=n.path(k);need(v.isTextual()&&!v.asText().isBlank(),"D223_BUDGET_IDENTITY_UNAVAILABLE:"+k);return v.asText();}
    private static BigDecimal number(JsonNode n,boolean positive){try{need(n!=null&&!n.isBoolean()&&(n.isTextual()||n.isNumber()),"D223_BUDGET_NUMERIC_SOURCE_UNAVAILABLE");BigDecimal v=new BigDecimal(n.asText());need(positive?v.signum()>0:v.signum()>=0,"D223_BUDGET_INVALID_DEBIT");return v;}catch(NumberFormatException bad){throw new IllegalArgumentException("D223_BUDGET_NUMERIC_SOURCE_UNAVAILABLE");}}
    private static String dec(BigDecimal n){return n.signum()==0?"0":n.stripTrailingZeros().toPlainString();}
    private static boolean yes(JsonNode n,String k){return n.path(k).isBoolean()&&n.path(k).booleanValue();}
    private static JsonNode rows(JsonNode n,String key){need(n.isObject()&&yes(n,"verified")&&"COMPLETE".equals(n.path("coverage").asText())&&n.path(key).isArray(),"D223_BUDGET_COMPLETE_SOURCE_UNAVAILABLE:"+key);return n.path(key);}
    private static LocalDate day(JsonNode n,String k){try{return LocalDate.parse(text(n,k));}catch(java.time.DateTimeException bad){throw new IllegalArgumentException("D223_BUDGET_DATE_UNAVAILABLE");}}
    private static boolean target(JsonNode n,boolean order){return "00881".equals(n.path("symbol").asText())&&"台股".equals(n.path("market").asText())&&"BUY".equals(n.path(order?"side":"action").asText());}
    private static void identity(JsonNode n,boolean order){text(n,"symbol");need(Set.of("台股","美股").contains(text(n,"market"))&&Set.of("BUY","SELL").contains(text(n,order?"side":"action")),"D223_BUDGET_COMMITMENT_IDENTITY_UNAVAILABLE");if(target(n,order))need(n.path("currency").isTextual()&&"TWD".equals(n.path("currency").asText()),"D223_BUDGET_CURRENCY_UNAVAILABLE");}
    // 1 = this plan, 2 = D215, 3 = SHORT; 0 = absent. Preserve exact independent identity.
    private static int attribution(JsonNode n){String plan=n.path("planId").asText(""),strategy=n.path("strategy").asText("");boolean own=PLAN_ID.equals(plan)||STRATEGY.equals(strategy),ind=INDEPENDENT.contains(strategy);need(plan.isBlank()||PLAN_ID.equals(plan)||ind,"D223_BUDGET_EXPLICIT_OTHER_PLAN_CONFLICT");need(!(own&&ind)&&!(STRATEGY.equals(strategy)&&!plan.isBlank()&&!PLAN_ID.equals(plan)),"D223_BUDGET_ATTRIBUTION_CONFLICT");return own?1:"NAMED_DEEP_DRAWDOWN_D215".equals(strategy)?2:"SHORT_TERM_D173".equals(strategy)?3:0;}
    private static int combine(int a,int b){need(a==0||b==0||a==b,"D223_BUDGET_LINK_ATTRIBUTION_CONFLICT");return a==0?b:a;}
    private static void entry(ArrayNode a,String prefix,String id,String status,JsonNode source,BigDecimal debit,String fallback){String ref=source.path("source_ref").asText("");a.addObject().put("id",prefix+":"+id).put("status",status).put("source_ref",ref.isBlank()?fallback:ref).put("plan_id",PLAN_ID).put("stock_code","00881").put("side","BUY").put("cash_debit_twd",dec(debit));}

    /** No sizing or trade authority. Unknown totals remain null; known over-cap is BLOCKED. */
    public static ObjectNode evaluate(JsonNode policyConfig,JsonNode planSource,JsonNode pendingSource,JsonNode reservationSource,LocalDate tradingDate){
        ObjectNode out=F.objectNode().put("status","UNAVAILABLE").put("reason","D223_BUDGET_SOURCE_UNAVAILABLE").put("budgetTwd","400000").put("placesOrders",false).put("tradeAuthorization",false);
        out.putNull("usedTwd").putNull("committedTwd").putNull("remainingTwd");ArrayNode accounting=out.putArray("accountingEntries");
        try{
            need(policyConfig!=null&&policyConfig.isObject()&&yes(policyConfig,"enabled")&&PLAN_ID.equals(policyConfig.path("plan_id").asText())&&"2026-11-18".equals(policyConfig.path("effective_date").asText())&&CAP.compareTo(number(policyConfig.get("total_buy_budget_twd"),true))==0,"D223_BUDGET_POLICY_UNAVAILABLE");
            need(yes(policyConfig,"budget_includes_buy_fees")&&yes(policyConfig,"budget_includes_pending_orders")&&yes(policyConfig,"budget_includes_immutable_reservations")&&policyConfig.path("reset_budget_automatically").isBoolean()&&!policyConfig.path("reset_budget_automatically").booleanValue(),"D223_BUDGET_POLICY_UNAVAILABLE");
            need(tradingDate!=null&&!tradingDate.isBefore(START),"D223_BUDGET_DATE_UNAVAILABLE");
            need(planSource!=null&&planSource.isObject()&&yes(planSource,"verified")&&"COMPLETE".equals(planSource.path("coverage").asText())&&PLAN_ID.equals(planSource.path("planId").asText()),"D223_BUDGET_PLAN_SOURCE_UNAVAILABLE");text(planSource,"source_ref");
            JsonNode fills=planSource.path("fills"),fillRows=rows(fills,"rows");need(START.equals(day(fills,"startDate"))&&tradingDate.equals(day(fills,"endDate")),"D223_BUDGET_FILL_COVERAGE_UNAVAILABLE");
            Set<String> ids=new HashSet<>();BigDecimal used=ZERO;
            for(JsonNode fill:fillRows){String id=text(fill,"fillId");need(ids.add(id)&&PLAN_ID.equals(fill.path("planId").asText())&&"00881".equals(fill.path("symbol").asText())&&"台股".equals(fill.path("market").asText())&&"BUY".equals(fill.path("side").asText())&&yes(fill,"confirmed"),"D223_BUDGET_FILL_IDENTITY_UNAVAILABLE");LocalDate date=day(fill,"tradeDate");need(!date.isBefore(START)&&!date.isAfter(tradingDate),"D223_BUDGET_FILL_DATE_OUTSIDE_COVERAGE");text(fill,"source_ref");BigDecimal shares=number(fill.get("shares"),true),price=number(fill.get("price"),true),amount=number(fill.get("amount"),true),fees=number(fill.get("fees"),false);need(shares.multiply(price).compareTo(amount)==0,"D223_BUDGET_FILL_AMOUNT_MISMATCH");BigDecimal debit=amount.add(fees);used=used.add(debit);entry(accounting,"FILL",id,"FILLED",fill,debit,"inputBundle.sources.topup-state.namedCashRotation.fills:"+id);}
            out.put("usedTwd",dec(used));
            JsonNode orders=rows(pendingSource,"orders"),reservations=rows(reservationSource,"rows");need("VERIFIED".equals(reservationSource.path("status").asText()),"D223_BUDGET_RESERVATION_SOURCE_UNAVAILABLE");
            Map<String,JsonNode> byOrder=new LinkedHashMap<>();Map<String,Integer> tags=new HashMap<>();Map<String,BigDecimal> linkedDebits=new HashMap<>();
            for(JsonNode order:orders){String id=text(order,"orderId");need(byOrder.putIfAbsent(id,order)==null,"D223_BUDGET_DUPLICATE_ORDER_ID");identity(order,true);if(target(order,true))tags.put(id,attribution(order));}
            ids.clear();Set<String> linked=new HashSet<>();BigDecimal committed=ZERO;
            for(JsonNode reservation:reservations){String id=text(reservation,"reservationId");need(ids.add(id),"D223_BUDGET_DUPLICATE_RESERVATION_ID");identity(reservation,false);String status=text(reservation,"status");if(!"ACTIVE".equals(status)||!target(reservation,false))continue;int tag=attribution(reservation);String link=reservation.path("linkedOrderId").asText("");
                if(!link.isBlank()){JsonNode order=byOrder.get(link);need(order!=null&&target(order,true)&&linked.add(link),"D223_BUDGET_LINKED_ORDER_UNAVAILABLE");need(reservation.path("currency").isTextual()&&order.path("currency").isTextual()&&"TWD".equals(reservation.path("currency").asText())&&"TWD".equals(order.path("currency").asText()),"D223_BUDGET_LINK_CURRENCY_CONFLICT");String orderPlan=order.path("planId").asText(""),reservedPlan=reservation.path("planId").asText("");need(orderPlan.isBlank()||reservedPlan.isBlank()||orderPlan.equals(reservedPlan),"D223_BUDGET_LINK_PLAN_ID_CONFLICT");tags.put(link,combine(tags.getOrDefault(link,0),tag));linkedDebits.put(link,number(reservation.get("amount"),false).add(number(reservation.get("fees"),false)));}
                else{need(tag!=0,"D223_BUDGET_ATTRIBUTION_UNAVAILABLE");if(tag==1){BigDecimal debit=number(reservation.get("amount"),false).add(number(reservation.get("fees"),false));committed=committed.add(debit);entry(accounting,"RESERVATION",id,"RESERVED",reservation,debit,"inputBundle.sources.report-reservations.rows:"+id);}}
            }
            for(var e:byOrder.entrySet()){JsonNode order=e.getValue();if(!target(order,true))continue;int tag=tags.getOrDefault(e.getKey(),0);need(tag!=0,"D223_BUDGET_ATTRIBUTION_UNAVAILABLE");if(tag==1){BigDecimal debit=number(order.get("remainingShares"),false).multiply(number(order.get("limitPrice"),true)).add(number(order.get("remainingFees"),false)).max(linkedDebits.getOrDefault(e.getKey(),ZERO));committed=committed.add(debit);entry(accounting,"PENDING",e.getKey(),"PENDING",order,debit,"inputBundle.sources.topup-state.pending.orders:"+e.getKey());}}
            out.put("committedTwd",dec(committed));BigDecimal remaining=CAP.subtract(used).subtract(committed);boolean exceeded=remaining.signum()<0;out.put("remainingTwd",dec(remaining.max(ZERO))).put("status",exceeded?"BLOCKED":"PASS").put("reason",exceeded?"D223_PLAN_BUDGET_EXCEEDED":"D223_PLAN_BUDGET_COMPLETE");
        }catch(IllegalArgumentException|NullPointerException invalid){out.put("status","UNAVAILABLE").put("reason",invalid.getMessage()==null?"D223_BUDGET_SOURCE_UNAVAILABLE":invalid.getMessage());out.putNull("committedTwd").putNull("remainingTwd");accounting.removeAll();}
        return out;
    }
}
