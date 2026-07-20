package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.ExchangeRateHistoryRepository;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockDividendHistoryRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * TradingRadarService.buildMarket() 的大盤 stale／intraday 判斷（Task 228，Requirement 43 修訂 V6）。
 * ruleEngine 用真實 TradingRadarRuleEngine（純函式、無副作用），其餘 12 個建構子依賴皆 mock，
 * 只隔離出大盤區塊：holdings／watchlist 一律回空，get() 因此只組裝 market，stocks 恆為空 list。
 */
@ExtendWith(MockitoExtension.class)
class TradingRadarMarketFreshnessTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Mock private TechnicalIndicatorService indicatorService;
    @Mock private DistributionAdjustedPriceService adjustedPriceService;
    @Mock private AssetClassifier assetClassifier;
    @Mock private TwseIndexDailyHistoryRepository twseRepo;
    @Mock private StockPriceHistoryRepository priceHistoryRepo;
    @Mock private StockDividendHistoryRepository dividendHistoryRepo;
    @Mock private PriceQueryService priceQueryService;
    @Mock private AssetSnapshotRepository snapshotRepo;
    @Mock private StockAlertRepository alertRepo;
    @Mock private StockRepository stockRepo;
    @Mock private MarketDataService marketDataService;
    @Mock private ExchangeRateHistoryRepository exchangeRateRepo;

    private TradingRadarService newService() {
        return new TradingRadarService(
                new TradingRadarRuleEngine(),
                indicatorService,
                adjustedPriceService,
                assetClassifier,
                twseRepo,
                priceHistoryRepo,
                dividendHistoryRepo,
                priceQueryService,
                snapshotRepo,
                alertRepo,
                stockRepo,
                marketDataService,
                exchangeRateRepo);
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
        lenient().when(marketDataService.isTradingDay(anyString(), any(LocalDate.class))).thenReturn(true);
        lenient().when(snapshotRepo.findLatestWithStocks()).thenReturn(Optional.empty());
        lenient().when(alertRepo.findDistinctStockCodeMarket()).thenReturn(List.of());
        lenient().when(indicatorService.computeAll(anyString(), anyString())).thenReturn(
                new TechnicalIndicatorService.FullIndicators(
                        BigDecimal.valueOf(20000), BigDecimal.valueOf(20000), BigDecimal.valueOf(20000),
                        BigDecimal.valueOf(60), BigDecimal.valueOf(50),
                        BigDecimal.valueOf(55), BigDecimal.valueOf(52)));
    }

    private PriceQueryService.LivePrice liveOn(LocalDate tradingDate, BigDecimal price) {
        return new PriceQueryService.LivePrice(
                "0000", "台股大盤", "台股", price, null, null, null,
                null, null, null, null, null, null,
                tradingDate.toString(), "2026-07-20T10:30:00", false, "TWSE指數(5m)");
    }

    @Test
    void completedKNotToday_liveFreshToday_stale_false_intraday_true() {
        stubCommon();
        LocalDate today = LocalDate.now(TAIPEI);
        when(twseRepo.findTopNByOrderByTradingDateDesc(241)).thenReturn(descRows(today.minusDays(1), 20000));
        when(priceQueryService.getLive("0000", "台股")).thenReturn(Optional.of(liveOn(today, BigDecimal.valueOf(20500))));

        TradingRadarDto.Response resp = newService().get();

        assertFalse(resp.market().stale());
        assertTrue(resp.market().intraday());
        assertNotNull(resp.market().liveUpdatedAt());
    }

    @Test
    void completedKNotToday_liveMissing_stale_true_intraday_false() {
        stubCommon();
        LocalDate today = LocalDate.now(TAIPEI);
        when(twseRepo.findTopNByOrderByTradingDateDesc(241)).thenReturn(descRows(today.minusDays(1), 20000));
        when(priceQueryService.getLive("0000", "台股")).thenReturn(Optional.empty());

        TradingRadarDto.Response resp = newService().get();

        assertTrue(resp.market().stale());
        assertFalse(resp.market().intraday());
        assertNull(resp.market().liveUpdatedAt());
    }

    @Test
    void completedKAlreadyToday_eodWins_stale_false_intraday_false() {
        stubCommon();
        LocalDate today = LocalDate.now(TAIPEI);
        when(twseRepo.findTopNByOrderByTradingDateDesc(241)).thenReturn(descRows(today, 20000));
        // Redis 也剛好留著今天的即時價：完成日 K 優先，不得誤判成 intraday。
        when(priceQueryService.getLive("0000", "台股")).thenReturn(Optional.of(liveOn(today, BigDecimal.valueOf(20500))));

        TradingRadarDto.Response resp = newService().get();

        assertFalse(resp.market().stale());
        assertFalse(resp.market().intraday());
        assertNull(resp.market().liveUpdatedAt());
    }

    @Test
    void confirmation_usesOnlyCompletedCloses_notLivePrice() {
        stubCommon();
        LocalDate today = LocalDate.now(TAIPEI);
        // 完成日序列：近期收盤持續低於遠期（下跌趨勢），兩收盤日確認理論值＝BELOW。
        when(twseRepo.findTopNByOrderByTradingDateDesc(241)).thenReturn(descRows(today.minusDays(1), 20000));
        // 即時價刻意設得遠高於所有均線；若被誤併入 confirm() 的 closes，確認狀態會被拉成 ABOVE／MIXED。
        when(priceQueryService.getLive("0000", "台股")).thenReturn(Optional.of(liveOn(today, BigDecimal.valueOf(99999))));

        TradingRadarDto.Response resp = newService().get();

        assertEquals("BELOW", resp.market().quarterlyConfirmation());
        assertEquals("BELOW", resp.market().annualConfirmation());
        // 同時仍應正確判為 intraday（即時價本身仍用於 price／MA／KD／regime）。
        assertTrue(resp.market().intraday());
    }
}
