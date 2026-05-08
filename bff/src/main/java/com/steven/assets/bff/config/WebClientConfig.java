package com.steven.assets.bff.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ClientCodecConfigurer;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public WebClient businessServicesClient(WebClient.Builder builder) {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs((ClientCodecConfigurer cfg) ->
                        cfg.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        return builder
                .baseUrl(businessServicesUrl)
                .exchangeStrategies(strategies)
                .build();
    }
}
