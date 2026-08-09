package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.UsIndexDailyHistory;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.EtfNavHistoryRepository;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockDividendHistoryRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import com.steven.assets.security.CurrentUserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 交易雷達規則引擎接入美股個股（Task 294）：IXIC 大盤情境 ＋ 移除台股限定 filter。
 *
 * <p>建構方式照抄同目錄 {@link TradingRadarMarketFreshnessTest}／{@link TradingRadarServiceOwnerScopeTest}：
 * 其餘依賴皆 mock。唯一差異是 {@code ruleEngine} 改用 {@link Mockito#spy}包住<b>真實</b>
 * {@link TradingRadarRuleEngine}——本檔要驗證的核心風險是「regime 變數傳錯」（294.3／294.6），
 * 用 spy 既能讓分數／確認狀態走真實計算（不必手刻假 StockResult），又能用
 * {@link ArgumentCaptor} 直接檢查餵進 {@code evaluateStock()}／{@code evaluateMarket()} 的
 * 實際參數，比只斷言最終 action 更直接、不受其他閘門條件（KD 過熱／乖離…）干擾。</p>
 */
@ExtendWith(MockitoExtension.class)
class TradingRadarUsStockEngineTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final ZoneId US_ZONE = ZoneId.of("America/New_York");

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
    @Mock private TradingRadarMarketContextService marketContextService;
    @Mock private FundamentalAnalysisService fundamentalAnalysisService;
    @Mock private EtfNavHistoryRepository etfNavHistoryRepo;
    @Mock private TradingRadarSnapshotStore snapshotStore;
    @Mock private CurrentUserContext currentUserContext;

    /** 真實 {@link TradingRadarRuleEngine} 包 spy；由 {@link #newService()} 建立並保留參照供 verify 用。 */
    private TradingRadarRuleEngine ruleEngine;

    private TradingRadarService newService() {
        ruleEngine = Mockito.spy(new TradingRadarRuleEngine());
        return new TradingRadarService(
                ruleEngine,
                indicatorService,
                adjustedPriceService,
                // 用真的 assembler 包同一組 mock（與既有兩檔同一慣例），並共用同一顆 spy ruleEngine，
                // 讓 RadarInputAssembler 內部的 confirm() 呼叫也一併被 spy 記錄。
                new RadarInputAssembler(indicatorService, adjustedPriceService, ruleEngine),
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
                currentUserContext);
    }

    /**
     * TW 組 FullIndicators：ma20/60/240 皆設 20000（遠高於 twDownRows() 的現價 15000）、K(30)&lt;D(70)。
     * 搭配 twDownRows() 的真實 confirm() 計算，會讓 buildMarket() 的 evaluateMarket() 產出 RISK_OFF。
     */
    private static final TechnicalIndicatorService.FullIndicators TW_RISK_OFF_IND =
            new TechnicalIndicatorService.FullIndicators(
                    BigDecimal.valueOf(20000), BigDecimal.valueOf(20000), BigDecimal.valueOf(20000),
                    BigDecimal.valueOf(30), BigDecimal.valueOf(70),
                    BigDecimal.valueOf(30), BigDecimal.valueOf(70),
                    null, TechnicalIndicatorService.ExtendedIndicators.EMPTY);

    /**
     * US（IXIC）組 FullIndicators：ma20/60/240 皆設 15000（遠低於 usUpRows() 的現價 19000）、K(70)&gt;D(50)。
     * 搭配 usUpRows() 的真實 confirm() 計算，會讓 buildUsMarket() 的 evaluateMarket() 產出 RISK_ON。
     */
    private static final TechnicalIndicatorService.FullIndicators US_RISK_ON_IND =
            new TechnicalIndicatorService.FullIndicators(
                    BigDecimal.valueOf(15000), BigDecimal.valueOf(15000), BigDecimal.valueOf(15000),
                    BigDecimal.valueOf(70), BigDecimal.valueOf(50),
                    BigDecimal.valueOf(70), BigDecimal.valueOf(50),
                    null, TechnicalIndicatorService.ExtendedIndicators.EMPTY);

    /** 241 筆「由新到舊」台股加權指數收盤：closes[i] = 15000+5i，近期最低（下跌趨勢）。 */
    private List<TwseIndexDailyHistory> twDownRows() {
        LocalDate today = LocalDate.now(TAIPEI);
        List<TwseIndexDailyHistory> rows = new ArrayList<>();
        for (int i = 0; i < 241; i++) {
            TwseIndexDailyHistory h = new TwseIndexDailyHistory();
            h.setTradingDate(today.minusDays(i));
            h.setClosePoint(BigDecimal.valueOf(15000 + i * 5));
            rows.add(h);
        }
        return rows;
    }

    /** 241 筆「由新到舊」IXIC 收盤：closes[i] = 19000-5i，近期最高（上漲趨勢）。 */
    private List<UsIndexDailyHistory> usUpRows() {
        LocalDate today = LocalDate.now(US_ZONE);
        List<UsIndexDailyHistory> rows = new ArrayList<>();
        for (int i = 0; i < 241; i++) {
            UsIndexDailyHistory h = new UsIndexDailyHistory();
            h.setIndexCode("IXIC");
            h.setTradingDate(today.minusDays(i));
            BigDecimal close = BigDecimal.valueOf(19000 - i * 5);
            h.setClosePoint(close);
            h.setHighPoint(close.add(BigDecimal.ONE));
            h.setLowPoint(close.subtract(BigDecimal.ONE));
            rows.add(h);
        }
        return rows;
    }

    /** holdings 恆空；watchlist／大盤資料由各測試自行決定。 */
    private void stubBaseline() {
        lenient().when(taiexDisplayPriceService.resolve()).thenReturn(
                new TaiexDisplayPriceService.DisplayQuote(
                        null, null, null, null, null, null,
                        null, null, true, "CLOSE_PENDING"));
        lenient().when(marketDataService.isTradingDay(anyString(), any(LocalDate.class))).thenReturn(true);
        lenient().when(marketContextService.resolve(any())).thenReturn(
                new TradingRadarMarketContextService.Resolved(
                        TradingRadarMarketContextService.MarketContext.EMPTY, List.of()));
        // evaluateForNotification／buildMarketSnapshot 的台股分支改走 resolveMarket（Task 302），
        // 頁面路徑 assemble() 仍走上面的 resolve()；兩者都要 stub。
        lenient().when(marketContextService.resolveMarket(any()))
                .thenReturn(TradingRadarMarketContextService.MarketContext.EMPTY);
        lenient().when(marketContextService.resolveFx(anyString(), any()))
                .thenReturn(TradingRadarMarketContextService.FxContext.EMPTY);
        lenient().when(fundamentalAnalysisService.resolve(any(), any(), any(), any()))
                .thenReturn(FundamentalAnalysisService.Resolved.unavailable(false));
        lenient().when(snapshotRepo.findLatestWithStocks()).thenReturn(Optional.empty());
        lenient().when(alertRepo.findDistinctStockCodeMarket()).thenReturn(List.of());
        lenient().when(priceHistoryRepo.findRecentN(anyString(), anyString(), anyInt())).thenReturn(List.of());
        lenient().when(priceQueryService.getLive(anyString(), anyString())).thenReturn(Optional.empty());
        lenient().when(priceQueryService.getDisplayPrice(anyString(), anyString())).thenReturn(Optional.empty());
        lenient().when(twseRepo.findTopNByOrderByTradingDateDesc(241)).thenReturn(List.of());
        lenient().when(usIndexDailyHistoryRepo.findTopNByIndexCodeOrderByTradingDateDesc(anyString(), anyInt()))
                .thenReturn(List.of());
    }

    /** 把兩組大盤都設成互不相同、可觀測的 regime：TW 組 RISK_OFF、US（IXIC）組 RISK_ON。 */
    private void stubDivergentRegimes() {
        // lenient：evaluateForNotification() 依 market 只組裝其中一組（Task 294.6），
        // 另一組的 stub 在那些測試裡本來就不會被用到，屬預期行為。
        lenient().when(twseRepo.findTopNByOrderByTradingDateDesc(241)).thenReturn(twDownRows());
        lenient().when(indicatorService.computeAll("0000", "台股")).thenReturn(TW_RISK_OFF_IND);
        lenient().when(usIndexDailyHistoryRepo.findTopNByIndexCodeOrderByTradingDateDesc("IXIC", 241))
                .thenReturn(usUpRows());
        lenient().when(indicatorService.computeAllForNasdaq()).thenReturn(US_RISK_ON_IND);
    }

    // ─────────────────────────── (a) addTarget 白名單回歸 ───────────────────────────

    @Test
    void addTarget對美股不再排除但英股與其他任意市場字串仍排除() {
        stubBaseline();
        when(alertRepo.findDistinctStockCodeMarket()).thenReturn(List.of(
                new Object[]{"AAPL", "美股"},
                new Object[]{"BP", "英股"},
                new Object[]{"7203", "日股"}));

        TradingRadarDto.Response resp = newService().get();

        List<String> codes = resp.stocks().stream().map(TradingRadarDto.StockDecision::stockCode).toList();
        assertTrue(codes.contains("AAPL"), "美股標的必須進入 decisions，不得再被 skippedNonTw 吞掉");
        assertFalse(codes.contains("BP"), "英股仍應被排除（回歸）");
        assertFalse(codes.contains("7203"), "其他任意市場字串仍應被排除（回歸）");
        assertEquals(2, resp.skippedNonTwStocks(), "只有英股／日股計入 skipped，美股不再計入");
    }

    // ─────────────────────────── (c) regime 依 target.market() 選對應組別 ───────────────────────────

    @Test
    void 美股個股的marketRegime來自IXIC組而非台股組() {
        stubBaseline();
        stubDivergentRegimes();
        when(alertRepo.findDistinctStockCodeMarket()).thenReturn(List.<Object[]>of(new Object[]{"AAPL", "美股"}));

        TradingRadarDto.Response resp = newService().get();

        assertEquals("RISK_OFF", resp.market().regime(), "前提：台股組必須真的是 RISK_OFF，測試才有意義");

        ArgumentCaptor<TradingRadarRuleEngine.StockInput> captor =
                ArgumentCaptor.forClass(TradingRadarRuleEngine.StockInput.class);
        verify(ruleEngine).evaluateStock(captor.capture());
        assertEquals(TradingRadarRuleEngine.MarketRegime.RISK_ON, captor.getValue().marketRegime(),
                "美股個股的 marketAllowsBuy 不受台股 regime（RISK_OFF）拖累——必須吃到 IXIC 組的 RISK_ON");
        assertFalse(captor.getValue().marketStale());
        assertEquals(1, resp.stocks().size());
    }

    @Test
    void 台股個股的marketRegime來自台股組而非IXIC組_反之亦然() {
        stubBaseline();
        stubDivergentRegimes();
        when(alertRepo.findDistinctStockCodeMarket()).thenReturn(List.<Object[]>of(new Object[]{"2330", "台股"}));

        TradingRadarDto.Response resp = newService().get();

        ArgumentCaptor<TradingRadarRuleEngine.StockInput> captor =
                ArgumentCaptor.forClass(TradingRadarRuleEngine.StockInput.class);
        verify(ruleEngine).evaluateStock(captor.capture());
        assertEquals(TradingRadarRuleEngine.MarketRegime.RISK_OFF, captor.getValue().marketRegime(),
                "台股個股不得被 IXIC 組的 RISK_ON 誤套用");
        assertEquals(1, resp.stocks().size());
    }

    // ─────────────────────────── (d) 美股 MarketInput 的跨市場欄位恆為缺值 ───────────────────────────

    @Test
    void 美股組MarketInput的跨市場領先訊號三欄為null且usTechAvailable為false() {
        stubBaseline();
        stubDivergentRegimes();
        // 本測試只關心 evaluateMarket() 的輸入，watchlist 留空即可（buildUsMarket 不論有無標的都會跑）。

        newService().get();

        ArgumentCaptor<TradingRadarRuleEngine.MarketInput> captor =
                ArgumentCaptor.forClass(TradingRadarRuleEngine.MarketInput.class);
        verify(ruleEngine, org.mockito.Mockito.times(2)).evaluateMarket(captor.capture());

        TradingRadarRuleEngine.MarketInput usInput = captor.getAllValues().stream()
                .filter(in -> in.price() != null && in.price().compareTo(BigDecimal.valueOf(19000)) == 0)
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到美股（IXIC，現價 19000）那組 MarketInput"));

        assertNull(usInput.nasdaqChangePercent());
        assertNull(usInput.soxChangePercent());
        assertNull(usInput.usTechCompositePercent());
        assertFalse(usInput.usTechAvailable());
    }

    // ─────────────────────────── (e) buildUsMarket() 例外不拖垮 buildMarket() ───────────────────────────

    @Test
    void buildUsMarket例外時不影響buildMarket的台股組正常回傳且美股個股優雅降級() {
        stubBaseline();
        when(twseRepo.findTopNByOrderByTradingDateDesc(241)).thenReturn(twDownRows());
        when(indicatorService.computeAll("0000", "台股")).thenReturn(TW_RISK_OFF_IND);
        when(usIndexDailyHistoryRepo.findTopNByIndexCodeOrderByTradingDateDesc(anyString(), anyInt()))
                .thenThrow(new RuntimeException("IXIC 讀取失敗（模擬）"));
        when(alertRepo.findDistinctStockCodeMarket()).thenReturn(List.<Object[]>of(new Object[]{"AAPL", "美股"}));

        TradingRadarDto.Response resp = newService().get();

        assertEquals("RISK_OFF", resp.market().regime(), "美股組例外不得拖垮台股組的正常回傳");
        assertTrue(resp.market().dataComplete());
        assertEquals(1, resp.stocks().size());
        TradingRadarDto.StockDecision decision = resp.stocks().get(0);
        assertEquals("NO_TRADE", decision.action(),
                "美股大盤讀取失敗時，美股個股應優雅降級為今日不交易，而非讓整個請求失敗");
        assertFalse(decision.dataComplete());
    }

    // ─────────────────────────── (f) 美股個股走既有 FX 路徑 ───────────────────────────

    @Test
    void 美股個股確實算出非null的fxPercentile() {
        stubBaseline();
        stubDivergentRegimes();
        when(marketContextService.resolveFx(eq("USD"), any())).thenReturn(
                new TradingRadarMarketContextService.FxContext(BigDecimal.valueOf(72.5), LocalDate.now(TAIPEI)));
        when(alertRepo.findDistinctStockCodeMarket()).thenReturn(List.<Object[]>of(new Object[]{"AAPL", "美股"}));

        TradingRadarDto.Response resp = newService().get();

        assertEquals(1, resp.stocks().size());
        TradingRadarDto.StockDecision decision = resp.stocks().get(0);
        assertEquals("USD", decision.underlyingCurrency());
        assertNotNull(decision.fxPercentile(), "美股個股應走既有 FX 路徑算出非 null 的 fxPercentile");
        assertEquals(0, BigDecimal.valueOf(72.5).compareTo(decision.fxPercentile()));
    }

    // ─────────────────────────── (g) evaluateForNotification 依 market 選組別 ───────────────────────────

    @Test
    void evaluateForNotification對美股走IXIC組regime且不讀台股資料() {
        stubBaseline();
        stubDivergentRegimes();

        TradingRadarService service = newService();
        service.evaluateForNotification("AAPL", "美股", false);

        ArgumentCaptor<TradingRadarRuleEngine.StockInput> captor =
                ArgumentCaptor.forClass(TradingRadarRuleEngine.StockInput.class);
        verify(ruleEngine).evaluateStock(captor.capture());
        assertEquals(TradingRadarRuleEngine.MarketRegime.RISK_ON, captor.getValue().marketRegime());

        verify(twseRepo, never()).findTopNByOrderByTradingDateDesc(anyInt());
        verify(marketContextService, never()).resolve(any());
    }

    @Test
    void evaluateForNotification對台股走台股組regime且不讀IXIC資料() {
        stubBaseline();
        stubDivergentRegimes();

        TradingRadarService service = newService();
        service.evaluateForNotification("2330", "台股", false);

        ArgumentCaptor<TradingRadarRuleEngine.StockInput> captor =
                ArgumentCaptor.forClass(TradingRadarRuleEngine.StockInput.class);
        verify(ruleEngine).evaluateStock(captor.capture());
        assertEquals(TradingRadarRuleEngine.MarketRegime.RISK_OFF, captor.getValue().marketRegime());

        verify(usIndexDailyHistoryRepo, never())
                .findTopNByIndexCodeOrderByTradingDateDesc(anyString(), anyInt());
    }

    // ─────────────────────────── (h) 美股 ETF 折溢價因子明確排除 ───────────────────────────

    @Test
    void 美股ETF折溢價因子仍為null即使既有資料來源都查得到值() {
        stubBaseline();
        stubDivergentRegimes();
        // 模擬「若 294.5 的排除邏輯漏寫」時本應被讀到的既有資料
        // （比照既有 EtfNavPoller 對美股 ETF 寫入 etf_nav_history／Redis 的資料形狀）。
        lenient().when(priceQueryService.getEtfNav("VOO", "美股")).thenReturn(Optional.of(
                new PriceQueryService.EtfNav("VOO", "美股", BigDecimal.valueOf(400),
                        BigDecimal.valueOf(1.5), LocalDate.now(TAIPEI).toString(), "YAHOO")));
        lenient().when(etfNavHistoryRepo.findRecentPremiumPct(eq("VOO"), eq("美股"), any()))
                .thenReturn(List.of(BigDecimal.valueOf(2.0), BigDecimal.valueOf(1.8)));
        when(alertRepo.findDistinctStockCodeMarket()).thenReturn(List.<Object[]>of(new Object[]{"VOO", "美股"}));

        TradingRadarDto.Response resp = newService().get();

        assertEquals(1, resp.stocks().size());
        TradingRadarDto.StockDecision decision = resp.stocks().get(0);
        assertNull(decision.etfPremiumPct(),
                "294.5：market=\"美股\" 時折溢價恆為 null，不得依賴「查無資料自然為 null」的假設");
        assertNull(decision.etfPremiumPercentile());
        verify(priceQueryService, never()).getEtfNav(anyString(), eq("美股"));
        verify(etfNavHistoryRepo, never()).findRecentPremiumPct(anyString(), eq("美股"), any());
    }

    // ─────────────────────────── (i) shouldAddLiveRow 用標的市場時區判斷「今日」（Task 297） ───────────────────────────

    /**
     * 冬令時段跨夜點：2026-01-15T16:30:00Z = 台北 2026-01-16 00:30 = 美東 2026-01-15 11:30。
     * 此時美東日期（D-1）與台北日期（D）錯開一天，是 297 背景所述 bug 的機械重現點。
     */
    private static final Instant WINTER_STRADDLE_INSTANT = Instant.parse("2026-01-15T16:30:00Z");

    private static PriceQueryService.LivePrice liveWithTradingDate(String tradingDate) {
        return new PriceQueryService.LivePrice(
                "AAPL", null, "美股", BigDecimal.valueOf(100), null, null, null,
                null, null, null, null, null, null,
                tradingDate, null, null, null, null);
    }

    private static StockPriceHistory completedRow(String tradingDate) {
        return StockPriceHistory.builder()
                .stockCode("AAPL")
                .market("美股")
                .tradingDate(LocalDate.parse(tradingDate))
                .closePrice(BigDecimal.valueOf(99))
                .build();
    }

    @Test
    void shouldAddLiveRow美股用美東日期判斷今日跨夜情境應併入() {
        List<StockPriceHistory> rows = List.of(completedRow("2026-01-14"));
        PriceQueryService.LivePrice live = liveWithTradingDate("2026-01-15");

        boolean result = newService()
                .shouldAddLiveRow(rows, live, WINTER_STRADDLE_INSTANT, "美股");

        assertTrue(result,
                "台北 00:30／美東 11:30 時，tradingDate 為美東當日的美股 live 應併入序列（修正前為 false）");
    }

    @Test
    void shouldAddLiveRow台股仍用台北日期判斷今日行為不變() {
        List<StockPriceHistory> rows = List.of(completedRow("2026-01-14"));
        PriceQueryService.LivePrice live = liveWithTradingDate("2026-01-15");

        boolean result = newService()
                .shouldAddLiveRow(rows, live, WINTER_STRADDLE_INSTANT, "台股");

        assertFalse(result, "同一 instant 下台北日期為 01-16，live 為 01-15，台股既有行為不得改變");
    }

    @Test
    void shouldAddLiveRow美股live等於完成列最新日期時不重複併入() {
        List<StockPriceHistory> rows = List.of(completedRow("2026-01-15"));
        PriceQueryService.LivePrice live = liveWithTradingDate("2026-01-15");

        boolean result = newService()
                .shouldAddLiveRow(rows, live, WINTER_STRADDLE_INSTANT, "美股");

        assertFalse(result, "live tradingDate 已等於完成列最新日期時，既有防重複行為不得改變");
    }

    // ─────────────────────────── (j) 同一 assemble() 內同幣別 FX 只解析一次（Task 303） ───────────────────────────

    @Test
    void assemble內兩檔同幣別美股只呼叫一次resolveFx且台股短路不觸發() {
        stubBaseline();
        when(alertRepo.findDistinctStockCodeMarket()).thenReturn(List.<Object[]>of(
                new Object[]{"AAPL", "美股"},
                new Object[]{"MSFT", "美股"},
                new Object[]{"2330", "台股"}));

        TradingRadarDto.Response resp = newService().get();

        assertEquals(3, resp.stocks().size());
        verify(marketContextService, org.mockito.Mockito.times(1)).resolveFx(eq("USD"), any());
        verify(marketContextService, never()).resolveFx(eq("TWD"), any());
    }
}
