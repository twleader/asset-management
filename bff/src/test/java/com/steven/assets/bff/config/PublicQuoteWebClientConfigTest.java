package com.steven.assets.bff.config;

import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.TenantIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ReactorResourceFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Requirement 108：兩顆 public aggregation 專用 outbound client 在任何 Reactor tenant 下都不可送 X-User-*。 */
class PublicQuoteWebClientConfigTest {

    @Test
    @SuppressWarnings("unchecked")
    void dedicatedClientsNeverCopyTenantIdentityHeaders() {
        List<ClientRequest> calls = new ArrayList<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            calls.add(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK).body("{}").build());
        });
        ObjectProvider<ReactorResourceFactory> resources = mock(ObjectProvider.class);
        WebClientConfig config = new WebClientConfig();
        ReflectionTestUtils.setField(config, "businessServicesUrl", "http://business.invalid");
        ReflectionTestUtils.setField(config, "externalMaterialsUrl", "http://external.invalid");

        WebClient raw = config.publicQuoteRawClient(builder, resources);
        WebClient market = config.publicMarketDataBusinessClient(builder, resources);
        Context tenant = Context.of(AuthConstants.CTX_IDENTITY, new TenantIdentity(77L, "ADMIN", "ACTIVE"));

        raw.get().uri("/api/quotes/one").exchangeToMono(response -> response.releaseBody())
                .contextWrite(tenant).block();
        market.get().uri("/api/market-data/history/stock").exchangeToMono(response -> response.releaseBody())
                .contextWrite(tenant).block();

        assertThat(calls).hasSize(2).allSatisfy(request -> {
            assertThat(request.headers().containsKey(AuthConstants.HDR_USER_ID)).isFalse();
            assertThat(request.headers().containsKey(AuthConstants.HDR_USER_ROLE)).isFalse();
            assertThat(request.headers().containsKey(AuthConstants.HDR_USER_STATUS)).isFalse();
        });
        assertThat(calls).extracting(request -> request.url().getHost())
                .containsExactly("external.invalid", "business.invalid");
    }
}
