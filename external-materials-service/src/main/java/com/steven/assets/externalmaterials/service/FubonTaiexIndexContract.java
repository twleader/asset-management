package com.steven.assets.externalmaterials.service;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.regex.Pattern;

/** Shared fail-closed wire/domain validation for the normalized TAIEX stream. */
final class FubonTaiexIndexContract {

    private static final Pattern SYMBOL = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final Pattern CANONICAL_DECIMAL = Pattern.compile("^(0|[1-9][0-9]*)(?:\\.[0-9]+)?$");

    private FubonTaiexIndexContract() {
    }

    static boolean validSymbol(String symbol) {
        return symbol != null && SYMBOL.matcher(symbol).matches();
    }

    static BigDecimal parsePositiveCanonicalIndex(String raw) {
        if (raw == null || !CANONICAL_DECIMAL.matcher(raw).matches()) return null;
        try {
            BigDecimal value = new BigDecimal(raw);
            if (value.signum() <= 0 || value.precision() > 20 || value.scale() < 0 || value.scale() > 10) {
                return null;
            }
            return value;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    static Instant instantFromMicros(long micros) {
        if (micros <= 0) return null;
        try {
            return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                    Math.floorMod(micros, 1_000_000L) * 1_000L);
        } catch (DateTimeException invalid) {
            return null;
        }
    }
}
