package com.steven.assets.externalmaterials.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Deterministic bounded Task466 windows. Dates are inclusive. */
public final class FubonHistoricalBackfillPlanner {
    public static final LocalDate MINUTE_API_START = LocalDate.of(2023, 5, 23);
    private FubonHistoricalBackfillPlanner() {}
    public record Window(String dataset, String symbol, LocalDate from, LocalDate to) {}

    public static List<Window> windows(List<String> sortedSymbols, LocalDate from, LocalDate to) {
        if (sortedSymbols == null || sortedSymbols.isEmpty() || from == null || to == null || from.isAfter(to)
                || from.plusYears(10).isBefore(to)) throw new IllegalArgumentException("INVALID_BACKFILL_RANGE");
        List<Window> result = new ArrayList<>();
        for (String symbol : sortedSymbols) {
            append(result, "DAILY_CANDLE", symbol, from, to, 365);
            LocalDate minuteFrom = from.isAfter(MINUTE_API_START) ? from : MINUTE_API_START;
            if (!minuteFrom.isAfter(to)) append(result, "INTRADAY_CANDLE_1M", symbol, minuteFrom, to, 31);
        }
        return List.copyOf(result);
    }

    private static void append(List<Window> out, String dataset, String symbol, LocalDate from, LocalDate to, int inclusiveDays) {
        LocalDate start = from;
        while (!start.isAfter(to)) {
            LocalDate end = start.plusDays(inclusiveDays - 1L);
            if (end.isAfter(to)) end = to;
            out.add(new Window(dataset, symbol, start, end));
            start = end.plusDays(1);
        }
    }
}
