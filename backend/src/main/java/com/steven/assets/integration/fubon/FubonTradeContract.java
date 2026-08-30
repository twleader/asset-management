package com.steven.assets.integration.fubon;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/** Validation of the normalized read response, never a claim to recheck the raw account. */
final class FubonTradeContract {
    private static final Pattern BATCH_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{24}");
    private static final Pattern STOCK_CODE = Pattern.compile("[0-9A-Z]{2,10}");

    private FubonTradeContract() {}

    static boolean validBatch(FubonDtos.TradeBatchResponse batch, LocalDate start, LocalDate end) {
        if (batch == null || start == null || end == null || start.isAfter(end)
                || ChronoUnit.DAYS.between(start, end) > 7
                || !start.equals(batch.startDate()) || !end.equals(batch.endDate())
                || batch.batchId() == null || !BATCH_ID.matcher(batch.batchId()).matches()
                || batch.accountFingerprint() == null || !FINGERPRINT.matcher(batch.accountFingerprint()).matches()
                || batch.trades() == null || batch.emptyConfirmed() != batch.trades().isEmpty()) return false;
        for (FubonDtos.FilledTrade trade : batch.trades()) {
            if (trade == null || !validCode(trade.stockCode())
                    || !("Buy".equals(trade.side()) || "Sell".equals(trade.side()))
                    || trade.filledQty() < 1 || trade.filledQty() > ExactSharesDeserializer.MAX_SHARES
                    || !positiveWireDecimal(trade.filledPrice()) || !positiveWireDecimal(trade.filledAvgPrice())
                    || trade.filledDate() == null || trade.filledDate().isBefore(start) || trade.filledDate().isAfter(end)
                    || trade.filledNo() == null || trade.filledNo().isEmpty() || trade.filledNo().length() > 50
                    || trade.filledTime() == null || trade.filledTime().isEmpty()) return false;
            BigDecimal shares = BigDecimal.valueOf(trade.filledQty());
            BigDecimal price = trade.filledAvgPrice().value();
            if (!exactPositiveNumeric(shares, 15, 5) || !exactPositiveNumeric(price, 17, 6)
                    || amount(price, shares).precision() > 20) return false;
        }
        return true;
    }

    static boolean validCode(String value) {
        return value != null && !"0000".equals(value) && STOCK_CODE.matcher(value).matches();
    }

    private static boolean positiveWireDecimal(CanonicalFubonDecimal decimal) {
        if (decimal == null) return false;
        BigDecimal value = decimal.value();
        return value.signum() > 0 && value.precision() <= 20 && value.scale() >= 0 && value.scale() <= 10;
    }

    static boolean exactPositiveNumeric(BigDecimal value, int precision, int scale) {
        if (value == null || value.signum() <= 0) return false;
        try {
            return value.setScale(scale, RoundingMode.UNNECESSARY).precision() <= precision;
        } catch (ArithmeticException notExact) {
            return false;
        }
    }

    static BigDecimal amount(BigDecimal price, BigDecimal shares) {
        return price.multiply(shares).setScale(2, RoundingMode.HALF_UP);
    }
}
