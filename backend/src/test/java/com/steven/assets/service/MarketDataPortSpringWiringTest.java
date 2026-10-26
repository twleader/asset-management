package com.steven.assets.service;

import com.steven.assets.integration.marketdata.*;
import com.steven.assets.client.ExternalTechnicalHistoryBackfillClient;
import com.steven.assets.integration.fubon.FubonConfigState;
import com.steven.assets.integration.crawler.CrawlerRunHttpAdapter;
import com.steven.assets.repository.*;
import com.steven.assets.security.CurrentUserContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Exercises production constructor injection after all seven HTTP and three Redis seams move outward. */
class MarketDataPortSpringWiringTest {
    @Test void springAssemblesEveryServiceWithTheProductionAdaptersWithoutNetworkIo() {
        try (var context = new AnnotationConfigApplicationContext()) {
            for (Class<?> dependency : new Class<?>[] {
                    StringRedisTemplate.class, CrawlerExportSettingRepository.class, StockRepository.class, FubonEtfHoldingsSnapshotRepository.class,
                    StockPriceHistoryRepository.class, ExchangeRateHistoryRepository.class,
                    CommodityPriceHistoryRepository.class, FundMasterRepository.class,
                    TwseIndexDailyHistoryRepository.class, TaiwanGdpPerCapitaHistoryRepository.class,
                    JapanGdpPerCapitaHistoryRepository.class, KoreaGdpPerCapitaHistoryRepository.class,
                    UsIndexDailyHistoryRepository.class, StockDividendHistoryRepository.class,
                    DividendCurrentStateProjectionService.class, DividendCashEnrichmentService.class,
                    GdriveOutputSupport.class, CurrentUserContext.class }) {
                registerMock(context, dependency);
            }
            Class<?>[] services = { MarketDataService.class, PriceQueryService.class, HistoricalDataService.class,
                    PublicMarketDataReadOnlyService.class, DividendHistoryService.class, MacroHistoryService.class,
                    CrawlerExportPathService.class, TradingRadarRefreshService.class, TradingRadarTechnicalBackfillService.class };
            context.register(services);
            context.register(FubonConfigState.class, ExternalTechnicalHistoryBackfillClient.class, MarketDataReadHttpAdapter.class, HistoricalDataHttpAdapter.class,
                    MacroDataHttpAdapter.class, ReadOnlyIntradayHttpAdapter.class, DividendSyncHttpAdapter.class,
                    PriceRefreshHttpAdapter.class, RedisPriceCacheReadAdapter.class,
                    RedisCooldownStoreAdapter.class, CrawlerRunHttpAdapter.class);
            context.refresh();
            for (Class<?> service : services) assertThat(context.getBean(service)).isNotNull();
        }
    }

    private static <T> void registerMock(AnnotationConfigApplicationContext context, Class<T> type) {
        context.registerBean(type, () -> mock(type));
    }
}
