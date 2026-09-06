package com.steven.assets.integration.fubon;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class FubonRealizedGainFingerprint {
    static final String SOURCE = "FUBON_REALIZED_GAIN_SYNC";
    private static final String VERSION = "FUBON_REALIZED_GAIN_SYNC_V1";

    private FubonRealizedGainFingerprint() {}

    static String of(FubonDtos.RealizedGainRow row) {
        if (row == null || row.sourceDate() == null || row.stockNo() == null
                || !"Sell".equals(row.buySell()) || !"Stock".equals(row.orderType())
                || row.filledPrice() == null || row.realizedProfit() == null || row.realizedLoss() == null) {
            throw new IllegalArgumentException("INVALID_REALIZED_FINGERPRINT_INPUT");
        }
        String payload = String.join("\n",
                "version=" + VERSION,
                "sourceDate=" + row.sourceDate(),
                "stockNo=" + row.stockNo(),
                "buySell=Sell",
                "orderType=Stock",
                "filledQty=" + Long.toString(row.filledQty()),
                "filledPrice=" + plain(row.filledPrice().value()),
                "realizedProfit=" + integer(row.realizedProfit().value()),
                "realizedLoss=" + integer(row.realizedLoss().value()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String plain(BigDecimal value) {
        BigDecimal stripped = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        return stripped.toPlainString();
    }

    private static String integer(BigDecimal value) {
        try {
            return value.setScale(0).toPlainString();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("INVALID_REALIZED_FINGERPRINT_INPUT", invalid);
        }
    }
}
