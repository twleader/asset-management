package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Pure completed daily OHLCV calculations. Input is ascending and on one adjusted price/share basis. */
public final class SrppCompletedTechnicalCalculator {
    public static final String FORMULA_VERSION = "SRPP_DAILY_OHLCV_V1";

    public record Values(
            BigDecimal ma5, BigDecimal rsi14,
            BigDecimal macdLine, BigDecimal macdSignal, BigDecimal macdHistogram,
            BigDecimal bollingerMiddle, BigDecimal bollingerUpper, BigDecimal bollingerLower,
            BigDecimal adx14, BigDecimal plusDi14, BigDecimal minusDi14,
            BigDecimal obv20Change, BigDecimal volumeRatio20, BigDecimal oneYearPositionPct) {}

    public record Result(Values values, List<String> missing) {}

    private SrppCompletedTechnicalCalculator() {}

    public static Result calculate(List<StockPriceHistory> rows, LocalDate asOf) {
        int n = rows.size();
        List<String> missing = new ArrayList<>();
        double[] close = new double[n];
        for (int i = 0; i < n; i++) {
            BigDecimal value = rows.get(i).getClosePrice();
            close[i] = value == null || value.signum() <= 0 ? Double.NaN : value.doubleValue();
        }
        BigDecimal ma5 = null, rsi14 = null, macdLine = null, macdSignal = null, macdHistogram = null;
        BigDecimal middle = null, upper = null, lower = null;
        BigDecimal adx14 = null, plusDi14 = null, minusDi14 = null;
        BigDecimal obv20 = null, volumeRatio20 = null, position = null;

        if (n >= 5 && valid(close, n - 5, n)) ma5 = decimal(mean(close, n - 5, n));
        else missing.add("MA5_HISTORY_OR_CLOSE");

        if (n >= 15 && valid(close, 0, n)) {
            double gain = 0, loss = 0;
            for (int i = 1; i <= 14; i++) {
                double diff = close[i] - close[i - 1];
                gain += Math.max(0, diff);
                loss += Math.max(0, -diff);
            }
            gain /= 14;
            loss /= 14;
            for (int i = 15; i < n; i++) {
                double diff = close[i] - close[i - 1];
                gain = (gain * 13 + Math.max(0, diff)) / 14;
                loss = (loss * 13 + Math.max(0, -diff)) / 14;
            }
            rsi14 = decimal(loss == 0 ? (gain == 0 ? 50 : 100) : 100 - 100 / (1 + gain / loss));
        } else missing.add("RSI14_HISTORY_OR_CLOSE");

        if (n >= 34 && valid(close, 0, n)) {
            double ema12 = mean(close, 0, 12);
            double ema26 = mean(close, 0, 26);
            for (int i = 12; i < 26; i++) ema12 += (close[i] - ema12) * 2.0 / 13;
            double signal = 0;
            int signalCount = 0;
            double line = Double.NaN;
            for (int i = 25; i < n; i++) {
                if (i > 25) {
                    ema12 += (close[i] - ema12) * 2.0 / 13;
                    ema26 += (close[i] - ema26) * 2.0 / 27;
                }
                line = ema12 - ema26;
                if (signalCount < 9) {
                    signal += line;
                    signalCount++;
                    if (signalCount == 9) signal /= 9;
                } else signal += (line - signal) * 2.0 / 10;
            }
            macdLine = decimal(line);
            macdSignal = decimal(signal);
            macdHistogram = decimal(line - signal);
        } else missing.add("MACD_12_26_9_HISTORY_OR_CLOSE");

        if (n >= 20 && valid(close, n - 20, n)) {
            double mean = mean(close, n - 20, n);
            double variance = 0;
            for (int i = n - 20; i < n; i++) variance += Math.pow(close[i] - mean, 2);
            double width = 2 * Math.sqrt(variance / 20);
            middle = decimal(mean);
            upper = decimal(mean + width);
            lower = decimal(mean - width);
        } else missing.add("BOLLINGER_20_2_HISTORY_OR_CLOSE");

        if (n >= 28 && valid(close, 0, n) && validHighLow(rows, 0, n)) {
            double tr = 0, pdm = 0, mdm = 0, adx = 0;
            int dxCount = 0;
            double pdi = Double.NaN, mdi = Double.NaN;
            for (int i = 1; i < n; i++) {
                StockPriceHistory prev = rows.get(i - 1);
                StockPriceHistory row = rows.get(i);
                double high = row.getHighPrice().doubleValue(), low = row.getLowPrice().doubleValue();
                double up = high - prev.getHighPrice().doubleValue();
                double down = prev.getLowPrice().doubleValue() - low;
                double trueRange = Math.max(high - low, Math.max(Math.abs(high - close[i - 1]), Math.abs(low - close[i - 1])));
                double plus = up > down && up > 0 ? up : 0;
                double minus = down > up && down > 0 ? down : 0;
                if (i <= 14) {
                    tr += trueRange;
                    pdm += plus;
                    mdm += minus;
                } else {
                    tr = tr - tr / 14 + trueRange;
                    pdm = pdm - pdm / 14 + plus;
                    mdm = mdm - mdm / 14 + minus;
                }
                if (i >= 14) {
                    pdi = tr == 0 ? 0 : 100 * pdm / tr;
                    mdi = tr == 0 ? 0 : 100 * mdm / tr;
                    double dx = pdi + mdi == 0 ? 0 : 100 * Math.abs(pdi - mdi) / (pdi + mdi);
                    if (dxCount < 14) {
                        adx += dx;
                        dxCount++;
                        if (dxCount == 14) adx /= 14;
                    } else adx = (adx * 13 + dx) / 14;
                }
            }
            adx14 = decimal(adx);
            plusDi14 = decimal(pdi);
            minusDi14 = decimal(mdi);
        } else missing.add("ADX14_HISTORY_OR_OHLC");

        obv20 = obv20ChangeAt(rows, n);
        if (obv20 == null) missing.add("OBV20_HISTORY_OR_VOLUME");

        if (n >= 21 && validVolume(rows, n - 21, n)) {
            BigDecimal previousVolumeSum = BigDecimal.ZERO;
            for (int i = n - 21; i < n - 1; i++) {
                previousVolumeSum = previousVolumeSum.add(BigDecimal.valueOf(rows.get(i).getVolume()));
            }
            if (previousVolumeSum.signum() > 0) {
                volumeRatio20 = BigDecimal.valueOf(rows.get(n - 1).getVolume())
                        .multiply(BigDecimal.valueOf(20))
                        .divide(previousVolumeSum, 6, RoundingMode.HALF_UP);
            } else missing.add("VOLUME_RATIO20_ZERO_BASE");
        } else missing.add("VOLUME_RATIO20_HISTORY_OR_VOLUME");

        if (n > 0) {
            LocalDate yearStart = asOf.minusYears(1);
            int first = 0;
            while (first < n && rows.get(first).getTradingDate().isBefore(yearStart)) first++;
            if (first < n && n - first >= 180
                    && !rows.get(first).getTradingDate().isAfter(yearStart.plusDays(7))
                    && validHighLow(rows, first, n) && valid(close, first, n)) {
                double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
                for (int i = first; i < n; i++) {
                    min = Math.min(min, rows.get(i).getLowPrice().doubleValue());
                    max = Math.max(max, rows.get(i).getHighPrice().doubleValue());
                }
                if (max > min) position = decimal(100 * (close[n - 1] - min) / (max - min));
            }
        }
        if (position == null) missing.add("ONE_YEAR_HISTORY_OR_OHLC");
        return new Result(new Values(ma5, rsi14, macdLine, macdSignal, macdHistogram,
                middle, upper, lower, adx14, plusDi14, minusDi14, obv20, volumeRatio20, position),
                List.copyOf(missing));
    }

    private static boolean valid(double[] values, int from, int to) {
        for (int i = from; i < to; i++) if (!Double.isFinite(values[i])) return false;
        return true;
    }

    private static boolean validHighLow(List<StockPriceHistory> rows, int from, int to) {
        for (int i = from; i < to; i++) {
            StockPriceHistory row = rows.get(i);
            if (row.getHighPrice() == null || row.getLowPrice() == null
                    || row.getHighPrice().signum() <= 0 || row.getLowPrice().signum() <= 0
                    || row.getHighPrice().compareTo(row.getLowPrice()) < 0) return false;
        }
        return true;
    }

    private static boolean validVolume(List<StockPriceHistory> rows, int from, int to) {
        for (int i = from; i < to; i++) {
            if (rows.get(i).getVolume() == null || rows.get(i).getVolume() < 0) return false;
        }
        return true;
    }

    /** Last twenty signed daily volumes; the first close is only the direction anchor. */
    static BigDecimal obv20ChangeAt(List<StockPriceHistory> rows, int exclusiveEnd) {
        if (exclusiveEnd < 21 || exclusiveEnd > rows.size()
                || !validVolume(rows, exclusiveEnd - 20, exclusiveEnd)) return null;
        BigDecimal delta = BigDecimal.ZERO;
        for (int i = exclusiveEnd - 20; i < exclusiveEnd; i++) {
            BigDecimal previous = rows.get(i - 1).getClosePrice();
            BigDecimal current = rows.get(i).getClosePrice();
            if (previous == null || current == null
                    || previous.signum() <= 0 || current.signum() <= 0) return null;
            int direction = current.compareTo(previous);
            if (direction != 0) {
                delta = delta.add(BigDecimal.valueOf(rows.get(i).getVolume())
                        .multiply(BigDecimal.valueOf(direction)));
            }
        }
        return delta;
    }

    private static double mean(double[] values, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) sum += values[i];
        return sum / (to - from);
    }

    private static BigDecimal decimal(double value) {
        return Double.isFinite(value) ? BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP) : null;
    }
}
