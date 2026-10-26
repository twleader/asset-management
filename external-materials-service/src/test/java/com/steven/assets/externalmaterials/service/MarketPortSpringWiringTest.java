package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.FubonNormalizedQuoteClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient;
import com.steven.assets.externalmaterials.service.port.FubonNormalizedQuotePort;
import com.steven.assets.externalmaterials.service.port.LivePriceFetchPort;
import com.steven.assets.externalmaterials.service.port.PriceProjectionPort;
import com.steven.assets.externalmaterials.service.port.StockMarketRepository;
import com.steven.assets.externalmaterials.service.port.StockUniversePort;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mockingDetails;

/** Uses Spring's real constructor selection, generic providers and class-based transactional proxy. */
class MarketPortSpringWiringTest {
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions {}

    @Test
    void oneTransactionalAdapterSuppliesBothStockPortsAndAbsentOptionalClientsRemainAbsent() {
        try (var context = context(false)) {
            assertThat(context.getBean(StockUniversePort.class)).isSameAs(context.getBean(StockMarketRepository.class));
            assertThat(context.getBean(StockMarketRepository.class)).isInstanceOf(StockSourceQuery.class);
            assertThat(org.springframework.aop.support.AopUtils.isCglibProxy(context.getBean(StockMarketRepository.class))).isTrue();
            assertThat(context.getBean(PriceProjectionPort.class)).isInstanceOf(PriceCacheWriter.class);
            assertThat(context.getBean(LivePriceFetchPort.class)).isInstanceOf(PriceFetchClient.class);
            assertThat(context.getBeanProvider(FubonNormalizedQuotePort.class).getIfAvailable()).isNull();
            assertThat(context.getBean(TwLiveQuoteDispatcher.class)).isNotNull();
            assertThat(context.getBean(PricePoller.class)).isNotNull();
            assertThat(context.getBean(FubonRadarScope.class)).isNotNull();
            assertFrameworkInitializationOnly(context.getBean(JdbcTemplate.class));
            assertFrameworkInitializationOnly(context.getBean(StringRedisTemplate.class));
        }
    }

    @Test
    void enabledNormalizedAdapterResolvesThroughTheOptionalPortWithoutOpeningVendorIo() {
        try (var context = context(true)) {
            assertThat(context.getBeanProvider(FubonNormalizedQuotePort.class).getIfAvailable())
                    .isSameAs(context.getBean(FubonNormalizedQuoteClient.class));
            assertThat(context.getBean(TwLiveQuoteDispatcher.class)).isNotNull();
            assertFrameworkInitializationOnly(context.getBean(JdbcTemplate.class));
            assertFrameworkInitializationOnly(context.getBean(StringRedisTemplate.class));
            verifyNoInteractions(context.getBean(ExternalApiErrorLogWriter.class));
        }
    }

    private static void assertFrameworkInitializationOnly(Object collaborator) {
        assertThat(mockingDetails(collaborator).getInvocations())
                .extracting(call -> call.getMethod().getName())
                .isSubsetOf("afterPropertiesSet", "setBeanClassLoader");
    }

    private static AnnotationConfigApplicationContext context(boolean enabled) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                "fubon.enabled", Boolean.toString(enabled), "fubon.tw-live-quotes-enabled", "false",
                "fubon.shared-token-path", "/fixture/no-token-read")));
        context.register(Transactions.class);
        context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
        context.registerBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));
        context.registerBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
        context.registerBean(TradingDateResolver.class, () -> mock(TradingDateResolver.class));
        context.registerBean(IntradayHighLowTracker.class, () -> mock(IntradayHighLowTracker.class));
        context.registerBean(IntradayTickStore.class, () -> mock(IntradayTickStore.class));
        context.registerBean(MarketClock.class, () -> mock(MarketClock.class));
        context.registerBean(ExternalApiErrorLogWriter.class, () -> mock(ExternalApiErrorLogWriter.class));
        context.registerBean(FubonStockPushSubscriptionManager.class, () -> mock(FubonStockPushSubscriptionManager.class));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.register(StockSourceQuery.class, PriceCacheWriter.class, PriceFetchClient.class,
                FubonLiveResponseStore.class, FubonLiveResponseCache.class, TwLiveQuoteOutcomeCounters.class,
                FubonNormalizedQuoteClient.class, TwLiveQuoteDispatcher.class, PricePoller.class, FubonRadarScope.class);
        context.refresh();
        return context;
    }
}
