package com.steven.assets.service;

import com.steven.assets.dto.TreasuryYieldDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/** Business-side Treasury proxy、atomic upsert orchestration 與 decision query。 */
@Service
public class TreasuryYieldService {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final java.time.LocalTime US_CLOSE = java.time.LocalTime.of(16, 0);
    private static final int MAX_CURVE_LAG_SESSIONS = 3;
    private static final String FUTURE_CURVE_DATE_CODE = "FUTURE_CURVE_DATE";
    private final TreasuryYieldBatchRepository repository;
    private final TreasuryYieldClient client;
    private final Clock clock;
    /** Optional for legacy constructor callers; Spring production wiring supplies the authoritative US calendar. */
    private final MarketDataService marketDataService;

    @Autowired
    public TreasuryYieldService(
            TreasuryYieldBatchRepository repository,
            TreasuryYieldClient client,
            MarketDataService marketDataService) {
        this(repository, client, Clock.systemUTC(), marketDataService);
    }

    /** Compatibility constructor for tests/adapters before calendar-aware Treasury freshness. */
    public TreasuryYieldService(TreasuryYieldBatchRepository repository, TreasuryYieldClient client) {
        this(repository, client, Clock.systemUTC(), null);
    }

    TreasuryYieldService(TreasuryYieldBatchRepository repository, TreasuryYieldClient client, Clock clock) {
        this(repository, client, clock, null);
    }

    TreasuryYieldService(
            TreasuryYieldBatchRepository repository,
            TreasuryYieldClient client,
            Clock clock,
            MarketDataService marketDataService) {
        this.repository = repository;
        this.client = client;
        this.clock = clock;
        this.marketDataService = marketDataService;
    }

    /** 省略 year 時供 controller/scheduler 刷新 New York 當地 current year。 */
    public TreasuryYieldDto.RefreshSummary refresh(Integer requestedYear) {
        int year = requestedYear == null ? LocalDate.now(clock.withZone(NEW_YORK)).getYear() : requestedYear;
        validateYear(year);
        List<TreasuryYieldDto.FetchBatch> fetched = client.fetch(year);
        if (fetched == null || fetched.isEmpty()) {
            throw new IllegalStateException("Treasury " + year + " 官方與 fallback 均無可落地 batch");
        }

        int inserted = 0;
        int noOp = 0;
        int complete = 0;
        int incomplete = 0;
        for (TreasuryYieldDto.FetchBatch batch : fetched) {
            TreasuryYieldDto.PersistResult result = repository.persist(batch);
            if (result.inserted()) inserted++; else noOp++;
            if (result.complete()) complete++; else incomplete++;
        }
        return new TreasuryYieldDto.RefreshSummary(year, fetched.size(), inserted, noOp, complete, incomplete);
    }

    public List<TreasuryYieldDto.StoredBatch> findByYear(int year) {
        validateYear(year);
        return repository.findByYear(year);
    }

    /** 先選完整 batch，再回整條 curve；永不逐 tenor 選 provider。 */
    public Optional<TreasuryYieldDto.StoredBatch> resolveSelected(Instant decisionInstant) {
        return repository.findSelected(decisionInstant == null ? clock.instant() : decisionInstant);
    }

    /**
     * 供 BondRateFactorService/回測整合的 typed context。即使只取一個 tenor，sourceManifest 仍是完整四筆。
     */
    public Optional<TreasuryYieldDto.RateContext> resolveRateContext(Instant decisionInstant, String tenor) {
        if (!TreasuryYieldBatchRepository.TENOR_ORDER.contains(tenor)) {
            throw new IllegalArgumentException("Treasury tenor 必須是 M3/Y5/Y10/Y30：" + tenor);
        }
        Instant at = decisionInstant == null ? clock.instant() : decisionInstant;
        return repository.findSelected(at).map(batch -> {
            if (!batch.complete() || batch.values().size() != 4 || !batch.values().containsKey(tenor)) {
                // Store resolver 已防禦；這裡保留第二層，禁止 incomplete header 靜默進規則。
                throw new IllegalStateException("Treasury selected batch 不完整：" + batch.batchId());
            }
            LocalDate decisionDateEt = at.atZone(NEW_YORK).toLocalDate();
            LocalDate expected = latestCompletedUsSession(decisionDateEt,
                    at.atZone(NEW_YORK).toLocalTime().isBefore(US_CLOSE));
            String staleReason;
            if (expected == null) {
                staleReason = "Treasury required completed US session 無法由權威日曆確認";
            } else if (batch.curveDate().isAfter(expected)) {
                staleReason = FUTURE_CURVE_DATE_CODE + ": Treasury curve date "
                        + batch.curveDate() + " 晚於 decision-time expected completed US session " + expected;
            } else {
                long sessionLag = sessionLag(batch.curveDate(), expected);
                staleReason = sessionLag > MAX_CURVE_LAG_SESSIONS
                        ? "Treasury curve 落後要求 completed US session " + sessionLag
                        + " sessions（上限 " + MAX_CURVE_LAG_SESSIONS + "）" : null;
            }
            long lagDays = Math.max(0, ChronoUnit.DAYS.between(batch.curveDate(), decisionDateEt));
            return new TreasuryYieldDto.RateContext(batch.batchId(), true, tenor,
                    batch.values().get(tenor), batch.curveDate(), batch.provider(), batch.sourceManifest(),
                    batch.availableAt(), batch.availabilityBasis(), batch.fetchedAt(), lagDays, staleReason);
        });
    }

    private LocalDate latestCompletedUsSession(LocalDate date, boolean beforeClose) {
        LocalDate candidate = beforeClose ? date.minusDays(1) : date;
        for (int i = 0; i < 370; i++, candidate = candidate.minusDays(1)) {
            java.util.Optional<Boolean> known = marketDataService == null
                    ? java.util.Optional.of(candidate.getDayOfWeek().getValue() <= 5)
                    : marketDataService.isTradingDayKnown("美股", candidate);
            if (known == null || known.isEmpty()) return null;
            if (known.get()) return candidate;
        }
        return null;
    }

    private long sessionLag(LocalDate curveDate, LocalDate expected) {
        if (curveDate == null || expected == null || !curveDate.isBefore(expected)) return 0;
        long count = 0;
        for (LocalDate d = curveDate.plusDays(1); !d.isAfter(expected); d = d.plusDays(1)) {
            java.util.Optional<Boolean> known = marketDataService == null
                    ? java.util.Optional.of(d.getDayOfWeek().getValue() <= 5)
                    : marketDataService.isTradingDayKnown("美股", d);
            if (known == null || known.isEmpty()) return MAX_CURVE_LAG_SESSIONS + 1;
            if (known.get()) count++;
        }
        return count;
    }

    private void validateYear(int year) {
        int currentYear = LocalDate.now(clock.withZone(NEW_YORK)).getYear();
        if (year < 1990 || year > currentYear) {
            throw new IllegalArgumentException("Treasury year 必須介於 1990 與 " + currentYear + "：" + year);
        }
    }
}
