package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /** holdings／watchlist 一律回空（owner／無 owner 兩種查詢都要 stub），只隔離出 owner 分支本身。 */
    private void stubCommon() {
        lenient().when(taiexDisplayPriceService.resolve()).thenReturn(
                new TaiexDisplayPriceService.DisplayQuote(
                        null, null, null, null, null, null,
                        null, null, true, "CLOSE_PENDING"));
        lenient().when(marketDataService.isTradingDay(anyString(), any(LocalDate.class))).thenReturn(true);
        lenient().when(marketContextService.resolve(any())).thenReturn(
                new TradingRadarMarketContextService.Resolved(
                        TradingRadarMarketContextService.MarketContext.EMPTY, List.of()));
        lenient().when(twseRepo.findTopNByOrderByTradingDateDesc(241)).thenReturn(List.of());
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
}
