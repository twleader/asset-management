package com.steven.assets.service;

import com.steven.assets.model.CommodityPriceHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.TwseInstitutionalDaily;
import com.steven.assets.model.UsIndexDailyHistory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TradingRadarMarketFeatureResolverTest {

    @Test
    void usIndexUsesOnlyRowsPastConservativeCloseAndDuplicatesStayDisclosureOnly() {
        List<UsIndexDailyHistory> rows = new ArrayList<>();
        for (String code : List.of("IXIC", "SPX")) {
            for (int day = 1; day <= 7; day++) {
                rows.add(us(code, LocalDate.of(2026, 8, day), day * 10L, day * 100L));
            }
        }
        // 2026-08-07 17:00 New York: the 8/7 row is present in DB but not yet available.
        Instant decision = Instant.parse("2026-08-07T21:00:00Z");

        var evidence = TradingRadarMarketFeatureResolver.resolve(
                "美股", decision,
                new TradingRadarMarketFeatureResolver.Sources(rows, List.of(), Map.of()));

        CandidateMarketFeature spx = evidence.feature("SPX_RET5");
        assertThat(spx.status()).isEqualTo(CandidateMarketFeature.AVAILABLE);
        assertThat(spx.asOfDate()).isEqualTo(LocalDate.of(2026, 8, 6));
        assertThat(spx.value()).isEqualByComparingTo("500.00000000");
        CandidateMarketFeature ixic = evidence.feature("IXIC_RET5");
        assertThat(ixic.status()).isEqualTo(CandidateMarketFeature.DISCLOSURE_ONLY);
        assertThat(ixic.duplicateOf()).isEqualTo("MARKET_REGIME");
        assertThat(evidence.hasAvailable("IXIC_RET5")).isFalse();

        var twEvidence = TradingRadarMarketFeatureResolver.resolve(
                "台股", decision,
                new TradingRadarMarketFeatureResolver.Sources(rows, List.of(), Map.of()));
        assertThat(twEvidence.feature("SPX_RET5").status())
                .isEqualTo(CandidateMarketFeature.AVAILABLE);
        assertThat(twEvidence.feature("IXIC_RET5").status())
                .isEqualTo(CandidateMarketFeature.DISCLOSURE_ONLY,
                        "TW regime 同樣使用 Nasdaq/SOX 40/60 composite，不得重複計分");
    }

    @Test
    void strictExpectedTerminalRequiresExactCompletedSessionAndFailsClosedWhenUnknown() {
        List<UsIndexDailyHistory> rows = new ArrayList<>();
        for (int day = 1; day <= 8; day++) {
            rows.add(us("SPX", LocalDate.of(2026, 8, day), day * 10L, day * 100L));
        }
        Instant decision = Instant.parse("2026-08-08T21:00:00Z");
        var exactExpected = TradingRadarMarketFeatureResolver.ExpectedSessions.strict(
                LocalDate.of(2026, 8, 8), LocalDate.of(2026, 8, 7), LocalDate.of(2026, 8, 7));

        CandidateMarketFeature exact = TradingRadarMarketFeatureResolver.resolve(
                "美股", decision,
                new TradingRadarMarketFeatureResolver.Sources(rows, List.of(), Map.of()),
                exactExpected).feature("SPX_RET5");
        assertThat(exact.status()).isEqualTo(CandidateMarketFeature.AVAILABLE);
        assertThat(exact.asOfDate()).isEqualTo(LocalDate.of(2026, 8, 7));
        
        List<UsIndexDailyHistory> missingTerminal = rows.stream()
                .filter(row -> !LocalDate.of(2026, 8, 7).equals(row.getTradingDate()))
                .toList();
        CandidateMarketFeature missing = TradingRadarMarketFeatureResolver.resolve(
                "美股", decision,
                new TradingRadarMarketFeatureResolver.Sources(missingTerminal, List.of(), Map.of()),
                exactExpected).feature("SPX_RET5");
        assertThat(missing.status()).isEqualTo(CandidateMarketFeature.MISSING);
        assertThat(missing.missingReason()).contains("指定 completed terminal session");

        CandidateMarketFeature unknown = TradingRadarMarketFeatureResolver.resolve(
                "美股", decision,
                new TradingRadarMarketFeatureResolver.Sources(rows, List.of(), Map.of()),
                TradingRadarMarketFeatureResolver.ExpectedSessions.strict(null, null, null))
                .feature("SPX_RET5");
        assertThat(unknown.status()).isEqualTo(CandidateMarketFeature.MISSING);
        assertThat(unknown.missingReason()).contains("未由日曆確認");
    }

    @Test
    void legacyCommodityUsesNextMidnightEtInsteadOfSameDayCloseGuess() {
        List<CommodityPriceHistory> wti = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            wti.add(commodity("WTI", LocalDate.of(2026, 8, day), day * 10L));
        }
        // 8/7 23:30 ET: the 8/7 legacy row is not available until 8/8 00:00 ET.
        Instant decision = Instant.parse("2026-08-08T03:30:00Z");

        var evidence = TradingRadarMarketFeatureResolver.resolve(
                "美股", decision,
                new TradingRadarMarketFeatureResolver.Sources(
                        List.of(), List.of(), Map.of("WTI", wti)));

        CandidateMarketFeature feature = evidence.feature("WTI_RET5");
        assertThat(feature.status()).isEqualTo(CandidateMarketFeature.AVAILABLE);
        assertThat(feature.asOfDate()).isEqualTo(LocalDate.of(2026, 8, 6));
        assertThat(feature.availableAt()).isEqualTo(Instant.parse("2026-08-07T04:00:00Z"));
        assertThat(feature.availabilityBasis())
                .isEqualTo("RECONSTRUCTED_CONSERVATIVE_NEXT_MIDNIGHT_ET");
    }

    @Test
    void institutionalCandidateIsExplicitMissingAndNeverParsedFromNews() {
        var evidence = TradingRadarMarketFeatureResolver.resolve(
                "台股", Instant.parse("2026-08-07T08:30:00Z"),
                TradingRadarMarketFeatureResolver.Sources.empty());

        CandidateMarketFeature institutional = evidence.feature(
                "TW_INSTITUTIONAL_NET_TURNOVER");
        assertThat(institutional.status()).isEqualTo(CandidateMarketFeature.MISSING);
        assertThat(institutional.value()).isNull();
        assertThat(institutional.missingReason()).contains("禁止從新聞字串反解析");
    }

    @Test
    void institutionalUsesTypedTotalNetAndSameDateTurnoverAfterObservedBoundary() {
        LocalDate date = LocalDate.of(2026, 8, 7);
        Instant observedAt = Instant.parse("2026-08-07T08:05:00Z");
        TwseInstitutionalDaily observation = TwseInstitutionalDaily.builder()
                .tradingDate(date)
                .foreignNet(new BigDecimal("60000000000"))
                .trustNet(new BigDecimal("-10000000000"))
                .dealerNet(BigDecimal.ZERO)
                .totalNet(new BigDecimal("50000000000"))
                .provider("TWSE_BFI82U")
                .sourceUrl("https://www.twse.com.tw/rwd/zh/fund/BFI82U?response=json&date=20260807")
                .observedAt(observedAt)
                .availabilityBasis("OBSERVED_AT_NO_PUBLISHED_TIMESTAMP")
                .status(CandidateMarketFeature.AVAILABLE)
                .build();
        TwseIndexDailyHistory taiex = new TwseIndexDailyHistory(
                date, null, null, null, BigDecimal.ONE, null, 1L,
                new BigDecimal("1000000000000"));
        var sources = new TradingRadarMarketFeatureResolver.Sources(
                List.of(), List.of(taiex), Map.of(), List.of(observation));

        CandidateMarketFeature tooEarly = TradingRadarMarketFeatureResolver.resolve(
                "台股", observedAt.minusSeconds(1), sources)
                .feature("TW_INSTITUTIONAL_NET_TURNOVER");
        CandidateMarketFeature visible = TradingRadarMarketFeatureResolver.resolve(
                "台股", observedAt, sources)
                .feature("TW_INSTITUTIONAL_NET_TURNOVER");

        assertThat(tooEarly.status()).isEqualTo(CandidateMarketFeature.MISSING);
        assertThat(visible.status()).isEqualTo(CandidateMarketFeature.AVAILABLE);
        assertThat(visible.value()).isEqualByComparingTo("0.05000000");
        assertThat(visible.availableAt()).isEqualTo(observedAt);
        assertThat(visible.provider()).isEqualTo("TWSE_BFI82U");
        assertThat(visible.sourceUrl()).contains("BFI82U");
    }

    @Test
    void commodityWithProvenanceUsesSourceAvailableAtAndExposesSource() {
        List<CommodityPriceHistory> gold = new ArrayList<>();
        for (int day = 1; day <= 6; day++) {
            Instant fetchedAt = Instant.parse("2026-08-08T10:00:00Z").plusSeconds(day);
            gold.add(CommodityPriceHistory.builder()
                    .commodityCode("GOLD")
                    .priceDate(LocalDate.of(2026, 8, day))
                    .closePrice(BigDecimal.valueOf(day * 10L))
                    .provider("YAHOO_FINANCE_CHART")
                    .sourceUrl("https://query1.finance.yahoo.com/chart/GC%3DF")
                    .sourceAvailableAt(fetchedAt)
                    .fetchedAt(fetchedAt)
                    .build());
        }
        Instant decision = Instant.parse("2026-08-08T10:00:06Z");

        CandidateMarketFeature feature = TradingRadarMarketFeatureResolver.resolve(
                "美股", decision,
                new TradingRadarMarketFeatureResolver.Sources(
                        List.of(), List.of(), Map.of("GOLD", gold)))
                .feature("GOLD_RET5");

        assertThat(feature.status()).isEqualTo(CandidateMarketFeature.AVAILABLE);
        assertThat(feature.value()).isEqualByComparingTo("500.00000000");
        assertThat(feature.availableAt()).isEqualTo(decision);
        assertThat(feature.availabilityBasis()).isEqualTo("SOURCE_AVAILABLE_AT");
        assertThat(feature.provider()).isEqualTo("YAHOO_FINANCE_CHART");
        assertThat(feature.sourceUrl()).contains("GC%3DF");
    }

    @Test
    void aggregateContributionClampsSignedValuesAndExcludesDuplicateDisclosure() {
        Instant decision = Instant.parse("2026-08-08T08:00:00Z");
        CandidateMarketFeature spx = new CandidateMarketFeature(
                "SPX_RET5", new BigDecimal("2.5"), LocalDate.of(2026, 8, 7), decision,
                "CLOSE_18_ET", "SPX", null, "GLOBAL_EQUITY", null,
                CandidateMarketFeature.AVAILABLE, null);
        CandidateMarketFeature wti = new CandidateMarketFeature(
                "WTI_RET5", new BigDecimal("-0.5"), LocalDate.of(2026, 8, 7), decision,
                "SOURCE_AVAILABLE_AT", "WTI", null, "美股_EQUITY", null,
                CandidateMarketFeature.AVAILABLE, null);
        CandidateMarketFeature ixic = new CandidateMarketFeature(
                "IXIC_RET5", BigDecimal.ONE, LocalDate.of(2026, 8, 7), decision,
                "CLOSE_18_ET", "IXIC", null, "美股_EQUITY", "MARKET_REGIME",
                CandidateMarketFeature.DISCLOSURE_ONLY, null);

        var evidence = new TradingRadarMarketFeatureResolver.Evidence(
                "美股", decision, Map.of("SPX_RET5", spx, "WTI_RET5", wti, "IXIC_RET5", ixic));
        var aggregate = evidence.aggregateContribution();

        assertThat(aggregate.value())
                .as("低 coverage 不得把 2/8 資料放大成完整強度；各 raw feature 先轉自身單位")
                .isEqualTo(0.1);
        assertThat(aggregate.availableCount()).isEqualTo(2);
        assertThat(aggregate.eligibleCount()).isEqualTo(8);
        assertThat(aggregate.coverage()).isEqualTo(0.25);
        assertThat(aggregate.unavailableReasons())
                .anyMatch(reason -> reason.contains("MARKET_FEATURE_DISCLOSURE_ONLY[IXIC_RET5]"));
    }

    @Test
    void taiwanAggregateExcludesNasdaqAndSoxAlreadyUsedByMarketRegime() {
        Instant decision = Instant.parse("2026-08-08T08:00:00Z");
        CandidateMarketFeature ixic = new CandidateMarketFeature(
                "IXIC_RET5", BigDecimal.ONE, LocalDate.of(2026, 8, 7), decision,
                "CLOSE_18_ET", "IXIC", null, "GLOBAL_EQUITY", "MARKET_REGIME",
                CandidateMarketFeature.DISCLOSURE_ONLY, null);
        CandidateMarketFeature sox = new CandidateMarketFeature(
                "SOX_RET5", BigDecimal.ONE, LocalDate.of(2026, 8, 7), decision,
                "CLOSE_18_ET", "SOX", null, "GLOBAL_EQUITY", "MARKET_REGIME",
                CandidateMarketFeature.DISCLOSURE_ONLY, null);
        CandidateMarketFeature spx = new CandidateMarketFeature(
                "SPX_RET5", BigDecimal.ONE, LocalDate.of(2026, 8, 7), decision,
                "CLOSE_18_ET", "SPX", null, "GLOBAL_EQUITY", null,
                CandidateMarketFeature.AVAILABLE, null);

        var aggregate = new TradingRadarMarketFeatureResolver.Evidence(
                "台股", decision, Map.of("IXIC_RET5", ixic, "SOX_RET5", sox, "SPX_RET5", spx))
                .aggregateContribution();

        assertThat(aggregate.availableCount()).isEqualTo(1);
        assertThat(aggregate.eligibleCount()).isEqualTo(7);
        assertThat(aggregate.value()).isCloseTo(0.4 / 7.0,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(aggregate.unavailableReasons()).anyMatch(reason ->
                reason.contains("MARKET_FEATURE_DISCLOSURE_ONLY[IXIC_RET5]"));
        assertThat(aggregate.unavailableReasons()).anyMatch(reason ->
                reason.contains("MARKET_FEATURE_DISCLOSURE_ONLY[SOX_RET5]"));
    }

    @Test
    void unavailableFeaturesRetainApplicabilityAndExplicitNaIsOutOfDenominator() {
        Instant decision = Instant.parse("2026-08-08T08:00:00Z");
        var missing = TradingRadarMarketFeatureResolver.Evidence.empty("美股", decision, "source missing");
        assertThat(missing.feature("WTI_RET5").profileApplicability())
                .isEqualTo("GLOBAL_ASSET_AGNOSTIC");
        assertThat(missing.feature("INDEX_VOLUME_RATIO20").profileApplicability())
                .isEqualTo("美股_EQUITY");

        CandidateMarketFeature na = CandidateMarketFeature.notApplicable(
                "WTI_RET5", "instrument scope", "NOT_APPLICABLE");
        CandidateMarketFeature spx = new CandidateMarketFeature(
                "SPX_RET5", BigDecimal.ONE, LocalDate.of(2026, 8, 7), decision,
                "CLOSE_18_ET", "SPX", null, "GLOBAL_EQUITY", null,
                CandidateMarketFeature.AVAILABLE, null);
        var aggregate = new TradingRadarMarketFeatureResolver.Evidence(
                "美股", decision, Map.of("WTI_RET5", na, "SPX_RET5", spx))
                .aggregateContribution();
        assertThat(aggregate.eligibleCount())
                .as("explicit N/A 不得進 eligible denominator")
                .isEqualTo(8);
    }

    @Test
    void aggregateContributionMissingAndDisclosureOnlyNeverBecomeNeutralScore() {
        Instant decision = Instant.parse("2026-08-08T08:00:00Z");
        var aggregate = TradingRadarMarketFeatureResolver.Evidence.empty(
                "美股", decision, "source missing").aggregateContribution();

        assertThat(aggregate.value()).isNull();
        assertThat(aggregate.availableCount()).isZero();
        assertThat(aggregate.coverage()).isZero();
        assertThat(aggregate.unavailableReasons())
                .anyMatch(reason -> reason.contains("MARKET_FEATURE_NO_AVAILABLE"));
    }

    @Test
    void featureContributionUsesFeatureSpecificDimensionlessUnits() {
        Instant decision = Instant.parse("2026-08-08T08:00:00Z");
        CandidateMarketFeature returnUp = new CandidateMarketFeature(
                "SPX_RET5", new BigDecimal("2.5"), LocalDate.of(2026, 8, 7), decision,
                "CLOSE_18_ET", "SPX", null, "GLOBAL_EQUITY", null,
                CandidateMarketFeature.AVAILABLE, null);
        CandidateMarketFeature volumeNeutral = new CandidateMarketFeature(
                "INDEX_VOLUME_RATIO20", BigDecimal.ONE, LocalDate.of(2026, 8, 7), decision,
                "CLOSE_18_ET", "IXIC", null, "美股_EQUITY", null,
                CandidateMarketFeature.AVAILABLE, null);
        CandidateMarketFeature institutionalTiny = new CandidateMarketFeature(
                "TW_INSTITUTIONAL_NET_TURNOVER", new BigDecimal("0.003"),
                LocalDate.of(2026, 8, 7), decision, "CLOSE_16_TW", "TWSE", null,
                "台股_EQUITY", null, CandidateMarketFeature.AVAILABLE, null);

        var evidence = new TradingRadarMarketFeatureResolver.Evidence(
                "台股", decision, Map.of(
                        "SPX_RET5", returnUp,
                        "INDEX_VOLUME_RATIO20", volumeNeutral,
                        "TW_INSTITUTIONAL_NET_TURNOVER", institutionalTiny));
        var profile = TradingRadarAssetProfileResolver.resolve(
                "0050", "台股", "ETF", null, "EQUITY_ETF", null, null, null, null);
        var aggregate = evidence.aggregateContribution(profile);

        // 2.5% return saturates +1; ratio 1.0 is neutral (0); institutional 0.3% is
        // not a full-strength +1 signal.  Missing candidates remain in the denominator;
        // with this sparse map the aggregate is (1 + 0 + 0.003/0.05) / 8.
        assertThat(aggregate.value()).isCloseTo(1.06 / 8.0,
                org.assertj.core.data.Offset.offset(1e-12));
    }

    private static UsIndexDailyHistory us(
            String code, LocalDate date, long close, long volume) {
        return new UsIndexDailyHistory(code, date, null, null, null,
                BigDecimal.valueOf(close), volume);
    }

    private static CommodityPriceHistory commodity(
            String code, LocalDate date, long close) {
        return CommodityPriceHistory.builder()
                .commodityCode(code).priceDate(date).closePrice(BigDecimal.valueOf(close)).build();
    }
}
