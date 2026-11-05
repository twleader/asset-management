package com.steven.assets.service;

import com.steven.assets.dto.AnnualDividendSummary;
import com.steven.assets.model.AnnualDividendCalculator;
import com.steven.assets.security.TenantGuard;
import com.steven.assets.security.UnauthenticatedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class AnnualDividendService {
    private final TenantGuard tenantGuard;
    private final AnnualDividendReadRepository repository;
    private final Clock clock;
    private CanonicalDividendDisplayReadPort display;

    @Autowired
    public AnnualDividendService(TenantGuard tenantGuard, AnnualDividendReadRepository repository, CanonicalDividendDisplayReadPort display) {
        this(tenantGuard, repository, Clock.system(ZoneId.of("Asia/Taipei")));
        this.display=display;
    }
    public AnnualDividendService(TenantGuard tenantGuard, AnnualDividendReadRepository repository) {
        this(tenantGuard,repository,Clock.system(ZoneId.of("Asia/Taipei")));
    }
    AnnualDividendService(TenantGuard tenantGuard, AnnualDividendReadRepository repository, Clock clock) {
        this.tenantGuard = tenantGuard;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AnnualDividendSummary summary(String requestedYear, String requestedMarket) {
        return summary(requestedYear, requestedMarket, List.of());
    }

    @Transactional(readOnly = true)
    public AnnualDividendSummary summary(String requestedYear, String requestedMarket, List<String> requestedStocks) {
        Long owner = tenantGuard.requireCurrentUserId();
        if (owner == null || owner <= 0) throw new UnauthenticatedException();
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Taipei")));
        int year = today.getYear();
        if (requestedYear != null) {
            if (!requestedYear.matches("[0-9]{4}")) throw new IllegalArgumentException("年度須為1900至2100");
            year = Integer.parseInt(requestedYear);
        }
        if (year < 1900 || year > 2100) throw new IllegalArgumentException("年度須為1900至2100");
        var facts = repository.load(owner, today);
        if(display!=null){
            var keys=facts.securities().stream().map(s->new com.steven.assets.model.CanonicalDividend.Key(s.code(),s.market())).toList();
            var shared=display.read(keys,clock.instant());
            var events=new java.util.ArrayList<com.steven.assets.model.AnnualDividendData.Event>();
            long sequence=0;
            for(var security:facts.securities()){
                var value=shared.get(new com.steven.assets.model.CanonicalDividend.Key(security.code(),security.market()));
                if(value==null)continue;
                for(var missing:value.undated())if(missing.year()!=null)events.add(new com.steven.assets.model.AnnualDividendData.Event(++sequence,security.code(),security.market(),missing.year(),null,null,null,null,null,missing.source(),"UNRESOLVED",missing.reason()));
                for(var day:value.days())events.add(new com.steven.assets.model.AnnualDividendData.Event(++sequence,security.code(),security.market(),day.date().getYear(),
                    day.cash()!=null&&day.cash().signum()==0&&day.stock()!=null&&day.stock().signum()>0?null:day.date(),
                    day.stock()!=null&&day.stock().signum()>0?day.date():null,day.cashPayment(),day.cash(),day.stock(),day.source(),day.resolution(),day.reason()));
            }
            facts=new com.steven.assets.model.AnnualDividendData.Facts(facts.securities(),facts.snapshots(),facts.trades(),events,facts.markets());
        }
        String market = requestedMarket == null ? "" : requestedMarket;
        if (!market.isEmpty() && facts.markets().stream().noneMatch(m -> m.code().equals(market)))
            throw new IllegalArgumentException("市場代碼不存在");
        Set<Integer> years = new TreeSet<>(Comparator.reverseOrder());
        years.add(today.getYear());
        years.add(year);
        facts.snapshots().forEach(s -> years.add(s.date().getYear()));
        facts.trades().forEach(t -> years.add(t.date().getYear()));
        facts.events().forEach(e -> { if (e.exDividendDate() != null) years.add(e.exDividendDate().getYear()); });
        var calculated = AnnualDividendCalculator.calculate(facts, year, market, today);
        var stockOptions = AnnualDividendCalculator.stockOptions(calculated);
        Set<String> allowedKeys = new HashSet<>();
        stockOptions.forEach(option -> allowedKeys.add(option.key()));
        Set<String> selectedKeys = new TreeSet<>();
        for (String key : requestedStocks == null ? List.<String>of() : requestedStocks) {
            if (key == null || key.isBlank() || !allowedKeys.contains(key))
                throw new IllegalArgumentException("股票選項不在目前年度、市場或使用者範圍內");
            selectedKeys.add(key);
        }
        calculated = AnnualDividendCalculator.filterByStockKeys(calculated, selectedKeys, today);
        return new AnnualDividendSummary(year, today, years.stream().filter(y -> y >= 1900 && y <= 2100).toList(),
                facts.markets(), calculated.currencyTotals(), calculated.rows(), calculated.gaps(), calculated.unavailableCount(),
                stockOptions, List.copyOf(selectedKeys));
    }
}
