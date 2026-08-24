package com.steven.assets.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Regression: Spring must select the production @Value constructor over the package-private test seam. */
class PublicMarketDataReadOnlyServiceSpringContextTest {

    @Test
    void springInstantiatesTheServiceUsingItsProductionValueConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "readonly-service-test", Map.of("external-materials.base-url", "http://readonly.test")));
            context.register(PublicMarketDataReadOnlyService.class);
            context.refresh();

            assertNotNull(context.getBean(PublicMarketDataReadOnlyService.class));
        }
    }
}
