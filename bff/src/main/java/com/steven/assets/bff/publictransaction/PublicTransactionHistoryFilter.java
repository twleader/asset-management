package com.steven.assets.bff.publictransaction;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

/** Strict local query parser for ALL/year/date-range; no defaults or partially inferred ranges. */
final class PublicTransactionHistoryFilter {

    private static final Pattern YEAR = Pattern.compile("^[0-9]{4}$");

    private PublicTransactionHistoryFilter() {}

    static Filter parse(List<String> years, List<String> starts, List<String> ends) {
        boolean hasYear = present(years);
        boolean hasStart = present(starts);
        boolean hasEnd = present(ends);
        if (!hasYear && !hasStart && !hasEnd) {
            return new Filter(null, null, null);
        }
        if (hasYear) {
            if (hasStart || hasEnd) {
                throw new PublicTransactionHistoryRequestException();
            }
            String year = exactlyOne(years);
            if (!YEAR.matcher(year).matches()) {
                throw new PublicTransactionHistoryRequestException();
            }
            int parsed;
            try {
                parsed = Integer.parseInt(year);
            } catch (NumberFormatException invalid) {
                throw new PublicTransactionHistoryRequestException();
            }
            if (parsed < 1900 || parsed > 9999) {
                throw new PublicTransactionHistoryRequestException();
            }
            return new Filter(String.format("%04d", parsed), null, null);
        }
        if (!hasStart || !hasEnd) {
            throw new PublicTransactionHistoryRequestException();
        }
        LocalDate start = parseDate(exactlyOne(starts));
        LocalDate end = parseDate(exactlyOne(ends));
        if (start.isAfter(end)) {
            throw new PublicTransactionHistoryRequestException();
        }
        return new Filter(null, start, end);
    }

    private static boolean present(List<String> values) {
        return values != null && !values.isEmpty();
    }

    private static String exactlyOne(List<String> values) {
        if (values == null || values.size() != 1 || values.getFirst() == null) {
            throw new PublicTransactionHistoryRequestException();
        }
        String value = values.getFirst();
        if (value.isBlank() || !value.equals(value.trim())) {
            throw new PublicTransactionHistoryRequestException();
        }
        return value;
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException invalid) {
            throw new PublicTransactionHistoryRequestException();
        }
    }

    record Filter(String year, LocalDate start, LocalDate end) {}
}
