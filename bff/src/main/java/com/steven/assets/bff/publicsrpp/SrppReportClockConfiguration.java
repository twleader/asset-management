package com.steven.assets.bff.publicsrpp;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keeps the report-slot gate on Taipei time while allowing deterministic boundary tests. */
@Configuration(proxyBeanMethods = false)
class SrppReportClockConfiguration {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Bean("srppReportClock")
    @ConditionalOnMissingBean(name = "srppReportClock")
    Clock srppReportClock() {
        return Clock.system(TAIPEI);
    }
}
