package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.util.MarketZones;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 交易雷達單次判斷的行情 observation 解析器。
 *
 * <p>Redis live 只能在「資料日期等於該市場 decision instant 的當地日期」且尚未存在
 * 同日完成列時採用。否則一律退回最近一筆可信完成收盤；不把 stale/future/invalid live
 * 當成技術序列未採用、但仍拿來當規則現價的第二個來源。</p>
 *
 * <p>本類別不持有 repository、clock 或 Spring state，production 與 backtest 可以共用，
 * 也能在沒有 Spring context 的單元測試中直接驗證。</p>
 */
public final class RadarObservationResolver {

    private static final Set<String> TRUSTED_TW_CLOSE_SOURCES = Set.of(
            "TWSE_MI_INDEX", "TPEX_DAILY_CLOSE", "FINMIND_TW_CLOSE");

    private RadarObservationResolver() {}

    /** Market-aware session pair selected by the explicit decision instant. */
    public record DecisionSessions(
            LocalDate currentLiveDate,
            LocalDate targetCompletedSession,
            boolean currentSessionTrading) {
        /** Compatibility shape for callers that only know the two dates. */
        public DecisionSessions(LocalDate currentLiveDate, LocalDate targetCompletedSession) {
            this(currentLiveDate, targetCompletedSession,
                    currentLiveDate != null && currentLiveDate.equals(targetCompletedSession));
        }
    }

    public enum Quality {
        LIVE,
        COMPLETED_CLOSE,
        STALE,
        INVALID,
        MISSING
    }

    /**
     * 被規則、技術序列與畫面共同採用的單一價格 observation。
     * {@code live} 只在 {@code liveAccepted=true} 時存在，避免呼叫端重新判斷日期。
     */
    public record AcceptedPrice(
            BigDecimal value,
            LocalDate tradingDate,
            String updatedAt,
            String source,
            Quality quality,
            boolean liveAccepted,
            PriceQueryService.LivePrice live,
            List<StockPriceHistory> trustedCompletedRows,
            String missingReason
    ) {
        public boolean available() {
            return value != null && tradingDate != null
                    && (quality == Quality.LIVE || quality == Quality.COMPLETED_CLOSE);
        }

        public String quoteStatus() {
            return switch (quality) {
                case LIVE -> "LIVE";
                case COMPLETED_CLOSE -> "VERIFIED_CLOSE";
                case STALE, INVALID, MISSING -> "CLOSE_PENDING";
            };
        }

        public static AcceptedPrice missing(Quality quality, String reason) {
            return new AcceptedPrice(null, null, null, null, quality, false, null, List.of(), reason);
        }
    }

    /**
     * 解析 accepted price。{@code completedRows} 預期為任意順序；方法會自行按日期排序，
     * 並拒絕超過 target date 的列，避免資料庫誤插入未來列污染歷史判斷。
     */
    public static AcceptedPrice resolveAcceptedPrice(
            List<StockPriceHistory> completedRows,
            PriceQueryService.LivePrice rawLive,
            String market,
            Instant decisionInstant) {
        return resolveAcceptedPrice(completedRows, rawLive, market, decisionInstant,
                RadarObservationResolver::weekday);
    }

    /**
     * Strict production overload.  The predicate must be the market's authoritative calendar
     * (MarketDataService); the no-arg compatibility path only has a weekday fallback for pure
     * unit callers.  Before the close, a Monday decision therefore targets Friday's completed
     * session while still allowing a valid Monday live quote; after the close it targets Monday.
     */
    public static AcceptedPrice resolveAcceptedPrice(
            List<StockPriceHistory> completedRows,
            PriceQueryService.LivePrice rawLive,
            String market,
            Instant decisionInstant,
            Predicate<LocalDate> tradingDay) {
        Function<LocalDate, Optional<Boolean>> known = date ->
                tradingDay == null ? Optional.empty() : Optional.of(tradingDay.test(date));
        return resolveAcceptedPriceStrict(completedRows, rawLive, market, decisionInstant, known);
    }

    /** Strict production overload: an unknown calendar is terminal, never a weekday guess. */
    public static AcceptedPrice resolveAcceptedPriceStrict(
            List<StockPriceHistory> completedRows,
            PriceQueryService.LivePrice rawLive,
            String market,
            Instant decisionInstant,
            Function<LocalDate, Optional<Boolean>> tradingDayKnown) {
        LocalDate targetDate = targetDate(market, decisionInstant);
        if (targetDate == null) {
            return AcceptedPrice.missing(Quality.INVALID, "缺少可解析的 decision instant 或市場時區。");
        }
        DecisionSessions sessions = decisionSessionsStrict(market, decisionInstant, tradingDayKnown);
        return resolveAcceptedPrice(completedRows, rawLive, market, decisionInstant, sessions);
    }

    /** Resolve against one already-authoritative session clock snapshot. */
    public static AcceptedPrice resolveAcceptedPrice(
            List<StockPriceHistory> completedRows,
            PriceQueryService.LivePrice rawLive,
            String market,
            Instant decisionInstant,
            DecisionSessions sessions) {
        if (sessions == null || sessions.targetCompletedSession() == null) {
            return AcceptedPrice.missing(Quality.MISSING,
                    "市場日曆無法確認 completed terminal session，禁止以週末／假日 live 或舊收盤冒充。");
        }

        LocalDate completedSession = sessions.targetCompletedSession();
        List<StockPriceHistory> trustedRows = trustedCompletedRows(completedRows, market, completedSession);
        if (rawLive != null) {
            LocalDate liveDate = parseDate(rawLive.tradingDate());
            boolean validPrice = positiveFinite(rawLive.price());
            if (liveDate != null && validPrice
                    && liveDate.equals(sessions.currentLiveDate())
                    && sessions.currentSessionTrading()
                    && !hasTrustedCompletedDate(trustedRows, liveDate)) {
                return new AcceptedPrice(
                        rawLive.price(), liveDate, rawLive.updatedAt(),
                        nonBlank(rawLive.source(), "REDIS_LIVE"), Quality.LIVE,
                        true, rawLive, trustedRows, null);
            }
        }

        // A completed close is accepted only for the exact market session selected by the
        // market-aware DecisionMarketClock.  Prior rows remain in trustedCompletedRows for the
        // indicator window, but never become today's accepted quote when a newer completed
        // session is expected.
        StockPriceHistory completed = trustedRows.stream()
                .filter(row -> completedSession.equals(row.getTradingDate()))
                .findFirst().orElse(null);
        if (completed != null) {
            return new AcceptedPrice(
                    completed.getClosePrice(), completed.getTradingDate(), null,
                    nonBlank(completed.getCloseSource(), "COMPLETED_CLOSE"),
                    Quality.COMPLETED_CLOSE, false, null, trustedRows, null);
        }

        Quality quality = rawLive == null ? Quality.MISSING : classifyRejectedLive(
                rawLive, sessions.currentLiveDate());
        String reason = rawLive == null
                ? "沒有可採用的 live 或可信完成收盤。"
                : "live 日期／價格未通過 accepted-price 時效與完整性檢查，且沒有可信完成收盤。";
        return AcceptedPrice.missing(quality, reason);
    }

    /** 與既有 production live 併入條件完全相同，供服務與測試共用。 */
    public static boolean shouldAddLiveRow(
            List<StockPriceHistory> completedRows,
            PriceQueryService.LivePrice live,
            Instant decisionInstant,
            String market) {
        return shouldAddLiveRow(completedRows, live, decisionInstant, market,
                RadarObservationResolver::weekday);
    }

    public static boolean shouldAddLiveRow(
            List<StockPriceHistory> completedRows,
            PriceQueryService.LivePrice live,
            Instant decisionInstant,
            String market,
            Predicate<LocalDate> tradingDay) {
        if (live == null || !positiveFinite(live.price())) return false;
        LocalDate liveDate = parseDate(live.tradingDate());
        DecisionSessions sessions = decisionSessions(market, decisionInstant, tradingDay);
        if (sessions == null || sessions.currentLiveDate() == null
                || sessions.targetCompletedSession() == null) return false;
        List<StockPriceHistory> trustedRows = trustedCompletedRows(
                completedRows, market, sessions.targetCompletedSession());
        return liveDate != null && liveDate.equals(sessions.currentLiveDate())
                && tradingDay.test(liveDate)
                && !hasTrustedCompletedDate(trustedRows, liveDate);
    }

    /** Strict calendar-aware live-row overload matching accepted-price resolution. */
    public static boolean shouldAddLiveRowStrict(
            List<StockPriceHistory> completedRows,
            PriceQueryService.LivePrice live,
            Instant decisionInstant,
            String market,
            Function<LocalDate, Optional<Boolean>> tradingDayKnown) {
        return shouldAddLiveRow(completedRows, live, decisionInstant, market,
                decisionSessionsStrict(market, decisionInstant, tradingDayKnown));
    }

    /** Use a previously resolved session clock; unknown sessions fail closed. */
    public static boolean shouldAddLiveRow(
            List<StockPriceHistory> completedRows,
            PriceQueryService.LivePrice live,
            Instant decisionInstant,
            String market,
            DecisionSessions sessions) {
        if (live == null || !positiveFinite(live.price()) || sessions == null
                || sessions.currentLiveDate() == null || sessions.targetCompletedSession() == null) return false;
        LocalDate liveDate = parseDate(live.tradingDate());
        List<StockPriceHistory> trustedRows = trustedCompletedRows(
                completedRows, market, sessions.targetCompletedSession());
        return liveDate != null && liveDate.equals(sessions.currentLiveDate())
                && sessions.currentSessionTrading()
                && !hasTrustedCompletedDate(trustedRows, liveDate);
    }

    /** Resolve current live date and latest completed session from one explicit instant. */
    public static DecisionSessions decisionSessions(
            String market, Instant decisionInstant, Predicate<LocalDate> tradingDay) {
        if (market == null || decisionInstant == null || tradingDay == null) return null;
        return decisionSessionsStrict(market, decisionInstant,
                date -> Optional.of(tradingDay.test(date)));
    }

    /** Calendar-aware resolver; Optional.empty means the authority is unavailable. */
    public static DecisionSessions decisionSessionsStrict(
            String market,
            Instant decisionInstant,
            Function<LocalDate, Optional<Boolean>> tradingDayKnown) {
        if (market == null || decisionInstant == null || tradingDayKnown == null) return null;
        LocalDate current = targetDate(market, decisionInstant);
        if (current == null) return null;
        LocalTime localTime = decisionInstant.atZone(MarketZones.resolve(market)).toLocalTime();
        Optional<Boolean> currentKnown = safeKnown(tradingDayKnown, current);
        if (currentKnown.isEmpty()) return null;
        LocalDate completed = current;
        if (!currentKnown.get()
                || localTime.isBefore(MarketZones.closeTime(market))) {
            completed = previousTradingDayStrict(current.minusDays(1), tradingDayKnown);
        }
        return new DecisionSessions(current, completed, currentKnown.get());
    }

    public static LocalDate targetDate(String market, Instant decisionInstant) {
        if (market == null || decisionInstant == null) return null;
        return decisionInstant.atZone(MarketZones.resolve(market)).toLocalDate();
    }

    public static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.substring(0, Math.min(raw.length(), 10)));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static List<StockPriceHistory> trustedCompletedRows(
            List<StockPriceHistory> rows, String market, LocalDate targetDate) {
        if (rows == null) return List.of();
        return rows.stream()
                .filter(row -> row != null && row.getTradingDate() != null
                        && !row.getTradingDate().isAfter(targetDate)
                        && positiveFinite(row.getClosePrice())
                        && isTrustedClose(row, market))
                .sorted(Comparator.comparing(StockPriceHistory::getTradingDate).reversed())
                .toList();
    }

    private static boolean hasTrustedCompletedDate(List<StockPriceHistory> rows, LocalDate date) {
        return rows != null && rows.stream()
                .anyMatch(row -> row != null && date.equals(row.getTradingDate()));
    }

    private static boolean isTrustedClose(StockPriceHistory row, String market) {
        if (!"台股".equals(market)) return true;
        return row.getCloseSource() != null
                && TRUSTED_TW_CLOSE_SOURCES.contains(row.getCloseSource());
    }

    private static Quality classifyRejectedLive(PriceQueryService.LivePrice live, LocalDate targetDate) {
        LocalDate liveDate = parseDate(live.tradingDate());
        if (!positiveFinite(live.price()) || liveDate == null) return Quality.INVALID;
        return liveDate.isAfter(targetDate) ? Quality.INVALID : Quality.STALE;
    }

    private static boolean positiveFinite(BigDecimal value) {
        return value != null && value.signum() > 0
                && value.doubleValue() != Double.POSITIVE_INFINITY
                && value.doubleValue() != Double.NEGATIVE_INFINITY
                && !Double.isNaN(value.doubleValue());
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean isWeekday(LocalDate date) {
        return date != null && date.getDayOfWeek().getValue() <= 5;
    }

    private static boolean weekday(LocalDate date) {
        return isWeekday(date);
    }

    private static LocalDate previousTradingDay(LocalDate start, Predicate<LocalDate> tradingDay) {
        LocalDate candidate = start;
        // A year is intentionally bounded: an unavailable calendar must fail closed instead of
        // silently turning an old row into the current terminal session.
        for (int i = 0; i < 370; i++, candidate = candidate.minusDays(1)) {
            if (tradingDay.test(candidate)) return candidate;
        }
        return null;
    }

    private static LocalDate previousTradingDayStrict(
            LocalDate start, Function<LocalDate, Optional<Boolean>> tradingDayKnown) {
        LocalDate candidate = start;
        for (int i = 0; i < 370; i++, candidate = candidate.minusDays(1)) {
            Optional<Boolean> known = safeKnown(tradingDayKnown, candidate);
            if (known.isEmpty()) return null;
            if (known.get()) return candidate;
        }
        return null;
    }

    private static Optional<Boolean> safeKnown(
            Function<LocalDate, Optional<Boolean>> tradingDayKnown, LocalDate date) {
        try {
            Optional<Boolean> value = tradingDayKnown.apply(date);
            return value == null ? Optional.empty() : value;
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }
}
