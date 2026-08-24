package com.steven.assets.bff.publicquote;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** Requirement 108 的可注入台北時鐘，讓預設一年窗口與 deadline 測試可重現。 */
@Configuration
class PublicQuoteMarketDataConfiguration {

    static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Bean("publicQuoteClock")
    @Qualifier("publicQuoteClock")
    Clock publicQuoteClock() {
        return Clock.system(TAIPEI);
    }
}
