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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
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
    void listTenorBatch只選一次完整curve並保留各tenor值() {
        TreasuryYieldBatchRepository repository = mock(TreasuryYieldBatchRepository.class);
        MarketDataService marketDataService = knownUsCalendar();
        Instant decision = Instant.parse("2026-08-10T14:00:00Z");
        when(repository.findSelected(decision)).thenReturn(Optional.of(storedBatch(LocalDate.of(2026, 8, 7))));
        TreasuryYieldService service = serviceFor(repository, decision, marketDataService);

        Map<String, TreasuryYieldDto.RateContext> contexts = service.resolveRateContexts(
                decision, Set.of("Y5", "Y10", "Y30"));

        assertThat(contexts).containsOnlyKeys("Y5", "Y10", "Y30");
        assertThat(contexts.get("Y5").value()).isEqualByComparingTo("4.2000");
        assertThat(contexts.get("Y10").value()).isEqualByComparingTo("4.3000");
        verify(repository, times(1)).findSelected(decision);
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
     * Task 382 再升為 {@code TW_RULES_V18}（gate 診斷改列各自持有期的風險，與本檔的 J 值極性修正無關）。
     *
     * <p>{@code v12Default()} 仍是 {@code TW_RULES_V12}——那是回測 baseline 參數集，
     * 本次未改動任何個股參數值，故不得跟著改（改了會擴散到十餘個呼叫點且零語意收益）。
     * 兩者都不得等於 {@code V13_VERSION}：那是 {@code evaluateCandidate()} 的 guard 標籤，
     * production 竊用會讓「這是不是 candidate」的判別式失效。</p>
     */
    @Test
    void productionRuleVersion升V18而v12Default仍為回測baseline標籤() {
        assertThat(TradingRadarRuleEngine.RULE_VERSION).isEqualTo("TW_RULES_V20");
        assertThat(RuleParameters.v12Default().ruleVersion()).isEqualTo("TW_RULES_V12");
        assertThat(TradingRadarRuleEngine.RULE_VERSION).isNotEqualTo(RuleParameters.V13_VERSION);
        assertThat(RuleParameters.v12Default().ruleVersion()).isNotEqualTo(RuleParameters.V13_VERSION);
    }

    /**
     * Task 447.3：{@code resolveRateContextFromSeries}（消費 {@code findAllRevisionsThrough}
     * 風格、未去重複的 revision 列表）必須與既有「逐日呼叫 {@code resolveRateContext}」在同一組
     * decisionInstant 序列上逐位元相同——這是本任務排除
     * {@code JdbcTreasuryYieldBatchRepository.findCompleteSeriesThrough} 的原因：那支方法會先用
     * {@code ROW_NUMBER() PARTITION BY curve_date} 把同一 curve_date 的多筆 revision collapse
     * 成一筆，較早的 decisionInstant 因此會錯過它原本該看到的舊 revision。
     *
     * <p><b>決定性測項</b>：同一 curve_date 存在兩筆不同 available_at 的 revision——一筆
     * YAHOO_PROXY 於 T1 建立，之後同一 curve_date 被 US_TREASURY 於 T2&gt;T1 補件。
     * decisionInstant∈[T1,T2) 必須選中 T1 那筆；decisionInstant&gt;=T2 必須選中 T2 那筆
     * （curve_date 相同時 provider 只是 tie-break，US_TREASURY 優先於 YAHOO_PROXY）。
     * 若實作誤用 {@code findCompleteSeriesThrough} 風格的 collapse，或誤將
     * {@code findAllRevisionsThrough} 寫成有 collapse 效果，這個測項會先紅燈。</p>
     */
    @Test
    void resolveRateContextFromSeries同一curveDate的較新revision補件前後選中不同批次() {
        LocalDate curveDate = LocalDate.of(2026, 8, 7);
        Instant t1 = Instant.parse("2026-08-07T22:00:00Z");
        Instant t2 = Instant.parse("2026-08-10T15:00:00Z");
        Map<String, BigDecimal> oldValues = orderedValues();
        Map<String, BigDecimal> newValues = orderedValues();
        newValues.put("Y10", new BigDecimal("4.3500"));
        TreasuryYieldDto.StoredBatch oldRevision = storedBatch(
                101L, curveDate, "YAHOO_PROXY", t1, t1, true, oldValues);
        TreasuryYieldDto.StoredBatch newRevision = storedBatch(
                102L, curveDate, "US_TREASURY", t2, t2, true, newValues);
        List<TreasuryYieldDto.StoredBatch> series = List.of(oldRevision, newRevision);
        MarketDataService marketDataService = knownUsCalendar();

        Instant justBeforeT2 = t2.minusSeconds(1);
        Instant atT2 = t2;
        TreasuryYieldService serviceAtT1 = serviceFor(mockFindSelected(oldRevision), t1, marketDataService);
        TreasuryYieldService serviceBeforeT2 =
                serviceFor(mockFindSelected(oldRevision), justBeforeT2, marketDataService);
        TreasuryYieldService serviceAtT2 = serviceFor(mockFindSelected(newRevision), atT2, marketDataService);

        var seriesAtT1 = serviceAtT1.resolveRateContextFromSeries(series, t1, "Y10").orElseThrow();
        var mockedAtT1 = serviceAtT1.resolveRateContext(t1, "Y10").orElseThrow();
        var seriesBeforeT2 = serviceBeforeT2.resolveRateContextFromSeries(series, justBeforeT2, "Y10")
                .orElseThrow();
        var mockedBeforeT2 = serviceBeforeT2.resolveRateContext(justBeforeT2, "Y10").orElseThrow();
        var seriesAtT2 = serviceAtT2.resolveRateContextFromSeries(series, atT2, "Y10").orElseThrow();
        var mockedAtT2 = serviceAtT2.resolveRateContext(atT2, "Y10").orElseThrow();

        assertThat(seriesAtT1.batchId()).isEqualTo(101L);
        assertThat(seriesAtT1.value()).isEqualByComparingTo("4.3000");
        assertThat(seriesAtT1).isEqualTo(mockedAtT1);
        assertThat(seriesBeforeT2.batchId()).isEqualTo(101L);
        assertThat(seriesBeforeT2).isEqualTo(mockedBeforeT2);
        assertThat(seriesAtT2.batchId()).isEqualTo(102L);
        assertThat(seriesAtT2.provider()).isEqualTo("US_TREASURY");
        assertThat(seriesAtT2.value()).isEqualByComparingTo("4.3500");
        assertThat(seriesAtT2).isEqualTo(mockedAtT2);
    }

    /**
     * 覆蓋「decisionInstant 恰好落在兩個不同 curve_date 交界前後」與「available_at 晚於部分
     * decisionInstant（該天應選到更舊的 curve_date）」——同一份 fixture 天然涵蓋兩者：
     * 較新 curve_date 的 revision 較晚才 available，其 available 之前的 decisionInstant
     * 必須退回較舊的 curve_date，而非把「curve_date 較新」誤當成無條件優先。
     */
    @Test
    void resolveRateContextFromSeries在新curveDate可得前退回較舊curveDate() {
        LocalDate olderCurveDate = LocalDate.of(2026, 8, 5);
        LocalDate newerCurveDate = LocalDate.of(2026, 8, 6);
        Instant olderAvailableAt = Instant.parse("2026-08-05T22:00:00Z");
        Instant newerAvailableAt = Instant.parse("2026-08-08T22:00:00Z");
        TreasuryYieldDto.StoredBatch older = storedBatch(
                201L, olderCurveDate, "YAHOO_PROXY", olderAvailableAt, olderAvailableAt, true, orderedValues());
        TreasuryYieldDto.StoredBatch newer = storedBatch(
                202L, newerCurveDate, "YAHOO_PROXY", newerAvailableAt, newerAvailableAt, true, orderedValues());
        List<TreasuryYieldDto.StoredBatch> series = List.of(older, newer);
        MarketDataService marketDataService = knownUsCalendar();

        Instant beforeNewerAvailable = newerAvailableAt.minusSeconds(1);
        Instant atNewerAvailable = newerAvailableAt;
        TreasuryYieldService serviceBefore =
                serviceFor(mockFindSelected(older), beforeNewerAvailable, marketDataService);
        TreasuryYieldService serviceAt = serviceFor(mockFindSelected(newer), atNewerAvailable, marketDataService);

        var seriesBefore = serviceBefore
                .resolveRateContextFromSeries(series, beforeNewerAvailable, "Y10").orElseThrow();
        var mockedBefore = serviceBefore.resolveRateContext(beforeNewerAvailable, "Y10").orElseThrow();
        var seriesAt = serviceAt.resolveRateContextFromSeries(series, atNewerAvailable, "Y10").orElseThrow();
        var mockedAt = serviceAt.resolveRateContext(atNewerAvailable, "Y10").orElseThrow();

        assertThat(seriesBefore.batchId()).isEqualTo(201L)
                .as("新 curve_date 尚未 available 前必須退回較舊 curve_date");
        assertThat(seriesBefore.curveDate()).isEqualTo(olderCurveDate);
        assertThat(seriesBefore).isEqualTo(mockedBefore);
        assertThat(seriesAt.batchId()).isEqualTo(202L);
        assertThat(seriesAt.curveDate()).isEqualTo(newerCurveDate);
        assertThat(seriesAt).isEqualTo(mockedAt);
    }

    /**
     * {@code findAllRevisionsThrough} 的 SQL 篩選（complete=TRUE 且四個 tenor 皆合法）應已排除
     * incomplete batch／不合法 tenor 的列；本測試不重新驗證 SQL，只確認就算這種列意外混入
     * 記憶體中的 series（例如未來重構不慎繞過該篩選），選批次邏輯仍不會選中它——即使它的
     * available_at 較新、provider 較被優先（US_TREASURY），curve_date 較舊這件事仍必須讓它
     * 輸給合法的較新 curve_date 批次。
     */
    @Test
    void resolveRateContextFromSeries不會選中incomplete或缺tenor的列() {
        LocalDate validCurveDate = LocalDate.of(2026, 8, 5);
        LocalDate incompleteCurveDate = LocalDate.of(2026, 8, 1);
        Instant validAvailableAt = Instant.parse("2026-08-05T22:00:00Z");
        Instant incompleteAvailableAt = Instant.parse("2026-08-09T22:00:00Z");
        TreasuryYieldDto.StoredBatch valid = storedBatch(
                301L, validCurveDate, "YAHOO_PROXY", validAvailableAt, validAvailableAt, true, orderedValues());
        Map<String, BigDecimal> missingTenor = new LinkedHashMap<>(orderedValues());
        missingTenor.remove("Y30");
        TreasuryYieldDto.StoredBatch incomplete = storedBatch(
                302L, incompleteCurveDate, "US_TREASURY",
                incompleteAvailableAt, incompleteAvailableAt, false, missingTenor);
        List<TreasuryYieldDto.StoredBatch> series = List.of(valid, incomplete);
        MarketDataService marketDataService = knownUsCalendar();
        Instant decision = Instant.parse("2026-08-10T12:00:00Z");
        TreasuryYieldService service = serviceFor(mockFindSelected(valid), decision, marketDataService);

        var result = service.resolveRateContextFromSeries(series, decision, "Y10").orElseThrow();
        var mocked = service.resolveRateContext(decision, "Y10").orElseThrow();

        assertThat(result.batchId()).isEqualTo(301L).as("incomplete 列即使 available_at 較新也不得中選");
        assertThat(result.curveDate()).isEqualTo(validCurveDate);
        assertThat(result).isEqualTo(mocked);
    }

    private static TreasuryYieldBatchRepository mockFindSelected(TreasuryYieldDto.StoredBatch expected) {
        TreasuryYieldBatchRepository repository = mock(TreasuryYieldBatchRepository.class);
        when(repository.findSelected(any())).thenReturn(Optional.of(expected));
        return repository;
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

    /** Task 447.3 fixture：可自訂 batchId／curveDate／provider／available_at／fetchedAt／complete／values。 */
    private static TreasuryYieldDto.StoredBatch storedBatch(
            long batchId, LocalDate curveDate, String provider,
            Instant availableAt, Instant fetchedAt, boolean complete, Map<String, BigDecimal> values) {
        return new TreasuryYieldDto.StoredBatch(batchId, curveDate, provider, "https://source/",
                availableAt, "OFFICIAL_PUBLISHED_AT", fetchedAt, complete,
                "b".repeat(63) + Long.toHexString(batchId % 16), values, orderedManifest(values));
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
