package com.steven.assets.service;

import com.steven.assets.dto.TreasuryYieldDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Business-side Treasury proxy、atomic upsert orchestration 與 decision query。 */
@Service
public class TreasuryYieldService {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final java.time.LocalTime US_CLOSE = java.time.LocalTime.of(16, 0);
    private static final int MAX_CURVE_LAG_SESSIONS = 3;
    private static final String FUTURE_CURVE_DATE_CODE = "FUTURE_CURVE_DATE";
    private static final String UNKNOWN_CALENDAR_CODE = "UNKNOWN_CALENDAR";
    private final TreasuryYieldBatchRepository repository;
    private final TreasuryYieldClient client;
    private final Clock clock;
    private final TradingRadarSessionCalendarPort sessionCalendar;

    /** Compatibility callers fail closed; only Spring's typed adapter can supply known sessions. */
    private static final TradingRadarSessionCalendarPort UNAVAILABLE_CALENDAR = (market, date) ->
            new TradingRadarSessionCalendarPort.DayResolution(
                    date, TradingRadarSessionCalendarPort.Status.UNKNOWN,
                    "UNAVAILABLE_CALENDAR_PORT",
                    TradingRadarSessionCalendarPort.MARKET_CALENDAR_UNAVAILABLE);

    @Autowired
    public TreasuryYieldService(
            TreasuryYieldBatchRepository repository,
            TreasuryYieldClient client,
            TradingRadarSessionCalendarPort sessionCalendar) {
        this(repository, client, Clock.systemUTC(), sessionCalendar);
    }

    /** Compatibility constructor for tests/adapters before calendar-aware Treasury freshness. */
    public TreasuryYieldService(TreasuryYieldBatchRepository repository, TreasuryYieldClient client) {
        this(repository, client, Clock.systemUTC(), UNAVAILABLE_CALENDAR);
    }

    TreasuryYieldService(TreasuryYieldBatchRepository repository, TreasuryYieldClient client, Clock clock) {
        this(repository, client, clock, UNAVAILABLE_CALENDAR);
    }

    TreasuryYieldService(
            TreasuryYieldBatchRepository repository,
            TreasuryYieldClient client,
            Clock clock,
            TradingRadarSessionCalendarPort sessionCalendar) {
        this.repository = repository;
        this.client = client;
        this.clock = clock;
        this.sessionCalendar = java.util.Objects.requireNonNull(sessionCalendar, "sessionCalendar");
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
        return selectedRateBatch(at).map(selected -> rateContext(selected, tenor));
    }

    /** One selected complete curve and one calendar check for every requested tenor in a list. */
    public Map<String, TreasuryYieldDto.RateContext> resolveRateContexts(
            Instant decisionInstant, Set<String> rawTenors) {
        if (rawTenors == null || rawTenors.isEmpty()) return Map.of();
        List<String> tenors = rawTenors.stream().filter(TreasuryYieldBatchRepository.TENOR_ORDER::contains)
                .sorted().toList();
        if (tenors.isEmpty()) return Map.of();
        Instant at = decisionInstant == null ? clock.instant() : decisionInstant;
        Optional<SelectedRateBatch> selected = selectedRateBatch(at);
        if (selected.isEmpty()) return Map.of();
        Map<String, TreasuryYieldDto.RateContext> out = new java.util.LinkedHashMap<>();
        for (String tenor : tenors) out.put(tenor, rateContext(selected.get(), tenor));
        return Map.copyOf(out);
    }

    private Optional<SelectedRateBatch> selectedRateBatch(Instant at) {
        return repository.findSelected(at).map(batch -> toSelectedRateBatch(batch, at));
    }

    /**
     * Task 447.3：預先載入請求範圍內全部 curve batch 的所有 revision（不去重複，見
     * {@link TreasuryYieldBatchRepository#findAllRevisionsThrough}），供
     * {@link #resolveRateContextFromSeries} 在記憶體內對每個 decisionInstant 各自選批次，
     * 取代回測逐日觸發 {@link TreasuryYieldBatchRepository#findSelected} 的真實 SQL 查詢。
     * {@code latestDecisionInstant} 只需是「不早於本次請求任何 decisionInstant」的上界
     * （呼叫端一律傳目前時刻），不必是精確值。
     */
    public List<TreasuryYieldDto.StoredBatch> preloadRevisionsThrough(Instant latestDecisionInstant) {
        return repository.findAllRevisionsThrough(
                latestDecisionInstant == null ? clock.instant() : latestDecisionInstant);
    }

    /**
     * Task 447.3：{@link #resolveRateContext} 的 series 版本——消費
     * {@link #preloadRevisionsThrough} 預先載入、未去重複的 revision 列表，在記憶體內重現
     * {@link TreasuryYieldBatchRepository#findSelected} 的選批次規則，不逐日觸發 SQL。
     *
     * <p>{@code series} 中每一列都已經通過與 {@code findSelected} 完全相同的 completeness／
     * tenor 有效性 SQL 篩選，故此處不重新驗證合法 tenor 集合。任一 {@code (series,
     * decisionInstant, tenor)} 組合的輸出必須與「先 {@code repository.findSelected(decisionInstant)}
     * 取得同一個 batch id、再呼叫 {@link #rateContext}」的結果逐位元相同。</p>
     */
    public Optional<TreasuryYieldDto.RateContext> resolveRateContextFromSeries(
            List<TreasuryYieldDto.StoredBatch> series, Instant decisionInstant, String tenor) {
        if (!TreasuryYieldBatchRepository.TENOR_ORDER.contains(tenor)) {
            throw new IllegalArgumentException("Treasury tenor 必須是 M3/Y5/Y10/Y30：" + tenor);
        }
        Instant at = decisionInstant == null ? clock.instant() : decisionInstant;
        return selectFromSeries(series, at).map(selected -> rateContext(selected, tenor));
    }

    /**
     * 在記憶體內重現 {@code findSelected} 的 {@code ORDER BY curve_date DESC, provider CASE
     * (US_TREASURY 優先於 YAHOO_PROXY), available_at DESC, fetched_at DESC, id DESC LIMIT 1}：
     * 先篩出 {@code available_at <= at}，再用鏡射該排序的比較器取整體最大值（非逐 curve_date
     * 分組後取最大——SQL 本身就是對整個候選集合排序取第一筆，不是先分組）。provider 的比較鍵
     * 刻意讓 {@code US_TREASURY} 對應較大值，以便用 {@code max} 語意直接表達「優先」。
     */
    private Optional<SelectedRateBatch> selectFromSeries(
            List<TreasuryYieldDto.StoredBatch> series, Instant at) {
        if (series == null || series.isEmpty()) return Optional.empty();
        return series.stream()
                .filter(batch -> batch.availableAt() != null && !batch.availableAt().isAfter(at))
                .max(Comparator.comparing(TreasuryYieldDto.StoredBatch::curveDate)
                        .thenComparing(batch -> "US_TREASURY".equals(batch.provider()) ? 1 : 0)
                        .thenComparing(TreasuryYieldDto.StoredBatch::availableAt)
                        .thenComparing(TreasuryYieldDto.StoredBatch::fetchedAt)
                        .thenComparing(TreasuryYieldDto.StoredBatch::batchId))
                .map(batch -> toSelectedRateBatch(batch, at));
    }

    private SelectedRateBatch toSelectedRateBatch(TreasuryYieldDto.StoredBatch batch, Instant at) {
        if (!batch.complete() || batch.values().size() != 4) {
            throw new IllegalStateException("Treasury selected batch 不完整：" + batch.batchId());
        }
        LocalDate decisionDateEt = at.atZone(NEW_YORK).toLocalDate();
        CompletedSessionResolution completed = latestCompletedUsSession(decisionDateEt,
                at.atZone(NEW_YORK).toLocalTime().isBefore(US_CLOSE));
        LocalDate expected = completed.date();
        String staleReason;
        if (completed.unknown() != null) {
            staleReason = unknownCalendarReason(completed.unknown());
        } else if (batch.curveDate().isAfter(expected)) {
            staleReason = FUTURE_CURVE_DATE_CODE + ": Treasury curve date "
                    + batch.curveDate() + " 晚於 decision-time expected completed US session " + expected;
        } else {
            SessionLagResolution lag = sessionLag(batch.curveDate(), expected);
            staleReason = lag.unknown() != null ? unknownCalendarReason(lag.unknown())
                    : lag.sessions() > MAX_CURVE_LAG_SESSIONS
                    ? "Treasury curve 落後要求 completed US session " + lag.sessions()
                    + " sessions（上限 " + MAX_CURVE_LAG_SESSIONS + "）" : null;
        }
        long lagDays = Math.max(0, ChronoUnit.DAYS.between(batch.curveDate(), decisionDateEt));
        return new SelectedRateBatch(batch, lagDays, staleReason);
    }

    private TreasuryYieldDto.RateContext rateContext(SelectedRateBatch selected, String tenor) {
        TreasuryYieldDto.StoredBatch batch = selected.batch();
        if (!batch.values().containsKey(tenor)) {
            throw new IllegalStateException("Treasury selected batch tenor 缺漏：" + tenor);
        }
        return new TreasuryYieldDto.RateContext(batch.batchId(), true, tenor,
                batch.values().get(tenor), batch.curveDate(), batch.provider(), batch.sourceManifest(),
                batch.availableAt(), batch.availabilityBasis(), batch.fetchedAt(), selected.lagDays(),
                selected.staleReason());
    }

    private record SelectedRateBatch(
            TreasuryYieldDto.StoredBatch batch, long lagDays, String staleReason) {}

    private record CompletedSessionResolution(
            LocalDate date, TradingRadarSessionCalendarPort.DayResolution unknown) {}

    private record SessionLagResolution(
            long sessions, TradingRadarSessionCalendarPort.DayResolution unknown) {}

    private CompletedSessionResolution latestCompletedUsSession(LocalDate date, boolean beforeClose) {
        LocalDate candidate = beforeClose ? date.minusDays(1) : date;
        for (int i = 0; i < 370; i++, candidate = candidate.minusDays(1)) {
            TradingRadarSessionCalendarPort.DayResolution day = resolveDay(candidate);
            if (day.status() == TradingRadarSessionCalendarPort.Status.UNKNOWN) {
                return new CompletedSessionResolution(null, day);
            }
            if (day.status() == TradingRadarSessionCalendarPort.Status.OPEN) {
                return new CompletedSessionResolution(candidate, null);
            }
        }
        return new CompletedSessionResolution(null, new TradingRadarSessionCalendarPort.DayResolution(
                candidate, TradingRadarSessionCalendarPort.Status.UNKNOWN,
                "CALENDAR_SEARCH_BOUND",
                TradingRadarSessionCalendarPort.MARKET_CALENDAR_UNAVAILABLE));
    }

    private SessionLagResolution sessionLag(LocalDate curveDate, LocalDate expected) {
        if (curveDate == null || expected == null || !curveDate.isBefore(expected)) {
            return new SessionLagResolution(0, null);
        }
        long count = 0;
        for (LocalDate d = curveDate.plusDays(1); !d.isAfter(expected); d = d.plusDays(1)) {
            TradingRadarSessionCalendarPort.DayResolution day = resolveDay(d);
            if (day.status() == TradingRadarSessionCalendarPort.Status.UNKNOWN) {
                return new SessionLagResolution(count, day);
            }
            if (day.status() == TradingRadarSessionCalendarPort.Status.OPEN) count++;
        }
        return new SessionLagResolution(count, null);
    }

    private TradingRadarSessionCalendarPort.DayResolution resolveDay(LocalDate date) {
        try {
            TradingRadarSessionCalendarPort.DayResolution result = sessionCalendar.resolve("美股", date);
            if (result != null) return result;
        } catch (RuntimeException ignored) {
            // Even a broken custom port must fail closed with the same stable reason.
        }
        return new TradingRadarSessionCalendarPort.DayResolution(
                date, TradingRadarSessionCalendarPort.Status.UNKNOWN,
                "CALENDAR_PORT", TradingRadarSessionCalendarPort.MARKET_CALENDAR_UNAVAILABLE);
    }

    private String unknownCalendarReason(TradingRadarSessionCalendarPort.DayResolution resolution) {
        return UNKNOWN_CALENDAR_CODE + ": provider="
                + java.util.Objects.toString(resolution.provider(), "UNKNOWN")
                + ", reason=" + java.util.Objects.toString(
                resolution.reason(), TradingRadarSessionCalendarPort.MARKET_CALENDAR_UNAVAILABLE)
                + ", date=" + resolution.date();
    }

    private void validateYear(int year) {
        int currentYear = LocalDate.now(clock.withZone(NEW_YORK)).getYear();
        if (year < 1990 || year > currentYear) {
            throw new IllegalArgumentException("Treasury year 必須介於 1990 與 " + currentYear + "：" + year);
        }
    }
}
