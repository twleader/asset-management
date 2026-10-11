package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;

/** D198 minimum of tier, quotas, target/group room, opposite ask depth and protected funding. */
public final class SrppReportTopupSizing {
    private static final BigDecimal ZERO=BigDecimal.ZERO,THOUSAND=new BigDecimal("1000");
    private SrppReportTopupSizing(){}
    public static ObjectNode calculate(JsonNode input){
        int tier=count(input,"tierLots"),daily=count(input,"remainingDailyLots"),weekly=count(input,"remainingWeeklyLots");BigDecimal total=n(input,"total"),current=n(input,"current"),group=n(input,"group"),equity=n(input,"equity"),target=n(input,"targetWeight"),groupCap=n(input,"groupCap"),equityCap=n(input,"equityCap"),available=n(input,"settledTwd").subtract(n(input,"payableTwd")).subtract(n(input,"reservedFunds")),term=n(input,"termTwd"),totalFloor=n(input,"totalFloor"),termFloor=n(input,"termFloor"),feeRate=n(input,"feeRate"),reservedFees=n(input,"reservedFees"),reservedSymbol=n(input,"reservedSymbolValue"),reservedGroup=n(input,"reservedGroupValue"),reservedEquity=n(input,"reservedEquityValue"),unfilledDca=n(input,"unfilledDca");
        for(String field:new String[]{"total","current","group","equity","targetWeight","groupCap","equityCap","settledTwd","payableTwd","reservedFunds","termTwd","totalFloor","termFloor","feeRate","reservedFees","reservedSymbolValue","reservedGroupValue","reservedEquityValue","unfilledDca"})if(n(input,field).signum()<0)throw bad("NEGATIVE_CAPACITY_SOURCE");if(total.signum()<=0||target.compareTo(BigDecimal.ONE)>0||groupCap.compareTo(BigDecimal.ONE)>0||equityCap.compareTo(BigDecimal.ONE)>0)throw bad("CAPACITY_BASE_UNVERIFIED");
        JsonNode levels=input.path("levels");if(!levels.isArray()||levels.size()!=5)throw bad("OPPOSITE_BEST_FIVE_UNVERIFIED");BigDecimal previous=null;long depth=0;for(JsonNode level:levels){BigDecimal price=n(level,"askPrice");int lots=count(level,"askVolumeLots");if(price.signum()<=0||previous!=null&&price.compareTo(previous)<=0)throw bad("OPPOSITE_BEST_FIVE_UNVERIFIED");previous=price;depth+=lots;}
        ObjectNode result=JsonNodeFactory.instance.objectNode();result.put("lots",0);result.putNull("limitPrice");result.putNull("amount");result.putNull("fees");String reason=term.compareTo(termFloor)<0?"PROTECTED_TERM_FLOOR":tier==0?"ZERO_PRICE_TIER":daily==0||weekly==0?"QUOTA_EXHAUSTED":depth==0?"OPPOSITE_DEPTH_EMPTY":"NO_FUNDED_TARGET_CAPACITY";
        if(term.compareTo(termFloor)>=0)for(int lots=Math.min(tier,Math.min(daily,weekly));lots>0;lots--){long accumulated=0;BigDecimal ask=null;for(JsonNode level:levels){accumulated+=count(level,"askVolumeLots");if(accumulated>=lots){ask=n(level,"askPrice");break;}}if(ask==null)continue;BigDecimal tick=ask.compareTo(new BigDecimal("50"))<0?new BigDecimal("0.01"):new BigDecimal("0.05"),limit=ask.divide(tick,0,RoundingMode.FLOOR).multiply(tick),amount=limit.multiply(THOUSAND).multiply(BigDecimal.valueOf(lots)),fee=amount.multiply(feeRate).setScale(2,RoundingMode.CEILING),denominator=total.subtract(reservedFees).subtract(fee);
            if(denominator.signum()<=0||available.subtract(amount).subtract(fee).compareTo(totalFloor)<0||current.add(reservedSymbol).add(unfilledDca).add(amount).compareTo(target.multiply(denominator))>0||group.add(reservedGroup).add(amount).compareTo(groupCap.multiply(denominator))>0||equity.add(reservedEquity).add(amount).compareTo(equityCap.multiply(denominator))>0)continue;result.put("lots",lots);result.put("limitPrice",d(limit));result.put("amount",d(amount));result.put("fees",d(fee));reason="MINIMUM_OF_TIER_QUOTAS_TARGET_GROUP_EQUITY_FUNDING_AND_DEPTH";break;}
        result.put("reason",reason);return result;
    }
    private static BigDecimal n(JsonNode n,String key){JsonNode value=n.get(key);if(value==null||value.isNull()||!value.isTextual()&&!value.isNumber())throw bad("CAPACITY_SOURCE_UNVERIFIED:"+key);try{return new BigDecimal(value.asText());}catch(NumberFormatException invalid){throw bad("CAPACITY_SOURCE_UNVERIFIED:"+key);}}
    private static int count(JsonNode node,String key){try{int value=n(node,key).intValueExact();if(value<0)throw bad("CAPACITY_COUNT_NEGATIVE");return value;}catch(ArithmeticException invalid){throw bad("CAPACITY_COUNT_UNVERIFIED");}}
    private static String d(BigDecimal value){return value.signum()==0?"0":value.stripTrailingZeros().toPlainString();}
    private static IllegalArgumentException bad(String reason){return new IllegalArgumentException(reason);}
}
