package com.steven.assets.bff.config;

import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.TenantIdentity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ReactorResourceFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ClientCodecConfigurer;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfig {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public WebClient businessServicesClient(WebClient.Builder builder,
                                            ObjectProvider<ReactorResourceFactory> resourceFactory) {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs((ClientCodecConfigurer cfg) ->
                        cfg.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        return builder
                // DNS 快取上限（Task 208，理由見 DnsCacheConfig）：gateway 那條由 HttpClientCustomizer 套，
                // 這條 WebClient 是另一個 HttpClient 實例，必須在此明確套用，否則 business-services
                // 容器重建換 IP 後這條路徑仍會卡 Docker DNS 的 600s TTL。
                // ⚠ 自建 connector＝已脫離 Boot 的 connector 組裝管線：日後若導入
                // spring.http.client.ssl bundle 或 ReactorNettyHttpClientMapper bean，對本 client 不會生效，
                // 必須同步補在這裡。
                .clientConnector(new ReactorClientHttpConnector(
                        DnsCacheConfig.applyDnsCacheLimit(sharedHttpClient(resourceFactory), "webclient")))
                .baseUrl(businessServicesUrl)
                .exchangeStrategies(strategies)
                .filter(tenantHeaderFilter())
                .build();
    }

    /**
     * 與 gateway 共用同一組 reactor-netty 資源（連線池 ＋ event loop），避免自建 client 另開一套。
     * 優先取 Boot 自動組態的 {@link ReactorResourceFactory}；該 bean 缺席時退回 {@code HttpClient.create()}
     * （走全域 {@code HttpResources}，與預設 {@code useGlobalResources=true} 等價），不讓 BFF 因此起不來。
     */
    private static HttpClient sharedHttpClient(ObjectProvider<ReactorResourceFactory> provider) {
        ReactorResourceFactory factory = provider.getIfAvailable();
        if (factory == null) {
            return HttpClient.create();
        }
        return HttpClient.create(factory.getConnectionProvider()).runOn(factory.getLoopResources());
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
