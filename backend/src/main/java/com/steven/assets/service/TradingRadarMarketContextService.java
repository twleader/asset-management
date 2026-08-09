package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.model.News;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.UsIndexDailyHistory;
import com.steven.assets.repository.ExchangeRateHistoryRepository;
import com.steven.assets.repository.NewsHeadlineRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 今日交易雷達的全域市場脈絡組裝者（Task 291）。
 *
 * <p>台股量能、IXIC／SOX、匯率與新聞內容一律讀本地 PostgreSQL；唯一跨服務呼叫是
 * {@link MarketDataService#isTwTradingDayKnown(LocalDate)} 的既有權威假日日曆 proxy／快取。
 * 所有入口都要求顯式 {@code decisionInstant}，內部不讀系統日期，production 與歷史回測因而
 * 共用同一套完成日與防前視規則。</p>
 *
 * <p>新聞只做來源、時間與原文連結揭露，不做正負情緒評分。現有資料沒有可信的結構化方向欄位；
 * 用關鍵字會把「利空出盡」「跌幅收斂」等語句反向誤判。可量化的大盤量價與美股科技日報酬才進分數。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TradingRadarMarketContextService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalTime TW_MARKET_COMPLETE = LocalTime.of(14, 0);
    private static final LocalTime FX_COMPLETE = LocalTime.of(17, 0);
    private static final LocalTime US_MARKET_COMPLETE = LocalTime.of(16, 0);
    private static final int RATIO_LOOKBACK = 20;
    private static final int RATIO_MIN_SAMPLES = 10;
    private static final int FX_MIN_SAMPLES = 600;
    private static final int FX_LOOKBACK_YEARS = 5;
    private static final int MAX_CALENDAR_LOOKBACK = 20;
    /**
     * {@link #resolveMarket} 的台股 bounded 查詢筆數（Task 302，取代舊版全表掃描）。
     * {@code resolveMarketFromRows} 只用最新一筆＋前 {@link #RATIO_LOOKBACK}（20）個正量能日
     * 與漲跌用前一列；60 筆為假日與缺量能列留餘裕。
     */
    private static final int MARKET_TW_BOUNDED_ROWS = 60;
    /**
     * {@link #resolveMarket} 的 IXIC／SOX 各自 bounded 查詢筆數。{@link #resolveUsTech} 只需
     * 最新共同日＋各自嚴格前一筆，且共同日距 decision instant 超過 5 個日曆日整組即 unavailable，
     * 15 筆已足夠、遠小於 {@link #MARKET_TW_BOUNDED_ROWS}。
     */
    private static final int MARKET_US_BOUNDED_ROWS = 15;

    private final TwseIndexDailyHistoryRepository twseRepo;
    private final UsIndexDailyHistoryRepository usIndexRepo;
    private final ExchangeRateHistoryRepository exchangeRateRepo;
    private final NewsHeadlineRepository newsRepo;
    private final MarketDataService marketDataService;

    public record MarketContext(
            LocalDate marketAsOfDate,
            BigDecimal completedMarketChangePercent,
            BigDecimal marketVolumeRatio,
            BigDecimal marketTurnoverRatio,
            BigDecimal nasdaqChangePercent,
            BigDecimal soxChangePercent,
            BigDecimal usTechCompositePercent,
            LocalDate usTechAsOfDate,
            boolean usTechAvailable,
            /** Explicit capability: true when this market index has meaningful liquidity data. */
            boolean liquidityApplicable
    ) {
        /** Compatibility shape used by older callers; known contexts are applicable. */
        public MarketContext(
                LocalDate marketAsOfDate,
                BigDecimal completedMarketChangePercent,
                BigDecimal marketVolumeRatio,
                BigDecimal marketTurnoverRatio,
                BigDecimal nasdaqChangePercent,
                BigDecimal soxChangePercent,
                BigDecimal usTechCompositePercent,
                LocalDate usTechAsOfDate,
                boolean usTechAvailable) {
            this(marketAsOfDate, completedMarketChangePercent, marketVolumeRatio,
                    marketTurnoverRatio, nasdaqChangePercent, soxChangePercent,
                    usTechCompositePercent, usTechAsOfDate, usTechAvailable, true);
        }

        public static final MarketContext EMPTY = new MarketContext(
                null, null, null, null, null, null, null, null, false, false);
    }

    public record FxContext(BigDecimal percentile, LocalDate asOfDate) {
        public static final FxContext EMPTY = new FxContext(null, null);
    }

    /**
     * Request-scoped calendar evidence for session-based historical windows.
     * {@code known=false} is distinct from a legitimate empty range: callers must
     * fail closed when the authority cannot prove the calendar for every date.
     */
    public record TradingSessions(List<LocalDate> dates, boolean known) {
        public TradingSessions {
            dates = dates == null ? List.of() : List.copyOf(dates);
        }

        public static TradingSessions unavailable() {
            return new TradingSessions(List.of(), false);
        }
    }

    /**
     * Resolve a bounded inclusive market calendar once for a caller's request.
     * No weekday approximation is permitted when the TW calendar proxy is
     * unavailable; an unknown date invalidates the whole range.
     */
    public TradingSessions resolveTradingSessions(String market, LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to) || marketDataService == null) {
            return TradingSessions.unavailable();
        }
        List<LocalDate> sessions = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            Optional<Boolean> tradingDay = marketDataService.isTradingDayKnown(market, date);
            if (tradingDay == null || tradingDay.isEmpty()) {
                return TradingSessions.unavailable();
            }
            if (tradingDay.get()) sessions.add(date);
        }
        return new TradingSessions(sessions, true);
    }

    /**
     * Resolve the two session dates needed by one radar decision from the
     * authoritative calendar.  {@code Optional.empty()} is a real unknown
     * state (calendar outage), not permission to infer a weekday.  Returning an
     * Optional also lets compatibility-only test adapters that predate this
     * method be distinguished from a production calendar failure.
     */
    public Optional<RadarObservationResolver.DecisionSessions> resolveDecisionSessions(
            String market, Instant decisionInstant) {
        if (market == null || decisionInstant == null || marketDataService == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(RadarObservationResolver.decisionSessionsStrict(
                market, decisionInstant, date -> marketDataService.isTradingDayKnown(market, date)));
    }

    public record Resolved(
            MarketContext market,
            List<TradingRadarDto.PublicInformationItem> publicInformation
    ) {}

    /** Production 入口：同一個 decision instant 組成市場數字與近 72 小時公開資訊。 */
    public Resolved resolve(Instant decisionInstant) {
        if (decisionInstant == null) {
            return new Resolved(MarketContext.EMPTY, List.of());
        }
        MarketContext market = resolveMarket(decisionInstant);

        List<TradingRadarDto.PublicInformationItem> publicInformation;
        try {
            publicInformation = publicInformation(decisionInstant);
        } catch (Exception e) {
            log.warn("交易雷達公開財經資訊讀取失敗：{}", e.toString());
            publicInformation = List.of();
        }
        return new Resolved(market, publicInformation);
    }

    /**
     * Bounded 版市場 as-of 入口（Task 302）：只查最新所需筆數，取代 {@link #resolve} 舊版對台股
     * 與美股指數全表掃描（10 年 ×250 筆／IXIC／SOX 全史）。委派既有純函數
     * {@link #resolveMarketFromRows}，本體與簽章不動，故回測（走全表掃描版本）不受影響。
     *
     * <p>Fail-soft：bounded 查詢或委派計算失敗一律回 {@link MarketContext#EMPTY} 並記警告，
     * 不拋出——{@link TradingRadarService#buildMarketSnapshot} 經由本方法組裝通知路徑的批次
     * 大盤快照，該呼叫站在 {@code TradingRadarNotificationService#flushEvaluations} 逐檔 catch
     * 之外，DB 例外絕不可逸出整輪 flush。</p>
     */
    public MarketContext resolveMarket(Instant decisionInstant) {
        if (decisionInstant == null) return MarketContext.EMPTY;
        try {
            List<UsIndexDailyHistory> usRows = new ArrayList<>();
            usRows.addAll(usIndexRepo.findTopNByIndexCodeOrderByTradingDateDesc("IXIC", MARKET_US_BOUNDED_ROWS));
            usRows.addAll(usIndexRepo.findTopNByIndexCodeOrderByTradingDateDesc("SOX", MARKET_US_BOUNDED_ROWS));
            return resolveMarketFromRows(
                    decisionInstant,
                    twseRepo.findTopNByOrderByTradingDateDesc(MARKET_TW_BOUNDED_ROWS),
                    usRows);
        } catch (Exception e) {
            log.warn("交易雷達市場脈絡讀取失敗：{}", e.toString());
            return MarketContext.EMPTY;
        }
    }

    /**
     * 純計算的市場 as-of 入口，供 production 與回測共用；即使傳入包含未來日期的序列也會先截斷。
     */
    public MarketContext resolveMarketFromRows(
            Instant decisionInstant,
            List<TwseIndexDailyHistory> twRows,
            List<UsIndexDailyHistory> usRows) {
        if (decisionInstant == null) return MarketContext.EMPTY;

        List<TwseIndexDailyHistory> tw = twRows == null ? List.of() : twRows.stream()
                .filter(r -> r != null && r.getTradingDate() != null)
                .filter(r -> !twCompletion(r.getTradingDate()).isAfter(decisionInstant))
                .sorted(Comparator.comparing(TwseIndexDailyHistory::getTradingDate))
                .toList();

        LocalDate marketDate = null;
        BigDecimal marketChange = null;
        BigDecimal volumeRatio = null;
        BigDecimal turnoverRatio = null;
        if (!tw.isEmpty()) {
            int latestIndex = tw.size() - 1;
            TwseIndexDailyHistory latest = tw.get(latestIndex);
            marketDate = latest.getTradingDate();
            if (latestIndex > 0) {
                marketChange = percentChange(latest.getClosePoint(), tw.get(latestIndex - 1).getClosePoint());
            }
            volumeRatio = ratio(latest.getTradeVolume() == null
                            ? null : BigDecimal.valueOf(latest.getTradeVolume()),
                    priorPositive(tw, latestIndex, true));
            turnoverRatio = ratio(latest.getTradeValue(), priorPositive(tw, latestIndex, false));
        }

        UsTech us = resolveUsTech(decisionInstant, usRows);
        return new MarketContext(
                marketDate, marketChange, volumeRatio, turnoverRatio,
                us.nasdaqChange(), us.soxChange(), us.composite(), us.asOfDate(), us.available(), true);
    }

    /**
     * V13 的 market-aware context。台股與美股不可共用同一個 marketAsOf／量能來源：
     * 美股只使用 IXIC 的完成日與成交量，台股則使用 TAIEX；不存在可用來源時回傳
     * {@link MarketContext#EMPTY} 欄位，而不是把另一市場的數字代入。
     *
     * <p>台股 daily volume/value 沒有 immutable observed-at 欄位，因此 V13 採較保守的
     * 16:00 completion boundary；14:00 signal 看到的同日 final row 會被排除，避免把收盤後
     * 知道的全天量能前視帶入。</p>
     */
    public MarketContext resolveMarketFromRows(
            String market,
            Instant decisionInstant,
            List<TwseIndexDailyHistory> twRows,
            List<UsIndexDailyHistory> usRows) {
        if (decisionInstant == null || market == null) return MarketContext.EMPTY;
        if ("台股".equals(market)) {
            return resolveTaiwanV13(decisionInstant, twRows);
        }
        if ("美股".equals(market)) {
            return resolveUsV13(decisionInstant, usRows);
        }
        return MarketContext.EMPTY;
    }

    private MarketContext resolveTaiwanV13(
            Instant decisionInstant, List<TwseIndexDailyHistory> suppliedRows) {
        List<TwseIndexDailyHistory> rows = suppliedRows == null ? List.of() : suppliedRows.stream()
                .filter(r -> r != null && r.getTradingDate() != null
                        && !twCompletionV13(r.getTradingDate()).isAfter(decisionInstant))
                .sorted(Comparator.comparing(TwseIndexDailyHistory::getTradingDate))
                .toList();
        if (rows.isEmpty()) return MarketContext.EMPTY;
        int latestIndex = rows.size() - 1;
        TwseIndexDailyHistory latest = rows.get(latestIndex);
        BigDecimal change = latestIndex > 0
                ? percentChange(latest.getClosePoint(), rows.get(latestIndex - 1).getClosePoint()) : null;
        BigDecimal volume = ratio(latest.getTradeVolume() == null
                        ? null : BigDecimal.valueOf(latest.getTradeVolume()),
                priorPositive(rows, latestIndex, true));
        BigDecimal turnover = ratio(latest.getTradeValue(), priorPositive(rows, latestIndex, false));
        return new MarketContext(latest.getTradingDate(), change, volume, turnover,
                null, null, null, null, false, true);
    }

    private MarketContext resolveUsV13(
            Instant decisionInstant, List<UsIndexDailyHistory> suppliedRows) {
        List<UsIndexDailyHistory> ixic = suppliedRows == null ? List.of() : suppliedRows.stream()
                .filter(r -> r != null && "IXIC".equals(r.getIndexCode())
                        && r.getTradingDate() != null && r.getClosePoint() != null
                        && r.getClosePoint().signum() > 0
                        && !usCompletion(r.getTradingDate()).isAfter(decisionInstant))
                .sorted(Comparator.comparing(UsIndexDailyHistory::getTradingDate))
                .toList();
        if (ixic.isEmpty()) return MarketContext.EMPTY;
        int latestIndex = ixic.size() - 1;
        UsIndexDailyHistory latest = ixic.get(latestIndex);
        BigDecimal change = latestIndex > 0
                ? percentChange(latest.getClosePoint(), ixic.get(latestIndex - 1).getClosePoint()) : null;
        List<BigDecimal> prior = new ArrayList<>();
        for (int i = latestIndex - 1; i >= 0 && prior.size() < RATIO_LOOKBACK; i--) {
            Long raw = ixic.get(i).getVolume();
            if (raw != null && raw > 0) prior.add(BigDecimal.valueOf(raw));
        }
        BigDecimal volume = ratio(latest.getVolume() == null
                        ? null : BigDecimal.valueOf(latest.getVolume()), prior);
        UsTech us = resolveUsTech(decisionInstant,
                suppliedRows == null ? List.of() : suppliedRows);
        return new MarketContext(latest.getTradingDate(), change, volume, null,
                us.nasdaqChange(), us.soxChange(), us.composite(), us.asOfDate(), us.available(), true);
    }

    /** Production 匯率入口；只查目標日往前五年，無精確有效目標列就 fail closed。 */
    public FxContext resolveFx(String currency, Instant decisionInstant) {
        if (currency == null || currency.isBlank() || decisionInstant == null) return FxContext.EMPTY;
        LocalDate target = fxTargetDate(decisionInstant);
        if (target == null) return FxContext.EMPTY;
        try {
            List<ExchangeRateHistory> rows = exchangeRateRepo
                    .findByCurrencyAndRateDateBetweenOrderByRateDateAsc(
                            currency.trim().toUpperCase(), target.minusYears(FX_LOOKBACK_YEARS), target);
            return resolveFxFromRows(currency, decisionInstant, rows);
        } catch (Exception e) {
            log.warn("交易雷達匯率脈絡讀取失敗（{}）：{}", currency, e.toString());
            return FxContext.EMPTY;
        }
    }

    /**
     * 匯率純計算入口。只接受 target date 精確列，且排除缺值、非正與 buy=sell 的 fallback 中間價。
     */
    public FxContext resolveFxFromRows(
            String currency, Instant decisionInstant, List<ExchangeRateHistory> suppliedRows) {
        if (currency == null || currency.isBlank() || decisionInstant == null) return FxContext.EMPTY;
        LocalDate target = fxTargetDate(decisionInstant);
        if (target == null) return FxContext.EMPTY;
        LocalDate lower = target.minusYears(FX_LOOKBACK_YEARS);
        String normalizedCurrency = currency.trim().toUpperCase();
        List<ExchangeRateHistory> valid = suppliedRows == null ? List.of() : suppliedRows.stream()
                .filter(r -> r != null && normalizedCurrency.equalsIgnoreCase(r.getCurrency()))
                .filter(this::validFxRow)
                .filter(r -> !r.getRateDate().isBefore(lower) && !r.getRateDate().isAfter(target))
                .sorted(Comparator.comparing(ExchangeRateHistory::getRateDate))
                .toList();
        ExchangeRateHistory exact = valid.stream()
                .filter(r -> target.equals(r.getRateDate()))
                .reduce((first, second) -> second)
                .orElse(null);
        if (exact == null || valid.size() < FX_MIN_SAMPLES) return FxContext.EMPTY;

        BigDecimal reference = midRate(exact);
        long atOrBelow = valid.stream()
                .map(this::midRate)
                .filter(v -> v.compareTo(reference) <= 0)
                .count();
        BigDecimal percentile = BigDecimal.valueOf(100.0 * atOrBelow / valid.size())
                .setScale(2, RoundingMode.HALF_UP);
        return new FxContext(percentile, target);
    }

    /**
     * 找出 decision instant 前已完成 17:00 的最近台股交易日；日曆 unknown 立即 fail closed。
     */
    public LocalDate fxTargetDate(Instant decisionInstant) {
        if (decisionInstant == null) return null;
        ZonedDateTime local = decisionInstant.atZone(TAIPEI);
        LocalDate candidate = local.toLocalTime().isBefore(FX_COMPLETE)
                ? local.toLocalDate().minusDays(1)
                : local.toLocalDate();
        for (int i = 0; i < MAX_CALENDAR_LOOKBACK; i++) {
            Optional<Boolean> tradingDay = marketDataService.isTwTradingDayKnown(candidate);
            if (tradingDay.isEmpty()) return null;
            if (tradingDay.get()) return candidate;
            candidate = candidate.minusDays(1);
        }
        return null;
    }

    private List<TradingRadarDto.PublicInformationItem> publicInformation(Instant decisionInstant) {
        Instant cutoff = decisionInstant.minus(72, ChronoUnit.HOURS);
        List<News> rows = newsRepo.findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(cutoff);
        if (rows == null || rows.isEmpty()) return List.of();
        Comparator<News> newest = Comparator
                .comparing(News::getPublishedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(News::getId, Comparator.nullsLast(Comparator.reverseOrder()));
        List<News> ordered = rows.stream()
                .filter(n -> n != null && News.CATEGORY_NEWS.equals(n.getCategory()))
                .filter(n -> n.getPublishedAt() != null && !n.getPublishedAt().isAfter(decisionInstant))
                // Historical market evidence must not expose an old article that was
                // only ingested after the decision boundary (late backfill).
                .filter(n -> n.getFetchedAt() != null && !n.getFetchedAt().isAfter(decisionInstant))
                .filter(n -> "TW".equals(n.getRegion()) || "US".equals(n.getRegion()))
                .sorted(newest)
                .toList();

        List<TradingRadarDto.PublicInformationItem> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        appendRegion(out, seen, ordered, "TW");
        appendRegion(out, seen, ordered, "US");
        return List.copyOf(out);
    }

    private void appendRegion(
            List<TradingRadarDto.PublicInformationItem> out,
            Set<String> seen,
            List<News> ordered,
            String region) {
        int count = 0;
        for (News n : ordered) {
            if (!region.equals(n.getRegion())) continue;
            String key = n.getDedupeKey() == null
                    ? n.getSource() + "\u0000" + n.getUrl()
                    : n.getDedupeKey();
            if (!seen.add(region + "\u0000" + key)) continue;
            out.add(new TradingRadarDto.PublicInformationItem(
                    region, n.getTitle(), n.getSource(), n.getUrl(), n.getPublishedAt().toString(),
                    null,
                    PublicInfoEvidenceResolver.knownAt(n) == null
                            ? null : PublicInfoEvidenceResolver.knownAt(n).toString(),
                    PublicInfoEvidenceResolver.availabilityBasis(n)));
            if (++count == 3) return;
        }
    }

    private record UsTech(
            BigDecimal nasdaqChange,
            BigDecimal soxChange,
            BigDecimal composite,
            LocalDate asOfDate,
            boolean available
    ) {
        private static final UsTech EMPTY = new UsTech(null, null, null, null, false);
    }

    private UsTech resolveUsTech(Instant decisionInstant, List<UsIndexDailyHistory> rows) {
        if (rows == null || rows.isEmpty()) return UsTech.EMPTY;
        Map<LocalDate, BigDecimal> ixic = new HashMap<>();
        Map<LocalDate, BigDecimal> sox = new HashMap<>();
        for (UsIndexDailyHistory row : rows) {
            if (row == null || row.getTradingDate() == null || row.getClosePoint() == null) continue;
            if (usCompletion(row.getTradingDate()).isAfter(decisionInstant)) continue;
            if ("IXIC".equals(row.getIndexCode())) ixic.put(row.getTradingDate(), row.getClosePoint());
            if ("SOX".equals(row.getIndexCode())) sox.put(row.getTradingDate(), row.getClosePoint());
        }
        LocalDate common = ixic.keySet().stream().filter(sox::containsKey).max(LocalDate::compareTo).orElse(null);
        if (common == null) return UsTech.EMPTY;
        long age = ChronoUnit.DAYS.between(common, decisionInstant.atZone(TAIPEI).toLocalDate());
        if (age < 0 || age > 5) return UsTech.EMPTY;
        LocalDate ixicPrevious = ixic.keySet().stream().filter(d -> d.isBefore(common)).max(LocalDate::compareTo).orElse(null);
        LocalDate soxPrevious = sox.keySet().stream().filter(d -> d.isBefore(common)).max(LocalDate::compareTo).orElse(null);
        if (ixicPrevious == null || soxPrevious == null) return UsTech.EMPTY;
        BigDecimal nasdaqChange = percentChange(ixic.get(common), ixic.get(ixicPrevious));
        BigDecimal soxChange = percentChange(sox.get(common), sox.get(soxPrevious));
        if (nasdaqChange == null || soxChange == null) return UsTech.EMPTY;
        BigDecimal composite = nasdaqChange.multiply(new BigDecimal("0.4"))
                .add(soxChange.multiply(new BigDecimal("0.6")))
                .setScale(4, RoundingMode.HALF_UP);
        return new UsTech(nasdaqChange, soxChange, composite, common, true);
    }

    private List<BigDecimal> priorPositive(List<TwseIndexDailyHistory> rows, int latestIndex, boolean volume) {
        List<BigDecimal> out = new ArrayList<>();
        for (int i = latestIndex - 1; i >= 0 && out.size() < RATIO_LOOKBACK; i--) {
            BigDecimal value = volume
                    ? (rows.get(i).getTradeVolume() == null ? null : BigDecimal.valueOf(rows.get(i).getTradeVolume()))
                    : rows.get(i).getTradeValue();
            if (value != null && value.signum() > 0) out.add(value);
        }
        return out;
    }

    private BigDecimal ratio(BigDecimal current, List<BigDecimal> prior) {
        if (current == null || current.signum() <= 0 || prior.size() < RATIO_MIN_SAMPLES) return null;
        List<BigDecimal> sorted = new ArrayList<>(prior);
        sorted.sort(Comparator.naturalOrder());
        BigDecimal median;
        int n = sorted.size();
        if (n % 2 == 1) {
            median = sorted.get(n / 2);
        } else {
            median = sorted.get(n / 2 - 1).add(sorted.get(n / 2))
                    .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
        }
        if (median.signum() <= 0) return null;
        return current.divide(median, 4, RoundingMode.HALF_UP);
    }

    private boolean validFxRow(ExchangeRateHistory row) {
        return row != null && row.getRateDate() != null
                && row.getBuyRate() != null && row.getBuyRate().signum() > 0
                && row.getSellRate() != null && row.getSellRate().signum() > 0
                && row.getBuyRate().compareTo(row.getSellRate()) != 0;
    }

    private BigDecimal midRate(ExchangeRateHistory row) {
        return row.getBuyRate().add(row.getSellRate())
                .divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal percentChange(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null || previous.signum() <= 0) return null;
        return current.subtract(previous)
                .divide(previous, 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private Instant twCompletion(LocalDate date) {
        return date.atTime(TW_MARKET_COMPLETE).atZone(TAIPEI).toInstant();
    }

    /** V13 conservative boundary for daily TAIEX volume/value without observed_at provenance. */
    private Instant twCompletionV13(LocalDate date) {
        return date.atTime(LocalTime.of(16, 0)).atZone(TAIPEI).toInstant();
    }

    private Instant usCompletion(LocalDate date) {
        return date.atTime(US_MARKET_COMPLETE).atZone(NEW_YORK).toInstant();
    }
}
