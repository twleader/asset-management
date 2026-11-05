package com.steven.assets.bff.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Private business transport. Its quality/provenance fields are never the published 9090 DTO. */
public final class CanonicalDividendTransport {
    private CanonicalDividendTransport() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record History(String stockCode, String market, String source, String message,
                          List<Row> rows, String quality) {
        public History { rows = rows == null ? List.of() : List.copyOf(rows); }
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Row(Integer year, BigDecimal cashDividend, BigDecimal stockDividend,
                      String exDividendDate, BigDecimal yieldPct, String cashPaymentDate,
                      String stockPaymentDate, Integer fillDays, BigDecimal previousClose,
                      String exRightsDate, String quality, String reason, String source, String observedAt) {}
    public record DailyAmount(LocalDate date, BigDecimal cash, BigDecimal stock) {}

    public static boolean financial(History history) {
        return history != null && "RESOLVED".equals(history.quality())
                && history.rows().stream().allMatch(CanonicalDividendTransport::financial);
    }
    public static boolean financial(Row row) {
        if (row == null || !"RESOLVED".equals(row.quality()) || row.cashDividend() == null
                || row.stockDividend() == null || row.cashDividend().signum() < 0 || row.stockDividend().signum() < 0)
            return false;
        return (row.cashDividend().signum() == 0 || date(row.exDividendDate()) != null)
                && (row.stockDividend().signum() == 0 || date(row.exRightsDate()) != null);
    }

    /** Validates private canonical facts, then sums both components on their own effective dates. */
    public static List<DailyAmount> dailyAmountsFromRows(List<Row> rows, LocalDate from, LocalDate to) {
        if (rows == null) throw new IllegalArgumentException("DIVIDEND_HISTORY_UNAVAILABLE");
        Map<LocalDate, BigDecimal[]> grouped = new TreeMap<>();
        for (Row row : rows) {
            if (row == null) throw new IllegalArgumentException("DIVIDEND_FACT_MISSING");
            LocalDate cashDate = date(row.exDividendDate()), stockDate = date(row.exRightsDate());
            boolean relevant = inRange(cashDate,from,to) || inRange(stockDate,from,to)
                    || cashDate == null && stockDate == null
                    || cashDate == null && (row.cashDividend() == null || row.cashDividend().signum() > 0)
                    || stockDate == null && (row.stockDividend() == null || row.stockDividend().signum() > 0);
            if (!relevant) continue;
            if (!financial(row)) throw new IllegalArgumentException("CANONICAL_DIVIDEND_UNRESOLVED");
            if (row.cashDividend().signum() > 0 && inRange(cashDate,from,to))
                amount(grouped,cashDate,0,row.cashDividend());
            if (row.stockDividend().signum() > 0 && inRange(stockDate,from,to))
                amount(grouped,stockDate,1,row.stockDividend());
        }
        return grouped.entrySet().stream().map(e->new DailyAmount(e.getKey(),e.getValue()[0],e.getValue()[1])).toList();
    }
    private static void amount(Map<LocalDate,BigDecimal[]> grouped,LocalDate date,int slot,BigDecimal value){
        var amounts=grouped.computeIfAbsent(date,ignored->new BigDecimal[]{BigDecimal.ZERO,BigDecimal.ZERO});
        amounts[slot]=amounts[slot].add(value);
    }
    private static boolean inRange(LocalDate date,LocalDate from,LocalDate to){return date!=null&&(from==null||!date.isBefore(from))&&(to==null||!date.isAfter(to));}
    private static LocalDate date(String value){try{return value==null||value.isBlank()?null:LocalDate.parse(value);}catch(RuntimeException invalid){return null;}}
}
