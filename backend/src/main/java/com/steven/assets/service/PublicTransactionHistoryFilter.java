package com.steven.assets.service;

import com.steven.assets.dto.PublicTransactionHistoryDto.TransactionHistoryMode;
import com.steven.assets.dto.PublicTransactionHistoryDto.TransactionHistorySelection;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

/** Internal bridge filter parser. It rejects ambiguity before any ledger repository read. */
final class PublicTransactionHistoryFilter {

    private static final Pattern YEAR = Pattern.compile("^[0-9]{4}$");

    private PublicTransactionHistoryFilter() {}

    static Filter parse(List<String> years, List<String> starts, List<String> ends) {
        boolean hasYear = present(years);
        boolean hasStart = present(starts);
        boolean hasEnd = present(ends);
        if (!hasYear && !hasStart && !hasEnd) {
            return new Filter(new TransactionHistorySelection(TransactionHistoryMode.ALL, null, null, null), null, null);
        }
        if (hasYear) {
            if (hasStart || hasEnd) {
                throw new InvalidPublicTransactionHistoryRequestException();
            }
            String rawYear = exactlyOne(years);
            if (!YEAR.matcher(rawYear).matches()) {
                throw new InvalidPublicTransactionHistoryRequestException();
            }
            int year;
            try {
                year = Integer.parseInt(rawYear);
            } catch (NumberFormatException invalid) {
                throw new InvalidPublicTransactionHistoryRequestException();
            }
            if (year < 1900 || year > 9999) {
                throw new InvalidPublicTransactionHistoryRequestException();
            }
            return new Filter(new TransactionHistorySelection(TransactionHistoryMode.YEAR, year,
                    LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)),
                    LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
        }
        if (!hasStart || !hasEnd) {
            throw new InvalidPublicTransactionHistoryRequestException();
        }
        LocalDate start = parseDate(exactlyOne(starts));
        LocalDate end = parseDate(exactlyOne(ends));
        if (start.isAfter(end)) {
            throw new InvalidPublicTransactionHistoryRequestException();
        }
        return new Filter(new TransactionHistorySelection(TransactionHistoryMode.DATE_RANGE, null, start, end), start, end);
    }

    private static boolean present(List<String> values) {
        return values != null && !values.isEmpty();
    }

    private static String exactlyOne(List<String> values) {
        if (values == null || values.size() != 1 || values.getFirst() == null) {
            throw new InvalidPublicTransactionHistoryRequestException();
        }
        String value = values.getFirst();
        if (value.isBlank() || !value.equals(value.trim())) {
            throw new InvalidPublicTransactionHistoryRequestException();
        }
        return value;
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException invalid) {
            throw new InvalidPublicTransactionHistoryRequestException();
        }
    }

    record Filter(TransactionHistorySelection selection, LocalDate start, LocalDate end) {}
}
