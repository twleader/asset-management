package com.steven.assets.service.tradereport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.dto.TradeReportFeeDto.*;
import com.steven.assets.service.fubon.*;
import com.steven.assets.srpp.SrppJcs;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Offline historical as-of evidence matching. No repositories, clock reads, or vendor calls. */
public final class TradeReportFeeResolver {
    public static final String VERSION = "OFFICIAL_SETTLEMENT_ORDER_MATCH_V1";
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final Set<String> ROOT = Set.of("schemaVersion","queryDate","observedAt","accountFingerprint","accountBindingExplicit","coverageStatus","reason","details");
    private static final Set<String> DAY = Set.of("status","sourceQueryDate","settlementDate","currency","buyValue","buyFee","buySettlement","buyTax","sellValue","sellFee","sellSettlement","sellTax","totalBsValue","totalFee","totalTax","totalSettlementAmount");
    private static final List<String> AMOUNTS = List.of("buyValue","buyFee","buySettlement","buyTax","sellValue","sellFee","sellSettlement","sellTax","totalBsValue","totalFee","totalTax","totalSettlementAmount");

    public Resolution resolve(long owner, List<TradeReportParser.Row> rows,
            FubonSettlementReceiptStore.Receipt settlement, FubonSrppSourceStore.Observation orders, Instant now) {
        String evidence = evidenceHash(settlement, orders);
        String reason = "ORDER_IDENTITY_UNVERIFIED";
        try {
            require(rows != null && rows.size() == 1 && "買".equals(rows.getFirst().side()));
            var row = rows.getFirst();
            require(orders != null && orders.owner() == owner && "VERIFIED".equals(orders.status())
                    && row.tradeDate().equals(orders.date()) && orders.id() != null && orders.capturedAt() != null);
            // Validate at its own historical capture instant, not against today's freshness window.
            FubonSrppSourceContract.validate(orders.payload(), row.tradeDate(), orders.capturedAt());
            require(now != null && !orders.capturedAt().isAfter(now)
                    && orders.capturedAt().equals(Instant.parse(orders.payload().path("capturedAt").asText()))
                    && Objects.equals(orders.revision(), orders.payload().path("revision").asText())
                    && Objects.equals(orders.accountFingerprint(), orders.payload().path("accountFingerprint").asText()));
            require(!orders.capturedAt().isBefore(row.tradeDate().atTime(LocalTime.parse(row.filledTime())).atZone(TAIPEI).toInstant()));
            JsonNode matched = null;
            for (JsonNode order : orders.payload().path("orders")) {
                require(order.path("active").isBoolean() && !order.path("active").booleanValue());
                long filled = order.path("filled_qty").longValue();
                if (filled == 0) { require(order.path("filled_money").longValue() == 0); continue; }
                require(matched == null && order.path("status").intValue() == 50
                        && filled == order.path("quantity").longValue() && filled == order.path("after_qty").longValue());
                require("Buy".equals(order.path("buy_sell").asText())
                        && row.orderNo().equals(order.path("order_no").asText())
                        && row.stockCode().equals(order.path("stock_no").asText())
                        && row.shares().compareTo(BigDecimal.valueOf(filled)) == 0
                        && row.amount().signum() > 0
                        && row.amount().compareTo(BigDecimal.valueOf(order.path("filled_money").longValue())) == 0);
                matched = order; // Wire filled_qty is already shares. Never multiply by unit.
            }
            require(matched != null);
            reason = "SETTLEMENT_UNAVAILABLE";
            require(settlement != null && settlement.owner() == owner && "VERIFIED".equals(settlement.status())
                    && settlement.id() > 0 && settlement.observedAt() != null && row.tradeDate().equals(settlement.queryDate())
                    && !settlement.observedAt().isAfter(now) && !settlement.observedAt().isBefore(orders.capturedAt())
                    && Objects.equals(settlement.accountFingerprint(), orders.accountFingerprint()));
            ObjectNode payload = settlement.payload();
            require(payload != null && SrppJcs.hash(payload).equals(settlement.payloadHash()));
            exact(payload, ROOT);
            require(payload.path("schemaVersion").isIntegralNumber() && payload.path("schemaVersion").intValue() == 1
                    && payload.path("accountBindingExplicit").isBoolean() && payload.path("accountBindingExplicit").booleanValue()
                    && row.tradeDate().toString().equals(payload.path("queryDate").asText())
                    && settlement.observedAt().equals(Instant.parse(payload.path("observedAt").asText()))
                    && settlement.observedAt().atZone(TAIPEI).toLocalDate().equals(row.tradeDate())
                    && settlement.accountFingerprint().equals(payload.path("accountFingerprint").asText())
                    && "SDK_RANGE_3D_RETURNED_ROWS".equals(payload.path("coverageStatus").asText())
                    && payload.path("reason").isNull() && payload.path("details").isArray());
            reason = "SETTLEMENT_AMBIGUOUS";
            JsonNode day = null;
            Set<String> dates = new HashSet<>(), pairs = new HashSet<>();
            for (JsonNode candidate : payload.path("details")) {
                exact(candidate, DAY);
                LocalDate sourceDate = LocalDate.parse(candidate.path("sourceQueryDate").asText());
                require(!sourceDate.isAfter(settlement.queryDate()));
                require(pairs.add(sourceDate+":"+candidate.path("settlementDate").asText()));
                if ("NO_DATA_OBSERVED".equals(candidate.path("status").asText())) {
                    require(candidate.path("settlementDate").isNull() && candidate.path("currency").isNull());
                    for (String key : AMOUNTS) require(candidate.path(key).isNull());
                    continue;
                }
                require("AVAILABLE".equals(candidate.path("status").asText()) && "TWD".equals(candidate.path("currency").asText()));
                LocalDate settles = LocalDate.parse(candidate.path("settlementDate").asText());
                require(!settles.isBefore(sourceDate) && dates.add(settles.toString()));
                for (String key : AMOUNTS) if (!Set.of("buyTax","sellTax").contains(key) || !candidate.path(key).isNull()) amount(candidate,key);
                require(amount(candidate,"buySettlement").signum() <= 0 && amount(candidate,"sellSettlement").signum() >= 0
                        && amount(candidate,"buySettlement").add(amount(candidate,"sellSettlement")).compareTo(amount(candidate,"totalSettlementAmount")) == 0);
                require(settles.isAfter(settlement.queryDate()) || (amount(candidate,"buySettlement").signum()==0 && amount(candidate,"sellSettlement").signum()==0));
                if (sourceDate.equals(row.tradeDate())) { require(day == null && settles.isAfter(row.tradeDate())); day = candidate; }
            }
            require(day != null);
            reason = "FEE_COMPONENTS_UNVERIFIED";
            for (String zero : List.of("sellValue","sellFee","sellTax","sellSettlement","buyTax","totalTax")) require(amount(day,zero).signum() == 0);
            BigDecimal value=amount(day,"buyValue"), fee=amount(day,"buyFee"), buy=amount(day,"buySettlement");
            require(value.signum()>0 && fee.signum()>=0 && value.compareTo(row.amount())==0
                    && fee.compareTo(amount(day,"totalFee"))==0
                    && value.compareTo(amount(day,"totalBsValue"))==0
                    && buy.compareTo(value.add(fee).negate())==0
                    && buy.compareTo(amount(day,"totalSettlementAmount"))==0
                    && buy.abs().subtract(row.amount()).compareTo(fee)==0);
            return new Resolution(fee, BigDecimal.ZERO, new FeeProvenance("DERIVED",null,"OFFICIAL_SETTLEMENT_ORDER_MATCH",
                    settlement.id(),orders.id().toString(),evidence,settlement.observedAt()));
        } catch (RuntimeException invalid) {
            return new Resolution(null,null,new FeeProvenance("UNAVAILABLE",reason,null,
                    settlement==null?null:settlement.id(),orders==null||orders.id()==null?null:orders.id().toString(),evidence,null));
        }
    }
    public static Resolution unavailable(String reason) {
        return new Resolution(null,null,new FeeProvenance("UNAVAILABLE",reason,null,null,null,null,null));
    }
    private static void require(boolean valid) { if(!valid) throw new IllegalArgumentException("EVIDENCE_UNVERIFIED"); }
    private static void exact(JsonNode node,Set<String> keys) {
        require(node.isObject() && node.size()==keys.size());
        node.fieldNames().forEachRemaining(key->require(keys.contains(key)));
    }
    private static BigDecimal amount(JsonNode node,String key) {
        JsonNode value=node.path(key);
        require(value.isTextual() && value.textValue().matches("-?(?:0|[1-9][0-9]*)"));
        BigDecimal result=new BigDecimal(value.textValue());require(result.precision()<=20);return result;
    }
    private static String evidenceHash(FubonSettlementReceiptStore.Receipt settlement,FubonSrppSourceStore.Observation orders) {
        ObjectNode node=JsonNodeFactory.instance.objectNode();node.put("resolverVersion",VERSION);
        if(settlement!=null){ObjectNode s=node.putObject("settlement");s.put("id",settlement.id());s.put("owner",settlement.owner());s.put("date",Objects.toString(settlement.queryDate(),null));s.put("observedAt",Objects.toString(settlement.observedAt(),null));s.put("status",settlement.status());s.put("account",settlement.accountFingerprint());s.put("hash",settlement.payloadHash());s.put("reason",settlement.reason());s.set("payload",settlement.payload());}
        if(orders!=null){ObjectNode o=node.putObject("orders");o.put("id",Objects.toString(orders.id(),null));o.put("owner",orders.owner());o.put("date",Objects.toString(orders.date(),null));o.put("capturedAt",Objects.toString(orders.capturedAt(),null));o.put("status",orders.status());o.put("account",orders.accountFingerprint());o.put("revision",orders.revision());o.set("payload",orders.payload());}
        // Hash malformed evidence too; a failed/latest attempt must still invalidate an old preview.
        return SrppJcs.sha256Hex(node.toString());
    }
}
