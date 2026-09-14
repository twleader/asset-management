package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.Stock;
import com.steven.assets.model.StockHolding;
import com.steven.assets.model.StockPriceHistory;
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

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TradingRadarService 背景重算的 owner 隔離（Task 260）。
 *
 * <p>這是本任務唯一防止跨租戶污染的測試——背景無 request context，{@code TenantFilterAspect}
 * 不啟用 {@code @Filter(ownerFilter)}，無 owner 版本的 {@code findLatestWithStocks()}／
 * {@code findDistinctStockCodeMarket()} 在背景會撈到全部租戶的資料。</p>
 *
 * <p>建構方式照抄同目錄 {@link TradingRadarMarketFreshnessTest}：{@code ruleEngine} 用真實
 * {@link TradingRadarRuleEngine}（純函式、無副作用），其餘 14 個建構子依賴皆 mock；
 * holdings／watchlist 一律回空，只隔離出 owner 分支本身。</p>
 */
@ExtendWith(MockitoExtension.class)
class TradingRadarServiceOwnerScopeTest {

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
    @Mock private TradingRadarSnapshotStore snapshotStore;
    @Mock private CurrentUserContext currentUserContext;
    @Mock private DividendEventEvidenceRepository dividendEventEvidenceRepository;
    @Mock private TreasuryYieldService treasuryYieldService;
    @Mock private TradingRadarListBatchPreloader listBatchPreloader;

    private TradingRadarService newService() {
        TradingRadarService service = new TradingRadarService(
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
        service.setListBatchPreloader(listBatchPreloader);
        return service;
    }

    /** holdings／watchlist 一律回空（owner／無 owner 兩種查詢都要 stub），只隔離出 owner 分支本身。 */
    private void stubCommon() {
        lenient().when(adjustedPriceService.adjust(anyList(), anyList())).thenAnswer(call ->
                new DistributionAdjustedPriceService.Adjustment(call.getArgument(0), false));
        lenient().when(taiexDisplayPriceService.resolve()).thenReturn(
                new TaiexDisplayPriceService.DisplayQuote(
                        null, null, null, null, null, null,
                        null, null, true, "CLOSE_PENDING"));
        lenient().when(marketDataService.isTradingDay(anyString(), any(LocalDate.class))).thenReturn(true);
        lenient().when(marketContextService.resolve(any())).thenReturn(
                new TradingRadarMarketContextService.Resolved(
                        TradingRadarMarketContextService.MarketContext.EMPTY, List.of()));
        lenient().when(marketContextService.resolveDecisionSessionsCachedOnly(anyString(), any()))
                .thenAnswer(call -> Optional.ofNullable(RadarObservationResolver.decisionSessionsStrict(
                        call.getArgument(0), call.getArgument(1), date -> Optional.of(true))));
        lenient().when(twseRepo.findTopNByOrderByTradingDateDesc(500)).thenReturn(List.of());
        // Task 294：buildUsMarket() 每輪都會計算（不論本輪有沒有美股標的），需同步 stub 避免 NPE。
        lenient().when(usIndexDailyHistoryRepo.findTopNByIndexCodeOrderByTradingDateDesc(anyString(), anyInt()))
                .thenReturn(List.of());
        lenient().when(priceQueryService.getLive(anyString(), anyString())).thenReturn(Optional.empty());
        lenient().when(indicatorService.computeAll(anyString(), anyString()))
                .thenReturn(TechnicalIndicatorService.FullIndicators.EMPTY);
        lenient().when(indicatorService.computeAllForNasdaq())
                .thenReturn(TechnicalIndicatorService.FullIndicators.EMPTY);
        lenient().when(snapshotRepo.findLatestWithStocks()).thenReturn(Optional.empty());
        lenient().when(snapshotRepo.findLatestWithStocksByOwnerUserId(anyLong())).thenReturn(Optional.empty());
        lenient().when(alertRepo.findDistinctStockCodeMarket()).thenReturn(List.of());
        lenient().when(alertRepo.findDistinctStockCodeMarketByOwnerUserId(anyLong())).thenReturn(List.of());
        // Task 323：buildUsMarket() 改由 resolveMarketFromRows 取得 IXIC 量能 context；
        // 本檔只驗證 owner 分支，美股組回 EMPTY 即可。
        lenient().when(marketContextService.resolveMarketFromRows(
                        anyString(), any(), anyList(), anyList()))
                .thenReturn(TradingRadarMarketContextService.MarketContext.EMPTY);
    }

    @Test
    void 背景重算走owner_scoped查詢且不觸碰CurrentUserContext() {
        stubCommon();

        TradingRadarDto.Response response = newService().recomputeAndStoreForOwner(7L);

        assertNotNull(response);
        verify(snapshotRepo).findLatestWithStocksByOwnerUserId(7L);
        verify(alertRepo).findDistinctStockCodeMarketByOwnerUserId(7L);
        verify(snapshotRepo, never()).findLatestWithStocks();
        verify(alertRepo, never()).findDistinctStockCodeMarket();
        verify(currentUserContext, never()).getEffectiveUserId();
        verify(snapshotStore).saveRecomputed(eq(7L), any());
        verify(snapshotStore, never()).save(anyLong(), any());
    }

    /**
     * 回歸錨點：HTTP 路徑（{@code get()}）未被改壞——{@code assemble(null)} 的分支若寫反
     * （HTTP 誤走 owner-scoped 查詢並以 null 當 owner），會在 runtime 才炸。測試環境無 request
     * context，{@code get()} 既有的快照寫入守門不會觸發，故只驗證 owner 分支本身沒有被交換。
     */
    @Test
    void HTTP路徑get仍走無owner查詢且不觸發saveRecomputed() {
        stubCommon();

        TradingRadarDto.Response response = newService().get();

        assertNotNull(response);
        verify(snapshotRepo).findLatestWithStocks();
        verify(alertRepo).findDistinctStockCodeMarket();
        verify(snapshotRepo, never()).findLatestWithStocksByOwnerUserId(anyLong());
        verify(alertRepo, never()).findDistinctStockCodeMarketByOwnerUserId(anyLong());
        verify(snapshotStore, never()).saveRecomputed(anyLong(), any());
    }

    /**
     * Requirement 86：公開 current read 與 HTTP 頁面共用 request-scoped owner 分支，
     * 但無論 request context 是否存在都不得建立任何匯出 snapshot。
     */
    @Test
    void 公開current走無owner查詢且完全不寫入snapshot() {
        stubCommon();

        TradingRadarDto.Response response = newService().getCurrent();

        assertNotNull(response);
        verify(snapshotRepo).findLatestWithStocks();
        verify(alertRepo).findDistinctStockCodeMarket();
        verify(snapshotRepo, never()).findLatestWithStocksByOwnerUserId(anyLong());
        verify(alertRepo, never()).findDistinctStockCodeMarketByOwnerUserId(anyLong());
        verify(snapshotStore, never()).save(anyLong(), any());
        verify(snapshotStore, never()).saveRecomputed(anyLong(), any());
        verify(currentUserContext, never()).getEffectiveUserId();
    }

    @Test
    void browserList走同一owner範圍但不建立完整response快照() {
        stubCommon();

        TradingRadarDto.ListResponse response = newService().getList();

        assertNotNull(response);
        verify(snapshotRepo).findLatestWithStocks();
        verify(alertRepo).findDistinctStockCodeMarket();
        verify(snapshotStore, never()).save(anyLong(), any());
        verify(snapshotStore, never()).saveRecomputed(anyLong(), any());
        verify(currentUserContext, never()).getEffectiveUserId();
    }

    @Test
    void browserListContext禁止逐檔價格調整快取與基本面IO() {
        stubCommon();
        AssetSnapshot snapshot = AssetSnapshot.builder().stocks(List.of(StockHolding.builder()
                .stockCode("2330").market("台股").shares(BigDecimal.ONE).build())).build();
        when(snapshotRepo.findLatestWithStocks()).thenReturn(Optional.of(snapshot));
        Stock stock = Stock.builder().code("2330").market("台股").name("台積電").build();
        StockPriceHistory price = StockPriceHistory.builder().stockCode("2330").market("台股")
                .tradingDate(LocalDate.of(2026, 9, 11)).closePrice(new BigDecimal("100")).build();
        TradingRadarListBatchPreloader.Entry entry = new TradingRadarListBatchPreloader.Entry(
                Optional.of(stock), List.of(price), Optional.empty(), Optional.empty(), List.of(),
                DividendEventEvidenceResolver.Resolution.MISSING,
                FundamentalAnalysisService.Resolved.unavailable(false),
                BondYieldBetaResolver.Result.notApplicable(null), null,
                TradingRadarMarketContextService.FxContext.EMPTY,
                TradingRadarMarketFeatureResolver.Evidence.empty("台股", java.time.Instant.EPOCH, "test"),
                LocalDate.of(2026, 9, 11), List.of());
        when(listBatchPreloader.preload(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(new TradingRadarListBatchPreloader.Context(Map.of(
                        new TradingRadarListBatchPreloader.Key("2330", "台股"), entry)));

        TradingRadarDto.ListResponse response = newService().getList();

        assertNotNull(response);
        verify(priceHistoryRepo, never()).findRecentN(anyString(), anyString(), anyInt());
        verify(priceQueryService, never()).getLive("2330", "台股");
        verify(stockRepo, never()).findByCodeAndMarket(anyString(), anyString());
        verify(dividendEventEvidenceRepository, never()).resolve(anyString(), anyString(), any(), any());
        verify(fundamentalAnalysisService, never()).resolve(anyString(), anyString(), anyString(), any());
        verify(etfNavHistoryRepo, never()).findRecentPremiumPctAsOf(anyString(), anyString(), any(), any());
        verify(marketDataService, never()).isTwTradingDayKnown(any(LocalDate.class));
        verify(marketDataService, never()).isTradingDay(anyString(), any(LocalDate.class));
    }

    @Test
    void browserListBatchThrow仍完整保留持股與觀察清單的eligible聯集且不重開逐檔讀取() {
        stubCommon();
        stubEligibleUnion();
        when(listBatchPreloader.preload(any(), any(), any(), any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("batch unavailable"));

        TradingRadarDto.ListResponse response = newService().getList();

        assertThat(response.stocks()).extracting(stock -> key(stock.stockCode(), stock.market()))
                .containsExactlyInAnyOrder(key("2330", "台股"), key("DUP", "台股"), key("AAPL", "美股"));
        assertThat(response.stocks()).allSatisfy(stock -> {
            assertThat(stock.action()).isEqualTo(TradingRadarRuleEngine.Action.NO_TRADE.name());
            assertThat(stock.price()).isNull();
            assertThat(stock.score()).isNull();
        });
        assertThat(response.stocks()).filteredOn(stock -> "DUP".equals(stock.stockCode()))
                .singleElement().satisfies(stock -> assertThat(stock.held()).isTrue());
        verify(priceHistoryRepo, never()).findRecentN(anyString(), anyString(), anyInt());
        verify(priceQueryService, never()).getLive("2330", "台股");
        verify(priceQueryService, never()).getLive("DUP", "台股");
        verify(priceQueryService, never()).getLive("AAPL", "美股");
        verify(stockRepo, never()).findByCodeAndMarket(anyString(), anyString());
        verify(dividendEventEvidenceRepository, never()).resolve(anyString(), anyString(), any(), any());
        verify(fundamentalAnalysisService, never()).resolve(anyString(), anyString(), anyString(), any());
        verify(etfNavHistoryRepo, never()).findRecentPremiumPctAsOf(anyString(), anyString(), any(), any());
    }

    @Test
    void browserListCompactProjectionThrow仍以同identity輸出unavailableNoTradeRows() {
        stubCommon();
        stubEligibleUnion();
        when(listBatchPreloader.preload(any(), any(), any(), any(), any(), anyInt()))
                .thenAnswer(call -> TradingRadarListBatchPreloader.Context.complete(
                        call.getArgument(0), null, call.getArgument(1)));

        TradingRadarDto.ListResponse response = new ProjectionFailingTradingRadarService().getList();

        assertThat(response.stocks()).extracting(stock -> key(stock.stockCode(), stock.market()))
                .containsExactlyInAnyOrder(key("2330", "台股"), key("DUP", "台股"), key("AAPL", "美股"));
        assertThat(response.stocks()).allSatisfy(stock -> {
            assertThat(stock.action()).isEqualTo(TradingRadarRuleEngine.Action.NO_TRADE.name());
            assertThat(stock.quoteStatus()).isEqualTo("CLOSE_PENDING");
            assertThat(stock.price()).isNull();
            assertThat(stock.score()).isNull();
        });
    }

    @Test
    void list與full在兩市場三個target的全部可見scalar一致且不混用同碼市場資料() {
        stubCommon();
        Stock tw = Stock.builder().code("SAME").market("台股").name("台灣同碼").build();
        Stock usSame = Stock.builder().code("SAME").market("美股").name("美股同碼").build();
        Stock usOther = Stock.builder().code("OTHER").market("美股").name("美股另一檔").build();
        AssetSnapshot snapshot = AssetSnapshot.builder().stocks(List.of(
                StockHolding.builder().stockCode("SAME").market("台股").shares(BigDecimal.ONE).build(),
                StockHolding.builder().stockCode("SAME").market("美股").shares(BigDecimal.ONE).build(),
                StockHolding.builder().stockCode("OTHER").market("美股").shares(BigDecimal.ONE).build())).build();
        when(snapshotRepo.findLatestWithStocks()).thenReturn(Optional.of(snapshot));

        Map<String, Stock> stocks = Map.of(
                key("SAME", "台股"), tw,
                key("SAME", "美股"), usSame,
                key("OTHER", "美股"), usOther);
        Map<String, List<StockPriceHistory>> prices = Map.of(
                key("SAME", "台股"), List.of(price("SAME", "台股", "101")),
                key("SAME", "美股"), List.of(price("SAME", "美股", "202")),
                key("OTHER", "美股"), List.of(price("OTHER", "美股", "303")));
        when(stockRepo.findByCodeAndMarket(anyString(), anyString())).thenAnswer(call ->
                Optional.ofNullable(stocks.get(key(call.getArgument(0), call.getArgument(1)))));
        when(priceHistoryRepo.findRecentN(anyString(), anyString(), anyInt())).thenAnswer(call ->
                prices.getOrDefault(key(call.getArgument(0), call.getArgument(1)), List.of()));
        when(dividendEventEvidenceRepository.resolve(anyString(), anyString(), any(), any()))
                .thenReturn(DividendEventEvidenceResolver.Resolution.MISSING);
        when(fundamentalAnalysisService.resolve(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(FundamentalAnalysisService.Resolved.unavailable(false));
        when(marketContextService.resolveFx(anyString(), any()))
                .thenReturn(TradingRadarMarketContextService.FxContext.EMPTY);

        Map<TradingRadarListBatchPreloader.Key, TradingRadarListBatchPreloader.Entry> entries = Map.of(
                new TradingRadarListBatchPreloader.Key("SAME", "台股"), entry(tw, prices.get(key("SAME", "台股"))),
                new TradingRadarListBatchPreloader.Key("SAME", "美股"), entry(usSame, prices.get(key("SAME", "美股"))),
                new TradingRadarListBatchPreloader.Key("OTHER", "美股"), entry(usOther, prices.get(key("OTHER", "美股"))));
        when(listBatchPreloader.preload(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(new TradingRadarListBatchPreloader.Context(entries));

        TradingRadarService service = newService();
        TradingRadarDto.ListResponse list = service.getList();
        TradingRadarDto.Response full = service.getCurrent();

        assertThat(list.stocks()).hasSize(3);
        assertThat(full.stocks()).hasSize(3);
        Map<String, TradingRadarDto.StockDecision> fullByPair = full.stocks().stream()
                .collect(java.util.stream.Collectors.toMap(
                        stock -> key(stock.stockCode(), stock.market()), java.util.function.Function.identity()));
        list.stocks().forEach(listStock -> assertVisibleListScalarsMatchFull(listStock,
                fullByPair.get(key(listStock.stockCode(), listStock.market()))));
        assertThat(list.stocks().stream().filter(stock -> "SAME".equals(stock.stockCode()))
                .collect(java.util.stream.Collectors.toMap(TradingRadarDto.ListStock::market,
                        TradingRadarDto.ListStock::stockName)))
                .containsExactlyInAnyOrderEntriesOf(Map.of("台股", "台灣同碼", "美股", "美股同碼"));
    }

    private void stubEligibleUnion() {
        AssetSnapshot snapshot = AssetSnapshot.builder().stocks(List.of(
                StockHolding.builder().stockCode("2330").market("台股").shares(BigDecimal.ONE).build(),
                StockHolding.builder().stockCode("DUP").market("台股").shares(BigDecimal.ONE).build(),
                StockHolding.builder().stockCode("0000").market("台股").shares(BigDecimal.ONE).build(),
                StockHolding.builder().stockCode("ZERO").market("美股").shares(BigDecimal.ZERO).build())).build();
        when(snapshotRepo.findLatestWithStocks()).thenReturn(Optional.of(snapshot));
        when(alertRepo.findDistinctStockCodeMarket()).thenReturn(List.of(
                new Object[]{"DUP", "台股"},
                new Object[]{"AAPL", "美股"},
                new Object[]{"0000", "台股"}));
    }

    private final class ProjectionFailingTradingRadarService extends TradingRadarService {
        private ProjectionFailingTradingRadarService() {
            super(
                    new TradingRadarRuleEngine(),
                    indicatorService,
                    adjustedPriceService,
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
            setListBatchPreloader(listBatchPreloader);
        }

        @Override
        TradingRadarDto.ListStock toListStock(DecisionCore core) {
            throw new IllegalStateException("projection unavailable");
        }
    }

    private static String key(String code, String market) {
        return code + '\0' + market;
    }

    private static StockPriceHistory price(String code, String market, String close) {
        BigDecimal value = new BigDecimal(close);
        return StockPriceHistory.builder().stockCode(code).market(market)
                .tradingDate(LocalDate.of(2026, 9, 11)).openPrice(value).highPrice(value)
                .lowPrice(value).closePrice(value).volume(1_000L).build();
    }

    private static TradingRadarListBatchPreloader.Entry entry(Stock stock, List<StockPriceHistory> prices) {
        return new TradingRadarListBatchPreloader.Entry(
                Optional.of(stock), prices, Optional.empty(), Optional.empty(), List.of(),
                DividendEventEvidenceResolver.Resolution.MISSING,
                FundamentalAnalysisService.Resolved.unavailable(false),
                BondYieldBetaResolver.Result.notApplicable(null), null,
                TradingRadarMarketContextService.FxContext.EMPTY,
                TradingRadarMarketFeatureResolver.Evidence.empty(stock.getMarket(), java.time.Instant.EPOCH, "test"),
                LocalDate.of(2026, 9, 11), List.of());
    }

    private static void assertVisibleListScalarsMatchFull(
            TradingRadarDto.ListStock list, TradingRadarDto.StockDecision full) {
        assertThat(full).isNotNull();
        assertThat(list.stockCode()).isEqualTo(full.stockCode());
        assertThat(list.stockName()).isEqualTo(full.stockName());
        assertThat(list.market()).isEqualTo(full.market());
        assertThat(list.assetClass()).isEqualTo(full.assetClass());
        assertThat(list.distributionAdjusted()).isEqualTo(full.distributionAdjusted());
        assertThat(list.held()).isEqualTo(full.held());
        assertThat(list.fxPercentile()).isEqualTo(full.fxPercentile());
        assertThat(list.underlyingCurrency()).isEqualTo(full.underlyingCurrency());
        assertThat(list.fundamental()).isEqualTo(full.fundamental() == null ? null
                : new TradingRadarDto.ListFundamental(full.fundamental().applicable(), full.fundamental().coverage(),
                full.fundamental().industryName(), full.fundamental().industryRevenueYoyPct()));
        assertThat(list.shortAction()).isEqualTo(full.shortAction());
        assertThat(list.shortActionLabel()).isEqualTo(full.shortActionLabel());
        assertThat(list.shortScore()).isEqualTo(full.shortScore());
        assertThat(list.swingAction()).isEqualTo(full.swingAction());
        assertThat(list.swingActionLabel()).isEqualTo(full.swingActionLabel());
        assertThat(list.swingScore()).isEqualTo(full.swingScore());
        assertThat(list.action()).isEqualTo(full.action());
        assertThat(list.actionLabel()).isEqualTo(full.actionLabel());
        assertThat(list.score()).isEqualTo(full.score());
        assertThat(list.horizonConflict()).isEqualTo(full.horizonConflict());
        assertThat(list.timingState()).isEqualTo(full.timingState());
        assertThat(list.timingLabel()).isEqualTo(full.timingLabel());
        assertThat(list.counterTrendState()).isEqualTo(full.counterTrendState());
        assertThat(list.counterTrendLabel()).isEqualTo(full.counterTrendLabel());
        assertThat(list.price()).isEqualTo(full.price());
        assertThat(list.changePercent()).isEqualTo(full.changePercent());
        assertThat(list.quoteStatus()).isEqualTo(full.quoteStatus());
        assertThat(list.priceUpdatedAt()).isEqualTo(full.priceUpdatedAt());
        assertThat(list.etfPremiumLivePct()).isEqualTo(full.etfPremiumLivePct());
        assertThat(list.etfPremiumLiveNavAsOf()).isEqualTo(full.etfPremiumLiveNavAsOf());
        assertThat(list.weeklyMa()).isEqualTo(full.weeklyMa());
        assertThat(list.monthlyMa()).isEqualTo(full.monthlyMa());
        assertThat(list.quarterlyMa()).isEqualTo(full.quarterlyMa());
        assertThat(list.annualMa()).isEqualTo(full.annualMa());
        assertThat(list.kValue()).isEqualTo(full.kValue());
        assertThat(list.dValue()).isEqualTo(full.dValue());
        assertThat(list.kdHeat()).isEqualTo(full.kdHeat());
        assertThat(list.weeklyIndicators()).isEqualTo(full.weeklyIndicators() == null ? null
                : new TradingRadarDto.ListWeeklyIndicators(full.weeklyIndicators().k(), full.weeklyIndicators().d(),
                full.weeklyIndicators().changePercent()));
        assertThat(list.dailyCandleAsOfDate()).isEqualTo(full.dailyCandle() == null ? null : full.dailyCandle().asOfDate());
    }
}
