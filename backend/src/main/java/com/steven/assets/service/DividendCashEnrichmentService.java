package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.repository.StockPriceHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

/** Completes missing cash-dividend facts using completed local price evidence only. */
@Service
public class DividendCashEnrichmentService {
    private final DividendCurrentStateRepository events;
    private final StockPriceHistoryRepository prices;
    private final MarketDataService calendar;

    public DividendCashEnrichmentService(DividendCurrentStateRepository events,
            StockPriceHistoryRepository prices, MarketDataService calendar) {
        this.events = events;
        this.prices = prices;
        this.calendar = calendar;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enrich(String code, String market, Instant now) {
        List<DividendCurrentStateRepository.ActiveEventDetail> active = events.findActiveEventDetails(code, market);
        List<DividendCurrentStateRepository.ActiveEventDetail> candidates = active.stream().filter(e -> e.exDividendDate() != null && positive(e.cashDividend())
                        && (e.previousClose() == null || e.yieldPct() == null || e.fillDays() == null)).toList();
        if (candidates.isEmpty()) return;
        LocalDate asOf = completedAsOf(market, now);
        if (asOf == null) return;
        for (var event : candidates) {
            LocalDate ex = event.exDividendDate();
            if (ex.isAfter(asOf) || !Boolean.TRUE.equals(known(market, ex))) continue;
            LocalDate prior = null;
            for (int i = 1; i <= 20; i++) {
                LocalDate date = ex.minusDays(i);
                Boolean open = known(market, date);
                if (open == null) break;
                if (open) { prior = date; break; }
            }
            if (prior == null) continue;
            List<StockPriceHistory> rows = prices.findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(
                    code, market, ex.minusDays(20), asOf);
            Map<LocalDate, BigDecimal> closes = validatedCloses(rows, ex.minusDays(20), asOf);
            if (closes == null || !closes.containsKey(ex) || !closes.containsKey(prior)) continue;
            BigDecimal basis = decimal(closes.get(prior), 15);
            if (basis == null || (event.previousClose() != null && event.previousClose().compareTo(basis) != 0)) continue;
            BigDecimal yield = decimal(event.cashDividend().multiply(BigDecimal.valueOf(100))
                    .divide(basis, 4, RoundingMode.HALF_UP), 10);
            if (yield == null) continue;
            // No verified split/corporate-action absence feed exists. Only a same-session
            // hit has a provable comparison interval; multi-session fill remains unknown.
            Integer fill = closes.get(ex).compareTo(basis) >= 0
                    && !unverifiedActionInComparison(event, active, prior, ex) ? 0 : null;
            events.fillMissingCashEnrichment(code, market, event, basis, yield, fill);
        }
    }

    /** The prior close already includes actions on that session; actions after it may change the price basis. */
    private static boolean unverifiedActionInComparison(DividendCurrentStateRepository.ActiveEventDetail event,
            List<DividendCurrentStateRepository.ActiveEventDetail> active, LocalDate prior, LocalDate ex) {
        if (inComparison(event.exRightsDate(), prior, ex)) return true;
        return active.stream().filter(other -> other.id() != event.id())
                .anyMatch(other -> inComparison(other.exDividendDate(), prior, ex)
                        || inComparison(other.exRightsDate(), prior, ex));
    }

    private static boolean inComparison(LocalDate action, LocalDate prior, LocalDate ex) {
        return action != null && action.isAfter(prior) && !action.isAfter(ex);
    }

    private LocalDate completedAsOf(String market, Instant now) {
        ZoneId zone;
        LocalTime close;
        if ("台股".equals(market) || "TW".equalsIgnoreCase(market)) {
            zone = ZoneId.of("Asia/Taipei"); close = LocalTime.of(13, 30);
        } else if ("美股".equals(market) || "US".equalsIgnoreCase(market)) {
            zone = ZoneId.of("America/New_York"); close = LocalTime.of(16, 0);
        } else if ("英股".equals(market) || "UK".equalsIgnoreCase(market)) {
            zone = ZoneId.of("Europe/London"); close = LocalTime.of(16, 30);
        } else return null;
        ZonedDateTime local = now.atZone(zone);
        LocalDate date = local.toLocalTime().isBefore(close) ? local.toLocalDate().minusDays(1) : local.toLocalDate();
        for (int i = 0; i <= 20; i++, date = date.minusDays(1)) {
            Boolean open = known(market, date);
            if (open == null) return null;
            if (open) return date;
        }
        return null;
    }

    private Boolean known(String market, LocalDate date) {
        Optional<Boolean> value = calendar.isTradingDayCachedOnly(market, date);
        return value == null ? null : value.orElse(null);
    }

    static Map<LocalDate, BigDecimal> validatedCloses(List<StockPriceHistory> rows, LocalDate from, LocalDate to) {
        if (rows == null) return null;
        Map<LocalDate, BigDecimal> result = new HashMap<>();
        LocalDate last = null;
        for (var row : rows) {
            if (row == null || row.getTradingDate() == null || !positive(row.getClosePrice())) return null;
            LocalDate date = row.getTradingDate();
            if (date.isBefore(from) || date.isAfter(to) || (last != null && !date.isAfter(last))) return null;
            result.put(date, row.getClosePrice()); last = date;
        }
        return result;
    }

    static BigDecimal decimal(BigDecimal value, int precision) {
        if (!positive(value)) return null;
        BigDecimal scaled = value.setScale(4, RoundingMode.HALF_UP);
        return scaled.signum() > 0 && scaled.precision() <= precision ? scaled : null;
    }

    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
}
