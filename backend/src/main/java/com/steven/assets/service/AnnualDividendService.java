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

    @Autowired
    public AnnualDividendService(TenantGuard tenantGuard, AnnualDividendReadRepository repository) {
        this(tenantGuard, repository, Clock.system(ZoneId.of("Asia/Taipei")));
    }
    AnnualDividendService(TenantGuard tenantGuard, AnnualDividendReadRepository repository, Clock clock) {
        this.tenantGuard = tenantGuard;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AnnualDividendSummary summary(String requestedYear, String requestedMarket) {
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
        return new AnnualDividendSummary(year, today, years.stream().filter(y -> y >= 1900 && y <= 2100).toList(),
                facts.markets(), calculated.currencyTotals(), calculated.rows(), calculated.gaps(), calculated.unavailableCount());
    }
}
