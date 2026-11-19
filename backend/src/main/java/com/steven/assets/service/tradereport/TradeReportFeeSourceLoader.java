package com.steven.assets.service.tradereport;
import com.steven.assets.service.fubon.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDate;
/** Optional sources only; callers catch failures AFTER this independent transaction is ended. */
@Service
public class TradeReportFeeSourceLoader {
 private final FubonSettlementReceiptStore settlements; private final FubonSrppSourceStore orders;
 public TradeReportFeeSourceLoader(FubonSettlementReceiptStore settlements,FubonSrppSourceStore orders){this.settlements=settlements;this.orders=orders;}
 public record Sources(FubonSettlementReceiptStore.Receipt settlement,FubonSrppSourceStore.Observation orders){}
 @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true)
 public Sources load(long owner,LocalDate date){return new Sources(settlements.latest(owner).orElse(null),orders.latest(owner,date).orElse(null));}
}
