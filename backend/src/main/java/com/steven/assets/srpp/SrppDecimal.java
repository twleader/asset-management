package com.steven.assets.srpp;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Requirement 163／Task 452.3：canonical Decimal 字串（無千分位、指數、前導零、尾端零或負零）。 */
public final class SrppDecimal {
    private SrppDecimal() {}

    private static final Pattern CANONICAL = Pattern.compile("^-?(0|[1-9][0-9]*)(\\.[0-9]*[1-9])?$");
    public static final int MAX_LENGTH = 80;

    public static String format(BigDecimal value) {
        if (value == null) throw new IllegalArgumentException("DECIMAL_NULL");
        if (value.signum() == 0) return "0";
        return value.stripTrailingZeros().toPlainString();
    }

    public static boolean isCanonical(String text) {
        return text != null && !text.isEmpty() && text.length() <= MAX_LENGTH
                && !"-0".equals(text) && CANONICAL.matcher(text).matches();
    }
}
