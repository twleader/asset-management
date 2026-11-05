package com.steven.assets.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Immutable facts and estimates; no persistence, HTTP or vendor dependencies. */
public final class AnnualDividendData {
    private AnnualDividendData() {}

    public record Security(String code, String name, String market) {}
    public record StockOption(String key, String code, String name, String market) {}
    public record Market(String code, String displayName) {}
    public record Position(String code, String market, BigDecimal shares) {}
    public record Snapshot(LocalDate date, List<Position> positions, String market) {
        /** null market retains legacy all-market snapshots; daily history is explicitly market scoped. */
        public Snapshot(LocalDate date, List<Position> positions) { this(date, positions, null); }
        public Snapshot { positions = List.copyOf(positions); }
    }
    public record Trade(String code, String market, LocalDate date,
                        String direction, BigDecimal shares) {}
    public record Event(long id, String code, String market, int year,
                        LocalDate exDividendDate, LocalDate exRightsDate, LocalDate cashPaymentDate,
                        BigDecimal cashDividend, BigDecimal stockDividend, String source, String quality, String reason) {
        public Event(long id,String code,String market,int year,LocalDate exDividendDate,LocalDate exRightsDate,
                     LocalDate cashPaymentDate,BigDecimal cashDividend,BigDecimal stockDividend,String source){
            this(id,code,market,year,exDividendDate,exRightsDate,cashPaymentDate,cashDividend,stockDividend,source,null,null);
        }
    }
    public record Facts(List<Security> securities, List<Snapshot> snapshots,
                        List<Trade> trades, List<Event> events, List<Market> markets) {
        public Facts {
            securities = List.copyOf(securities);
            snapshots = List.copyOf(snapshots);
            trades = List.copyOf(trades);
            events = List.copyOf(events);
            markets = List.copyOf(markets);
        }
    }
    public record Row(String code, String name, String market, String currency,
                      LocalDate exDividendDate, LocalDate cashPaymentDate,
                      BigDecimal cashDividend, BigDecimal shares, BigDecimal estimatedCash,
                      LocalDate basisSnapshotDate, int transactionCount,
                      String holdingBasis, String reason, String source) {}
    public record Gap(String code, String name, String market, String reason) {}
    public record CurrencyTotal(String currency, BigDecimal historicalEstimate,
                                BigDecimal futureEstimate, BigDecimal total) {}
    public record Calculation(List<Row> rows, List<Gap> gaps,
                              List<CurrencyTotal> currencyTotals, int unavailableCount) {
        public Calculation {
            rows = List.copyOf(rows);
            gaps = List.copyOf(gaps);
            currencyTotals = List.copyOf(currencyTotals);
        }
    }
}
