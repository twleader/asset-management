package com.steven.assets.service;

import com.steven.assets.model.CanonicalDividend.*;
import com.steven.assets.model.CanonicalDividendResolver;
import com.steven.assets.service.CanonicalDividendDisplayReadPort;
import com.steven.assets.service.CanonicalDividendRepository;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class CanonicalDividendDisplayReadService implements CanonicalDividendDisplayReadPort {
    private final CanonicalDividendRepository repository;
    public CanonicalDividendDisplayReadService(CanonicalDividendRepository repository){this.repository=repository;}
    @Override public Map<Key,Display> read(List<Key> keys,Instant asOf){
        LocalDate today=asOf.atZone(ZoneId.of("Asia/Taipei")).toLocalDate();
        Map<Key,Display> output=new LinkedHashMap<>();
        for(Key key:new LinkedHashSet<>(keys)){
            Facts facts=repository.load(key,asOf);
            List<Day> derived=CanonicalDividendResolver.resolve(facts,asOf,today,true).days();
            List<Day> days=new ArrayList<>();
            for(Day day:derived){
                if(day.withdrawn()){days.add(day);continue;}
                if(day.date().isAfter(today)&&!"UNRESOLVED".equals(day.resolution())){
                    days.add(day);continue;
                }
                Current canonical=facts.current().stream().filter(r->"ACTIVE".equals(r.status())
                        &&day.date().equals(r.canonicalDate())).findFirst().orElse(null);
                if(canonical!=null&&"UNRESOLVED".equals(day.resolution())&&"RESOLVED".equals(canonical.resolution())){
                    days.add(day);continue;
                }
                if(canonical!=null){
                    var observed=facts.evidence().stream().filter(e->Objects.equals(e.snapshotId(),canonical.snapshotId()))
                            .map(Evidence::observedAt).max(Comparator.naturalOrder()).orElse(null);
                    days.add(new Day(day.date(),"RESOLVED".equals(canonical.resolution())?canonical.cash():null,
                            "RESOLVED".equals(canonical.resolution())?canonical.stock():null,canonical.cashPayment(),
                            canonical.stockPayment(),canonical.source(),canonical.snapshotId(),null,observed,
                            canonical.resolution(),canonical.reason(),canonical.eventKey(),true,false,DividendCashEnrichmentService.isUsMarket(key.market())?null:canonical.yieldPct(),
                            DividendCashEnrichmentService.isUsMarket(key.market())?null:canonical.previousClose(),
                            DividendCashEnrichmentService.isUsMarket(key.market())?null:canonical.fillDays()));
                }else{
                    String source=facts.current().stream().filter(r->"ACTIVE".equals(r.status())
                            &&(day.date().equals(r.cashDate())||day.date().equals(r.stockDate())))
                            .map(Current::source).filter(Objects::nonNull).distinct().sorted().reduce((a,b)->a+"、"+b).orElse(day.source());
                    days.add(new Day(day.date(),null,null,null,null,source,null,null,null,"UNRESOLVED",
                            "舊來源或新證據尚未完成正式日期組投影，未把未知金額視為零",day.eventKey(),false,false));
                }
            }
            var undated=facts.current().stream().filter(row->"ACTIVE".equals(row.status())&&row.cashDate()==null&&row.stockDate()==null&&row.canonicalDate()==null)
                    .map(row->new Undated(row.year(),row.source(),"缺少可驗作用日期，不能把來源金額視為可採用配息")).distinct().toList();
            output.put(key,new Display(key.code(),key.market(),days,undated));
        }
        return Map.copyOf(output);
    }
}
