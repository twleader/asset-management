package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.StockPriceHistory;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/** Pure comparison of one persisted official raw SMA20 with the accepted completed-price basis. */
public final class RadarOfficialSma20Verifier {
    private static final BigDecimal TOLERANCE = new BigDecimal("0.00000001");
    private RadarOfficialSma20Verifier() {}

    public static boolean sameRawBasis(List<StockPriceHistory> raw, List<StockPriceHistory> adjusted,
                                       boolean liveAccepted, LocalDate completedDate) {
        if (raw == null || adjusted == null || raw.size() < 20
                || adjusted.size() < 20 + (liveAccepted ? 1 : 0) || completedDate == null) return false;
        int offset = liveAccepted ? 1 : 0;
        LocalDate previous = null;
        for (int i = 0; i < 20; i++) {
            StockPriceHistory a = raw.get(i);
            StockPriceHistory b = adjusted.get(i + offset);
            if (a == null || b == null || a.getTradingDate() == null || b.getTradingDate() == null
                    || a.getClosePrice() == null || b.getClosePrice() == null
                    || !a.getTradingDate().equals(b.getTradingDate())
                    || a.getClosePrice().compareTo(b.getClosePrice()) != 0
                    || a.getClosePrice().signum() <= 0 || !Double.isFinite(a.getClosePrice().doubleValue())
                    || previous != null && !a.getTradingDate().isBefore(previous)) return false;
            if (i == 0 && !completedDate.equals(a.getTradingDate())) return false;
            previous = a.getTradingDate();
        }
        return true;
    }

    public static TradingRadarDto.OfficialSma20Verification verify(
            String market, boolean liveAccepted, LocalDate completedDate,
            List<StockPriceHistory> raw, List<StockPriceHistory> adjusted,
            BigDecimal localMa20, RadarTechnicalFactPort.OfficialSma20 official) {
        if (!"台股".equals(market)) return unavailable(completedDate, "NON_TAIWAN_MARKET");
        if (completedDate == null || raw == null || raw.size() < 20) return unavailable(completedDate, "NO_COMPLETED_20_BARS");
        if (official == null || !completedDate.equals(official.sourceDate()) || official.value() == null
                || official.value().signum() <= 0 || !Double.isFinite(official.value().doubleValue())) {
            return unavailable(completedDate, "OFFICIAL_SMA20_UNAVAILABLE");
        }
        if (!sameRawBasis(raw, adjusted, liveAccepted, completedDate)) {
            return new TradingRadarDto.OfficialSma20Verification("NOT_COMPARABLE", completedDate.toString(),
                    "RAW_ADJUSTED_BASIS_DIFFERENT", official == null ? null : official.value(), localMa20, null, false);
        }
        if (liveAccepted) {
            return new TradingRadarDto.OfficialSma20Verification("NOT_COMPARABLE", completedDate.toString(),
                    "LIVE_PRICE_IN_TECHNICAL", official == null ? null : official.value(), localMa20, null, false);
        }
        // Match the existing TechnicalIndicatorService projection: newest to oldest double sum,
        // then BigDecimal two-decimal HALF_UP.  A drift here outranks an official conflict.
        double sum = 0.0;
        for (int i = 0; i < 20; i++) sum += raw.get(i).getClosePrice().doubleValue();
        BigDecimal projection = BigDecimal.valueOf(sum / 20.0).setScale(2, RoundingMode.HALF_UP);
        if (localMa20 == null || localMa20.signum() <= 0 || !Double.isFinite(localMa20.doubleValue())
                || localMa20.compareTo(projection) != 0) {
            return new TradingRadarDto.OfficialSma20Verification("NOT_COMPARABLE", completedDate.toString(),
                    "LOCAL_MA20_PROJECTION_MISMATCH", official == null ? null : official.value(), localMa20, null, false);
        }
        BigDecimal exactSum = BigDecimal.ZERO;
        for (int i = 0; i < 20; i++) exactSum = exactSum.add(raw.get(i).getClosePrice(), MathContext.DECIMAL128);
        BigDecimal replay = exactSum.divide(BigDecimal.valueOf(20), MathContext.DECIMAL128);
        BigDecimal delta = official.value().subtract(replay).abs();
        boolean conflict = delta.compareTo(TOLERANCE) > 0;
        return new TradingRadarDto.OfficialSma20Verification(conflict ? "CONFLICT" : "CONFIRMED",
                completedDate.toString(), conflict ? "OFFICIAL_LOCAL_SMA20_CONFLICT" : "RAW_SMA20_CONFIRMED",
                official.value(), localMa20, delta, conflict);
    }

    private static TradingRadarDto.OfficialSma20Verification unavailable(LocalDate date, String reason) {
        return new TradingRadarDto.OfficialSma20Verification("UNAVAILABLE",
                date == null ? null : date.toString(), reason, null, null, null, false);
    }
}
