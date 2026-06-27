package com.steven.assets.bff.config;

import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.TenantIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ClientCodecConfigurer;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

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
                .filter(tenantHeaderFilter())
                .build();
    }

    /**
     * 多租戶身分注入（Requirement 28）：aggregation controller 的下游呼叫從 Reactor context 取出
     * {@link TenantIdentity}（由 {@code TenantWebFilter} 寫入），加上 {@code X-User-*} header。
     * 引導階段（login-upsert / by-email）context 尚無身分，則不加—— business 端對該兩端點放行。
     */
    private ExchangeFilterFunction tenantHeaderFilter() {
        return (request, next) -> Mono.deferContextual(ctx -> {
            if (ctx.hasKey(AuthConstants.CTX_IDENTITY)) {
                TenantIdentity id = ctx.get(AuthConstants.CTX_IDENTITY);
                ClientRequest mutated = ClientRequest.from(request)
                        .headers(h -> {
                            h.set(AuthConstants.HDR_USER_ID, String.valueOf(id.effectiveUserId()));
                            h.set(AuthConstants.HDR_USER_ROLE, id.role());
                            h.set(AuthConstants.HDR_USER_STATUS, id.status());
                        })
                        .build();
                return next.exchange(mutated);
            }
            return next.exchange(request);
        });
    }
}
