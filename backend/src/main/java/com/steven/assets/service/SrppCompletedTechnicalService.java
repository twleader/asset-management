package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.repository.StockDividendHistoryRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/** Read-only SRPP facts from persisted daily prices; no quote refresh or owner lookup. */
@Service
@RequiredArgsConstructor
public class SrppCompletedTechnicalService {
    private final StockPriceHistoryRepository prices;
    private final StockDividendHistoryRepository dividends;
    private final DistributionAdjustedPriceService adjustments;

    public record SymbolFacts(
            String stockCode, String market, String status, LocalDate asOf,
            LocalDate historyStart, int sampleCount, String priceBasis,
            List<LocalDate> appliedEventDates, String sourceSha256,
            SrppCompletedTechnicalCalculator.Values indicators, List<String> missing) {}

    public record Coverage(int requestedCount, int completeCount, int partialCount, int unavailableCount) {}

    public record Response(String formulaVersion, String market, LocalDate asOf,
                           Coverage coverage, List<SymbolFacts> symbols) {}

    record LoadedSymbol(SymbolFacts facts, List<StockPriceHistory> rawRows,
                        List<StockPriceHistory> adjustedRows) {}

    @Transactional(readOnly = true)
    public Response read(String market, LocalDate asOf, List<String> codes) {
        List<SymbolFacts> symbols = new ArrayList<>();
        for (String code : codes) symbols.add(loadOne(code, market, asOf).facts());
        int complete = (int) symbols.stream().filter(s -> s.status().equals("COMPLETE")).count();
        int partial = (int) symbols.stream().filter(s -> s.status().equals("PARTIAL")).count();
        return new Response(SrppCompletedTechnicalCalculator.FORMULA_VERSION, market, asOf,
                new Coverage(codes.size(), complete, partial, codes.size() - complete - partial),
                List.copyOf(symbols));
    }

    LoadedSymbol loadOne(String code, String market, LocalDate asOf) {
        List<StockPriceHistory> rows = prices.findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(
                code, market, asOf.minusYears(2), asOf);
        if (rows.isEmpty() || !asOf.equals(rows.get(rows.size() - 1).getTradingDate())) {
            return new LoadedSymbol(
                    new SymbolFacts(code, market, "UNAVAILABLE", asOf,
                            rows.isEmpty() ? null : rows.get(0).getTradingDate(),
                            rows.size(), "UNAVAILABLE", List.of(), null, null,
                            List.of("AS_OF_BAR_MISSING")),
                    List.copyOf(rows), List.of());
        }
        LocalDate historyStart = rows.get(0).getTradingDate();
        var events = dividends.findAdjustmentEvents(code, market, historyStart, asOf);
        var desc = new ArrayList<>(rows);
        Collections.reverse(desc);
        var adjusted = adjustments.adjust(desc, events);
        List<StockPriceHistory> adjustedAsc = new ArrayList<>(adjusted.rowsDesc());
        Collections.reverse(adjustedAsc);
        var result = SrppCompletedTechnicalCalculator.calculate(adjustedAsc, asOf);
        var values = result.values();
        boolean any = values.ma5() != null || values.rsi14() != null || values.macdLine() != null
                || values.bollingerMiddle() != null || values.adx14() != null
                || values.obv20Change() != null || values.volumeRatio20() != null
                || values.oneYearPositionPct() != null;
        String status = !any ? "UNAVAILABLE" : result.missing().isEmpty() ? "COMPLETE" : "PARTIAL";
        return new LoadedSymbol(
                new SymbolFacts(code, market, status, asOf, historyStart, adjustedAsc.size(),
                        adjusted.adjusted() ? "ADJUSTED_RECORDED_EVENTS" : "RAW_NO_APPLIED_EVENT",
                        List.copyOf(adjusted.appliedEventDates()),
                        sha256(code, market, asOf, adjustedAsc, adjusted.appliedEventDates()),
                        values, result.missing()),
                List.copyOf(rows), List.copyOf(adjustedAsc));
    }

    private static String sha256(String code, String market, LocalDate asOf,
                                 List<StockPriceHistory> rows, List<LocalDate> events) {
        StringBuilder evidence = new StringBuilder(SrppCompletedTechnicalCalculator.FORMULA_VERSION)
                .append('|').append(code).append('|').append(market).append('|').append(asOf);
        for (StockPriceHistory row : rows) {
            evidence.append('\n').append(row.getTradingDate()).append('|')
                    .append(row.getOpenPrice()).append('|').append(row.getHighPrice()).append('|')
                    .append(row.getLowPrice()).append('|').append(row.getClosePrice()).append('|')
                    .append(row.getVolume()).append('|').append(row.getCloseSource());
        }
        evidence.append("\nevents=").append(events);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(evidence.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
