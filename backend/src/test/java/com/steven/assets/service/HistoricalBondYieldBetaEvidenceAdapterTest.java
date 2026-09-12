package com.steven.assets.service;

import com.steven.assets.dto.TreasuryYieldDto;
import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.repository.TradingRadarListBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HistoricalBondYieldBetaEvidenceAdapterTest {

    private static final String CODE = "TLT";
    private static final String MARKET = "美股";
    private static final LocalDate FIRST = LocalDate.of(2026, 1, 1);
    private static final Instant DECISION = LocalDate.of(2026, 1, 12)
            .atTime(20, 0).atZone(ZoneId.of("America/New_York")).toInstant();

    @Mock private TradingRadarListBatchRepository batchRepository;
    @Mock private MarketDataService marketDataService;

    private HistoricalBondYieldBetaEvidenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new HistoricalBondYieldBetaEvidenceAdapter(
                batchRepository, new DistributionAdjustedPriceService(), marketDataService);
        org.mockito.Mockito.lenient().when(batchRepository.findAdjustmentEvents(any(), any()))
                .thenAnswer(invocation -> emptyEvents(invocation.getArgument(0)));
        org.mockito.Mockito.lenient().when(batchRepository.findExchangeRatesByCurrency(any()))
                .thenReturn(List.of());
        org.mockito.Mockito.lenient().when(marketDataService.isUsTradingDay(any()))
                .thenReturn(true);
    }

    @Test
    void actualAdjustedPriceAndAppendOnlyTreasurySeriesProduceAvailablePerInstrumentBeta() {
        Fixture fixture = fixture(true);
        stubHistory(fixture.prices(), fixture.curves());
        BondYieldBetaResolver.Query query = query();

        BondYieldBetaResolver.Result result = adapter.resolve(query);

        assertThat(result.status()).isEqualTo(BondYieldBetaResolver.Status.AVAILABLE);
        assertThat(result.n()).isEqualTo(9);
        assertThat(result.univariateBeta()).isCloseTo(new BigDecimal("-2"),
                org.assertj.core.data.Offset.offset(new BigDecimal("0.0001")));
        assertThat(result.providers()).contains("VERIFIED_CLOSE", "US_TREASURY");
        assertThat(result.knownAt()).isBeforeOrEqualTo(DECISION);
        assertThat(result.effectiveShockPp()).isEqualByComparingTo("-0.01");
        assertThat(BondYieldBetaResolver.normalizedContribution(result))
                .isNotNull().isGreaterThan(0.0);
    }

    @Test
    void legacyRowsWithoutCloseSourceUseExplicitUnknownProvenanceAndRemainAsOfSafe() {
        Fixture fixture = fixture(false);
        stubHistory(fixture.prices(), fixture.curves());

        BondYieldBetaResolver.Result result = adapter.resolve(query());

        assertThat(result.status()).isEqualTo(BondYieldBetaResolver.Status.AVAILABLE);
        assertThat(result.n()).isEqualTo(9);
        assertThat(result.providers()).contains(HistoricalBondYieldBetaEvidenceAdapter.LEGACY_SOURCE_UNKNOWN);
        assertThat(BondYieldBetaResolver.normalizedContribution(result)).isNotNull();
    }

    @Test
    void returnUsingLegacyPreviousCloseRetainsLegacyBasisAndCombinedProvider() {
        Fixture fixture = fixture(true);
        StockPriceHistory previous = fixture.prices().getFirst();
        List<StockPriceHistory> mixed = new ArrayList<>(fixture.prices());
        mixed.set(0, price(previous.getTradingDate(), previous.getClosePrice(), null));
        stubHistory(mixed, fixture.curves());

        List<BondYieldBetaResolver.Sample> samples = adapter.load(query());

        assertThat(samples).isNotEmpty();
        BondYieldBetaResolver.NumericObservation firstReturn = samples.getFirst().adjustedReturnPct();
        assertThat(firstReturn.availabilityBasis())
                .isEqualTo(HistoricalBondYieldBetaEvidenceAdapter.LEGACY_PRICE_BASIS);
        assertThat(firstReturn.provider())
                .isEqualTo("LEGACY_SOURCE_UNKNOWN+VERIFIED_CLOSE");
    }

    @Test
    void nonAllowlistedNewCloseSourceCannotBecomeBetaEvidence() {
        Fixture fixture = fixture(true);
        List<StockPriceHistory> untrusted = fixture.prices().stream()
                .map(row -> price(row.getTradingDate(), row.getClosePrice(), "MANUAL_GUESS"))
                .toList();
        stubHistory(untrusted, fixture.curves());

        BondYieldBetaResolver.Result result = adapter.resolve(query());

        assertThat(result.status()).isEqualTo(BondYieldBetaResolver.Status.MISSING);
        assertThat(result.n()).isZero();
        assertThat(result.missingReason()).contains("observation");
    }

    @Test
    void missingMiddleCurveDateIsNotMislabeledAsOneSessionLag() {
        Fixture fixture = fixture(true);
        List<TreasuryYieldDto.StoredBatch> withGap = new ArrayList<>(fixture.curves());
        LocalDate missingDate = FIRST.plusDays(5);
        withGap.removeIf(batch -> missingDate.equals(batch.curveDate()));
        stubHistory(fixture.prices(), withGap);

        List<BondYieldBetaResolver.Sample> samples = adapter.load(query());

        assertThat(samples).noneMatch(sample -> missingDate.plusDays(1).equals(sample.returnDate()));
        assertThat(samples).allMatch(sample -> sample.lagSessionsApplied() == 1
                && sample.yieldChangeDate().equals(sample.returnDate().minusDays(1)));
    }

    @Test
    void nyseHolidayIsSkippedButAdjacentMarketSessionStillAligns() {
        LocalDate friday = LocalDate.of(2026, 1, 2);
        LocalDate mondayHoliday = LocalDate.of(2026, 1, 5);
        LocalDate tuesday = LocalDate.of(2026, 1, 6);
        LocalDate wednesday = LocalDate.of(2026, 1, 7);
        when(marketDataService.isUsTradingDay(any())).thenAnswer(invocation -> {
            LocalDate date = invocation.getArgument(0);
            return !date.equals(mondayHoliday)
                    && date.getDayOfWeek() != java.time.DayOfWeek.SATURDAY
                    && date.getDayOfWeek() != java.time.DayOfWeek.SUNDAY;
        });
        stubHistory(List.of(price(tuesday, new BigDecimal("100"), true),
                price(wednesday, new BigDecimal("99.8"), true)), List.of(
                curve(1, friday, new BigDecimal("4.00")),
                curve(2, tuesday, new BigDecimal("4.10"))));

        List<BondYieldBetaResolver.Sample> samples = adapter.load(query());

        assertThat(samples).singleElement().satisfies(sample -> {
            assertThat(sample.returnDate()).isEqualTo(wednesday);
            assertThat(sample.yieldChangeDate()).isEqualTo(tuesday);
            assertThat(sample.lagSessionsApplied()).isEqualTo(1);
        });
    }

    @Test
    void alternateCandidateTenorAndShapeAreResolvedAsOfWithoutFutureCurveLeakage() {
        LocalDate previousDate = LocalDate.of(2026, 1, 8);
        LocalDate currentDate = LocalDate.of(2026, 1, 9);
        Map<String, BigDecimal> previous = Map.of(
                "M3", new BigDecimal("3.00"), "Y5", new BigDecimal("4.00"),
                "Y10", new BigDecimal("5.00"), "Y30", new BigDecimal("6.00"));
        Map<String, BigDecimal> current = Map.of(
                "M3", new BigDecimal("3.02"), "Y5", new BigDecimal("4.10"),
                "Y10", new BigDecimal("5.01"), "Y30", new BigDecimal("6.01"));
        Map<String, BigDecimal> future = Map.of(
                "M3", new BigDecimal("3.50"), "Y5", new BigDecimal("9.00"),
                "Y10", new BigDecimal("5.50"), "Y30", new BigDecimal("6.50"));
        stubHistory(List.of(price(currentDate, new BigDecimal("100"), true),
                price(currentDate.plusDays(1), new BigDecimal("99.8"), true)), List.of(
                curve(1, previousDate, previous, DECISION.minusSeconds(120)),
                curve(2, currentDate, current, DECISION.minusSeconds(60)),
                curve(3, currentDate.plusDays(1), future, DECISION.plusSeconds(1))));
        BondYieldBetaResolver.RateSignalSpec spec = new BondYieldBetaResolver.RateSignalSpec(
                "Y5", "M3", "Y5", new BigDecimal("0.50"), BigDecimal.ONE);
        BondYieldBetaResolver.Query alternateQuery = new BondYieldBetaResolver.Query(
                CODE, MARKET, "Y5", DECISION, BondYieldBetaResolver.FxControl.NONE,
                1, 3, 3, 3, spec);

        BondYieldBetaEvidencePort.Evidence evidence = adapter.loadEvidence(alternateQuery);

        assertThat(evidence.rateSignal()).isNotNull();
        assertThat(evidence.rateSignal().curveDate()).isEqualTo(currentDate);
        assertThat(evidence.rateSignal().primaryShockPp().value()).isEqualByComparingTo("0.10");
        assertThat(evidence.rateSignal().curveShapeShockPp().value()).isEqualByComparingTo("0.08");
        assertThat(spec.effectiveShock(
                evidence.rateSignal().primaryShockPp().value(),
                evidence.rateSignal().curveShapeShockPp().value()))
                .isEqualByComparingTo("0.1400");
    }

    @Test
    void resolveBatchLoadsEachHistoricalSourceOnceForSharedCodeAndMarket() {
        Fixture fixture = fixture(true);
        stubHistory(fixture.prices(), fixture.curves());
        BondYieldBetaResolver.Query first = query();
        BondYieldBetaResolver.Query second = new BondYieldBetaResolver.Query(
                CODE, MARKET, "Y30", DECISION.minusSeconds(3600),
                BondYieldBetaResolver.FxControl.NONE, 1, 3, 3, 3);

        var results = adapter.resolveBatch(List.of(first, second));

        assertThat(results).containsKeys(first, second);
        verify(batchRepository, times(1)).findAllPrices(any());
        verify(batchRepository, times(1)).findAdjustmentEvents(any(), any());
        verify(batchRepository, times(1)).findCompleteTreasurySeriesThrough(any());
    }

    @Test
    void requestBatchRepositoryProvidesOneExactPairHistoryWithoutScalarFallback() {
        Fixture fixture = fixture(true);
        TradingRadarListBatchRepository.Key key = new TradingRadarListBatchRepository.Key(CODE, MARKET);
        when(batchRepository.findAllPrices(any())).thenReturn(Map.of(key, fixture.prices()));
        when(batchRepository.findAdjustmentEvents(any(), any())).thenReturn(Map.of(key, List.of()));
        when(batchRepository.findCompleteTreasurySeriesThrough(any())).thenReturn(fixture.curves());

        var result = adapter.resolveBatch(List.of(query()));

        assertThat(result.get(query()).status()).isEqualTo(BondYieldBetaResolver.Status.AVAILABLE);
        verify(batchRepository).findAllPrices(any());
        verify(batchRepository).findAdjustmentEvents(any(), any());
    }

    private static BondYieldBetaResolver.Query query() {
        return new BondYieldBetaResolver.Query(
                CODE, MARKET, "Y30", DECISION, BondYieldBetaResolver.FxControl.NONE,
                1, 3, 3, 3);
    }

    private void stubHistory(
            List<StockPriceHistory> prices, List<TreasuryYieldDto.StoredBatch> curves) {
        when(batchRepository.findAllPrices(any())).thenAnswer(invocation -> {
            Collection<TradingRadarListBatchRepository.Key> keys = invocation.getArgument(0);
            Map<TradingRadarListBatchRepository.Key, List<StockPriceHistory>> out = new LinkedHashMap<>();
            keys.forEach(key -> out.put(key, List.copyOf(prices)));
            return Map.copyOf(out);
        });
        when(batchRepository.findCompleteTreasurySeriesThrough(any())).thenReturn(curves);
    }

    private static Map<TradingRadarListBatchRepository.Key, List<StockDividendHistory>> emptyEvents(
            Collection<TradingRadarListBatchRepository.Key> keys) {
        if (keys == null) return Map.of();
        Map<TradingRadarListBatchRepository.Key, List<StockDividendHistory>> out = new LinkedHashMap<>();
        keys.forEach(key -> out.put(key, List.of()));
        return Map.copyOf(out);
    }

    private static Fixture fixture(boolean withPriceProvider) {
        List<BigDecimal> deltas = List.of(
                new BigDecimal("-0.01"), new BigDecimal("0.02"), new BigDecimal("-0.01"),
                new BigDecimal("-0.01"), new BigDecimal("0.02"), new BigDecimal("-0.01"),
                new BigDecimal("-0.01"), new BigDecimal("0.02"), new BigDecimal("-0.01"));
        List<TreasuryYieldDto.StoredBatch> curves = new ArrayList<>();
        BigDecimal level = new BigDecimal("4.00");
        curves.add(curve(1, FIRST, level));
        for (int i = 0; i < deltas.size(); i++) {
            level = level.add(deltas.get(i));
            curves.add(curve(i + 2L, FIRST.plusDays(i + 1L), level));
        }

        List<StockPriceHistory> prices = new ArrayList<>();
        BigDecimal close = new BigDecimal("100");
        prices.add(price(FIRST.plusDays(1), close, withPriceProvider));
        for (int i = 0; i < deltas.size(); i++) {
            BigDecimal returnPct = deltas.get(i).multiply(new BigDecimal("-2"));
            close = close.multiply(BigDecimal.ONE.add(returnPct.movePointLeft(2)))
                    .setScale(12, RoundingMode.HALF_UP);
            prices.add(price(FIRST.plusDays(i + 2L), close, withPriceProvider));
        }
        return new Fixture(List.copyOf(prices), List.copyOf(curves));
    }

    private static StockPriceHistory price(LocalDate date, BigDecimal close, boolean withProvider) {
        return price(date, close, withProvider ? "VERIFIED_CLOSE" : null);
    }

    private static StockPriceHistory price(LocalDate date, BigDecimal close, String closeSource) {
        return StockPriceHistory.builder()
                .stockCode(CODE).market(MARKET).tradingDate(date)
                .openPrice(close).highPrice(close).lowPrice(close).closePrice(close)
                .volume(1_000L).closeSource(closeSource)
                .build();
    }

    private static TreasuryYieldDto.StoredBatch curve(long id, LocalDate date, BigDecimal value) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        Map<String, String> manifest = new LinkedHashMap<>();
        for (String tenor : TreasuryYieldBatchRepository.TENOR_ORDER) {
            values.put(tenor, value);
            manifest.put(tenor, "https://home.treasury.gov/" + tenor);
        }
        Instant availableAt = date.plusDays(1).atStartOfDay(ZoneId.of("America/New_York")).toInstant();
        return new TreasuryYieldDto.StoredBatch(
                id, date, "US_TREASURY", "https://home.treasury.gov/curve",
                availableAt, "CONSERVATIVE_NEXT_MIDNIGHT_ET", availableAt,
                true, "hash-" + id, Map.copyOf(values), Map.copyOf(manifest));
    }

    private static TreasuryYieldDto.StoredBatch curve(
            long id, LocalDate date, Map<String, BigDecimal> values, Instant availableAt) {
        Map<String, String> manifest = new LinkedHashMap<>();
        TreasuryYieldBatchRepository.TENOR_ORDER.forEach(
                tenor -> manifest.put(tenor, "https://home.treasury.gov/" + tenor));
        return new TreasuryYieldDto.StoredBatch(
                id, date, "US_TREASURY", "https://home.treasury.gov/curve",
                availableAt, "TEST_AS_OF", availableAt, true, "hash-" + id,
                Map.copyOf(values), Map.copyOf(manifest));
    }

    private record Fixture(
            List<StockPriceHistory> prices,
            List<TreasuryYieldDto.StoredBatch> curves) {}
}
