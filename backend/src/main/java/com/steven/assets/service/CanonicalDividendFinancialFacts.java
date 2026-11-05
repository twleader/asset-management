package com.steven.assets.service;

import com.steven.assets.model.CanonicalDividendFinancialGuard;
import com.steven.assets.model.StockDividendHistory;
import java.time.LocalDate;
import java.util.List;

/** Maps persistence facts into the neutral guard without IO or inferred quality. */
public final class CanonicalDividendFinancialFacts {
    private CanonicalDividendFinancialFacts() {}

    public static CanonicalDividendFinancialGuard.Assessment assess(List<StockDividendHistory> rows) {
        if (rows == null) return CanonicalDividendFinancialGuard.assess(null);
        return CanonicalDividendFinancialGuard.assess(rows.stream().map(row -> row == null ? null
                : new CanonicalDividendFinancialGuard.Fact(
                    row.getId() == null ? row.getEventKey() : "history:" + row.getId(),
                    row.getStockCode(), row.getMarket(), row.getCanonicalDate(),
                    row.getCashDividend(), row.getStockDividend(), row.getResolutionStatus(),
                    row.getSourceSnapshotId(), row.getResolutionReason())).toList());
    }

    public static CanonicalDividendFinancialGuard.Assessment assess(
            List<StockDividendHistory> rows, LocalDate from, LocalDate to) {
        return assess(within(rows, from, to));
    }

    /** Keep unknown dates visible so a window cannot silently turn unresolved history into no event. */
    public static List<StockDividendHistory> within(List<StockDividendHistory> rows, LocalDate from, LocalDate to) {
        return rows == null ? null : rows.stream().filter(row -> {
            if (row == null) return true;
            LocalDate date = row.getCanonicalDate() == null ? row.anchorDate() : row.getCanonicalDate();
            return date == null || (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to));
        }).toList();
    }

    public static boolean verified(List<StockDividendHistory> rows) {
        return rows != null && !rows.isEmpty() && assess(rows).available();
    }
}
