package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.model.AssetTransaction;
import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.repository.AssetTransactionRepository;
import com.steven.assets.repository.ExchangeRateHistoryRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * Task 623 frozen sources for the D-143/D-234 satellite build: the owner's confirmed 009826/VT
 * ledger rows and the current month's USD/TWD mid rates. Database reads only: no broker call,
 * cache refresh, vendor I/O or write.
 */
@Component
public class SrppSatelliteBuildCapture {
    public static final String FILLS = "satellite-build-fills", MIDS = "usd-twd-month-mids";
    static final List<String> CODES = List.of("009826", "VT");
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private final AssetTransactionRepository transactions;
    private final ExchangeRateHistoryRepository rates;

    public record Sources(ObjectNode fills, ObjectNode mids) {}

    public SrppSatelliteBuildCapture(AssetTransactionRepository transactions, ExchangeRateHistoryRepository rates) {
        this.transactions = transactions;
        this.rates = rates;
    }

    @Transactional(readOnly = true)
    public Sources capture(long ownerUserId, LocalDate tradingDate, String policyBundleSha256) {
        ObjectNode fills;
        try {
            fills = fillsSource(ownerUserId, tradingDate, policyBundleSha256,
                    transactions.findSatelliteBuildRows(ownerUserId, CODES, selectionStart(tradingDate), tradingDate));
        } catch (RuntimeException unavailable) {
            fills = unavailableFills(ownerUserId, tradingDate, policyBundleSha256);
        }
        ObjectNode mids;
        try {
            mids = midsSource(tradingDate, rates.findByCurrencyAndRateDateBetweenOrderByRateDateAsc("USD", tradingDate.withDayOfMonth(1), tradingDate));
        } catch (RuntimeException unavailable) {
            mids = midsSource(tradingDate, null);
        }
        return new Sources(fills, mids);
    }

    /** Covers both the calendar month and the Monday-based calendar week that contain the trading date. */
    static LocalDate selectionStart(LocalDate tradingDate) {
        LocalDate month = tradingDate.withDayOfMonth(1), week = tradingDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return month.isBefore(week) ? month : week;
    }

    static ObjectNode fillsSource(long ownerUserId, LocalDate tradingDate, String bundle, List<AssetTransaction> rows) {
        ObjectNode out = identity(ownerUserId, tradingDate, bundle);
        out.put("status", "VERIFIED").put("verified", true).put("coverage", "COMPLETE");
        ObjectNode selection = out.putObject("selection");
        selection.put("mode", "DATE_RANGE").putNull("year");
        selection.put("start", selectionStart(tradingDate).toString()).put("end", tradingDate.toString());
        ArrayNode records = out.putArray("records");
        List<AssetTransaction> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing(AssetTransaction::getTradeDate).thenComparing(AssetTransaction::getId));
        for (AssetTransaction row : ordered) {
            ObjectNode r = records.addObject();
            r.put("id", row.getId());
            text(r, "assetCode", row.getAssetCode());text(r, "market", row.getMarket());text(r, "currency", row.getCurrency());
            text(r, "transactionType", row.getTransactionType());r.put("tradeDate", row.getTradeDate().toString());
            decimal(r, "shares", row.getShares());decimal(r, "price", row.getPrice());decimal(r, "amount", row.getAmount());
            decimal(r, "exchangeRate", row.getExchangeRate());decimal(r, "amountTwd", amountTwd(row));
        }
        return out;
    }

    /** Same rule as PublicTransactionHistoryService.toRecord: USD amount times the recorded rate, unrounded. */
    static BigDecimal amountTwd(AssetTransaction row) {
        BigDecimal amount = row.getAmount();
        if ("USD".equals(row.getCurrency()) && row.getExchangeRate() != null && row.getAmount() != null)
            amount = row.getAmount().multiply(row.getExchangeRate());
        return amount;
    }

    static ObjectNode unavailableFills(long ownerUserId, LocalDate tradingDate, String bundle) {
        ObjectNode out = identity(ownerUserId, tradingDate, bundle);
        out.put("status", "UNAVAILABLE").put("verified", false).put("coverage", "NONE").put("reason", "SATELLITE_BUILD_FILLS_UNAVAILABLE");
        return out;
    }

    /** One malformed or duplicated current-month row makes the whole month unknown (halved), never partially trusted. */
    static ObjectNode midsSource(LocalDate tradingDate, List<ExchangeRateHistory> rows) {
        ObjectNode out = F.objectNode();
        LocalDate start = tradingDate.withDayOfMonth(1);
        out.put("status", "UNAVAILABLE").put("month", tradingDate.toString().substring(0, 7)).put("start", start.toString()).put("end", tradingDate.toString());
        ArrayNode list = out.putArray("rows");
        if (rows == null) return out;
        Set<LocalDate> dates = new HashSet<>();
        List<ObjectNode> parsed = new ArrayList<>();
        for (ExchangeRateHistory row : rows) {
            BigDecimal buy = row == null ? null : row.getBuyRate(), sell = row == null ? null : row.getSellRate();
            LocalDate date = row == null ? null : row.getRateDate();
            if (date == null || buy == null || sell == null || buy.signum() <= 0 || sell.signum() <= 0 || buy.compareTo(sell) > 0
                    || date.isBefore(start) || date.isAfter(tradingDate) || !dates.add(date)) return out;
            ObjectNode r = F.objectNode();
            r.put("date", date.toString()).put("buyRate", SrppReportModuleCalculator.dec(buy)).put("sellRate", SrppReportModuleCalculator.dec(sell));
            r.put("midRate", SrppReportModuleCalculator.dec(buy.add(sell).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP)));
            parsed.add(r);
        }
        parsed.sort(Comparator.comparing(r -> r.path("date").asText()));
        parsed.forEach(list::add);
        out.put("status", "VERIFIED");
        return out;
    }

    private static ObjectNode identity(long ownerUserId, LocalDate tradingDate, String bundle) {
        ObjectNode out = F.objectNode();
        out.put("ownerUserId", ownerUserId).put("tradingDate", tradingDate.toString()).put("policyBundleSha256", bundle);
        return out;
    }

    private static void text(ObjectNode n, String key, String value) { if (value == null) n.putNull(key); else n.put(key, value); }
    private static void decimal(ObjectNode n, String key, BigDecimal value) { if (value == null) n.putNull(key); else n.put(key, SrppReportModuleCalculator.dec(value)); }
}
