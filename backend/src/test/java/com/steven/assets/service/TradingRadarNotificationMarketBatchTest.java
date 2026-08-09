package com.steven.assets.service;

import com.steven.assets.model.TradingRadarNotificationSetting;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.EtfNavHistoryRepository;
import com.steven.assets.repository.ExchangeRateHistoryRepository;
import com.steven.assets.repository.NewsHeadlineRepository;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockDividendHistoryRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.repository.TradingRadarNotificationSettingRepository;
import com.steven.assets.repository.TradingRadarNotificationStateRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import com.steven.assets.security.CurrentUserContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Task 302：{@code flushEvaluations} 對每輪出現的每個市場只組一次大盤，不隨 setting 數量重算。
 *
 * <p>{@link TradingRadarMarketContextService} 刻意用真實物件、只 mock 底層 repo——本檔要驗證的是
 * bounded 查詢真的只發一次；整個 mock 掉就量不到 {@code resolveMarket} 內部的 repo 呼叫次數。
 * {@link TradingRadarService} 同理只留 {@code twseRepo}／{@code usIndexRepo}／{@code marketContextService}
 * 為真實可觀測依賴，其餘一律 mock 且刻意不 stub——{@code buildMarket}／{@code buildUsMarket}／
 * {@code buildStock} 既有的 fail-soft catch 會吸收由此而生的 NPE，讓 flush 平順跑完，
 * 不影響本檔關心的呼叫次數與零互動斷言。</p>
 *
 * <p>{@link TradingRadarNotificationService#queueEvaluation} 只收台股（既有限制，本任務未變更），
 * 故用反射直接寫入 {@code pending}，讓一輪 flush 同時涵蓋台股與美股 setting——測的是
 * {@code flushEvaluations} 本身的跨市場批次邏輯，不依賴（也不測試）queueEvaluation 的既有市場閘門。</p>
 */
class TradingRadarNotificationMarketBatchTest {

    private static final String TW_MARKET = "台股";
    private static final String US_MARKET = "美股";

    private final TwseIndexDailyHistoryRepository twseRepo = mock(TwseIndexDailyHistoryRepository.class);
    private final UsIndexDailyHistoryRepository usIndexRepo = mock(UsIndexDailyHistoryRepository.class);
    private final MarketDataService marketDataService = mock(MarketDataService.class);
    private final NewsHeadlineRepository newsRepo = mock(NewsHeadlineRepository.class);

    private final TradingRadarMarketContextService marketContextService = new TradingRadarMarketContextService(
            twseRepo, usIndexRepo, mock(ExchangeRateHistoryRepository.class), newsRepo, marketDataService);

    private final TradingRadarService tradingRadarService = new TradingRadarService(
            mock(TradingRadarRuleEngine.class),
            mock(TechnicalIndicatorService.class),
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
            marketContextService,
            mock(FundamentalAnalysisService.class),
            mock(EtfNavHistoryRepository.class),
            mock(TradingRadarSnapshotStore.class),
            mock(CurrentUserContext.class),
            mock(DividendEventEvidenceRepository.class),
            mock(TreasuryYieldService.class));

    private final TradingRadarNotificationSettingRepository settingRepo =
            mock(TradingRadarNotificationSettingRepository.class);
    private final TradingRadarNotificationStateRepository stateRepo =
            mock(TradingRadarNotificationStateRepository.class);
    private final AssetSnapshotRepository notificationSnapshotRepo = mock(AssetSnapshotRepository.class);
    private final TradingRadarNotificationTransition transition = mock(TradingRadarNotificationTransition.class);
    private final TradingRadarNotificationDispatcher dispatcher = mock(TradingRadarNotificationDispatcher.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<TradingRadarNotificationService> self = mock(ObjectProvider.class);

    private final TradingRadarNotificationService service = new TradingRadarNotificationService(
            settingRepo, stateRepo, notificationSnapshotRepo, tradingRadarService, transition, dispatcher, self);

    private static TradingRadarNotificationSetting setting(long id, String code, String market) {
        return TradingRadarNotificationSetting.builder()
                .id(id).ownerUserId(1L).stockCode(code).market(market)
                .active(true).initialized(true).ruleVersion(TradingRadarRuleEngine.RULE_VERSION)
                .lastAction("HOLD").lastCounterTrendState("NONE")
                .build();
    }

    /** {@code queueEvaluation()} 只收台股（既有限制），故直接反射寫入 pending 以涵蓋美股 setting。 */
    private void seedPending(String... keys) throws Exception {
        Field field = TradingRadarNotificationService.class.getDeclaredField("pending");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> pending = (Set<String>) field.get(service);
        pending.addAll(List.of(keys));
    }

    @Test
    void 兩檔台股與一檔美股setting同輪flush每個市場只組一次大盤且不查新聞() throws Exception {
        when(settingRepo.findByActiveTrueAndStockCodeAndMarket("2330", TW_MARKET))
                .thenReturn(List.of(setting(1L, "2330", TW_MARKET)));
        when(settingRepo.findByActiveTrueAndStockCodeAndMarket("2454", TW_MARKET))
                .thenReturn(List.of(setting(2L, "2454", TW_MARKET)));
        when(settingRepo.findByActiveTrueAndStockCodeAndMarket("AAPL", US_MARKET))
                .thenReturn(List.of(setting(3L, "AAPL", US_MARKET)));
        seedPending("2330\0" + TW_MARKET, "2454\0" + TW_MARKET, "AAPL\0" + US_MARKET);

        service.flushEvaluations();

        // 兩檔台股 setting 只換來一次 bounded 查詢，證明大盤按市場批次、不隨 setting 數量重算。
        verify(twseRepo, times(1)).findTopNByOrderByTradingDateDesc(60);
        verify(usIndexRepo, times(1)).findTopNByIndexCodeOrderByTradingDateDesc("IXIC", 241);
        verifyNoInteractions(newsRepo);
    }
}
