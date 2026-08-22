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

    /**
     * indicator 序列的列數上限（Task 319.4）。
     *
     * <p>{@code TradingRadarRuleEngine.confirm(closes, 240)} 需要 241 根完成收盤
     * （{@code size >= period + 1}），故上限就是 241。呼叫端刻意多抓幾列當緩衝
     * （見 {@code TradingRadarService.buildStock} 的 250），讓「未來列」與「當日 provenance
     * 未驗證列」這兩種剔除有名額可遞補；<b>但序列本身必須截回 241</b>，否則
     * {@code RadarInputAssembler.ma60BiasPercentile} 的觀測數會隨取數量浮動，
     * 在沒有任何剔除的常態下也靜默改掉既有分位值。</p>
     *
     * <p><b>Task 356.4d-2：可見性由 private 放寬為 public，值與截斷行為一律不動。</b>擴窗之後
     * {@code RadarInputAssembler.assemble} 需要一個<b>顯式的</b>日K 契約列數參數
     * （{@code dailyContractRows}），而那個數字就是這個常數；讓呼叫端引用同一份常數，
     * 才不會在服務層再寫死一個 241。</p>
     */
    public static final int INDICATOR_SERIES_MAX_ROWS = 241;

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
     *
     * <p><b>兩份列刻意分開（Task 319.1）</b>：provenance 白名單只界定「什麼算 verified 收盤」，
     * 不是「什麼列可以進技術序列」。兩者原本共用一個欄位，導致台股歷史列（{@code close_source}
     * 依 Task 290 一律留 null、禁止 migration 猜來源）整批被擋在 MA／KD 之外，每檔只剩下
     * 官方對帳過的那幾根，全數輸出「今日不交易」。</p>
     *
     * @param verifiedCompletedRows verified 語意的完成列（台股須命中 provenance 白名單）：
     *                              決定 accepted price 取哪一列、{@code quoteStatus} 是否為
     *                              {@code VERIFIED_CLOSE}，以及「今日已有可信完成列因此不併 live K」。
     *                              日期由新到舊。
     * @param indicatorSeriesRows   餵給 {@code RadarInputAssembler} 算 MA20／60／240、KD、兩日確認、
     *                              季線乖離分位、52 週位置與 60 日 σ 的歷史序列：只要日期不晚於
     *                              completed session 且收盤為正且有限即納入，<b>不看 close_source</b>；
     *                              唯一例外是等於 completed session 的最新一根，台股仍須命中白名單
     *                              （當日尚未官方對帳完成時那根可能是盤中誤寫值，只能由 live K 併入
     *                              或缺席）。日期由新到舊，最多 {@value #INDICATOR_SERIES_MAX_ROWS} 列。
     * @param weeklySeriesRows      週K 聚合用的歷史序列（Task 356.4b）：<b>與
     *                              {@code indicatorSeriesRows} 套用完全相同的過濾，唯一差別是
     *                              不做 {@value #INDICATOR_SERIES_MAX_ROWS} 截斷</b>，因此
     *                              {@code indicatorSeriesRows} 恆為本清單的前綴。
     *                              日K 路徑一律只吃 {@code indicatorSeriesRows}——兩份分開才能讓
     *                              「取數視窗擴大」與「日K 契約不變」同時成立。日期由新到舊。
     */
    public record AcceptedPrice(
            BigDecimal value,
            LocalDate tradingDate,
            String updatedAt,
            String source,
            Quality quality,
            boolean liveAccepted,
            PriceQueryService.LivePrice live,
            List<StockPriceHistory> verifiedCompletedRows,
            List<StockPriceHistory> indicatorSeriesRows,
            List<StockPriceHistory> weeklySeriesRows,
            String missingReason
    ) {
        /**
         * 既有十參數形狀的相容建構式：呼叫端只備妥一份序列時，週K 序列即該序列
         * （回測等自組 snapshot 的路徑走這裡，行為與擴窗前完全相同）。
         */
        public AcceptedPrice(
                BigDecimal value,
                LocalDate tradingDate,
                String updatedAt,
                String source,
                Quality quality,
                boolean liveAccepted,
                PriceQueryService.LivePrice live,
                List<StockPriceHistory> verifiedCompletedRows,
                List<StockPriceHistory> indicatorSeriesRows,
                String missingReason) {
            this(value, tradingDate, updatedAt, source, quality, liveAccepted, live,
                    verifiedCompletedRows, indicatorSeriesRows, indicatorSeriesRows, missingReason);
        }

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
            return new AcceptedPrice(
                    null, null, null, null, quality, false, null,
                    List.of(), List.of(), List.of(), reason);
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
        // Task 356.4b：同一份過濾只跑一次；日K 契約序列是週K 長序列的前綴，兩者不可能分歧。
        List<StockPriceHistory> weeklyRows = eligibleSeriesRows(completedRows, market, completedSession);
        List<StockPriceHistory> indicatorRows = weeklyRows.size() <= INDICATOR_SERIES_MAX_ROWS
                ? weeklyRows
                : List.copyOf(weeklyRows.subList(0, INDICATOR_SERIES_MAX_ROWS));
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
                        true, rawLive, trustedRows, indicatorRows, weeklyRows, null);
            }
        }

        // A completed close is accepted only for the exact market session selected by the
        // market-aware DecisionMarketClock.  The two row lists have different jobs and are
        // deliberately not interchangeable: verifiedCompletedRows carries the Task 290 verified
        // semantics (TW provenance whitelist) and picks today's accepted quote, while
        // indicatorSeriesRows is the MA/KD history window and ignores close_source except for the
        // completed-session row itself.  Prior rows never become today's accepted quote when a
        // newer completed session is expected.
        StockPriceHistory completed = trustedRows.stream()
                .filter(row -> completedSession.equals(row.getTradingDate()))
                .findFirst().orElse(null);
        if (completed != null) {
            return new AcceptedPrice(
                    completed.getClosePrice(), completed.getTradingDate(), null,
                    nonBlank(completed.getCloseSource(), "COMPLETED_CLOSE"),
                    Quality.COMPLETED_CLOSE, false, null,
                    trustedRows, indicatorRows, weeklyRows, null);
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

    /**
     * 技術序列用的歷史列（Task 319.2）。與 {@link #trustedCompletedRows} 併存、不是取代。
     *
     * <p>close_source 白名單只界定 verified 收盤語意，<b>不是</b>技術指標的納入判準：台股歷史列
     * 依 Task 290 一律留 null，用白名單濾整條序列等於把每檔只剩官方對帳過的那幾根餵進 MA240。
     * 唯一仍套用白名單的是等於 {@code completedSession} 的那一根——當日尚未官方對帳完成時它可能是
     * 盤中誤寫值，不得當成完成收盤 K；該日只能由 live K 併入（{@code shouldAddLiveRow} 路徑）或缺席。</p>
     */
    private static List<StockPriceHistory> eligibleSeriesRows(
            List<StockPriceHistory> rows, String market, LocalDate completedSession) {
        if (rows == null) return List.of();
        return rows.stream()
                .filter(row -> row != null && row.getTradingDate() != null
                        && !row.getTradingDate().isAfter(completedSession)
                        && positiveFinite(row.getClosePrice())
                        && (!completedSession.equals(row.getTradingDate())
                            || isTrustedClose(row, market)))
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
