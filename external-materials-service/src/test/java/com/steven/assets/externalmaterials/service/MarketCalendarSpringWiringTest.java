package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class MarketCalendarSpringWiringTest {

    @Test
    void springContextUsesTheProductionMarketDataConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MarketDataFetchService.class,
                    () -> new MarketDataFetchService(null, null, ""));
            context.register(MarketCalendar.class);
            context.refresh();

            MarketCalendar calendar = context.getBean(MarketCalendar.class);
            assertThat(calendar).isNotNull();
            assertThat(context.getBean(MarketDataFetchService.class)).isNotNull();
            assertThat(calendar.isUsTradingDay(LocalDate.of(2026, 8, 13))).isTrue();
        }
    }
}
