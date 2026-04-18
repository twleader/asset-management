package com.steven.assets.bff.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public WebClient businessServicesClient(WebClient.Builder builder) {
        return builder
                .baseUrl(businessServicesUrl)
                .build();
    }
}
