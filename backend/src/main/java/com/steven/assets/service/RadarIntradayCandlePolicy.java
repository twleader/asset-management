package com.steven.assets.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Conservative SHORT-only confirmation. No IO, scoring, price substitution or derived persistence. */
public final class RadarIntradayCandlePolicy {
    public static final String AGGREGATION_SOURCE = "LOCAL_AGGREGATED_FUBON_1M";
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private RadarIntradayCandlePolicy() {}

    /** Time fields are endpoints of completed bars, never vendor-provided 5m indicators. */
    public record Confirmation(String status, String reason, LocalDate sourceDate, Instant observedAt,
                               Instant lastCompletedAt, Instant fiveMinuteAt, Instant oneMinuteAt,
                               String aggregationSource) {}

    public static Confirmation evaluate(String market, TradingRadarRuleEngine.Horizon horizon,
                                        Instant asOf, Boolean tradingDay,
                                        RadarIntradayCandlePort.Capture capture) {
        LocalDate day = asOf == null ? null : asOf.atZone(TAIPEI).toLocalDate();
        if (horizon != TradingRadarRuleEngine.Horizon.SHORT)
            return empty("NOT_APPLICABLE", "HORIZON_NOT_APPLICABLE", day);
        if (!"台股".equals(market)) return empty("NOT_APPLICABLE", "MARKET_NOT_APPLICABLE", day);
        if (asOf == null || tradingDay == null)
            return empty("UNAVAILABLE", "MARKET_CALENDAR_UNAVAILABLE", day);
        LocalTime time = asOf.atZone(TAIPEI).toLocalTime();
        if (!tradingDay || time.isBefore(LocalTime.of(9, 0)) || !time.isBefore(LocalTime.of(13, 30)))
            return empty("NOT_APPLICABLE", "OUTSIDE_TRADING_SESSION", day);
        if (capture == null) return empty("UNAVAILABLE", "CAPTURE_MISSING", day);
        if (!"台股".equals(capture.market()) || !"FUBON_SDK".equals(capture.provider())
                || !day.equals(capture.sourceDate()))
            return empty("UNAVAILABLE", "CAPTURE_IDENTITY_MISMATCH", day);
        if (!"AVAILABLE".equals(capture.status()))
            return empty("UNAVAILABLE", "CAPTURE_" + (capture.status() == null ? "INVALID" : capture.status()), day);
        Instant request = capture.requestStartedAt(), observed = capture.capturedAt(), end = capture.lastCompletedAt();
        if (request == null || observed == null || end == null || request.isAfter(asOf)
                || observed.isAfter(asOf) || observed.isBefore(request) || end.isAfter(request.minusSeconds(60)))
            return empty("UNAVAILABLE", "COMPLETION_EVIDENCE_INVALID", day);
        if (request.isBefore(asOf.minusSeconds(420)) || observed.isBefore(asOf.minusSeconds(420))
                || end.isBefore(asOf.minusSeconds(420)))
            return empty("UNAVAILABLE", "CAPTURE_STALE", day);
        List<RadarIntradayCandlePort.Candle> candles = capture.candles();
        if (candles.isEmpty() || candles.size() > 30)
            return empty("UNAVAILABLE", "INSUFFICIENT_COMPLETED_BARS", day);
        Instant open = day.atTime(9, 0).atZone(TAIPEI).toInstant();
        Instant close = day.atTime(13, 30).atZone(TAIPEI).toInstant();
        Map<Instant, RadarIntradayCandlePort.Candle> byTime = new HashMap<>();
        Instant previous = null;
        for (var candle : candles) {
            Instant at = candle.candleAt();
            if (at == null || at.isBefore(open) || !at.isBefore(close) || at.getNano() != 0
                    || (at.getEpochSecond() - open.getEpochSecond()) % 60 != 0
                    || at.plusSeconds(60).isAfter(end) || at.plusSeconds(60).isAfter(request.minusSeconds(60))
                    || previous != null && !at.isAfter(previous) || !validPrices(candle))
                return empty("UNAVAILABLE", "MINUTE_FACT_INVALID", day);
            byTime.put(at, candle);
            previous = at;
        }
        if (!end.equals(previous.plusSeconds(60)))
            return empty("UNAVAILABLE", "LATEST_COMPLETED_MISMATCH", day);
        long completedSeconds = end.getEpochSecond() - open.getEpochSecond();
        Instant fiveEnd = open.plusSeconds(completedSeconds / 300 * 300);
        if (fiveEnd.isBefore(open.plusSeconds(600)))
            return empty("UNAVAILABLE", "INSUFFICIENT_COMPLETED_BARS", day);
        var latestOne = byTime.get(end.minusSeconds(60));
        var priorOne = byTime.get(end.minusSeconds(120));
        Five latest = aggregate(byTime, fiveEnd), prior = aggregate(byTime, fiveEnd.minusSeconds(300));
        if (latestOne == null || priorOne == null || latest == null || prior == null)
            return empty("UNAVAILABLE", "MISSING_CONSECUTIVE_COMPLETED_BARS", day);
        String reason = latest.close().compareTo(latest.open()) <= 0 ? "FIVE_MINUTE_NOT_RISING"
                : latest.close().compareTo(prior.close()) <= 0 ? "FIVE_MINUTE_CLOSE_NOT_HIGHER"
                : prior.volume().signum() <= 0 ? "PRIOR_FIVE_MINUTE_ZERO_VOLUME"
                : latest.volume().compareTo(prior.volume()) < 0 ? "FIVE_MINUTE_VOLUME_CONTRACTING"
                : latestOne.close().compareTo(priorOne.close()) < 0 ? "ONE_MINUTE_WEAKENING"
                : "COMPLETED_PRICE_VOLUME_CONFIRMED";
        return new Confirmation("COMPLETED_PRICE_VOLUME_CONFIRMED".equals(reason) ? "CONFIRMED" : "WAIT",
                reason, day, observed, end, fiveEnd, end, AGGREGATION_SOURCE);
    }

    private static Confirmation empty(String status, String reason, LocalDate day) {
        return new Confirmation(status, reason, day, null, null, null, null, null);
    }
    private static boolean validPrices(RadarIntradayCandlePort.Candle candle) {
        if (candle.volume() < 0) return false;
        for (BigDecimal value : List.of(candle.open() == null ? BigDecimal.ZERO : candle.open(),
                candle.high() == null ? BigDecimal.ZERO : candle.high(),
                candle.low() == null ? BigDecimal.ZERO : candle.low(),
                candle.close() == null ? BigDecimal.ZERO : candle.close()))
            if (value.signum() <= 0 || value.precision() > 20 || value.scale() > 10) return false;
        return candle.high().compareTo(candle.open()) >= 0 && candle.high().compareTo(candle.close()) >= 0
                && candle.low().compareTo(candle.open()) <= 0 && candle.low().compareTo(candle.close()) <= 0
                && candle.high().compareTo(candle.low()) >= 0;
    }
    private record Five(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, BigInteger volume) {}
    private static Five aggregate(Map<Instant, RadarIntradayCandlePort.Candle> candles, Instant end) {
        BigDecimal opening = null, high = null, low = null, closing = null;
        BigInteger volume = BigInteger.ZERO;
        for (int i = 5; i >= 1; i--) {
            var candle = candles.get(end.minusSeconds(i * 60L));
            if (candle == null) return null;
            if (opening == null) opening = candle.open();
            high = high == null ? candle.high() : high.max(candle.high());
            low = low == null ? candle.low() : low.min(candle.low());
            closing = candle.close();
            volume = volume.add(BigInteger.valueOf(candle.volume()));
        }
        return new Five(opening, high, low, closing, volume);
    }

    /** Preserves original candidates and any earlier veto. Never resurrects a blocked buy. */
    public static TradingRadarEvidenceGate.GatedActions apply(TradingRadarEvidenceGate.GatedActions current,
                                                             Confirmation confirmation, boolean held) {
        if (current == null || confirmation == null || "NOT_APPLICABLE".equals(confirmation.status())
                || "CONFIRMED".equals(confirmation.status())) return current;
        List<String> diagnostics = new ArrayList<>(current.shortDiagnostics());
        diagnostics.add(disclosure(confirmation));
        var action = current.shortAction();
        if (action == TradingRadarRuleEngine.Action.BUY_CANDIDATE
                || action == TradingRadarRuleEngine.Action.ADD_CANDIDATE
                || action == TradingRadarRuleEngine.Action.TRIAL_BUY)
            action = held ? TradingRadarRuleEngine.Action.HOLD : TradingRadarRuleEngine.Action.WATCH;
        return new TradingRadarEvidenceGate.GatedActions(current.mediumAction(), action, current.swingAction(),
                current.mediumDiagnostics(), diagnostics, current.swingDiagnostics(), current.candidateMediumAction(),
                current.candidateShortAction(), current.candidateSwingAction());
    }
    public static String disclosure(Confirmation confirmation) {
        String message = "CONFIRMED".equals(confirmation.status())
                ? "盤中價量已確認：已完成 5 分 K 走高且量未縮，最新 1 分 K 未轉弱。"
                : "WAIT".equals(confirmation.status()) ? switch (confirmation.reason()) {
                    case "FIVE_MINUTE_NOT_RISING", "FIVE_MINUTE_CLOSE_NOT_HIGHER" -> "等待最新完成 5 分 K 轉強，暫緩短線買進。";
                    case "PRIOR_FIVE_MINUTE_ZERO_VOLUME" -> "前一完成 5 分 K 無成交量，暫緩短線買進。";
                    case "FIVE_MINUTE_VOLUME_CONTRACTING" -> "最新完成 5 分 K 量縮，等待量價確認後再評估短線買進。";
                    case "ONE_MINUTE_WEAKENING" -> "最新完成 1 分 K 轉弱，暫緩短線買進。";
                    default -> "盤中價量尚未確認，暫緩短線買進。";
                } : "盤中完成 K 線資料不足、過期或無法驗證，暫緩短線買進。";
        if (confirmation.sourceDate() != null) message += "來源日 " + confirmation.sourceDate() + "。";
        if (confirmation.lastCompletedAt() != null)
            message += "最新完成時間 " + confirmation.lastCompletedAt().atZone(TAIPEI) + "。";
        if (confirmation.observedAt() != null)
            message += "取得時間 " + confirmation.observedAt().atZone(TAIPEI) + "。";
        if (confirmation.aggregationSource() != null)
            message += "5 分 K 由富邦已完成 1 分 K 本地聚合，僅用於短線買進確認。";
        return message;
    }
}
