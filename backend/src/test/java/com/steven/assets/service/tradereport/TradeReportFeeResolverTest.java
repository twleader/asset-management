package com.steven.assets.service.tradereport;

import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.service.fubon.*;
import com.steven.assets.dto.TradeReportFeeDto.Resolution;
import com.steven.assets.srpp.SrppJcs;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;

class TradeReportFeeResolverTest {
    static final LocalDate DATE=LocalDate.of(2026,10,5);
    static final Instant CAPTURE=Instant.parse("2026-10-05T05:20:00Z"), OBSERVED=CAPTURE.plusSeconds(60), NOW=Instant.parse("2026-11-17T08:00:00Z");
    static final String ACCOUNT="0123456789abcdef01234567";
    static final TradeReportParser.Row ROW=new TradeReportParser.Row("0050","測試ETF","買",DATE,new BigDecimal("1000"),new BigDecimal("9.95"),new BigDecimal("9950"),"synthetic1","13:09:51");
    final TradeReportFeeResolver resolver=new TradeReportFeeResolver();
    static ObjectNode order(){
        ObjectNode o=JsonNodeFactory.instance.objectNode();
        o.put("date",DATE.toString());o.put("accountFingerprint",ACCOUNT);o.put("seq_no","seq1");o.put("order_no","synthetic1");o.put("asset_type",0);o.put("market","TAIEX");o.put("market_type","Common");o.put("stock_no","0050");o.put("buy_sell","Buy");o.put("price_type","Limit");o.put("price","9.95");o.put("quantity",1000);o.put("time_in_force","ROD");o.put("order_type","Stock");o.put("is_pre_order",false);o.put("status",50);o.put("after_price_type","Limit");o.put("after_price","9.95");o.put("unit",1000);o.put("after_qty",1000);o.put("filled_qty",1000);o.put("filled_money",9950);o.put("last_time","13:09:51");o.put("active",false);o.put("remainingQty",0);o.putNull("recommendationLegId");o.putArray("fillIds");return o;
    }
    static ObjectNode orders(){ObjectNode p=JsonNodeFactory.instance.objectNode();p.put("schemaVersion",1);p.put("broker","fubon");p.put("queryDate",DATE.toString());p.put("accountFingerprint",ACCOUNT);p.put("capturedAt",CAPTURE.toString());p.put("complete",true);p.put("verifiedZero",true);p.put("activeCount",0);p.putArray("orders").add(order());ObjectNode portfolio=p.putObject("portfolio");portfolio.put("queryDate",DATE.toString());portfolio.put("accountFingerprint",ACCOUNT);portfolio.put("emptyConfirmed",true);portfolio.putArray("positions");return p;}
    static FubonSrppSourceStore.Observation snapshot(ObjectNode payload){ObjectNode p=payload.deepCopy();p.remove("revision");p.put("revision",SrppJcs.hash(p));return new FubonSrppSourceStore.Observation(UUID.fromString("00000000-0000-0000-0000-000000000001"),1,DATE,ACCOUNT,Instant.parse(p.path("capturedAt").asText()),"VERIFIED",p.path("revision").asText(),p);}
    static ObjectNode settlement(){ObjectNode p=JsonNodeFactory.instance.objectNode();p.put("schemaVersion",1);p.put("queryDate",DATE.toString());p.put("observedAt",OBSERVED.toString());p.put("accountFingerprint",ACCOUNT);p.put("accountBindingExplicit",true);p.put("coverageStatus","SDK_RANGE_3D_RETURNED_ROWS");p.putNull("reason");ObjectNode day=p.putArray("details").addObject();day.put("status","AVAILABLE");day.put("sourceQueryDate",DATE.toString());day.put("settlementDate",DATE.plusDays(2).toString());day.put("currency","TWD");for(String k:List.of("buyValue","buyFee","buySettlement","buyTax","sellValue","sellFee","sellSettlement","sellTax","totalBsValue","totalFee","totalTax","totalSettlementAmount"))day.put(k,"0");day.put("buyValue","9950");day.put("buyFee","20");day.put("buySettlement","-9970");day.put("totalBsValue","9950");day.put("totalFee","20");day.put("totalSettlementAmount","-9970");return p;}
    static FubonSettlementReceiptStore.Receipt receipt(ObjectNode p){return new FubonSettlementReceiptStore.Receipt(1,1,DATE,Instant.parse(p.path("observedAt").asText()),"VERIFIED",p.path("accountFingerprint").asText(),SrppJcs.hash(p),p,null);}
    Resolution resolve(ObjectNode s,ObjectNode o){return resolver.resolve(1,List.of(ROW),receipt(s),snapshot(o),NOW);}
    @Test void historicalFullFillDerivesFeeAndSharesNeverMultiplyUnit(){var r=resolve(settlement(),orders());assertThat(r.fee()).isEqualByComparingTo("20");assertThat(r.transactionTax()).isEqualByComparingTo("0");assertThat(r.provenance().status()).isEqualTo("DERIVED");assertThat(r.provenance().asOf()).isEqualTo(OBSERVED);assertThat(r.provenance().source()).isEqualTo("OFFICIAL_SETTLEMENT_ORDER_MATCH");}
    @Test void explicitZeroFeeIsDifferentFromUnknown(){ObjectNode p=settlement();ObjectNode d=(ObjectNode)p.path("details").get(0);for(String key:List.of("buyFee","totalFee"))d.put(key,"0");for(String key:List.of("buySettlement","totalSettlementAmount"))d.put(key,"-9950");assertThat(resolve(p,orders()).fee()).isEqualByComparingTo("0");d.putNull("buyTax");assertThat(resolve(p,orders()).fee()).isNull();}
    @Test void componentsAndAllEqualitiesFailClosed(){
        for(String key:List.of("buyValue","buyFee","buySettlement","buyTax","sellValue","sellFee","sellSettlement","sellTax","totalBsValue","totalFee","totalTax","totalSettlementAmount")){
            ObjectNode p=settlement();((ObjectNode)p.path("details").get(0)).put(key,"1");assertThat(resolve(p,orders()).fee()).as(key).isNull();
            p=settlement();((ObjectNode)p.path("details").get(0)).putNull(key);assertThat(resolve(p,orders()).fee()).as(key+" null").isNull();
        }
    }
    @Test void partialReducedMultipleActiveAndUnknownOrdersRejected(){
        List<Consumer<ObjectNode>> mutations=List.of(o->o.put("filled_qty",999),o->o.put("quantity",1001),o->o.put("after_qty",1001),o->o.put("filled_money",9949),o->o.put("order_no","other"),o->o.put("stock_no","00679B"),o->o.put("buy_sell","Sell"),o->o.put("status",40),o->o.put("active",true),o->o.put("unit",1),o->o.put("extra",true),o->o.remove("filled_qty"));
        for(var mutate:mutations){ObjectNode p=orders();mutate.accept((ObjectNode)p.path("orders").get(0));assertThat(resolve(settlement(),p).fee()).isNull();}
        ObjectNode p=orders();((ArrayNode)p.path("orders")).add(order().put("order_no","other"));assertThat(resolve(settlement(),p).fee()).isNull();
        assertThat(resolver.resolve(1,List.of(ROW,ROW),receipt(settlement()),snapshot(orders()),NOW).fee()).isNull();
    }
    @Test void nonfilledInactiveOrderCanCoexistButNoInventedMoney(){ObjectNode p=orders();ObjectNode empty=order().put("order_no","other").put("status",30).put("after_qty",0).put("filled_qty",0).put("filled_money",0);((ArrayNode)p.path("orders")).add(empty);assertThat(resolve(settlement(),p).fee()).isEqualByComparingTo("20");empty.put("filled_money",1);assertThat(resolve(settlement(),p).fee()).isNull();}
    @Test void settlementDatesIdentitySchemaAndAmbiguityFailClosed(){
        List<Consumer<ObjectNode>> mutations=List.of(p->p.put("accountFingerprint","fedcba9876543210fedcba98"),p->p.put("accountBindingExplicit",false),p->p.put("queryDate",DATE.minusDays(1).toString()),p->p.put("observedAt",CAPTURE.minusSeconds(1).toString()),p->p.put("observedAt",NOW.plusSeconds(1).toString()),p->p.put("extra",true),p->p.put("coverageStatus","UNKNOWN"),p->((ObjectNode)p.path("details").get(0)).put("settlementDate",DATE.toString()),p->((ObjectNode)p.path("details").get(0)).put("sourceQueryDate",DATE.minusDays(1).toString()),p->((ArrayNode)p.path("details")).add(p.path("details").get(0).deepCopy()));
        for(var mutate:mutations){ObjectNode p=settlement();mutate.accept(p);assertThat(resolve(p,orders()).fee()).isNull();}
        assertThat(resolver.resolve(2,List.of(ROW),receipt(settlement()),snapshot(orders()),NOW).fee()).isNull();
    }
    @Test void lateFailedAttemptAndMetadataChangeInvalidateEvidenceWithoutUsingOldSuccess(){var good=receipt(settlement());var failure=new FubonSettlementReceiptStore.Receipt(2,1,DATE,OBSERVED.plusSeconds(1),"UNAVAILABLE",null,null,JsonNodeFactory.instance.objectNode(),"SETTLEMENT_FAILED");var r=resolver.resolve(1,List.of(ROW),failure,snapshot(orders()),NOW);assertThat(r.fee()).isNull();assertThat(r.provenance().reason()).isEqualTo("SETTLEMENT_UNAVAILABLE");assertThat(r.provenance().evidenceHash()).isNotEqualTo(resolve(settlement(),orders()).provenance().evidenceHash());var forged=new FubonSettlementReceiptStore.Receipt(1,1,DATE,OBSERVED,"VERIFIED",ACCOUNT,"0".repeat(64),settlement(),null);assertThat(resolver.resolve(1,List.of(ROW),forged,snapshot(orders()),NOW).fee()).isNull();}
    @Test void orderCaptureBeforeFillOrFutureAndHashMismatchRejected(){ObjectNode p=orders();p.put("capturedAt","2026-10-05T04:00:00Z");assertThat(resolve(settlement(),p).fee()).isNull();var o=snapshot(orders());var forged=new FubonSrppSourceStore.Observation(o.id(),o.owner(),o.date(),o.accountFingerprint(),o.capturedAt(),o.status(),"0".repeat(64),o.payload());assertThat(resolver.resolve(1,List.of(ROW),receipt(settlement()),forged,NOW).fee()).isNull();assertThat(resolver.resolve(1,List.of(ROW),receipt(settlement()),o,CAPTURE.minusSeconds(1)).fee()).isNull();}
}
