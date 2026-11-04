package com.steven.assets.model;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static com.steven.assets.model.AnnualDividendData.*;
import static org.assertj.core.api.Assertions.assertThat;

class AnnualDividendCalculatorTest {
    private static final Security TW = new Security("0050", "元大台灣50", "台股");
    private static final Security US = new Security("AAPL", "Apple", "美股");
    private static final LocalDate TODAY = date("2026-11-04");
    private static LocalDate date(String value) { return LocalDate.parse(value); }
    private static BigDecimal n(String value) { return value == null ? null : new BigDecimal(value); }
    private static Snapshot snapshot(String date, String... quantities) {
        return new Snapshot(date(date), java.util.Arrays.stream(quantities)
                .map(q -> new Position(TW.code(), TW.market(), n(q))).toList());
    }
    private static Trade trade(String date, String direction, String quantity) {
        return new Trade(TW.code(), TW.market(), date(date), direction, n(quantity));
    }
    private static Event event(long id, String date, String amount) {
        return new Event(id, TW.code(), TW.market(), 2025, date == null ? null : date(date),
                null, date("2026-07-01"), n(amount), BigDecimal.ZERO, "official");
    }
    private static Facts facts(List<Snapshot> snapshots, List<Trade> trades, List<Event> events) {
        return new Facts(List.of(TW), snapshots, trades, events, List.of(new Market("台股", "台灣股市")));
    }
    private static Calculation calculate(Facts facts) { return AnnualDividendCalculator.calculate(facts, 2026, "", TODAY); }

    @Test void combinesBrokersAndAdjustsOnlyAfterSnapshotBeforeExDate() {
        var out = calculate(facts(List.of(snapshot("2026-06-01", "1000", "500")),
                List.of(trade("2026-06-01", "買", "999"), trade("2026-06-02", "買", "100"),
                        trade("2026-06-02", "賣", "50"), trade("2026-06-04", "賣", "200"),
                        trade("2026-06-05", "買", "999"), trade("2026-06-05", "賣", "999")),
                List.of(event(1, "2026-06-05", "2"))));
        assertThat(out.rows()).singleElement().satisfies(row -> {
            assertThat(row.shares()).isEqualByComparingTo("1350");
            assertThat(row.estimatedCash()).isEqualByComparingTo("2700.00");
            assertThat(row.transactionCount()).isEqualTo(3);
            assertThat(row.holdingBasis()).isEqualTo("HISTORICAL_ESTIMATE");
            assertThat(row.basisSnapshotDate()).isEqualTo(date("2026-06-01"));
        });
    }

    @Test void soldOutCurrentHoldingsDoNotErasePastEntitlementAndCutoffSnapshotWins() {
        var out = calculate(facts(List.of(snapshot("2026-05-01", "99"), snapshot("2026-06-04", "100"),
                        snapshot("2026-11-04")), List.of(trade("2026-06-04", "買", "100"), trade("2026-06-05", "賣", "100")),
                List.of(event(1, "2026-06-05", "2"))));
        assertThat(out.rows().getFirst().shares()).isEqualByComparingTo("100");
        assertThat(out.rows().getFirst().transactionCount()).isZero();
    }

    @Test void futureUsesLatestNotFutureDatedSnapshotAndOnlyTradesThroughToday() {
        var out = calculate(facts(List.of(snapshot("2026-11-01", "10"), snapshot("2026-11-05", "999")),
                List.of(trade("2026-11-04", "買", "0.5"), trade("2026-11-05", "買", "999")),
                List.of(event(1, "2026-11-10", "1"))));
        assertThat(out.rows().getFirst().shares()).isEqualByComparingTo("10.5");
        assertThat(out.rows().getFirst().holdingBasis()).isEqualTo("FUTURE_ESTIMATE");
        assertThat(out.currencyTotals().getFirst().futureEstimate()).isEqualByComparingTo("10.50");
        assertThat(out.currencyTotals().getFirst().historicalEstimate()).isEqualByComparingTo("0.00");
    }

    @Test void noBaselineDoesNotUseCurrentHoldingsOrSumLedgerFromZero() {
        var out = calculate(facts(List.of(snapshot("2026-11-04", "999")), List.of(trade("2026-01-01", "買", "100")),
                List.of(event(1, "2026-06-05", "2"))));
        assertThat(out.rows().getFirst().shares()).isNull();
        assertThat(out.rows().getFirst().estimatedCash()).isNull();
        assertThat(out.rows().getFirst().reason()).contains("缺少截止日前");
        assertThat(out.unavailableCount()).isEqualTo(1);
        assertThat(out.currencyTotals()).isEmpty();
    }

    @Test void negativeDailyBalanceCannotBeHiddenByLaterBuyButSameDayNettingIsAllowed() {
        var bad = calculate(facts(List.of(snapshot("2026-01-01", "100")),
                List.of(trade("2026-02-01", "賣", "200"), trade("2026-02-02", "買", "200")),
                List.of(event(1, "2026-06-05", "2"))));
        assertThat(bad.rows().getFirst().shares()).isNull();
        var dayNet = calculate(facts(List.of(snapshot("2026-01-01", "100")),
                List.of(trade("2026-02-01", "賣", "200"), trade("2026-02-01", "買", "200")),
                List.of(event(1, "2026-06-05", "2"))));
        assertThat(dayNet.rows().getFirst().shares()).isEqualByComparingTo("100");
    }

    @Test void missingNegativeAndUnknownTradeInputsNeverBecomeZero() {
        for (Trade invalid : List.of(trade("2026-02-01", "買", null), trade("2026-02-01", "賣", "-1"),
                trade("2026-02-01", "未知", "1"))) {
            var out = calculate(facts(List.of(snapshot("2026-01-01", "100")), List.of(invalid), List.of(event(1, "2026-06-05", "2"))));
            assertThat(out.rows().getFirst().estimatedCash()).isNull();
        }
        var out = calculate(facts(List.of(snapshot("2026-01-01", (String) null)), List.of(), List.of(event(1, "2026-06-05", "2"))));
        assertThat(out.rows().getFirst().shares()).isNull();
    }

    @Test void absentStockInCompleteBaselineMeansZeroWithoutInventingAnUnknownPosition() {
        var out = calculate(facts(List.of(snapshot("2026-01-01")), List.of(), List.of(event(1, "2026-06-05", "2"))));
        assertThat(out.rows().getFirst().estimatedCash()).isEqualByComparingTo("0.00");
        assertThat(out.unavailableCount()).isZero();
    }

    @Test void sameDateSameAmountDeduplicatesAndDifferentOrUnknownCashBlocksAmount() {
        var base = List.of(snapshot("2026-01-01", "100"));
        var repeated = calculate(facts(base, List.of(), List.of(event(1, "2026-06-05", "2"), event(2, "2026-06-05", "2.000000"))));
        assertThat(repeated.rows()).hasSize(1);
        assertThat(repeated.rows().getFirst().estimatedCash()).isEqualByComparingTo("200");
        for (String conflict : new String[]{"3", "0", null}) {
            var bad = calculate(facts(base, List.of(), List.of(event(1, "2026-06-05", "2"), event(2, "2026-06-05", conflict))));
            assertThat(bad.rows().getFirst().cashDividend()).isNull();
            assertThat(bad.rows().getFirst().estimatedCash()).isNull();
            assertThat(bad.rows().getFirst().reason()).contains("衝突");
        }
    }

    @Test void yearComesFromExDateAndAnnualSummariesOnlyProduceDeduplicatedGaps() {
        Event undated = new Event(2, TW.code(), TW.market(), 2026, null, null, null, n("20"), BigDecimal.ZERO, "old");
        var out = calculate(facts(List.of(snapshot("2026-01-01", "100")), List.of(),
                List.of(event(1, "2026-06-05", "2"), undated, undated)));
        assertThat(out.rows()).hasSize(1);
        assertThat(out.currencyTotals().getFirst().total()).isEqualByComparingTo("200");
        assertThat(out.gaps()).hasSize(1);
        assertThat(out.gaps().getFirst().reason()).contains("年度彙總");
        var absent = calculate(facts(List.of(snapshot("2026-01-01", "100")), List.of(), List.of()));
        assertThat(absent.rows()).isEmpty();
        assertThat(absent.gaps()).hasSize(1);
    }

    @Test void pureStockDividendsAndKnownZeroAreExcludedButUnknownCashIsReported() {
        Event stock = new Event(1, TW.code(), TW.market(), 2026, null, date("2026-06-05"), null, null, n("1"), "official");
        var pure = calculate(facts(List.of(snapshot("2026-01-01", "100")), List.of(), List.of(stock)));
        assertThat(pure.rows()).isEmpty();
        assertThat(pure.gaps()).isEmpty();
        var zero = calculate(facts(List.of(snapshot("2026-01-01", "100")), List.of(), List.of(event(2, "2026-06-05", "0"))));
        assertThat(zero.rows()).isEmpty();
        var unknown = calculate(facts(List.of(snapshot("2026-01-01", "100")), List.of(), List.of(event(3, "2026-06-05", null))));
        assertThat(unknown.rows().getFirst().estimatedCash()).isNull();
    }

    @Test void fractionalSharesRoundEachEventAndNeverMixCurrenciesOrGuessUnsupportedMarkets() {
        Security uk = new Security("VWRL", "Vanguard", "英股");
        List<Position> positions = List.of(new Position(TW.code(), TW.market(), n("1")),
                new Position(US.code(), US.market(), n("0.045")), new Position(uk.code(), uk.market(), n("1")));
        Facts f = new Facts(List.of(TW, US, uk), List.of(new Snapshot(date("2026-01-01"), positions)), List.of(),
                List.of(event(1, "2026-06-05", "2"), new Event(2, US.code(), US.market(), 2025, date("2026-06-05"), null, null, n("0.1"), null, "Yahoo"),
                        new Event(3, US.code(), US.market(), 2025, date("2026-07-05"), null, null, n("0.1"), null, "Yahoo"),
                        new Event(4, uk.code(), uk.market(), 2025, date("2026-06-05"), null, null, n("2"), null, "Yahoo")), List.of());
        var out = calculate(f);
        assertThat(out.currencyTotals()).extracting(CurrencyTotal::currency).containsExactly("TWD", "USD");
        assertThat(out.currencyTotals().get(1).total()).isEqualByComparingTo("0.00");
        assertThat(out.rows().stream().filter(r -> r.market().equals("英股")).findFirst().orElseThrow().currency()).isNull();
        assertThat(out.unavailableCount()).isEqualTo(1);
        assertThat(AnnualDividendCalculator.calculate(f, 2026, "美股", TODAY).rows()).hasSize(2);
    }
}
