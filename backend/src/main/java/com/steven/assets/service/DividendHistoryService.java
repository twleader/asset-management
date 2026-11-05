package com.steven.assets.service;

import com.steven.assets.model.CanonicalDividend.*;
import com.steven.assets.model.marketdata.MarketDataResults.*;
import com.steven.assets.repository.StockDividendHistoryRepository;
import com.steven.assets.service.port.DividendSyncPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

/** Shared display projection. Both legacy method names are now strictly read-only. */
@Service
public class DividendHistoryService {
    private final CanonicalDividendDisplayReadPort display;
    @Autowired public DividendHistoryService(CanonicalDividendDisplayReadPort display){this.display=display;}
    /** Source-compatible construction only; production always injects the canonical read port. */
    public DividendHistoryService(StockDividendHistoryRepository repository,DividendCurrentStateProjectionService ignored,DividendSyncPort unused){
        this((keys,asOf)->{
            Map<Key,Display> out=new HashMap<>();
            for(Key key:keys){List<Day> days=repository.findByStockSinceYear(key.code(),key.market(),1900).stream()
                .filter(r->r.anchorDate()!=null).map(r->new Day(r.anchorDate(),null,null,null,null,r.getSource(),null,null,null,
                    "UNRESOLVED","舊資料未驗證",r.getEventKey(),false,false)).toList();out.put(key,new Display(key.code(),key.market(),days));}
            return out;
        });
    }
    @Transactional(readOnly=true) public DividendHistoryResult findFromDb(String code,String market,int years){return findFromDbReadOnly(code,market,years);}
    @Transactional(readOnly=true) public DividendHistoryResult findFromDbReadOnly(String code,String market,int years){
        if(years<1||years>20)throw new IllegalArgumentException("股利查詢年數須為1至20");
        Instant now=Instant.now();int since=now.atZone(ZoneId.of("Asia/Taipei")).getYear()-years;
        Key key=new Key(code,market);Display result=display.read(List.of(key),now).get(key);
        List<Day> days=result==null?List.of():result.days().stream().filter(d->d.date().getYear()>=since&&!d.withdrawn())
            .sorted(Comparator.comparing(Day::date).reversed()).toList();
        List<DividendRow> rows=new ArrayList<>(days.stream().map(d->new DividendRow(d.date().getYear(),d.cash(),d.stock(),
            d.cash()!=null&&d.cash().signum()==0&&d.stock()!=null&&d.stock().signum()>0?null:d.date().toString(),DividendCashEnrichmentService.isUsMarket(market)?null:d.yieldPct(),
            d.cashPayment()==null?null:d.cashPayment().toString(),d.stockPayment()==null?null:d.stockPayment().toString(),DividendCashEnrichmentService.isUsMarket(market)?null:d.fillDays(),DividendCashEnrichmentService.isUsMarket(market)?null:d.previousClose(),
            d.stock()!=null&&d.stock().signum()>0?d.date().toString():null,d.resolution(),d.reason(),d.source(),d.observedAt()==null?null:d.observedAt().toString())).toList());
        if(result!=null)for(var missing:result.undated()) if(missing.year()!=null&&missing.year()>=since)rows.add(new DividendRow(missing.year(),null,null,null,null,null,null,null,null,null,"UNRESOLVED",missing.reason(),missing.source(),null));
        Map<Integer,List<Day>> byYear=new TreeMap<>(Comparator.reverseOrder());days.forEach(d->byYear.computeIfAbsent(d.date().getYear(),x->new ArrayList<>()).add(d));
        List<DividendAnnualSummary> summaries=new ArrayList<>();
        for(var entry:byYear.entrySet()){
            List<Day> items=entry.getValue();int unknown=(int)items.stream().filter(d->d.cash()==null||d.stock()==null||"UNRESOLVED".equals(d.resolution())).count();
            String quality=unknown>0?"UNRESOLVED":items.stream().anyMatch(d->"FUTURE_PARTIAL".equals(d.resolution()))?"FUTURE_PARTIAL":"RESOLVED";
            BigDecimal cash=unknown>0?null:items.stream().map(Day::cash).reduce(BigDecimal.ZERO,BigDecimal::add);
            BigDecimal stock=unknown>0?null:items.stream().map(Day::stock).reduce(BigDecimal.ZERO,BigDecimal::add);
            BigDecimal previous=items.stream().map(Day::previousClose).filter(v->v!=null&&v.signum()>0).findFirst().orElse(null);
            BigDecimal yield=!DividendCashEnrichmentService.isUsMarket(market)&&cash!=null&&previous!=null?cash.multiply(BigDecimal.valueOf(100)).divide(previous,6,RoundingMode.HALF_UP):null;
            summaries.add(new DividendAnnualSummary(entry.getKey(),cash,stock,yield,items.size(),unknown,quality));
        }
        if(result!=null)for(var missing:result.undated()) if(missing.year()!=null&&missing.year()>=since){
            var old=summaries.stream().filter(x->x.year().equals(missing.year())).findFirst().orElse(null);
            if(old!=null)summaries.remove(old);
            summaries.add(new DividendAnnualSummary(missing.year(),null,null,null,old==null?1:old.eventCount()+1,old==null?1:old.unavailableCount()+1,"UNRESOLVED"));
        }
        summaries.sort(Comparator.comparing(DividendAnnualSummary::year).reversed());
        String quality=rows.isEmpty()||summaries.stream().anyMatch(y->"UNRESOLVED".equals(y.quality()))?"UNRESOLVED":summaries.stream().anyMatch(y->"FUTURE_PARTIAL".equals(y.quality()))?"FUTURE_PARTIAL":"RESOLVED";
        String source=rows.stream().map(DividendRow::source).filter(Objects::nonNull).distinct().sorted().reduce((a,b)->a+"、"+b).orElse(null);
        return new DividendHistoryResult(code,market,source,rows.isEmpty()?"尚無可驗證的配息資料":null,rows,summaries,quality);
    }
}
