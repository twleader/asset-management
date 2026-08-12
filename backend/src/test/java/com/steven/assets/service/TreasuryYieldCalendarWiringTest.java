package com.steven.assets.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TreasuryYieldCalendarWiringTest {

    @Test
    void springProductionConstructorUsesTheRealTypedAdapter() {
        MarketDataService marketDataService = mock(MarketDataService.class);
        when(marketDataService.isTradingDayKnown(eq("美股"), any())).thenReturn(Optional.empty());

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MarketDataService.class, () -> marketDataService);
            context.registerBean(TreasuryYieldBatchRepository.class,
                    () -> mock(TreasuryYieldBatchRepository.class));
            context.registerBean(TreasuryYieldClient.class, () -> mock(TreasuryYieldClient.class));
            context.register(MarketDataTradingCalendarAdapter.class, TreasuryYieldService.class);
            context.refresh();

            assertThat(context.getBean(TradingRadarSessionCalendarPort.class))
                    .isInstanceOf(MarketDataTradingCalendarAdapter.class);
            assertThat(context.getBean(TreasuryYieldService.class)).isNotNull();
            assertThat(context.getBeansOfType(TradingRadarSessionCalendarPort.class)).hasSize(1);
        }
    }
}
