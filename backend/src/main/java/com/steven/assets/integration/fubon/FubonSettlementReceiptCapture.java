package com.steven.assets.integration.fubon;

import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.service.fubon.*;
import com.steven.assets.srpp.SrppJcs;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;

/** No vendor I/O: captures only the result of the already-authorized sync. */
@Service
public class FubonSettlementReceiptCapture {
    private final FubonSettlementReceiptStore store;
    private final FubonSyncOwnerPort owners;
    public FubonSettlementReceiptCapture(FubonSettlementReceiptStore store,FubonSyncOwnerPort owners){this.store=store;this.owners=owners;}

    @Transactional(propagation=Propagation.MANDATORY)
    public void verified(long owner,FubonDtos.SettlementBatch batch){
        owners.lockAndRevalidate(owner);
        ObjectNode p=payload(batch);
        store.append(new FubonSettlementReceiptStore.Receipt(0,owner,batch.queryDate(),batch.observedAt(),"VERIFIED",batch.accountFingerprint(),SrppJcs.hash(p),p,null));
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void unavailable(long owner,Instant observedAt,FubonSettlementOutcome outcome){
        owners.lockAndRevalidate(owner);
        LocalDate date=observedAt.atZone(ZoneId.of("Asia/Taipei")).toLocalDate();
        ObjectNode p=JsonNodeFactory.instance.objectNode().put("schemaVersion",1).put("queryDate",date.toString())
                .put("observedAt",observedAt.toString()).put("reason",outcome.name());
        store.append(new FubonSettlementReceiptStore.Receipt(0,owner,date,observedAt,"UNAVAILABLE",null,SrppJcs.hash(p),p,outcome.name()));
    }
    static ObjectNode payload(FubonDtos.SettlementBatch b){
        ObjectNode p=JsonNodeFactory.instance.objectNode().put("schemaVersion",1).put("queryDate",b.queryDate().toString())
                .put("observedAt",b.observedAt().toString()).put("accountFingerprint",b.accountFingerprint())
                .put("accountBindingExplicit",b.accountBindingExplicit()).put("coverageStatus",b.coverageStatus()).put("reason",b.reason());
        ArrayNode rows=p.putArray("details");
        for(var d:b.details()){
            ObjectNode n=rows.addObject().put("status",d.status()).put("sourceQueryDate",d.sourceQueryDate().toString())
                    .put("settlementDate",d.settlementDate()==null?null:d.settlementDate().toString()).put("currency",d.currency());
            decimal(n,"buyValue",d.buyValue());decimal(n,"buyFee",d.buyFee());decimal(n,"buyTax",d.buyTax());decimal(n,"buySettlement",d.buySettlement());
            decimal(n,"sellValue",d.sellValue());decimal(n,"sellFee",d.sellFee());decimal(n,"sellTax",d.sellTax());decimal(n,"sellSettlement",d.sellSettlement());
            decimal(n,"totalBsValue",d.totalBsValue());decimal(n,"totalFee",d.totalFee());decimal(n,"totalTax",d.totalTax());decimal(n,"totalSettlementAmount",d.totalSettlementAmount());
        }
        return p;
    }
    private static void decimal(ObjectNode n,String key,CanonicalFubonDecimal d){if(d==null)n.putNull(key);else n.put(key,d.value().toPlainString());}
}
