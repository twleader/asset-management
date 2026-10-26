package com.steven.assets.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Regression: Spring assembles the inward service port with its outer HTTP adapter. */
class PublicMarketDataReadOnlyServiceSpringContextTest {

    @Test
    void springInstantiatesServiceWithProductionPortAdapter() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "readonly-service-test", Map.of("external-materials.base-url", "http://readonly.test")));
            context.register(PublicMarketDataReadOnlyService.class,
                    com.steven.assets.integration.marketdata.ReadOnlyIntradayHttpAdapter.class);
            context.refresh();

            assertNotNull(context.getBean(PublicMarketDataReadOnlyService.class));
        }
    }
}
