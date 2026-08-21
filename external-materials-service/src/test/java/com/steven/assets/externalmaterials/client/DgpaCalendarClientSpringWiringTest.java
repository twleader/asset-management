package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.service.DgpaCalendarAuthority;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import static org.assertj.core.api.Assertions.assertThat;

class DgpaCalendarClientSpringWiringTest {

    @Test
    void componentScanSelectsProductionConstructorAndExposesAuthorityWithoutNetworkCall() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(ClientConfiguration.class)) {
            DgpaCalendarAuthority authority = context.getBean(DgpaCalendarAuthority.class);

            assertThat(authority).isExactlyInstanceOf(DgpaCalendarClient.class);
            assertThat(context.getBean(ObjectMapper.class)).isNotNull();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackageClasses = DgpaCalendarClient.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = DgpaCalendarClient.class))
    static class ClientConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
