package com.steven.assets.service;

import com.steven.assets.dto.TreasuryYieldDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TreasuryYieldServiceTest {

    @Test
    void rateContext先選完整batch且即使單tenor仍帶四筆sourceManifest() {
        TreasuryYieldBatchRepository repository = mock(TreasuryYieldBatchRepository.class);
        MarketDataService marketDataService = knownUsCalendar();
        Instant decision = Instant.parse("2026-08-10T14:00:00Z");
        var selected = storedBatch(LocalDate.of(2026, 8, 7));
        when(repository.findSelected(decision)).thenReturn(Optional.of(selected));
        TreasuryYieldService service = serviceFor(repository, decision, marketDataService);

        var context = service.resolveRateContext(decision, "Y10").orElseThrow();

        assertThat(context.batchId()).isEqualTo(9L);
        assertThat(context.provider()).isEqualTo("YAHOO_PROXY");
        assertThat(context.tenor()).isEqualTo("Y10");
        assertThat(context.value()).isEqualByComparingTo("4.3000");
        assertThat(context.sourceManifest()).containsOnlyKeys("M3", "Y5", "Y10", "Y30");
        assertThat(context.sourceManifest().values()).doesNotHaveDuplicates();
        assertThat(context.complete()).isTrue();
        assertThat(context.staleReason()).isNull();
    }

    @Test
    void 美東收盤前排除當日而16時起納入當日completedSession() {
        TreasuryYieldBatchRepository repository = mock(TreasuryYieldBatchRepository.class);
        MarketDataService marketDataService = knownUsCalendar();
        Instant beforeClose = Instant.parse("2026-08-10T19:59:59Z");
        Instant atClose = Instant.parse("2026-08-10T20:00:00Z");
        var sameDayCurve = storedBatch(LocalDate.of(2026, 8, 10));
        when(repository.findSelected(beforeClose)).thenReturn(Optional.of(sameDayCurve));
        when(repository.findSelected(atClose)).thenReturn(Optional.of(sameDayCurve));
        TreasuryYieldService service = serviceFor(repository, atClose, marketDataService);

        var beforeContext = service.resolveRateContext(beforeClose, "Y10").orElseThrow();
        var atCloseContext = service.resolveRateContext(atClose, "Y10").orElseThrow();

        assertThat(beforeContext.staleReason()).isNotBlank().contains("FUTURE_CURVE_DATE");
        assertThat(atCloseContext.staleReason()).isNull();
        verify(marketDataService).isTradingDayKnown("美股", LocalDate.of(2026, 8, 7));
        verify(marketDataService).isTradingDayKnown("美股", LocalDate.of(2026, 8, 10));
    }

    @Test
    void 連續已知休市與週末會回溯且長假前最新曲線仍fresh() {
        TreasuryYieldBatchRepository repository = mock(TreasuryYieldBatchRepository.class);
        MarketDataService marketDataService = knownUsCalendar(LocalDate.of(2026, 12, 25));
        Instant decision = Instant.parse("2026-12-28T20:59:00Z");
        when(repository.findSelected(decision))
                .thenReturn(Optional.of(storedBatch(LocalDate.of(2026, 12, 24))));
        TreasuryYieldService service = serviceFor(repository, decision, marketDataService);

        var context = service.resolveRateContext(decision, "Y10").orElseThrow();

        assertThat(context.staleReason()).isNull();
        verify(marketDataService).isTradingDayKnown("美股", LocalDate.of(2026, 12, 27));
        verify(marketDataService).isTradingDayKnown("美股", LocalDate.of(2026, 12, 26));
        verify(marketDataService).isTradingDayKnown("美股", LocalDate.of(2026, 12, 25));
        verify(marketDataService).isTradingDayKnown("美股", LocalDate.of(2026, 12, 24));
    }

    @Test
    void 落後恰三個completedSessions仍fresh而第四個才stale() {
        TreasuryYieldBatchRepository repository = mock(TreasuryYieldBatchRepository.class);
        MarketDataService marketDataService = knownUsCalendar();
        Instant decision = Instant.parse("2026-08-10T20:00:00Z");
        when(repository.findSelected(decision))
                .thenReturn(Optional.of(storedBatch(LocalDate.of(2026, 8, 5))))
                .thenReturn(Optional.of(storedBatch(LocalDate.of(2026, 8, 4))));
        TreasuryYieldService service = serviceFor(repository, decision, marketDataService);

        var threeSessionContext = service.resolveRateContext(decision, "Y10").orElseThrow();
        var fourSessionContext = service.resolveRateContext(decision, "Y10").orElseThrow();

        assertThat(threeSessionContext.staleReason()).isNull();
        assertThat(fourSessionContext.staleReason()).contains("4 sessions");
    }

    @Test
    void futureCurveFailClosed且完整保留batch與tenorProvenance() {
        TreasuryYieldBatchRepository repository = mock(TreasuryYieldBatchRepository.class);
        MarketDataService marketDataService = knownUsCalendar();
        Instant decision = Instant.parse("2026-08-10T20:00:00Z");
        Instant availableAt = Instant.parse("2026-08-10T19:30:00Z");
        Instant fetchedAt = Instant.parse("2026-08-10T19:31:00Z");
        LocalDate curveDate = LocalDate.of(2026, 8, 11);
        Map<String, BigDecimal> values = orderedValues();
        Map<String, String> manifest = orderedManifest(values);
        var futureBatch = new TreasuryYieldDto.StoredBatch(318L, curveDate,
                "US_TREASURY", "https://source/", availableAt,
                "OFFICIAL_PUBLISHED_AT", fetchedAt, true,
                "b".repeat(64), values, manifest);
        when(repository.findSelected(decision)).thenReturn(Optional.of(futureBatch));
        TreasuryYieldService service = serviceFor(repository, decision, marketDataService);

        var context = service.resolveRateContext(decision, "Y30").orElseThrow();

        assertThat(context.staleReason()).isNotBlank().contains("FUTURE_CURVE_DATE");
        assertThat(context.batchId()).isEqualTo(318L);
        assertThat(context.provider()).isEqualTo("US_TREASURY");
        assertThat(context.curveDate()).isEqualTo(curveDate);
        assertThat(context.sourceManifest()).isSameAs(manifest)
                .containsOnlyKeys("M3", "Y5", "Y10", "Y30");
        assertThat(context.availableAt()).isEqualTo(availableAt);
        assertThat(context.availabilityBasis()).isEqualTo("OFFICIAL_PUBLISHED_AT");
        assertThat(context.fetchedAt()).isEqualTo(fetchedAt);
        assertThat(context.tenor()).isEqualTo("Y30");
        assertThat(context.value()).isEqualByComparingTo("4.4000");
        verify(marketDataService).isTradingDayKnown("美股", LocalDate.of(2026, 8, 10));
    }

    @Test
    void expectedSession搜尋遇日曆未知立即failClosed且保留provenance() {
        TreasuryYieldBatchRepository repository = mock(TreasuryYieldBatchRepository.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        Instant decision = Instant.parse("2026-08-10T20:00:00Z");
        when(repository.findSelected(decision))
                .thenReturn(Optional.of(storedBatch(LocalDate.of(2026, 8, 7))));
        when(marketDataService.isTradingDayKnown("美股", LocalDate.of(2026, 8, 10)))
                .thenReturn(Optional.empty());

        var context = serviceFor(repository, decision, marketDataService)
                .resolveRateContext(decision, "Y10").orElseThrow();

        assertThat(context.staleReason()).contains("UNKNOWN_CALENDAR")
                .contains("MARKET_CALENDAR_UNAVAILABLE")
                .contains("MARKET_DATA_SERVICE");
        assertThat(context.batchId()).isEqualTo(9L);
        assertThat(context.provider()).isEqualTo("YAHOO_PROXY");
        assertThat(context.sourceManifest()).containsOnlyKeys("M3", "Y5", "Y10", "Y30");
    }

    @Test
    void lag計數中途遇日曆未知立即failClosed而非偽裝超過三日() {
        TreasuryYieldBatchRepository repository = mock(TreasuryYieldBatchRepository.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        Instant decision = Instant.parse("2026-08-10T20:00:00Z");
        when(repository.findSelected(decision))
                .thenReturn(Optional.of(storedBatch(LocalDate.of(2026, 8, 7))));
        when(marketDataService.isTradingDayKnown("美股", LocalDate.of(2026, 8, 10)))
                .thenReturn(Optional.of(true));
        when(marketDataService.isTradingDayKnown("美股", LocalDate.of(2026, 8, 8)))
                .thenReturn(Optional.empty());

        var context = serviceFor(repository, decision, marketDataService)
                .resolveRateContext(decision, "Y10").orElseThrow();

        assertThat(context.staleReason()).contains("UNKNOWN_CALENDAR")
                .contains("date=2026-08-08")
                .doesNotContain("sessions（上限");
        assertThat(context.curveDate()).isEqualTo(LocalDate.of(2026, 8, 7));
    }

    /**
     * Task 360：production 版號升為 {@code TW_RULES_V16}（J 值因子極性修正——KD／J 與
     * 週線動能的 J 位置分量改由標準 J {@code 3K − 2D} 現算，不再餵入顯示慣例 {@code j9 = 3D − 2K}），
     * 與 {@link RuleParameters} 的 calibration／candidate 命名空間維持分家（Task 342 起）。
     * Task 365 再升為 {@code TW_RULES_V17}（估值因子權重表標籤與風險文案修正：「PE 自身分位」實為
     * PE／PB／殖利率三者算術平均，與本檔的 J 值極性修正無關）。
     *
     * <p>{@code v12Default()} 仍是 {@code TW_RULES_V12}——那是回測 baseline 參數集，
     * 本次未改動任何個股參數值，故不得跟著改（改了會擴散到十餘個呼叫點且零語意收益）。
     * 兩者都不得等於 {@code V13_VERSION}：那是 {@code evaluateCandidate()} 的 guard 標籤，
     * production 竊用會讓「這是不是 candidate」的判別式失效。</p>
     */
    @Test
    void productionRuleVersion升V17而v12Default仍為回測baseline標籤() {
        assertThat(TradingRadarRuleEngine.RULE_VERSION).isEqualTo("TW_RULES_V17");
        assertThat(RuleParameters.v12Default().ruleVersion()).isEqualTo("TW_RULES_V12");
        assertThat(TradingRadarRuleEngine.RULE_VERSION).isNotEqualTo(RuleParameters.V13_VERSION);
        assertThat(RuleParameters.v12Default().ruleVersion()).isNotEqualTo(RuleParameters.V13_VERSION);
    }

    private static TreasuryYieldService serviceFor(
            TreasuryYieldBatchRepository repository,
            Instant decision,
            MarketDataService marketDataService) {
        return new TreasuryYieldService(repository, mock(TreasuryYieldClient.class),
                Clock.fixed(decision, ZoneOffset.UTC),
                new MarketDataTradingCalendarAdapter(marketDataService));
    }

    private static MarketDataService knownUsCalendar(LocalDate... closedDates) {
        MarketDataService marketDataService = mock(MarketDataService.class);
        Set<LocalDate> knownClosed = Set.of(closedDates);
        when(marketDataService.isTradingDayKnown(eq("美股"), any(LocalDate.class)))
                .thenAnswer(invocation -> {
                    LocalDate date = invocation.getArgument(1);
                    boolean weekday = date.getDayOfWeek() != DayOfWeek.SATURDAY
                            && date.getDayOfWeek() != DayOfWeek.SUNDAY;
                    return Optional.of(weekday && !knownClosed.contains(date));
                });
        return marketDataService;
    }

    private static TreasuryYieldDto.StoredBatch storedBatch(LocalDate curveDate) {
        Map<String, BigDecimal> values = orderedValues();
        return new TreasuryYieldDto.StoredBatch(9L, curveDate,
                "YAHOO_PROXY", "https://source/", Instant.parse("2026-08-08T22:00:00Z"),
                "PROXY_CLOSE_CONSERVATIVE", Instant.parse("2026-08-08T23:00:00Z"), true,
                "a".repeat(64), values, orderedManifest(values));
    }

    private static Map<String, String> orderedManifest(Map<String, BigDecimal> values) {
        Map<String, String> manifest = new LinkedHashMap<>();
        values.keySet().forEach(tenor -> manifest.put(tenor, "https://source/" + tenor));
        return manifest;
    }

    private static Map<String, BigDecimal> orderedValues() {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        values.put("M3", new BigDecimal("4.1000"));
        values.put("Y5", new BigDecimal("4.2000"));
        values.put("Y10", new BigDecimal("4.3000"));
        values.put("Y30", new BigDecimal("4.4000"));
        return values;
    }
}
