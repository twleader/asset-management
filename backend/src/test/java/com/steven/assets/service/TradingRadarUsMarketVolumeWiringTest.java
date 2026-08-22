package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.UsIndexDailyHistory;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.EtfNavHistoryRepository;
import com.steven.assets.repository.ExchangeRateHistoryRepository;
import com.steven.assets.repository.NewsHeadlineRepository;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockDividendHistoryRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import com.steven.assets.security.CurrentUserContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 323：{@code buildUsMarket()} 把既有 IXIC 量能接進美股列的 {@code MarketSummary}，
 * 讓 {@code market_volume_turnover} 不再恆為 {@code MISSING}。
 *
 * <p><b>{@link TradingRadarMarketContextService} 刻意用真實物件（只 mock 底層 repository），
 * 不是 {@code @Mock}</b>——本檔的核心守門是「線上雷達與回測不分岔」：兩邊必須是同一支
 * {@code resolveMarketFromRows} 算出的同一個值。若把 context service 整個 mock 掉，
 * 期望值與實際值都出自同一顆 stub，斷言恆真、證明不了任何事。作法比照同目錄
 * {@link TradingRadarNotificationMarketBatchTest}（真實 service ＋ mock repo）。
 * 外面再包一層 {@link Mockito#spy} 只為了用 {@link ArgumentCaptor} 取得
 * {@code buildMarketSnapshot()} 內部那個 {@code Instant.now()} 與實際傳入的 rows；
 * 未 stub 任何方法，每次呼叫都真的落到 production 實作。</p>
 *
 * <p>⚠ 本檔的觀測入口是 {@code buildMarketSnapshot(US_MARKET).summary()}：{@code buildUsMarket}
 * 本身是 private。（Task 335 起 {@code assembleAt(Instant).usMarket()} 是第二個等價觀測入口——
 * {@code Response.usMarket} 帶的就是同一份 summary；本檔沿用 {@code buildMarketSnapshot}，
 * 因為它不需要組裝整份個股清單。）{@code buildMarketSnapshot(String)} 第一行即 {@code Instant.now()}、
 * 沒有吃 instant 的 overload，故所有 fixture 日期一律以「相對於現在」表達
 * （過去日＝已完成、未來日＝晚於完成邊界）。</p>
 */
class TradingRadarUsMarketVolumeWiringTest {

    private static final String US_MARKET = "美股";
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final TwseIndexDailyHistoryRepository twseRepo = mock(TwseIndexDailyHistoryRepository.class);
    private final UsIndexDailyHistoryRepository usIndexRepo = mock(UsIndexDailyHistoryRepository.class);
    private final MarketDataService marketDataService = mock(MarketDataService.class);
    private final TechnicalIndicatorService indicatorService = mock(TechnicalIndicatorService.class);

    /** 真實的 context service（底層 repo 才是 mock）——期望值就從這支算出來。 */
    private final TradingRadarMarketContextService realContextService = new TradingRadarMarketContextService(
            twseRepo, usIndexRepo, mock(ExchangeRateHistoryRepository.class),
            mock(NewsHeadlineRepository.class), marketDataService);

    /** 同一支實作外包一層 spy，只為了捕捉 decisionInstant／rows；不 stub 任何方法。 */
    private final TradingRadarMarketContextService contextService = Mockito.spy(realContextService);

    private TradingRadarRuleEngine ruleEngine;

    private TradingRadarService newService() {
        ruleEngine = Mockito.spy(new TradingRadarRuleEngine());
        return new TradingRadarService(
                ruleEngine,
                indicatorService,
                mock(DistributionAdjustedPriceService.class),
                mock(RadarInputAssembler.class),
                mock(AssetClassifier.class),
                twseRepo,
                usIndexRepo,
                mock(StockPriceHistoryRepository.class),
                mock(StockDividendHistoryRepository.class),
                mock(PriceQueryService.class),
                mock(TaiexDisplayPriceService.class),
                mock(AssetSnapshotRepository.class),
                mock(StockAlertRepository.class),
                mock(StockRepository.class),
                marketDataService,
                contextService,
                mock(FundamentalAnalysisService.class),
                mock(EtfNavHistoryRepository.class),
                mock(TradingRadarSnapshotStore.class),
                mock(CurrentUserContext.class),
                mock(DividendEventEvidenceRepository.class),
                mock(TreasuryYieldService.class));
    }

    private void stubBaseline() {
        when(indicatorService.computeAllForNasdaq())
                .thenReturn(TechnicalIndicatorService.FullIndicators.EMPTY);
        when(marketDataService.isTradingDay(anyString(), any(LocalDate.class))).thenReturn(true);
        // Task 332：mostRecentCompletedUsTradingDay 由 TradingRadarService 的 private 方法提升為
        // MarketDataService 的共用方法（與 IndexDailyRefreshScheduler 的回補判準同源）。marketDataService
        // 是 mock，未 stub 會回 null，buildUsMarket 的 stale 判斷即 NPE、被 catch 吞成 incompleteMarket()
        // （整組欄位變 null）。此處回填的是上一行「每日皆為交易日」stub 的等價語意：往回找永遠第一輪就命中，
        // 結果即美東當日（收盤前退回前一日）——搬移前後的期望值因此完全一致。
        when(marketDataService.mostRecentCompletedUsTradingDay(any(Instant.class)))
                .thenAnswer(inv -> everyDayTradingUsCompletedDay(inv.getArgument(0)));
    }

    /** 「每日皆為交易日」前提下的最近一個已完成美股交易日：美東當日，收盤（16:00 ET）前退回前一日。 */
    private static LocalDate everyDayTradingUsCompletedDay(Instant instant) {
        java.time.ZonedDateTime nowNy = instant.atZone(NEW_YORK);
        return nowNy.toLocalTime().isBefore(java.time.LocalTime.of(16, 0))
                ? nowNy.toLocalDate().minusDays(1)
                : nowNy.toLocalDate();
    }

    private static UsIndexDailyHistory ixic(LocalDate date, String close, Long volume) {
        BigDecimal price = new BigDecimal(close);
        return new UsIndexDailyHistory("IXIC", date, price, price, price, price, volume);
    }

    /**
     * 21 列 IXIC（新到舊，比照 repository 的 {@code OrderByTradingDateDesc}）：
     * 最新一列 volume=400，其前 20 列 volume=100 → {@code ratio()} 的中位數分母＝100、量能比＝4.0000。
     *
     * <p>⚠ 現成的 {@code TradingRadarUsStockEngineTest.usUpRowsAt(...)} 雖然建了 241 列，
     * 但從未呼叫 {@code setVolume}，直接沿用會得到 {@code volumeRatio == null}
     * （{@code ratio()} 要求 {@code prior.size() >= RATIO_MIN_SAMPLES}＝10）。故此處自建 fixture。</p>
     *
     * @param latest 最新一個完成交易日；為讓 {@code usCompletion()}（美東 16:00）確定早於
     *               {@code Instant.now()}，呼叫端一律傳「今天（美東）往前數天」。
     */
    private static List<UsIndexDailyHistory> ixicRowsDesc(LocalDate latest) {
        List<UsIndexDailyHistory> rows = new ArrayList<>();
        rows.add(ixic(latest, "19000", 400L));
        for (int i = 1; i <= 20; i++) {
            rows.add(ixic(latest.minusDays(i), "18000", 100L));
        }
        return rows;
    }

    private static LocalDate completedUsDay(int daysAgo) {
        return LocalDate.now(NEW_YORK).minusDays(daysAgo);
    }

    // ─────────── (a) 線上與回測不分岔：值必須與 resolveMarketFromRows 完全相等 ───────────

    @Test
    void 美股marketSummary的量能比與resolveMarketFromRows完全相等而非另算一份() {
        stubBaseline();
        LocalDate latest = completedUsDay(5);
        List<UsIndexDailyHistory> rows = ixicRowsDesc(latest);
        when(usIndexRepo.findTopNByIndexCodeOrderByTradingDateDesc("IXIC", 500)).thenReturn(rows);

        TradingRadarDto.MarketSummary summary = newService().buildMarketSnapshot(US_MARKET).summary();

        // 捕捉 buildUsMarket() 內部實際使用的 decisionInstant 與 rows（buildMarketSnapshot 用的是
        // 自己的 Instant.now()，測試無從指定），再用「未被 spy 包住」的同一支實作重算期望值。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UsIndexDailyHistory>> usRowsCaptor =
                ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TwseIndexDailyHistory>> twRowsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(contextService, times(1)).resolveMarketFromRows(
                eq(US_MARKET), instantCaptor.capture(), twRowsCaptor.capture(), usRowsCaptor.capture());
        assertEquals(List.of(), twRowsCaptor.getValue(),
                "美股 context 不得夾帶台股列——台股與美股不可共用同一個量能來源");

        TradingRadarMarketContextService.MarketContext expected = realContextService.resolveMarketFromRows(
                US_MARKET, instantCaptor.getValue(), List.of(), usRowsCaptor.getValue());

        assertNotNull(expected.marketVolumeRatio(), "前提：fixture 必須真的算得出量能比，測試才有意義");
        assertNotNull(summary.marketVolumeRatio(),
                "接線後美股 MarketSummary 的量能比不得再是 null（否則 market_volume_turnover 恆 MISSING）");
        assertEquals(expected.marketVolumeRatio(), summary.marketVolumeRatio(),
                "線上雷達與回測必須走同一支 resolveMarketFromRows，值要完全相等（含 scale）");
        assertEquals("4.0000", summary.marketVolumeRatio().toPlainString(),
                "20 列 volume=100 的中位數為分母、最新列 400 → 4.0000");
    }

    // ─────────── (b) marketVolumeAsOfDate 等於同一 context 的 marketAsOfDate() ───────────

    @Test
    void 美股marketVolumeAsOfDate等於同一context的marketAsOfDate字串() {
        stubBaseline();
        LocalDate latest = completedUsDay(5);
        when(usIndexRepo.findTopNByIndexCodeOrderByTradingDateDesc("IXIC", 500))
                .thenReturn(ixicRowsDesc(latest));

        TradingRadarDto.MarketSummary summary = newService().buildMarketSnapshot(US_MARKET).summary();

        assertEquals(latest.toString(), summary.marketVolumeAsOfDate());
    }

    // ─────────── (c) 無可用 IXIC 完成列時三欄皆 null 且不拋例外 ───────────

    @Test
    void IXIC列為空時三欄皆為null且不拋例外() {
        stubBaseline();
        when(usIndexRepo.findTopNByIndexCodeOrderByTradingDateDesc("IXIC", 500)).thenReturn(List.of());

        TradingRadarDto.MarketSummary summary = newService().buildMarketSnapshot(US_MARKET).summary();

        assertNotNull(summary, "美股組裝失敗／無資料都不得拋出（既有 try/catch 的隔離語意不變）");
        assertNull(summary.marketVolumeRatio());
        assertNull(summary.marketTurnoverRatio());
        assertNull(summary.marketVolumeAsOfDate());
    }

    @Test
    void IXIC列全部晚於完成邊界時三欄皆為null且不拋例外() {
        stubBaseline();
        // 未來日期：usCompletion()（美東 16:00）必定晚於 decisionInstant，整組被 V13 濾掉。
        when(usIndexRepo.findTopNByIndexCodeOrderByTradingDateDesc("IXIC", 500))
                .thenReturn(ixicRowsDesc(completedUsDay(-30)));

        TradingRadarDto.MarketSummary summary = newService().buildMarketSnapshot(US_MARKET).summary();

        assertNotNull(summary);
        assertNull(summary.marketVolumeRatio(),
                "晚於完成邊界的列不得被當成已完成量能（V13 的前視偏誤防線）");
        assertNull(summary.marketTurnoverRatio());
        assertNull(summary.marketVolumeAsOfDate());
    }

    // ─────────── (d) marketTurnoverRatio 恆為 null ───────────

    @Test
    void 美股marketTurnoverRatio恆為null不得由成交量偽造週轉率() {
        stubBaseline();
        when(usIndexRepo.findTopNByIndexCodeOrderByTradingDateDesc("IXIC", 500))
                .thenReturn(ixicRowsDesc(completedUsDay(5)));

        TradingRadarDto.MarketSummary summary = newService().buildMarketSnapshot(US_MARKET).summary();

        assertNotNull(summary.marketVolumeRatio(), "前提：量能比有值，才證明 turnover 的 null 是刻意的");
        assertNull(summary.marketTurnoverRatio(),
                "us_index_daily_history 沒有成交值／週轉率欄位，不得以成交量除以任何數字偽造");
    }

    // ─────────── (e) MarketInput 守門：量能兩欄與 MarketSummary 同源、不得各算一份 ───────────

    /**
     * Task 342（推翻 Task 323.2 的刻意留白）：{@code MarketInput} 的量能比與完成日漲跌幅
     * 自本版起真正進 regime 分數。斷言從「仍為 null」翻轉為「守同源」而非直接刪除——
     * 刪掉就失去「這兩欄為何一度刻意留白、後來又為何補上」的稽核痕跡。
     *
     * <p>{@code marketTurnoverRatio} 的 {@code assertNull} 是「不得偽造週轉率」的守門，不得動；
     * 294 的跨市場三欄 {@code assertNull} 亦原封保留。</p>
     */
    @Test
    void 美股MarketInput的量能與完成日漲跌幅必須與同一份context同源() {
        stubBaseline();
        List<UsIndexDailyHistory> rows = ixicRowsDesc(completedUsDay(5));
        when(usIndexRepo.findTopNByIndexCodeOrderByTradingDateDesc("IXIC", 500)).thenReturn(rows);

        TradingRadarDto.MarketSummary summary = newService().buildMarketSnapshot(US_MARKET).summary();

        // 用「未被 spy 包住」的同一支實作、同一個 decisionInstant 與同一批 rows 重算期望值。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UsIndexDailyHistory>> usRowsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TwseIndexDailyHistory>> twRowsCaptor = ArgumentCaptor.forClass(List.class);
        verify(contextService, times(1)).resolveMarketFromRows(
                eq(US_MARKET), instantCaptor.capture(), twRowsCaptor.capture(), usRowsCaptor.capture());
        TradingRadarMarketContextService.MarketContext expected = realContextService.resolveMarketFromRows(
                US_MARKET, instantCaptor.getValue(), List.of(), usRowsCaptor.getValue());

        ArgumentCaptor<TradingRadarRuleEngine.MarketInput> captor =
                ArgumentCaptor.forClass(TradingRadarRuleEngine.MarketInput.class);
        verify(ruleEngine, times(1)).evaluateMarket(captor.capture());
        TradingRadarRuleEngine.MarketInput usInput = captor.getValue();

        assertNotNull(expected.marketVolumeRatio(), "前提：fixture 必須真的算得出量能比，測試才有意義");
        assertNotNull(expected.completedMarketChangePercent(),
                "前提：fixture 必須真的算得出完成日漲跌幅，測試才有意義");
        assertEquals(expected.marketVolumeRatio(), usInput.marketVolumeRatio(),
                "MarketInput 的量能比必須就是 resolveMarketFromRows 那一份，不得各算一份");
        assertEquals(summary.marketVolumeRatio(), usInput.marketVolumeRatio(),
                "MarketInput 與 MarketSummary 必須吃同一個 context（畫面顯示值與計分值不得分岔）");
        assertEquals(expected.completedMarketChangePercent(), usInput.completedChangePercent(),
                "完成日漲跌幅必須取 usContext 這一份；用未經完成日過濾的區域變數 changePercent "
                        + "會與量比落在不同 as-of 日，把四個計分分支的正負號判錯");

        assertNull(usInput.marketTurnoverRatio(),
                "us_index_daily_history 沒有成交值／週轉率欄位，不得以成交量除以任何數字偽造");
        // 294 既有護欄一併回歸：跨市場領先訊號三欄仍為缺值，且改以「不適用」表達而非「資料不足」。
        assertNull(usInput.nasdaqChangePercent());
        assertNull(usInput.soxChangePercent());
        assertNull(usInput.usTechCompositePercent());
        assertFalse(usInput.crossMarketApplicable(),
                "美股大盤即 IXIC，跨市場因子不適用（Requirement 64 的排除未被推翻）");
    }

    // ─────────── (f) 假風險提醒消失 ───────────

    /**
     * Requirement 82 的可觀察行為：卡片顯示得出量比時，兩則假的「資料不足」提醒都不得再出現。
     */
    @Test
    void 美股風險提醒不再出現量價與跨市場的假資料不足() {
        stubBaseline();
        when(usIndexRepo.findTopNByIndexCodeOrderByTradingDateDesc("IXIC", 500))
                .thenReturn(ixicRowsDesc(completedUsDay(5)));

        TradingRadarDto.MarketSummary summary = newService().buildMarketSnapshot(US_MARKET).summary();

        assertNotNull(summary.marketVolumeRatio(), "前提：量比確實算得出來，否則本測試不成立");
        assertTrue(summary.risks().stream().noneMatch(r -> r.contains("量價資料不足")),
                "量比已接進 MarketInput，量價那則「資料不足」必須消失");
        assertTrue(summary.risks().stream().noneMatch(r -> r.contains("美股科技共同交易日")),
                "跨市場因子對美股是不適用，不得謊報成資料不足");
    }
}
