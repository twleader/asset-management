package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.EtfNavHistoryRepository;
import com.steven.assets.repository.ExchangeRateHistoryRepository;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockDividendHistoryRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import com.steven.assets.security.CurrentUserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TradingRadarService.buildMarket() 的大盤 stale／intraday 判斷（Task 228，Requirement 43 修訂 V6）。
 * ruleEngine 用真實 TradingRadarRuleEngine（純函式、無副作用），其餘 12 個建構子依賴皆 mock，
 * 只隔離出大盤區塊：holdings／watchlist 一律回空，get() 因此只組裝 market，stocks 恆為空 list。
 */
@ExtendWith(MockitoExtension.class)
class TradingRadarMarketFreshnessTest {

    private static final LocalDate FRIDAY = LocalDate.of(2026, 9, 18);
    private static final LocalDate THURSDAY = FRIDAY.minusDays(1);

    @Mock private TechnicalIndicatorService indicatorService;
    @Mock private DistributionAdjustedPriceService adjustedPriceService;
    @Mock private AssetClassifier assetClassifier;
    @Mock private TwseIndexDailyHistoryRepository twseRepo;
    @Mock private UsIndexDailyHistoryRepository usIndexDailyHistoryRepo;
    @Mock private StockPriceHistoryRepository priceHistoryRepo;
    @Mock private StockDividendHistoryRepository dividendHistoryRepo;
    @Mock private PriceQueryService priceQueryService;
    @Mock private TaiexDisplayPriceService taiexDisplayPriceService;
    @Mock private AssetSnapshotRepository snapshotRepo;
    @Mock private StockAlertRepository alertRepo;
    @Mock private StockRepository stockRepo;
    @Mock private MarketDataService marketDataService;
    @Mock private ExchangeRateHistoryRepository exchangeRateRepo;
    @Mock private TradingRadarMarketContextService marketContextService;
    @Mock private FundamentalAnalysisService fundamentalAnalysisService;
    @Mock private EtfNavHistoryRepository etfNavHistoryRepo;
    // Task 230：get() 回應前會 fail-soft 寫一筆 per-owner Redis 快照；本測試無 request context，
    // 該寫入路徑不會被觸發（RequestContextHolder 為 null），mock 僅供建構子。
    @Mock private TradingRadarSnapshotStore snapshotStore;
    @Mock private CurrentUserContext currentUserContext;
    @Mock private DividendEventEvidenceRepository dividendEventEvidenceRepository;
    @Mock private TreasuryYieldService treasuryYieldService;

    private TradingRadarService newService() {
        return new TradingRadarService(
                new TradingRadarRuleEngine(),
                indicatorService,
                adjustedPriceService,
                // Task 273：組裝已抽為 RadarInputAssembler。此處刻意用**真的** assembler 包同一組 mock，
                // 使本測試的行為與抽取前完全相同（mock 的 adjust 回 null → 走既有的降級分支）。
                new RadarInputAssembler(indicatorService, adjustedPriceService, new TradingRadarRuleEngine()),
                assetClassifier,
                twseRepo,
                usIndexDailyHistoryRepo,
                priceHistoryRepo,
                dividendHistoryRepo,
                priceQueryService,
                taiexDisplayPriceService,
                snapshotRepo,
                alertRepo,
                stockRepo,
                marketDataService,
                marketContextService,
                fundamentalAnalysisService,
                etfNavHistoryRepo,
                snapshotStore,
                currentUserContext,
                dividendEventEvidenceRepository,
                treasuryYieldService);
    }

    /** 建 241 筆「由新到舊」完成日收盤：closes[i] = base+i，i 越大代表越久以前、收盤越高（近期下跌趨勢）。 */
    private List<TwseIndexDailyHistory> descRows(LocalDate latest, int base) {
        List<TwseIndexDailyHistory> rows = new ArrayList<>();
        for (int i = 0; i < 241; i++) {
            TwseIndexDailyHistory h = new TwseIndexDailyHistory();
            h.setTradingDate(latest.minusDays(i));
            h.setClosePoint(BigDecimal.valueOf(base + i));
            rows.add(h);
        }
        return rows;
    }

    private void stubCommon() {
        lenient().when(taiexDisplayPriceService.resolve()).thenReturn(
                new TaiexDisplayPriceService.DisplayQuote(
                        null, null, null, null, null, null,
                        null, null, true, "CLOSE_PENDING"));
        lenient().when(marketDataService.isTwTradingDayKnown(any(LocalDate.class)))
                .thenAnswer(call -> Optional.of(((LocalDate) call.getArgument(0)).getDayOfWeek().getValue() <= 5));
        lenient().when(marketDataService.isTwTradingDayCachedOnly(any(LocalDate.class)))
                .thenAnswer(call -> Optional.of(((LocalDate) call.getArgument(0)).getDayOfWeek().getValue() <= 5));
        lenient().when(marketContextService.resolve(any())).thenReturn(
                new TradingRadarMarketContextService.Resolved(
                        TradingRadarMarketContextService.MarketContext.EMPTY, List.of()));
        lenient().when(snapshotRepo.findLatestWithStocks()).thenReturn(Optional.empty());
        lenient().when(alertRepo.findDistinctStockCodeMarket()).thenReturn(List.of());
        lenient().when(indicatorService.computeAll(anyString(), anyString())).thenReturn(
                new TechnicalIndicatorService.FullIndicators(
                        BigDecimal.valueOf(20000), BigDecimal.valueOf(20000), BigDecimal.valueOf(20000),
                        BigDecimal.valueOf(60), BigDecimal.valueOf(50),
                        BigDecimal.valueOf(55), BigDecimal.valueOf(52), null,
                        TechnicalIndicatorService.ExtendedIndicators.EMPTY, null));
        // Task 294：buildUsMarket() 每輪都會計算（不論本輪有沒有美股標的），需同步 stub 避免 NPE；
        // 本檔只驗證台股組 stale／intraday，美股組回 EMPTY／空序列即可（走 DATA_INCOMPLETE 分支，不影響斷言）。
        lenient().when(usIndexDailyHistoryRepo.findTopNByIndexCodeOrderByTradingDateDesc(anyString(), anyInt()))
                .thenReturn(List.of());
        lenient().when(indicatorService.computeAllForNasdaq())
                .thenReturn(TechnicalIndicatorService.FullIndicators.EMPTY);
        // Task 323：buildUsMarket() 改由 resolveMarketFromRows 取得 IXIC 量能 context；
        // 本檔只驗證台股組，美股組回 EMPTY 即可（未 stub 的 mock 回 null，靠 production 端的
        // null 防護也不會炸，但顯式 stub 讓「美股量能缺值」是刻意的前提而非碰巧）。
        lenient().when(marketContextService.resolveMarketFromRows(
                        anyString(), any(), anyList(), anyList()))
                .thenReturn(TradingRadarMarketContextService.MarketContext.EMPTY);
    }

    @Test
    void futureSessionsCalendarUnavailableFailsClosedInsteadOfInferringWeekdays() {
        when(marketDataService.isTwTradingDayKnown(any(LocalDate.class))).thenReturn(Optional.empty());

        List<LocalDate> sessions = newService().futureSessions(
                "台股", Instant.parse("2026-08-09T06:00:00Z"));

        assertTrue(sessions.isEmpty());
    }

    @Test
    void listFutureSessionsUsesOnlyCachedCalendarAndNeverCallsLegacyTaiwanCalendar() {
        when(marketDataService.futureTradingSessionsCachedOnly(anyString(), any(LocalDate.class), anyInt(), anyInt()))
                .thenReturn(Optional.empty());

        List<LocalDate> sessions = newService().futureSessionsCachedOnly(
                "台股", Instant.parse("2026-08-09T06:00:00Z"));

        assertTrue(sessions.isEmpty());
        verify(marketDataService).futureTradingSessionsCachedOnly("台股", LocalDate.of(2026, 8, 9), 20, 90);
        verify(marketDataService, never()).isTwTradingDayKnown(any(LocalDate.class));
        verify(marketDataService, never()).isTradingDay("台股", LocalDate.of(2026, 8, 10));
    }

    @Test
    void strictPremiumSessionExcludesIntradayAndUnknownCalendar() {
        when(marketDataService.isTwTradingDayKnown(any(LocalDate.class)))
                .thenReturn(Optional.of(true));
        TradingRadarService service = newService();

        assertEquals(LocalDate.of(2026, 8, 6), service.strictCompletedTaiwanSession(
                Instant.parse("2026-08-07T04:00:00Z")),
                "台北中午仍未收盤，不得把當日 NAV 當 completed session");
        assertEquals(LocalDate.of(2026, 8, 7), service.strictCompletedTaiwanSession(
                Instant.parse("2026-08-07T06:00:00Z")),
                "收盤後才可使用當日 completed session");

        when(marketDataService.isTwTradingDayKnown(any(LocalDate.class))).thenReturn(Optional.empty());
        assertNull(service.strictCompletedTaiwanSession(Instant.parse("2026-08-07T06:00:00Z")));
    }

    private PriceQueryService.LivePrice liveOn(LocalDate tradingDate, BigDecimal price) {
        return new PriceQueryService.LivePrice(
                "0000", "台股大盤", "台股", price, null, null, null,
                null, null, null, null, null, null,
                tradingDate.toString(), "2026-07-20T10:30:00", false, "TWSE指數(5m)", "LIVE");
    }

    /** Exercise the shared production builder with a fixed decision instant, without a wall clock. */
    private TradingRadarDto.MarketSummary market(String instant, boolean cacheOnly) throws Exception {
        return market(instant, cacheOnly, TradingRadarMarketContextService.MarketContext.EMPTY);
    }

    private TradingRadarDto.MarketSummary market(String instant, boolean cacheOnly,
            TradingRadarMarketContextService.MarketContext context) throws Exception {
        var method = TradingRadarService.class.getDeclaredMethod("buildMarket",
                TradingRadarMarketContextService.MarketContext.class, Instant.class, boolean.class);
        method.setAccessible(true);
        Object state = method.invoke(newService(), context, Instant.parse(instant), cacheOnly);
        var summary = state.getClass().getDeclaredMethod("summary");
        summary.setAccessible(true);
        return (TradingRadarDto.MarketSummary) summary.invoke(state);
    }

    private void history(LocalDate latest) {
        when(twseRepo.findTopNByOrderByTradingDateDesc(500)).thenReturn(descRows(latest, 20000));
    }

    private PriceQueryService.LivePrice live(LocalDate date, BigDecimal price, Boolean closed, String status) {
        var quote = liveOn(date, price);
        return new PriceQueryService.LivePrice(quote.stockCode(), quote.stockName(), quote.market(),
                price, null, null, null, null, null, null, null, null, null,
                date.toString(), quote.updatedAt(), closed, quote.source(), status);
    }

    @Test
    void midnightAndPreopenAcceptPreviousCompletedCloseWithoutLive() throws Exception {
        stubCommon(); history(THURSDAY);
        for (String instant : List.of("2026-09-17T16:01:00Z", "2026-09-18T00:59:59Z")) {
            var summary = market(instant, false);
            assertFalse(summary.stale());
            assertFalse(summary.intraday());
            assertNull(summary.liveUpdatedAt());
        }
    }

    @Test
    void intradayUsesTodayLiveAndKeepsCompletedConfirmationAndTimestamp() throws Exception {
        stubCommon(); history(THURSDAY);
        when(priceQueryService.getLive("0000", "台股"))
                .thenReturn(Optional.of(liveOn(FRIDAY, BigDecimal.valueOf(99999))));
        var summary = market("2026-09-18T02:30:00Z", false);
        assertFalse(summary.stale());
        assertTrue(summary.intraday());
        assertEquals("2026-07-20T10:30:00", summary.liveUpdatedAt(),
                "保留來源時間，不新增秒級新鮮度主張");
        assertEquals("BELOW", summary.quarterlyConfirmation());
        assertEquals("BELOW", summary.annualConfirmation());
    }

    @Test
    void intradayRejectsMissingPreviousDayClosedFallbackAndNonpositiveLive() throws Exception {
        stubCommon(); history(THURSDAY);
        List<Optional<PriceQueryService.LivePrice>> rejected = List.of(Optional.empty(),
                Optional.of(liveOn(THURSDAY, BigDecimal.valueOf(20500))),
                Optional.of(live(FRIDAY, BigDecimal.valueOf(20500), true, "LIVE")),
                Optional.of(live(FRIDAY, BigDecimal.valueOf(20500), false, "PREVIOUS_CLOSE")),
                Optional.of(live(FRIDAY, BigDecimal.valueOf(20500), false, "VERIFIED_CLOSE")),
                Optional.of(live(FRIDAY, BigDecimal.ZERO, false, "LIVE")),
                Optional.of(live(FRIDAY, null, false, "LIVE")),
                Optional.of(liveOn(FRIDAY.plusDays(1), BigDecimal.valueOf(20500))));
        for (var quote : rejected) {
            when(priceQueryService.getLive("0000", "台股")).thenReturn(quote);
            var summary = market("2026-09-18T02:30:00Z", false);
            assertTrue(summary.stale(), "rejected live: " + quote);
            assertFalse(summary.intraday());
            assertNull(summary.liveUpdatedAt());
        }
    }

    @Test
    void atOpenRequiresLiveAndAtCloseRequiresCurrentCompletedClose() throws Exception {
        stubCommon(); history(THURSDAY);
        assertTrue(market("2026-09-18T01:00:00Z", false).stale());
        assertTrue(market("2026-09-18T05:30:00Z", false).stale());
        history(FRIDAY);
        when(priceQueryService.getLive("0000", "台股"))
                .thenReturn(Optional.of(liveOn(FRIDAY, BigDecimal.valueOf(20500))));
        var summary = market("2026-09-18T05:30:00Z", false);
        assertFalse(summary.stale());
        assertFalse(summary.intraday());
        assertNull(summary.liveUpdatedAt());
    }

    @Test
    void weekendAndConsecutiveHolidaysUseLastCompletedTradingSession() throws Exception {
        stubCommon(); history(FRIDAY);
        assertFalse(market("2026-09-19T02:30:00Z", false).stale());
        when(marketDataService.isTwTradingDayKnown(any(LocalDate.class))).thenAnswer(call -> {
            LocalDate date = call.getArgument(0);
            return Optional.of(date.getDayOfWeek().getValue() <= 5
                    && !date.equals(LocalDate.of(2026, 9, 21)) && !date.equals(LocalDate.of(2026, 9, 22)));
        });
        var holiday = market("2026-09-22T02:30:00Z", false);
        assertFalse(holiday.stale()); assertFalse(holiday.intraday());
        assertFalse(market("2026-09-23T00:30:00Z", false).stale());
    }

    @Test
    void liveCannotExemptMissingOrInvalidCompletedBaseline() throws Exception {
        stubCommon(); history(THURSDAY.minusDays(1));
        when(priceQueryService.getLive("0000", "台股"))
                .thenReturn(Optional.of(liveOn(FRIDAY, BigDecimal.valueOf(20500))));
        assertTrue(market("2026-09-18T02:30:00Z", false).stale());
        history(THURSDAY);
        var rows = descRows(THURSDAY, 20000);
        rows.get(0).setClosePoint(BigDecimal.ZERO);
        when(twseRepo.findTopNByOrderByTradingDateDesc(500)).thenReturn(rows);
        assertTrue(market("2026-09-18T02:30:00Z", false).stale());
    }

    @Test
    void futureAndUncompletedTodayRowsNeverEnterCompletedConfirmation() throws Exception {
        stubCommon();
        var rows = descRows(THURSDAY, 20000);
        var future = new TwseIndexDailyHistory();
        future.setTradingDate(FRIDAY.plusDays(1)); future.setClosePoint(BigDecimal.valueOf(99999));
        var today = new TwseIndexDailyHistory();
        today.setTradingDate(FRIDAY); today.setClosePoint(BigDecimal.valueOf(99999));
        rows.add(0, today); rows.add(0, future);
        when(twseRepo.findTopNByOrderByTradingDateDesc(500)).thenReturn(rows);
        var summary = market("2026-09-18T00:30:00Z", false);
        assertFalse(summary.stale());
        assertEquals("BELOW", summary.quarterlyConfirmation());
        assertEquals("BELOW", summary.annualConfirmation());
        // A future row alone cannot stand in for the exact completed baseline.
        when(twseRepo.findTopNByOrderByTradingDateDesc(500)).thenReturn(List.of(future));
        assertTrue(market("2026-09-18T00:30:00Z", false).stale());
    }

    @Test
    void existingMarketAsOfCutoffCannotBeBypassedByBaselineOrLive() throws Exception {
        stubCommon(); history(THURSDAY);
        when(priceQueryService.getLive("0000", "台股"))
                .thenReturn(Optional.of(liveOn(FRIDAY, BigDecimal.valueOf(20500))));
        var context = new TradingRadarMarketContextService.MarketContext(
                THURSDAY.minusDays(1), null, null, null, null, null, null, null, false);
        assertTrue(market("2026-09-18T02:30:00Z", false, context).stale());
    }

    @Test
    void unknownCurrentOrPreviousCalendarFailsClosedDespiteLive() throws Exception {
        stubCommon();
        when(marketDataService.isTwTradingDayKnown(any(LocalDate.class))).thenReturn(Optional.empty());
        assertTrue(market("2026-09-18T02:30:00Z", false).stale());
        when(marketDataService.isTwTradingDayKnown(FRIDAY)).thenReturn(Optional.of(true));
        assertTrue(market("2026-09-18T02:30:00Z", false).stale());
        verify(twseRepo, never()).findTopNByOrderByTradingDateDesc(anyInt());
    }

    @Test
    void listBuilderUsesOnlyCachedCalendarAndFailsClosedOnCacheMiss() throws Exception {
        stubCommon(); history(THURSDAY);
        assertFalse(market("2026-09-18T00:30:00Z", true).stale());
        verify(marketDataService, never()).isTwTradingDayKnown(any(LocalDate.class));
        verify(marketDataService, never()).isTradingDay(anyString(), any(LocalDate.class));
        when(marketDataService.isTwTradingDayCachedOnly(any(LocalDate.class))).thenReturn(Optional.empty());
        assertTrue(market("2026-09-18T00:30:00Z", true).stale());
    }
}
